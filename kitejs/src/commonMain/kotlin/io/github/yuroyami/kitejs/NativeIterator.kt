/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The legacy `Iterator` object from JavaScript 1.7: it walks an object's own properties and throws
 * `StopIteration` when there is nothing left. The ES6 protocol lives in [ES6Iterator].
 *
 * Upstream also wraps a `java.util.Iterator` here. That is LiveConnect and is not ported.
 */
class NativeIterator private constructor(private val objectIterator: Any?) : ScriptableObject() {

    /** Only for building the prototype object. */
    private constructor() : this(null)

    override val className: String
        get() = CLASS_NAME

    private fun next(cx: Context, scope: Scriptable): Any? {
        if (!ScriptRuntime.enumNext(objectIterator, cx)) {
            // Out of values.
            throw JavaScriptException(getStopIterationObject(scope), null, 0)
        }
        return ScriptRuntime.enumId(objectIterator, cx)
    }

    /** The value a legacy generator throws when it runs out. It has no constructor of its own. */
    open class StopIteration(val value: Any? = Undefined.instance) : NativeObject() {
        override val className: String
            get() = STOP_ITERATION

        override fun hasInstance(instance: Scriptable): Boolean = instance is StopIteration
    }

    companion object {
        private val ITERATOR_TAG: Any = "Iterator"
        private const val CLASS_NAME = "Iterator"
        private const val STOP_ITERATION = "StopIteration"

        const val ITERATOR_PROPERTY_NAME: String = "__iterator__"

        internal fun init(cx: Context, scope: ScriptableObject, sealed: Boolean) {
            val constructor = LambdaConstructor(
                scope,
                CLASS_NAME,
                2,
                SerializableCallable { icx, s, thisObj, args -> jsConstructorCall(icx, s, thisObj, args) },
                SerializableConstructable { icx, s, args -> jsConstructor(icx, s, args) },
            )
            constructor.setPrototypePropertyAttributes(PERMANENT or READONLY or DONTENUM)
            constructor.setPrototypeScriptable(NativeIterator())

            constructor.definePrototypeMethod(scope, "next", 0, SerializableCallable { icx, s, thisObj, args -> js_next(icx, s, thisObj, args) })
            constructor.definePrototypeMethod(scope, ITERATOR_PROPERTY_NAME, 1, SerializableCallable { _, _, thisObj, _ -> thisObj })

            defineProperty(scope, CLASS_NAME, constructor, DONTENUM)
            if (sealed) {
                constructor.sealObject()
                (constructor.prototypeProperty as ScriptableObject).sealObject()
            }

            if (cx.languageVersion >= Context.VERSION_ES6) {
                ES6Generator.init(scope, sealed)
            } else {
                NativeGenerator.init(scope, sealed)
            }

            val stop: NativeObject = StopIteration()
            stop.prototype = getObjectPrototype(scope)
            stop.parentScope = scope
            if (sealed) stop.sealObject()
            defineProperty(scope, STOP_ITERATION, stop, DONTENUM)
            // Associated as well as defined, so a generator can still throw StopIteration after a
            // script replaces or deletes the global property.
            scope.associateValue(ITERATOR_TAG, stop)
        }

        /**
         * The `StopIteration` value, read from the top scope's associated values so a script that
         * overwrote the global property cannot break the generators.
         */
        fun getStopIterationObject(scope: Scriptable): Any? =
            getTopScopeValue(getTopLevelScope(scope), ITERATOR_TAG)

        private fun jsConstructorCall(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val target = requireIteratorTarget(cx, scope, args)
            val keyOnly = isKeyOnly(args)
            return ScriptRuntime.toIterator(cx, target, keyOnly)
                ?: createNativeIterator(cx, scope, target, keyOnly)
        }

        private fun jsConstructor(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
            val target = requireIteratorTarget(cx, scope, args)
            return createNativeIterator(cx, scope, target, isKeyOnly(args))
        }

        private fun requireIteratorTarget(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
            if (args.isEmpty() || args[0] == null || args[0] === Undefined.instance) {
                val argument = if (args.isEmpty()) Undefined.instance else args[0]
                throw ScriptRuntime.typeErrorById("msg.no.properties", ScriptRuntime.toString(argument))
            }
            return ScriptRuntime.toObject(cx, scope, args[0])
        }

        private fun isKeyOnly(args: Array<Any?>): Boolean =
            args.size > 1 && ScriptRuntime.toBoolean(args[1])

        private fun js_next(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            realThis(thisObj).next(cx, scope)

        private fun createNativeIterator(cx: Context, scope: Scriptable, obj: Scriptable, keyOnly: Boolean): NativeIterator {
            val enumType = if (keyOnly) ScriptRuntime.ENUMERATE_KEYS_NO_ITERATOR else ScriptRuntime.ENUMERATE_ARRAY_NO_ITERATOR
            val objectIterator = ScriptRuntime.enumInit(obj, cx, scope, enumType)
            ScriptRuntime.setEnumNumbers(objectIterator, true)
            val result = NativeIterator(objectIterator)
            result.prototype = getClassPrototype(scope, CLASS_NAME)
            result.parentScope = scope
            return result
        }

        private fun realThis(thisObj: Scriptable?): NativeIterator =
            LambdaConstructor.convertThisObject<NativeIterator>(thisObj)
    }
}
