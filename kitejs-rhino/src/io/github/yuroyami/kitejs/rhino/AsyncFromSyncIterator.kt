/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

/**
 * An async iterator over a value that has only a sync iterator (ECMAScript 2018, 27.1.4,
 * CreateAsyncFromSyncIterator). `for await`, `yield*` in an async generator and
 * `Array.fromAsync` use it. Its `next`, `return` and `throw` answer promises, and each value is
 * awaited before it is handed on. A value that rejects closes the sync iterator, as ECMAScript
 * 2025 has it.
 */
internal class AsyncFromSyncIterator private constructor(private val sync: OpenIterator) : ScriptableObject() {

    override val className: String
        get() = "Object"

    private fun next(cx: Context, scope: Scriptable, args: Array<Any?>): Any? {
        val promise = NativePromise.newIntrinsic(cx, scope)
        val result = try {
            val next = sync.nextMethod as? Callable
                ?: throw ScriptRuntime.typeErrorById("msg.isnt.function", "iterator.next", ScriptRuntime.typeOf(sync.nextMethod))
            objectResult(next.call(cx, scope, sync.iterator, args))
        } catch (e: RhinoException) {
            return promise.also { it.rejectFromEngine(cx, scope, errorValue(cx, scope, e)) }
        }
        return continuation(cx, scope, result, promise, closeOnRejection = true)
    }

    private fun doReturn(cx: Context, scope: Scriptable, args: Array<Any?>): Any? {
        val promise = NativePromise.newIntrinsic(cx, scope)
        val result = try {
            val method = getMethod(ES6Iterator.RETURN_PROPERTY)
            if (method == null) {
                val value = if (args.isNotEmpty()) args[0] else Undefined.instance
                promise.resolveFromEngine(cx, scope, ES6Iterator.makeIteratorResult(cx, scope, true, value))
                return promise
            }
            objectResult(method.call(cx, scope, sync.iterator, args))
        } catch (e: RhinoException) {
            return promise.also { it.rejectFromEngine(cx, scope, errorValue(cx, scope, e)) }
        }
        return continuation(cx, scope, result, promise, closeOnRejection = false)
    }

    private fun doThrow(cx: Context, scope: Scriptable, args: Array<Any?>): Any? {
        val promise = NativePromise.newIntrinsic(cx, scope)
        val result = try {
            val method = getMethod("throw")
            if (method == null) {
                // The sync iterator gets a chance to clean up before the protocol error.
                sync.close(cx, scope, quiet = false)
                throw ScriptRuntime.typeErrorById("msg.iterator.no.throw")
            }
            objectResult(method.call(cx, scope, sync.iterator, args))
        } catch (e: RhinoException) {
            return promise.also { it.rejectFromEngine(cx, scope, errorValue(cx, scope, e)) }
        }
        return continuation(cx, scope, result, promise, closeOnRejection = true)
    }

    /** AsyncFromSyncIteratorContinuation: settles [promise] once the result's value settles. */
    private fun continuation(cx: Context, scope: Scriptable, result: Scriptable, promise: NativePromise, closeOnRejection: Boolean): Any? {
        val done: Boolean
        val value: Any?
        try {
            done = ScriptRuntime.toBoolean(OpenIterator.read(result, ES6Iterator.DONE_PROPERTY))
            value = OpenIterator.read(result, ES6Iterator.VALUE_PROPERTY)
        } catch (e: RhinoException) {
            return promise.also { it.rejectFromEngine(cx, scope, errorValue(cx, scope, e)) }
        }
        val closeOnReject = closeOnRejection && !done
        val onFulfilled = NativeAsyncGenerator.HostReaction(scope, false) { rcx, v ->
            promise.resolveFromEngine(rcx, scope, ES6Iterator.makeIteratorResult(rcx, scope, done, v))
        }
        val onRejected = NativeAsyncGenerator.HostReaction(scope, false) { rcx, e ->
            if (closeOnReject) sync.close(rcx, scope, quiet = true)
            promise.rejectFromEngine(rcx, scope, e)
        }
        try {
            NativePromise.await(cx, scope, value, onFulfilled, onRejected)
        } catch (e: RhinoException) {
            if (closeOnReject) sync.close(cx, scope, quiet = true)
            promise.rejectFromEngine(cx, scope, errorValue(cx, scope, e))
        }
        return promise
    }

    private fun getMethod(name: String): Callable? {
        val method = OpenIterator.read(sync.iterator, name)
        if (method == null || Undefined.isUndefined(method)) return null
        return method as? Callable ?: throw ScriptRuntime.typeErrorById("msg.isnt.function", "iterator.$name", ScriptRuntime.typeOf(method))
    }

    private fun objectResult(result: Any?): Scriptable =
        result as? Scriptable ?: throw ScriptRuntime.typeErrorById("msg.iterator.result.not.object", ScriptRuntime.toString(result))

    companion object {
        private val PROTOTYPE_TAG: Any = "AsyncFromSyncIteratorPrototype"

        fun create(cx: Context, scope: Scriptable, sync: OpenIterator): AsyncFromSyncIterator {
            val top = getTopLevelScope(scope)
            val iterator = AsyncFromSyncIterator(sync)
            iterator.parentScope = top
            iterator.prototype = prototype(top as ScriptableObject)
            return iterator
        }

        /** %AsyncFromSyncIteratorPrototype%, which no script can reach. */
        private fun prototype(top: ScriptableObject): Scriptable {
            (top.getAssociatedValue(PROTOTYPE_TAG) as? Scriptable)?.let { return it }
            val proto = NativeObject()
            proto.parentScope = top
            proto.prototype = NativeAsyncGenerator.asyncIteratorPrototype(top)
            proto.defineProperty("next", method(top, "next") { cx, s, it, args -> it.next(cx, s, args) }, DONTENUM)
            proto.defineProperty("return", method(top, "return") { cx, s, it, args -> it.doReturn(cx, s, args) }, DONTENUM)
            proto.defineProperty("throw", method(top, "throw") { cx, s, it, args -> it.doThrow(cx, s, args) }, DONTENUM)
            return top.associateValue(PROTOTYPE_TAG, proto) as Scriptable
        }

        private fun method(
            top: Scriptable,
            name: String,
            body: (Context, Scriptable, AsyncFromSyncIterator, Array<Any?>) -> Any?,
        ): LambdaFunction = LambdaFunction(top, name, 1, SerializableCallable { cx, s, thisObj, args ->
            body(cx, s, thisObj as? AsyncFromSyncIterator ?: throw ScriptRuntime.typeErrorById("msg.incompat.call", name), args)
        })

        private fun errorValue(cx: Context, scope: Scriptable, e: RhinoException): Any? =
            if (e is JavaScriptException) e.value else ScriptRuntime.wrapException(e, scope, cx)
    }
}
