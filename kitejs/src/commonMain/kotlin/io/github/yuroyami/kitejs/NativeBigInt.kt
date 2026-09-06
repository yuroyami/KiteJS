/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The `BigInt` object: the wrapper a bigint gets when script asks it for a property, plus the
 * `BigInt` function and the two static methods that clip a value to a width.
 *
 * `new BigInt(...)` is a TypeError, the same as `new Symbol(...)`, because a bigint is a primitive.
 */
internal class NativeBigInt(private val bigIntValue: KBigInt) : ScriptableObject() {

    override val className: String
        get() = CLASS_NAME

    override fun toString(): String = ScriptRuntime.bigIntToString(bigIntValue, 10)

    companion object {
        private const val CLASS_NAME = "BigInt"

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val constructor = LambdaConstructor(
                scope,
                CLASS_NAME,
                1,
                SerializableCallable { icx, s, thisObj, args -> js_constructorFunc(args) },
                SerializableConstructable { _, _, _ -> js_constructor() },
            )
            constructor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)

            constructor.defineConstructorMethod(scope, "asIntN", 2, SerializableCallable { _, _, _, args ->
                js_asIntOrUintN(true, args)
            })
            constructor.defineConstructorMethod(scope, "asUintN", 2, SerializableCallable { _, _, _, args ->
                js_asIntOrUintN(false, args)
            })

            constructor.definePrototypeMethod(scope, "toString", 0, SerializableCallable { _, _, thisObj, args ->
                js_toString(thisObj, args)
            })
            // toLocaleString is toString here: there is no Intl to ask for anything else.
            constructor.definePrototypeMethod(scope, "toLocaleString", 0, SerializableCallable { _, _, thisObj, args ->
                js_toString(thisObj, args)
            })
            constructor.definePrototypeMethod(scope, "toSource", 0, SerializableCallable { _, _, thisObj, _ ->
                "(new BigInt(" + ScriptRuntime.toString(toSelf(thisObj).bigIntValue) + "))"
            })
            constructor.definePrototypeMethod(scope, "valueOf", 0, SerializableCallable { _, _, thisObj, _ ->
                toSelf(thisObj).bigIntValue
            })
            constructor.definePrototypeProperty(SymbolKey.TO_STRING_TAG, CLASS_NAME, DONTENUM or READONLY)

            if (sealed) {
                constructor.sealObject()
                (constructor.prototypeProperty as ScriptableObject).sealObject()
            }
            return constructor
        }

        private fun toSelf(thisObj: Scriptable?): NativeBigInt =
            LambdaConstructor.convertThisObject<NativeBigInt>(thisObj)

        private fun js_constructorFunc(args: Array<Any?>): Any =
            if (args.isNotEmpty()) ScriptRuntime.toBigInt(args[0]) else KBigInt.ZERO

        private fun js_constructor(): Scriptable =
            throw ScriptRuntime.typeErrorById("msg.no.new", CLASS_NAME)

        private fun js_toString(thisObj: Scriptable?, args: Array<Any?>): Any {
            val base = if (args.isEmpty() || args[0] == Undefined.instance) 10 else ScriptRuntime.toInt32(args[0])
            return ScriptRuntime.bigIntToString(toSelf(thisObj).bigIntValue, base)
        }

        /** Both `BigInt.asIntN` and `BigInt.asUintN`; [KBigInt] already knows the spec formula. */
        private fun js_asIntOrUintN(isSigned: Boolean, args: Array<Any?>): Any {
            val bits = ScriptRuntime.toIndex(if (args.isEmpty()) Undefined.instance else args[0])
            val value = ScriptRuntime.toBigInt(if (args.size < 2) Undefined.instance else args[1])
            if (bits == 0) return KBigInt.ZERO
            return if (isSigned) value.asIntN(bits) else value.asUintN(bits)
        }
    }
}
