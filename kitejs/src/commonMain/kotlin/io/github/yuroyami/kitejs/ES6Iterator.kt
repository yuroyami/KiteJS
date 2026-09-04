/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The base for the built-in iterators (array, string, map, set and the rest). A subclass says
 * when it is done and what the next value is; this class turns that into `{value, done}` results.
 */
abstract class ES6Iterator : ScriptableObject {

    protected var exhausted = false
    private var tag: String? = null

    protected constructor() : super()

    protected constructor(scope: Scriptable, tag: String) : super() {
        this.tag = tag
        val top = getTopLevelScope(scope)
        parentScope = top
        val prototype = getTopScopeValue(top, tag) as ScriptableObject
        this.prototype = prototype
    }

    protected abstract fun isDone(cx: Context, scope: Scriptable): Boolean

    protected abstract fun nextValue(cx: Context, scope: Scriptable): Any?

    protected open fun next(cx: Context, scope: Scriptable): Any? {
        var value: Any? = Undefined.instance
        val done = isDone(cx, scope) || exhausted
        if (!done) {
            value = nextValue(cx, scope)
        } else {
            exhausted = true
        }
        return makeIteratorResult(cx, scope, done, value)
    }

    protected open fun getTag(): String? = tag

    companion object {
        const val NEXT_METHOD = "next"
        const val DONE_PROPERTY = "done"
        const val RETURN_PROPERTY = "return"
        const val VALUE_PROPERTY = "value"
        const val RETURN_METHOD = "return"

        /** Installs [prototype] as the shared prototype for iterators tagged [tag]. */
        internal fun init(scope: ScriptableObject?, sealed: Boolean, prototype: ScriptableObject, tag: String) {
            if (scope != null) {
                prototype.parentScope = scope
                prototype.prototype = getObjectPrototype(scope)
            }
            val next = LambdaFunction(scope!!, NEXT_METHOD, 0, SerializableCallable { cx, s, thisObj, args -> js_next(cx, s, thisObj, args) })
            defineProperty(prototype, NEXT_METHOD, next, DONTENUM)
            val iterator = LambdaFunction(scope, "[Symbol.iterator]", 1, SerializableCallable { cx, s, thisObj, args -> js_iterator(cx, s, thisObj, args) })
            prototype.defineProperty(SymbolKey.ITERATOR, iterator, DONTENUM)
            prototype.defineProperty(SymbolKey.TO_STRING_TAG, prototype.className, DONTENUM or READONLY)
            if (sealed) prototype.sealObject()
            scope.associateValue(tag, prototype)
        }

        private fun realThis(thisObj: Scriptable?): ES6Iterator =
            LambdaConstructor.convertThisObject<ES6Iterator>(thisObj)

        private fun js_next(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            realThis(thisObj).next(cx, scope)

        private fun js_iterator(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            thisObj

        internal fun makeIteratorResult(cx: Context, scope: Scriptable, done: Boolean): Scriptable =
            makeIteratorResult(cx, scope, done, Undefined.instance)

        internal fun makeIteratorResult(cx: Context, scope: Scriptable, done: Boolean, value: Any?): Scriptable {
            val iteratorResult = cx.newObject(scope)
            putProperty(iteratorResult, VALUE_PROPERTY, value)
            putProperty(iteratorResult, DONE_PROPERTY, done)
            return iteratorResult
        }
    }
}
