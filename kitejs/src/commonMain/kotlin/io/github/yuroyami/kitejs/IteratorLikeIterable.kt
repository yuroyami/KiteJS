/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * Drives a script iterator from Kotlin: `next()` until `done`, and `return()` on close if the
 * iterator has one. Close it when done, or hand it to `use`.
 */
public class IteratorLikeIterable(
    private val cx: Context,
    private val scope: Scriptable,
    target: Any?,
) : Iterable<Any?>, AutoCloseable {

    private val next: Callable
    private val returnFunc: Callable?
    private val iterator: Scriptable?
    private var closed = false

    init {
        val nextCall = ScriptRuntime.getPropAndThis(target, ES6Iterator.NEXT_METHOD, cx, scope)!!
        next = nextCall.callable
        iterator = nextCall.thisObj
        val retObj = ScriptRuntime.getObjectPropNoWarn(target, ES6Iterator.RETURN_PROPERTY, cx, scope)
        returnFunc =
            if (retObj != null && !Undefined.isUndefined(retObj)) {
                retObj as? Callable ?: throw ScriptRuntime.notFunctionError(target, retObj, ES6Iterator.RETURN_PROPERTY)
            } else {
                null
            }
    }

    override fun close() {
        if (!closed) {
            closed = true
            returnFunc?.call(cx, scope, iterator, ScriptRuntime.emptyArgs)
        }
    }

    override fun iterator(): Itr = Itr()

    public inner class Itr : Iterator<Any?> {
        private var nextVal: Any? = null
        public var isDone: Boolean = false

        override fun hasNext(): Boolean {
            if (isDone) return false
            val v = next.call(cx, scope, iterator, ScriptRuntime.emptyArgs)
            var doneval = ScriptableObject.getProperty(ScriptableObject.ensureScriptable(v), ES6Iterator.DONE_PROPERTY)
            if (doneval === Scriptable.NOT_FOUND) doneval = Undefined.instance
            if (ScriptRuntime.toBoolean(doneval)) {
                isDone = true
                return false
            }
            nextVal = ScriptRuntime.getObjectPropNoWarn(v, ES6Iterator.VALUE_PROPERTY, cx, scope)
            return true
        }

        override fun next(): Any? {
            if (isDone) throw NoSuchElementException()
            return nextVal
        }
    }
}
