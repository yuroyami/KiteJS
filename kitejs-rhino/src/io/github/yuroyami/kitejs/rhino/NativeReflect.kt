/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

/**
 * `Reflect`: the object operations the engine performs internally, exposed as plain functions.
 *
 * Each method here matches one proxy trap, which is what makes a handler able to say "do the normal
 * thing" by forwarding to the matching `Reflect` call.
 */
internal class NativeReflect private constructor() : ScriptableObject() {

    override val className: String
        get() = "Reflect"

    companion object {
        private const val REFLECT_TAG = "Reflect"

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val reflect = NativeReflect()
            reflect.prototype = getObjectPrototype(scope)
            reflect.parentScope = scope

            reflect.defineBuiltinProperty(scope, "apply", 3, SerializableCallable(::apply))
            reflect.defineBuiltinProperty(scope, "construct", 2, SerializableCallable(::construct))
            reflect.defineBuiltinProperty(scope, "defineProperty", 3, SerializableCallable(::defineProperty))
            reflect.defineBuiltinProperty(scope, "deleteProperty", 2, SerializableCallable(::deleteProperty))
            reflect.defineBuiltinProperty(scope, "get", 2, SerializableCallable(::get))
            reflect.defineBuiltinProperty(
                scope,
                "getOwnPropertyDescriptor",
                2,
                SerializableCallable(::getOwnPropertyDescriptor),
            )
            reflect.defineBuiltinProperty(scope, "getPrototypeOf", 1, SerializableCallable(::getPrototypeOf))
            reflect.defineBuiltinProperty(scope, "has", 2, SerializableCallable(::has))
            reflect.defineBuiltinProperty(scope, "isExtensible", 1, SerializableCallable(::isExtensible))
            reflect.defineBuiltinProperty(scope, "ownKeys", 1, SerializableCallable(::ownKeys))
            reflect.defineBuiltinProperty(scope, "preventExtensions", 1, SerializableCallable(::preventExtensions))
            reflect.defineBuiltinProperty(scope, "set", 3, SerializableCallable(::set))
            reflect.defineBuiltinProperty(scope, "setPrototypeOf", 2, SerializableCallable(::setPrototypeOf))

            reflect.defineProperty(SymbolKey.TO_STRING_TAG, REFLECT_TAG, DONTENUM or READONLY)
            if (sealed) reflect.sealObject()
            return reflect
        }

        private fun apply(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (args.size < 3) {
                throw ScriptRuntime.typeErrorById(
                    "msg.method.missing.parameter",
                    "Reflect.apply",
                    "3",
                    args.size.toString(),
                )
            }

            val callable = args[0] as? Callable ?: throw ScriptRuntime.notFunctionError(args[0])
            val argumentsList = AbstractEcmaObjectOperations.ensureObject(args[2])
            val callArgs = ScriptRuntime.getApplyArguments(cx, argumentsList)
            val self = ScriptRuntime.getApplyOrCallThis(cx, scope, args[1], 1, callable)
            return callable.call(cx, scope, self, callArgs)
        }

        private fun construct(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Scriptable {
            if (args.isEmpty()) {
                throw ScriptRuntime.typeErrorById(
                    "msg.method.missing.parameter",
                    "Reflect.construct",
                    "3",
                    args.size.toString(),
                )
            }

            if (!AbstractEcmaObjectOperations.isConstructor(cx, args[0])) {
                throw ScriptRuntime.typeErrorById("msg.not.ctor", ScriptRuntime.typeOf(args[0]))
            }

            val ctor = args[0] as Constructable
            if (args.size > 2 && !AbstractEcmaObjectOperations.isConstructor(cx, args[2])) {
                throw ScriptRuntime.typeErrorById("msg.not.ctor", ScriptRuntime.typeOf(args[2]))
            }

            // CreateListFromArrayLike demands an object even when no argument will be passed, so a
            // missing list is a TypeError; upstream constructed with no arguments (D-89).
            val argumentsList = args.getOrElse(1) { Undefined.instance }
            if (!ScriptRuntime.isObject(argumentsList)) {
                throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeOf(argumentsList))
            }
            val callArgs = ScriptRuntime.getApplyArguments(cx, argumentsList)

            // Construct(target, args, newTarget). Upstream built a function's object by hand with
            // newTarget's prototype and then called the function, which for a built-in that can
            // also be called without `new` returned a fresh object ignoring newTarget and for one
            // that cannot threw; anything else got newTarget's prototype afterwards, a construct
            // trap's answer included (D-91).
            val newTarget = if (args.size > 2) args[2] as Scriptable else ctor as Scriptable
            return AbstractEcmaObjectOperations.construct(cx, scope, ctor, callArgs, newTarget)
        }

        /**
         * Reflect.defineProperty: the key is converted before the descriptor is read, both may
         * throw, and only the definition itself answers false. Upstream turned every TypeError the
         * engine raised into false, a proxy invariant violation or a `null.x` inside a trap
         * included, and accepted a getter that is not callable (D-89).
         */
        private fun defineProperty(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any {
            val target = checkTarget(args)
            val key = ScriptRuntime.toPropertyKey(args.getOrElse(1) { Undefined.instance })
            val desc = DescriptorInfo(AbstractEcmaObjectOperations.ensureObject(args.getOrElse(2) { Undefined.instance }))
            checkPropertyDefinition(desc)
            return AbstractEcmaObjectOperations.defineOwnPropertyOrFalse(cx, target, key, desc)
        }

        /**
         * Reflect.deleteProperty: [[Delete]] on the target alone. Upstream searched the prototype
         * chain for the property and deleted it wherever it found it, so
         * `Reflect.deleteProperty({}, 'toString')` removed Object.prototype.toString (D-89).
         */
        private fun deleteProperty(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any {
            val target = checkTarget(args)
            val key = ScriptRuntime.toPropertyKey(args.getOrElse(1) { Undefined.instance })
            return AbstractEcmaObjectOperations.delete(cx, target, key)
        }

        /**
         * Reflect.get: ToPropertyKey, then the target's [[Get]] with the receiver as `this` for a
         * getter. Upstream ignored the receiver, treated a numeric key as an array index through
         * ToIndex, and answered undefined for an omitted key instead of reading "undefined" (D-89).
         */
        private fun get(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val target = checkTarget(args)
            val key = ScriptRuntime.toPropertyKey(args.getOrElse(1) { Undefined.instance })
            val receiver = if (args.size > 2) args[2] else target
            return AbstractEcmaObjectOperations.get(cx, target, key, receiver)
        }

        private fun getOwnPropertyDescriptor(
            cx: Context,
            scope: Scriptable,
            thisObj: Scriptable?,
            args: Array<Any?>,
        ): Scriptable {
            val target = checkTarget(args)
            val key = ScriptRuntime.toPropertyKey(args.getOrElse(1) { Undefined.instance })
            return target.getOwnPropertyDescriptor(cx, key)?.toObject(scope) ?: Undefined.SCRIPTABLE_UNDEFINED
        }

        private fun getPrototypeOf(
            cx: Context,
            scope: Scriptable,
            thisObj: Scriptable?,
            args: Array<Any?>,
        ): Scriptable? = checkTarget(args).prototype

        private fun has(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any {
            val target = checkTarget(args)
            return when (val key = ScriptRuntime.toPropertyKey(args.getOrElse(1) { Undefined.instance })) {
                is Symbol -> hasProperty(target, key)
                is Int -> hasProperty(target, key)
                else -> hasProperty(target, key as String)
            }
        }

        private fun isExtensible(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any =
            checkTarget(args).isExtensible

        private fun ownKeys(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Scriptable {
            val target = checkTarget(args)

            // Ordinary objects order their own keys; proxies and hosts may supply another order.
            val ids: Array<Any?> = target.ownPropertyKeys().map {
                if (it is Symbol) it else ScriptRuntime.toString(it)
            }.toTypedArray()
            return cx.newArray(scope, ids)
        }

        private fun preventExtensions(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any =
            checkTarget(args).preventExtensions()

        /**
         * Reflect.set: the target's [[Set]] with the receiver, answering whether the write was
         * made. Upstream wrote straight to the receiver and answered true whatever happened, read
         * a missing value out of range, and with a separate receiver refused a write for a
         * non-configurable property where only a read-only one should refuse it (D-89).
         */
        private fun set(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any {
            val target = checkTarget(args)
            val key = ScriptRuntime.toPropertyKey(args.getOrElse(1) { Undefined.instance })
            val value = args.getOrElse(2) { Undefined.instance }
            val receiver = if (args.size > 3) args[3] else target
            return AbstractEcmaObjectOperations.set(cx, target, key, value, receiver)
        }

        /**
         * Reflect.setPrototypeOf: the target's [[SetPrototypeOf]] answer. Upstream checked for
         * itself and assigned the property, so a proxy's trap result was ignored and a cycle
         * through a proxy called its getPrototypeOf trap (D-91).
         */
        private fun setPrototypeOf(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any {
            // A missing prototype is undefined, which is no object either; upstream's message for it stays.
            if (args.size < 2) {
                throw ScriptRuntime.typeErrorById("msg.method.missing.parameter", "Reflect.js_setPrototypeOf", "2", args.size.toString())
            }
            val target = checkTarget(args)
            val proto = args[1]
            if (proto != null && (proto !is Scriptable || !ScriptRuntime.isObject(proto))) {
                throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeOf(proto))
            }
            return target.setPrototypeOf(cx, proto as Scriptable?)
        }

        private fun checkTarget(args: Array<Any?>): Scriptable {
            if (args.isEmpty() || args[0] == null || Undefined.isUndefined(args[0])) {
                val argument = if (args.isEmpty()) Undefined.instance else args[0]
                throw ScriptRuntime.typeErrorById("msg.no.properties", ScriptRuntime.toString(argument))
            }

            return AbstractEcmaObjectOperations.ensureObject(args[0])
        }
    }
}
