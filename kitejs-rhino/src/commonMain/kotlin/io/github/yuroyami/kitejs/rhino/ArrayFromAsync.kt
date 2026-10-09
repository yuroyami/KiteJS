/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

/**
 * `Array.fromAsync` (ECMAScript 2026, 23.1.2.2). The spec runs it as an async function. Here
 * each of its awaits attaches a [NativeAsyncGenerator.HostReaction], and the next step runs from
 * the microtask queue. An async iterable, a sync iterable and an array-like each take their own
 * path, as in the spec.
 */
internal class ArrayFromAsync private constructor(
    private val scope: Scriptable,
    private val constructor: Scriptable?,
    private val mapFn: Callable?,
    private val thisArg: Any?,
    private val promise: NativePromise,
) {
    private var record: OpenIterator? = null
    private var arrayLike: Scriptable? = null
    private var length = 0L
    private var result: Scriptable? = null
    private var k = 0L

    private fun begin(cx: Context, items: Any?) {
        val asyncMethod = getMethod(cx, items, SymbolKey.ASYNC_ITERATOR)
        val syncMethod = if (asyncMethod == null) getMethod(cx, items, SymbolKey.ITERATOR) else null
        if (asyncMethod != null || syncMethod != null) {
            val method = asyncMethod ?: syncMethod!!
            val iterator = method.call(cx, scope, ScriptRuntime.toReceiver(cx, items, scope)!!, ScriptRuntime.emptyArgs)
            if (iterator !is Scriptable) throw ScriptRuntime.typeErrorById("msg.iterator.result.not.object", ScriptRuntime.toString(iterator))
            val opened = OpenIterator(iterator, OpenIterator.read(iterator, ES6Iterator.NEXT_METHOD), -1, isAsync = asyncMethod != null)
            record = if (asyncMethod != null) opened else {
                val wrapper = AsyncFromSyncIterator.create(cx, scope, opened)
                OpenIterator(wrapper, OpenIterator.read(wrapper, ES6Iterator.NEXT_METHOD), -1, isAsync = true)
            }
            result = NativeArray.callConstructorOrCreateArray(cx, scope, constructor, 0, false)
            iteratorStep(cx)
        } else {
            val like = ScriptRuntime.toObject(cx, scope, items)
            arrayLike = like
            length = NativeArray.getLengthProperty(cx, like)
            result = NativeArray.callConstructorOrCreateArray(cx, scope, constructor, length, true)
            arrayLikeStep(cx)
        }
    }

    // ---- An iterable ---------------------------------------------------------------------------

    private fun iteratorStep(cx: Context) {
        val record = record!!
        val next = try {
            if (k >= MAX_SAFE_INTEGER) return close(cx, ScriptRuntime.wrapException(ScriptRuntime.typeErrorById("msg.arraylength.too.big", k), scope, cx))
            val method = record.nextMethod as? Callable
                ?: throw ScriptRuntime.typeErrorById("msg.isnt.function", "iterator.next", ScriptRuntime.typeOf(record.nextMethod))
            method.call(cx, scope, record.iterator, ScriptRuntime.emptyArgs)
        } catch (e: RhinoException) {
            return reject(cx, errorValue(cx, e))
        }
        await(cx, next, { c, r -> iteratorResult(c, r) }, { c, e -> reject(c, e) })
    }

    private fun iteratorResult(cx: Context, nextResult: Any?) {
        val value: Any?
        try {
            if (nextResult !is Scriptable) throw ScriptRuntime.typeErrorById("msg.iterator.result.not.object", ScriptRuntime.toString(nextResult))
            if (ScriptRuntime.toBoolean(OpenIterator.read(nextResult, ES6Iterator.DONE_PROPERTY))) {
                NativeArray.setLengthProperty(cx, result!!, k)
                return promise.resolveFromEngine(cx, scope, result)
            }
            value = OpenIterator.read(nextResult, ES6Iterator.VALUE_PROPERTY)
        } catch (e: RhinoException) {
            return reject(cx, errorValue(cx, e))
        }
        if (mapFn == null) return define(cx, value)
        val mapped = try {
            mapFn.call(cx, scope, ScriptRuntime.getApplyOrCallThis(cx, scope, thisArg, 1, mapFn), arrayOf(value, k.toDouble()))
        } catch (e: RhinoException) {
            return close(cx, errorValue(cx, e))
        }
        await(cx, mapped, { c, v -> define(c, v) }, { c, e -> close(c, e) })
    }

    private fun define(cx: Context, value: Any?) {
        try {
            ArrayLikeAbstractOperations.defineElem(cx, result!!, k, value)
        } catch (e: RhinoException) {
            return close(cx, errorValue(cx, e))
        }
        k++
        iteratorStep(cx)
    }

    /** AsyncIteratorClose after a throw: `return` runs and is awaited, and [error] still rejects. */
    private fun close(cx: Context, error: Any?) {
        val record = record!!
        val returned = try {
            val method = OpenIterator.read(record.iterator, ES6Iterator.RETURN_PROPERTY)
            if (method !is Callable) return reject(cx, error)
            method.call(cx, scope, record.iterator, ScriptRuntime.emptyArgs)
        } catch (e: RhinoException) {
            return reject(cx, error)
        }
        await(cx, returned, { c, _ -> reject(c, error) }, { c, _ -> reject(c, error) })
    }

    // ---- An array-like ---------------------------------------------------------------------------

    private fun arrayLikeStep(cx: Context) {
        if (k >= length) {
            try {
                NativeArray.setLengthProperty(cx, result!!, length)
            } catch (e: RhinoException) {
                return reject(cx, errorValue(cx, e))
            }
            return promise.resolveFromEngine(cx, scope, result)
        }
        val value = try {
            NativeArray.getElem(cx, arrayLike!!, k)
        } catch (e: RhinoException) {
            return reject(cx, errorValue(cx, e))
        }
        await(cx, value, { c, v -> arrayLikeValue(c, v) }, { c, e -> reject(c, e) })
    }

    private fun arrayLikeValue(cx: Context, value: Any?) {
        if (mapFn == null) return arrayLikeDefine(cx, value)
        val mapped = try {
            mapFn.call(cx, scope, ScriptRuntime.getApplyOrCallThis(cx, scope, thisArg, 1, mapFn), arrayOf(value, k.toDouble()))
        } catch (e: RhinoException) {
            return reject(cx, errorValue(cx, e))
        }
        await(cx, mapped, { c, v -> arrayLikeDefine(c, v) }, { c, e -> reject(c, e) })
    }

    private fun arrayLikeDefine(cx: Context, value: Any?) {
        try {
            ArrayLikeAbstractOperations.defineElem(cx, result!!, k, value)
        } catch (e: RhinoException) {
            return reject(cx, errorValue(cx, e))
        }
        k++
        arrayLikeStep(cx)
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    private fun await(cx: Context, value: Any?, onFulfilled: (Context, Any?) -> Unit, onRejected: (Context, Any?) -> Unit) {
        try {
            NativePromise.await(cx, scope, value, NativeAsyncGenerator.HostReaction(scope, false, onFulfilled), NativeAsyncGenerator.HostReaction(scope, false, onRejected))
        } catch (e: RhinoException) {
            onRejected(cx, errorValue(cx, e))
        }
    }

    private fun reject(cx: Context, error: Any?) = promise.rejectFromEngine(cx, scope, error)

    private fun errorValue(cx: Context, e: RhinoException): Any? =
        if (e is JavaScriptException) e.value else ScriptRuntime.wrapException(e, scope, cx)

    /** GetMethod: undefined for a missing or nullish method, a TypeError for one that is not callable. */
    private fun getMethod(cx: Context, value: Any?, key: Symbol): Callable? {
        val method = ScriptRuntime.getV(cx, scope, value, key)
        if (method === Scriptable.NOT_FOUND || method == null || Undefined.isUndefined(method)) return null
        return method as? Callable ?: throw ScriptRuntime.typeErrorById("msg.not.iterable", ScriptRuntime.typeOf(value))
    }

    companion object {
        private const val MAX_SAFE_INTEGER = 9007199254740991L

        fun start(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val promise = NativePromise.newIntrinsic(cx, scope)
            try {
                val items = if (args.isNotEmpty()) args[0] else Undefined.instance
                val mapArg = if (args.size >= 2) args[1] else Undefined.instance
                var mapFn: Callable? = null
                var thisArg: Any? = null
                if (!Undefined.isUndefined(mapArg)) {
                    val function = mapArg as? Function ?: throw ScriptRuntime.typeErrorById("msg.map.function.not")
                    mapFn = function
                    // Converted for each call: the first call takes a primitive's receiver mark (#78).
                    thisArg = if (args.size >= 3) args[2] else Undefined.SCRIPTABLE_UNDEFINED
                }
                ArrayFromAsync(scope, thisObj, mapFn, thisArg, promise).begin(cx, items)
            } catch (e: RhinoException) {
                promise.rejectFromEngine(cx, scope, if (e is JavaScriptException) e.value else ScriptRuntime.wrapException(e, scope, cx))
            }
            return promise
        }
    }
}
