/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

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

            val callable = ensureScriptable(args[0])

            var self = thisObj
            if (args[1] is Scriptable) {
                self = args[1] as Scriptable
            } else if (ScriptRuntime.isPrimitive(args[1])) {
                self = cx.newObject(scope, "Object", arrayOf(args[1]))
            }

            if (ScriptRuntime.isSymbol(args[2])) {
                throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeOf(args[2]))
            }
            val argumentsList = ensureScriptableObject(args[2])

            return ScriptRuntime.applyOrCall(true, cx, scope, callable, arrayOf(self, argumentsList))
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
            if (args.size < 2) {
                return ctor.construct(cx, scope, ScriptRuntime.emptyArgs)
            }

            if (args.size > 2 && !AbstractEcmaObjectOperations.isConstructor(cx, args[2])) {
                throw ScriptRuntime.typeErrorById("msg.not.ctor", ScriptRuntime.typeOf(args[2]))
            }

            val callArgs = ScriptRuntime.getApplyArguments(cx, args[1])

            var newTargetPrototype: Any? = null
            if (args.size > 2) {
                val newTarget = ensureScriptable(args[2])

                newTargetPrototype = if (newTarget is BaseFunction) {
                    newTarget.prototypeProperty
                } else {
                    newTarget.get("prototype", newTarget)
                }

                if (newTargetPrototype !is Scriptable ||
                    ScriptRuntime.isSymbol(newTargetPrototype) ||
                    Undefined.isUndefined(newTargetPrototype)
                ) {
                    newTargetPrototype = null
                }
            }

            // Constructable carries no newTarget, so a function constructor is driven by hand here:
            // build the object, fix its prototype, then call.
            if (ctor is BaseFunction && newTargetPrototype != null) {
                val result = ctor.createObject(cx, scope)
                if (result != null) {
                    result.prototype = newTargetPrototype as Scriptable

                    val value = ctor.call(cx, scope, result, callArgs)
                    if (value is Scriptable) return value

                    return result
                }
            }

            val newScriptable = ctor.construct(cx, scope, callArgs)
            if (newTargetPrototype != null) {
                newScriptable.prototype = newTargetPrototype as Scriptable
            }

            return newScriptable
        }

        private fun defineProperty(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any {
            if (args.size < 3) {
                throw ScriptRuntime.typeErrorById(
                    "msg.method.missing.parameter",
                    "Reflect.defineProperty",
                    "3",
                    args.size.toString(),
                )
            }

            val target = checkTarget(args)
            val desc = DescriptorInfo(ensureScriptableObject(args[2]))

            val key = args[1]

            return try {
                if (key is Symbol) {
                    target.defineOwnProperty(cx, key, desc)
                } else {
                    val propertyKey =
                        ScriptRuntime.toString(ScriptRuntime.toPrimitive(key, ScriptRuntime.StringClass))
                    target.defineOwnProperty(cx, propertyKey, desc)
                }
            } catch (e: EcmaError) {
                false
            }
        }

        private fun deleteProperty(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any {
            val target = checkTarget(args)

            if (args.size > 1) {
                if (ScriptRuntime.isSymbol(args[1])) {
                    return deleteProperty(target, args[1] as Symbol)
                }
                return deleteProperty(target, ScriptRuntime.toString(args[1]))
            }

            return false
        }

        private fun get(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val target = checkTarget(args)

            if (args.size > 1) {
                val prop = when {
                    ScriptRuntime.isSymbol(args[1]) -> getProperty(target, args[1] as Symbol)
                    args[1] is Number -> getProperty(target, ScriptRuntime.toIndex(args[1]))
                    else -> getProperty(target, ScriptRuntime.toString(args[1]))
                }
                return if (prop === Scriptable.NOT_FOUND) Undefined.SCRIPTABLE_UNDEFINED else prop
            }
            return Undefined.SCRIPTABLE_UNDEFINED
        }

        private fun getOwnPropertyDescriptor(
            cx: Context,
            scope: Scriptable,
            thisObj: Scriptable?,
            args: Array<Any?>,
        ): Scriptable {
            val target = checkTarget(args)

            if (args.size > 1) {
                val desc = if (ScriptRuntime.isSymbol(args[1])) {
                    target.getOwnPropertyDescriptor(cx, args[1])
                } else {
                    target.getOwnPropertyDescriptor(cx, ScriptRuntime.toString(args[1]))
                }
                return desc?.toObject(scope) ?: Undefined.SCRIPTABLE_UNDEFINED
            }
            return Undefined.SCRIPTABLE_UNDEFINED
        }

        private fun getPrototypeOf(
            cx: Context,
            scope: Scriptable,
            thisObj: Scriptable?,
            args: Array<Any?>,
        ): Scriptable? = checkTarget(args).prototype

        private fun has(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any {
            val target = checkTarget(args)

            if (args.size > 1) {
                if (ScriptRuntime.isSymbol(args[1])) {
                    return hasProperty(target, args[1] as Symbol)
                }

                return hasProperty(target, ScriptRuntime.toString(args[1]))
            }
            return false
        }

        private fun isExtensible(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any =
            checkTarget(args).isExtensible

        private fun ownKeys(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Scriptable {
            val target = checkTarget(args)

            val strings = ArrayList<Any?>()
            val symbols = ArrayList<Any?>()

            val ids = target.startCompoundOp(false).use { target.getIds(it, true, true) }
            for (o in ids) {
                if (o is Symbol) symbols.add(o) else strings.add(ScriptRuntime.toString(o))
            }

            // Strings first, then symbols, which is the order the spec asks for.
            strings.addAll(symbols)
            return cx.newArray(scope, strings.toTypedArray())
        }

        private fun preventExtensions(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any =
            checkTarget(args).preventExtensions()

        private fun set(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any {
            val target = checkTarget(args)
            if (args.size < 2) return true

            val receiver = if (args.size > 3) ensureScriptableObject(args[3]) else target
            if (receiver !== target) {
                val descriptor = target.getOwnPropertyDescriptor(cx, args[1])
                if (descriptor != null) {
                    val setter = descriptor.setter
                    if (setter != null && setter !== Scriptable.NOT_FOUND) {
                        (setter as Function).call(cx, scope, receiver, arrayOf(args[2]))
                        return true
                    }

                    if (descriptor.isConfigurable(false)) return false
                }
            }

            if (ScriptRuntime.isSymbol(args[1])) {
                receiver.put(args[1] as Symbol, receiver, args[2])
            } else {
                val s = ScriptRuntime.toStringIdOrIndex(args[1])
                if (s.stringId == null) {
                    receiver.put(s.index, receiver, args[2])
                } else {
                    receiver.put(s.stringId, receiver, args[2])
                }
            }

            return true
        }

        private fun setPrototypeOf(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any {
            if (args.size < 2) {
                throw ScriptRuntime.typeErrorById(
                    "msg.method.missing.parameter",
                    "Reflect.js_setPrototypeOf",
                    "2",
                    args.size.toString(),
                )
            }

            val target = checkTarget(args)

            if (target.prototype === args[1]) return true

            if (!target.isExtensible) return false

            if (args[1] == null) {
                target.prototype = null
                return true
            }

            if (ScriptRuntime.isSymbol(args[1])) {
                throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeOf(args[0]))
            }

            val proto = ensureScriptableObject(args[1])
            if (target.prototype === proto) return true

            // A prototype chain may not loop back onto the target.
            var p: Scriptable? = proto
            while (p != null) {
                if (target === p) return false
                p = p.prototype
            }

            target.prototype = proto
            return true
        }

        private fun checkTarget(args: Array<Any?>): ScriptableObject {
            if (args.isEmpty() || args[0] == null || args[0] === Undefined.instance) {
                val argument = if (args.isEmpty()) Undefined.instance else args[0]
                throw ScriptRuntime.typeErrorById("msg.no.properties", ScriptRuntime.toString(argument))
            }

            if (ScriptRuntime.isSymbol(args[0])) {
                throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeOf(args[0]))
            }
            return ensureScriptableObject(args[0])
        }
    }
}
