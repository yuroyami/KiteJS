/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

/**
 * The base for the built-in iterators (array, string, map, set and the rest). A subclass says
 * when it is done and what the next value is; this class turns that into `{value, done}` results.
 */
public abstract class ES6Iterator : ScriptableObject {

    protected var exhausted: Boolean = false
    private var tagValue: String? = null

    protected constructor() : super()

    protected constructor(scope: Scriptable, tag: String) : super() {
        this.tagValue = tag
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

    protected open val tag: String? get() = tagValue

    public companion object {
        public const val NEXT_METHOD: String = "next"
        public const val DONE_PROPERTY: String = "done"
        public const val RETURN_PROPERTY: String = "return"
        public const val VALUE_PROPERTY: String = "value"
        public const val RETURN_METHOD: String = "return"

        /** The key %IteratorPrototype% is parked under on the top scope. */
        private const val ITERATOR_PROTOTYPE_TAG = "IteratorPrototype"

        /**
         * Installs [prototype] as the shared prototype for iterators tagged [tag]. It inherits
         * %IteratorPrototype%, which holds `[Symbol.iterator]`; upstream gave each iterator
         * prototype a copy of its own and Object.prototype as its prototype (D-93).
         */
        internal fun init(scope: ScriptableObject, sealed: Boolean, prototype: ScriptableObject, tag: String) {
            prototype.parentScope = scope
            prototype.prototype = iteratorPrototype(scope, sealed)
            val next = LambdaFunction(scope, NEXT_METHOD, 0, SerializableCallable { cx, s, thisObj, args -> js_next(cx, s, thisObj, args) })
            defineProperty(prototype, NEXT_METHOD, next, DONTENUM)
            prototype.defineProperty(SymbolKey.TO_STRING_TAG, prototype.className, DONTENUM or READONLY)
            if (sealed) prototype.sealObject()
            scope.associateValue(tag, prototype)
        }

        /**
         * %IteratorPrototype% (ES 25.1.2) of [scope]'s realm: an ordinary object whose one property
         * is `[Symbol.iterator]`, which answers its `this`, and which the array, string, map, set,
         * regexp string and generator iterator prototypes all inherit. The first of them makes it.
         */
        internal fun iteratorPrototype(scope: ScriptableObject, sealed: Boolean): ScriptableObject {
            (scope.getAssociatedValue(ITERATOR_PROTOTYPE_TAG) as? ScriptableObject)?.let { return it }
            val proto = NativeObject()
            proto.parentScope = scope
            proto.prototype = getObjectPrototype(scope)
            // A primitive `this` comes back as it was.
            val iterator = LambdaFunction(scope, "[Symbol.iterator]", 0, SerializableCallable { _, _, thisObj, _ -> ScriptRuntime.takeReceiver(thisObj) ?: thisObj })
            proto.defineProperty(SymbolKey.ITERATOR, iterator, DONTENUM)
            // The helpers of ECMAScript 2025 go on it before it can be sealed (#113).
            if ((Context.getCurrentContext()?.languageVersion ?: 0) >= Context.VERSION_ES6) IteratorHelpers.installOn(proto, scope)
            if (sealed) proto.sealObject()
            return scope.associateValue(ITERATOR_PROTOTYPE_TAG, proto) as ScriptableObject
        }

        private fun realThis(thisObj: Scriptable?): ES6Iterator =
            LambdaConstructor.convertThisObject<ES6Iterator>(thisObj)

        private fun js_next(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            realThis(thisObj).next(cx, scope)

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
