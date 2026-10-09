/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

/**
 * An iterator record that an array pattern or a for-of loop has open (ECMAScript 2015, 7.4).
 * The interpreter keeps the open ones of each frame, so a throw out of the pattern or the loop
 * body closes them (#83). [openPc] is where it was opened, which tells whether a try block
 * that catches the throw lies around the pattern or loop or inside it.
 */
internal class OpenIterator(
    internal val iterator: Scriptable,
    internal val nextMethod: Any?,
    val openPc: Int,
    val isAsync: Boolean = false,
) {
    var done = false
        private set

    /**
     * For a `for await` loop: set when a throw leaves the loop, so its close keeps that throw
     * and ignores whatever `return` does.
     */
    var thrown = false

    /** For a `for await` loop: the value of the last step, or what `return` answered. */
    private var pending: Any? = null

    /** IteratorStep then IteratorValue: the next value, or [Scriptable.NOT_FOUND] once done. */
    fun step(cx: Context, scope: Scriptable): Any? {
        if (done) return Scriptable.NOT_FOUND
        // A step that throws leaves the record done, so nothing closes the iterator (7.4.5).
        done = true
        val next = nextMethod as? Callable ?: throw ScriptRuntime.typeErrorById("msg.isnt.function", "iterator.next", ScriptRuntime.typeOf(nextMethod))
        val result = next.call(cx, scope, iterator, ScriptRuntime.emptyArgs)
        if (result !is Scriptable) throw ScriptRuntime.typeErrorById("msg.iterator.result.not.object", ScriptRuntime.toString(result))
        if (ScriptRuntime.toBoolean(read(result, ES6Iterator.DONE_PROPERTY))) return Scriptable.NOT_FOUND
        val value = read(result, ES6Iterator.VALUE_PROPERTY)
        done = false
        return value
    }

    /** What is left, in a new array; the record is done after it. */
    fun rest(cx: Context, scope: Scriptable): Scriptable {
        val values = ArrayList<Any?>()
        while (true) {
            val value = step(cx, scope)
            if (value === Scriptable.NOT_FOUND) break
            values.add(value)
        }
        return cx.newArray(scope, values.toTypedArray())
    }

    /**
     * IteratorClose (7.4.6), unless the record is done. After a throw, [quiet] keeps that throw:
     * the `return` method still runs, but whatever it throws or answers is ignored.
     */
    fun close(cx: Context, scope: Scriptable, quiet: Boolean) {
        if (done) return
        done = true
        try {
            val method = read(iterator, ES6Iterator.RETURN_PROPERTY)
            if (method == null || Undefined.isUndefined(method)) return
            if (method !is Callable) throw ScriptRuntime.typeErrorById("msg.isnt.function", "iterator.return", ScriptRuntime.typeOf(method))
            val result = method.call(cx, scope, iterator, ScriptRuntime.emptyArgs)
            if (result !is Scriptable) throw ScriptRuntime.typeErrorById("msg.iterator.result.not.object", ScriptRuntime.toString(result))
        } catch (e: RhinoException) {
            if (!quiet) throw e
        }
    }

    /** Calls `next` for a `for await` loop; the record stays done until [asyncStep] reads a value. */
    fun asyncNext(cx: Context, scope: Scriptable): Any? {
        done = true
        val next = nextMethod as? Callable ?: throw ScriptRuntime.typeErrorById("msg.isnt.function", "iterator.next", ScriptRuntime.typeOf(nextMethod))
        return next.call(cx, scope, iterator, ScriptRuntime.emptyArgs)
    }

    /** Whether the awaited [result] of `next` has a value, which [asyncValue] then hands over. */
    fun asyncStep(result: Any?): Boolean {
        if (result !is Scriptable) throw ScriptRuntime.typeErrorById("msg.iterator.result.not.object", ScriptRuntime.toString(result))
        if (ScriptRuntime.toBoolean(read(result, ES6Iterator.DONE_PROPERTY))) return false
        pending = read(result, ES6Iterator.VALUE_PROPERTY)
        done = false
        return true
    }

    fun asyncValue(): Any? = pending.also { pending = null }

    /**
     * The first half of AsyncIteratorClose (ECMAScript 2018, 7.4.7): calls `return` unless the
     * record is done, and says whether it did; [asyncResult] then holds what it answered. After
     * a throw a failure here is ignored.
     */
    fun asyncClose(cx: Context, scope: Scriptable): Boolean {
        if (done) return false
        done = true
        try {
            val method = read(iterator, ES6Iterator.RETURN_PROPERTY)
            if (method == null || Undefined.isUndefined(method)) return false
            if (method !is Callable) throw ScriptRuntime.typeErrorById("msg.isnt.function", "iterator.return", ScriptRuntime.typeOf(method))
            pending = method.call(cx, scope, iterator, ScriptRuntime.emptyArgs)
            return true
        } catch (e: RhinoException) {
            if (!thrown) throw e
            return false
        }
    }

    fun asyncResult(): Any? = pending.also { pending = null }

    internal companion object {
        /** GetIterator (7.4.1): a TypeError for a value with no callable `Symbol.iterator`. */
        fun open(cx: Context, scope: Scriptable, value: Any?, pc: Int): OpenIterator {
            if (value == null || Undefined.isUndefined(value)) throw notIterable(value)
            val method = ScriptRuntime.getV(cx, scope, value, SymbolKey.ITERATOR)
            if (method !is Callable) throw notIterable(value)
            val iterator = method.call(cx, scope, ScriptRuntime.toReceiver(cx, value, scope)!!, ScriptRuntime.emptyArgs)
            if (iterator !is Scriptable) throw ScriptRuntime.typeErrorById("msg.iterator.result.not.object", ScriptRuntime.toString(iterator))
            return OpenIterator(iterator, read(iterator, ES6Iterator.NEXT_METHOD), pc)
        }

        /**
         * GetIterator with the async hint (ECMAScript 2018, 7.4.1): the value's
         * `Symbol.asyncIterator`, or its `Symbol.iterator` wrapped by [AsyncFromSyncIterator].
         */
        fun openAsync(cx: Context, scope: Scriptable, value: Any?, pc: Int): OpenIterator {
            if (value == null || Undefined.isUndefined(value)) throw notIterable(value)
            val method = ScriptRuntime.getV(cx, scope, value, SymbolKey.ASYNC_ITERATOR)
            if (method === Scriptable.NOT_FOUND || method == null || Undefined.isUndefined(method)) {
                val sync = open(cx, scope, value, pc)
                val wrapper = AsyncFromSyncIterator.create(cx, scope, sync)
                return OpenIterator(wrapper, read(wrapper, ES6Iterator.NEXT_METHOD), pc, isAsync = true)
            }
            if (method !is Callable) throw notIterable(value)
            val iterator = method.call(cx, scope, ScriptRuntime.toReceiver(cx, value, scope)!!, ScriptRuntime.emptyArgs)
            if (iterator !is Scriptable) throw ScriptRuntime.typeErrorById("msg.iterator.result.not.object", ScriptRuntime.toString(iterator))
            return OpenIterator(iterator, read(iterator, ES6Iterator.NEXT_METHOD), pc, isAsync = true)
        }

        internal fun read(obj: Scriptable, name: String): Any? =
            ScriptableObject.getProperty(obj, name).let { if (it === Scriptable.NOT_FOUND) Undefined.instance else it }

        /** Names an object by its type, so building the message never calls back into script. */
        private fun notIterable(value: Any?): EcmaError {
            val name = if (value is Scriptable || ScriptRuntime.isSymbol(value)) ScriptRuntime.typeOf(value) else ScriptRuntime.toString(value)
            return ScriptRuntime.typeErrorById("msg.not.iterable", name)
        }
    }
}
