/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.typedarrays

import io.github.yuroyami.kitejs.Context
import io.github.yuroyami.kitejs.KBigInt
import io.github.yuroyami.kitejs.LambdaConstructor
import io.github.yuroyami.kitejs.ScriptRuntime
import io.github.yuroyami.kitejs.ScriptRuntimeES6
import io.github.yuroyami.kitejs.Scriptable
import io.github.yuroyami.kitejs.ScriptableObject
import io.github.yuroyami.kitejs.SerializableConstructable
import io.github.yuroyami.kitejs.Undefined

/**
 * The two 64-bit views. They hold bigints rather than doubles, which is the whole reason they wait
 * for [KBigInt]: no double can carry 64 bits of integer without losing some.
 */
abstract class NativeBigIntArrayView : NativeTypedArrayView {

    protected constructor() : super()

    protected constructor(ab: NativeArrayBuffer, off: Int, len: Int, byteLen: Int) : super(ab, off, len, byteLen)

    /** Values written into one of these are converted with ToBigInt, not ToNumber. */
    override fun toNumeric(num: Any?): Any? = ScriptRuntime.toBigInt(num)
}

/** `BigInt64Array`: eight bytes per element, read back as a signed bigint. */
class NativeBigInt64Array : NativeBigIntArrayView {

    constructor() : super()

    constructor(ab: NativeArrayBuffer, off: Int, len: Int) : super(ab, off, len, len * BYTES_PER_ELEMENT)

    constructor(len: Int) : this(NativeArrayBuffer(len * BYTES_PER_ELEMENT), 0, len)

    override val className: String
        get() = CLASS_NAME

    override fun getBytesPerElement(): Int = BYTES_PER_ELEMENT

    override fun js_get(index: Int): Any? {
        if (checkIndex(index)) return Undefined.instance
        val base = ByteIo.readUint64Primitive(
            arrayBuffer.buffer!!,
            (index * BYTES_PER_ELEMENT) + offset,
            NativeArrayBufferView.useLittleEndian(),
        )
        // A Long is already the signed 64-bit reading, so nothing to correct.
        return KBigInt.fromLong(base)
    }

    override fun js_set(index: Int, c: Any?): Any? {
        val value = ScriptRuntime.toBigInt(c)
        if (checkIndex(index)) return Undefined.instance
        ByteIo.writeUint64(
            arrayBuffer.buffer!!,
            (index * BYTES_PER_ELEMENT) + offset,
            value.toLong(),
            NativeArrayBufferView.useLittleEndian(),
        )
        return null
    }

    companion object {
        private const val CLASS_NAME = "BigInt64Array"
        private const val BYTES_PER_ELEMENT = 8

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val constructor = LambdaConstructor(
                scope,
                CLASS_NAME,
                3,
                LambdaConstructor.CONSTRUCTOR_NEW,
                SerializableConstructable { icx, s, args ->
                    NativeTypedArrayView.js_constructor(
                        icx,
                        s,
                        args,
                        { ab, off, len -> NativeBigInt64Array(ab, off, len) },
                        BYTES_PER_ELEMENT,
                    )
                },
            )
            constructor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)
            NativeTypedArrayView.init(cx, scope, constructor)
            constructor.defineProperty("BYTES_PER_ELEMENT", BYTES_PER_ELEMENT, DONTENUM or READONLY or PERMANENT)
            constructor.definePrototypeProperty("BYTES_PER_ELEMENT", BYTES_PER_ELEMENT, DONTENUM or READONLY or PERMANENT)

            ScriptRuntimeES6.addSymbolSpecies(cx, scope, constructor)
            if (sealed) {
                constructor.sealObject()
                (constructor.prototypeProperty as ScriptableObject).sealObject()
            }
            return constructor
        }
    }
}

/** `BigUint64Array`: the same eight bytes, read back as a bigint that is never negative. */
class NativeBigUint64Array : NativeBigIntArrayView {

    constructor() : super()

    constructor(ab: NativeArrayBuffer, off: Int, len: Int) : super(ab, off, len, len * BYTES_PER_ELEMENT)

    constructor(len: Int) : this(NativeArrayBuffer(len * BYTES_PER_ELEMENT), 0, len)

    override val className: String
        get() = CLASS_NAME

    override fun getBytesPerElement(): Int = BYTES_PER_ELEMENT

    override fun js_get(index: Int): Any? {
        if (checkIndex(index)) return Undefined.instance
        val base = ByteIo.readUint64Primitive(
            arrayBuffer.buffer!!,
            (index * BYTES_PER_ELEMENT) + offset,
            NativeArrayBufferView.useLittleEndian(),
        )
        if ((base and Long.MIN_VALUE) == 0L) return KBigInt.fromLong(base)
        // The top bit is set, so the Long reads as negative. Rebuild it from two halves instead.
        val low = KBigInt.fromLong(base and 0xFFFFFFFFL)
        val high = KBigInt.fromLong((base shr 32) and 0xFFFFFFFFL).shiftLeft(32)
        return high.add(low)
    }

    override fun js_set(index: Int, c: Any?): Any? {
        val value = ScriptRuntime.toBigInt(c)
        if (checkIndex(index)) return Undefined.instance
        ByteIo.writeUint64(
            arrayBuffer.buffer!!,
            (index * BYTES_PER_ELEMENT) + offset,
            value.toLong(),
            NativeArrayBufferView.useLittleEndian(),
        )
        return null
    }

    companion object {
        private const val CLASS_NAME = "BigUint64Array"
        private const val BYTES_PER_ELEMENT = 8

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val constructor = LambdaConstructor(
                scope,
                CLASS_NAME,
                3,
                LambdaConstructor.CONSTRUCTOR_NEW,
                SerializableConstructable { icx, s, args ->
                    NativeTypedArrayView.js_constructor(
                        icx,
                        s,
                        args,
                        { ab, off, len -> NativeBigUint64Array(ab, off, len) },
                        BYTES_PER_ELEMENT,
                    )
                },
            )
            constructor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)
            NativeTypedArrayView.init(cx, scope, constructor)
            constructor.defineProperty("BYTES_PER_ELEMENT", BYTES_PER_ELEMENT, DONTENUM or READONLY or PERMANENT)
            constructor.definePrototypeProperty("BYTES_PER_ELEMENT", BYTES_PER_ELEMENT, DONTENUM or READONLY or PERMANENT)

            ScriptRuntimeES6.addSymbolSpecies(cx, scope, constructor)
            if (sealed) {
                constructor.sealObject()
                (constructor.prototypeProperty as ScriptableObject).sealObject()
            }
            return constructor
        }
    }
}
