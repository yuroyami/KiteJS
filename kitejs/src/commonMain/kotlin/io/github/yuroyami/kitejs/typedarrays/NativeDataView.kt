/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.typedarrays

import io.github.yuroyami.kitejs.Context
import io.github.yuroyami.kitejs.LambdaConstructor
import io.github.yuroyami.kitejs.ScriptRuntime
import io.github.yuroyami.kitejs.Scriptable
import io.github.yuroyami.kitejs.ScriptableObject
import io.github.yuroyami.kitejs.SerializableCallable
import io.github.yuroyami.kitejs.SerializableConstructable
import io.github.yuroyami.kitejs.SymbolKey
import io.github.yuroyami.kitejs.Undefined

/**
 * `DataView`: reads and writes numbers at byte positions in a buffer, in either endianness, with no
 * alignment rules. The typed array views fix an element type and an endianness; this one does not.
 */
class NativeDataView : NativeArrayBufferView {

    constructor() : super()

    constructor(ab: NativeArrayBuffer, offset: Int, length: Int) : super(ab, offset, length)

    override val className: String
        get() = CLASS_NAME

    fun isDataViewOutOfBounds(): Boolean {
        if (arrayBuffer.isDetached()) return true
        val bufferByteLength = arrayBuffer.length
        return offset > bufferByteLength || offset + byteLength > bufferByteLength
    }

    private fun js_getInt(bytes: Int, signed: Boolean, args: Array<Any?>): Any? {
        val pos = ScriptRuntime.toIndex(if (isArg(args, 0)) args[0] else Undefined.instance)
        val littleEndian = isArg(args, 1) && bytes > 1 && ScriptRuntime.toBoolean(args[1])

        if (isDataViewOutOfBounds()) throw ScriptRuntime.typeErrorById("msg.dataview.bounds")
        if (pos.toLong() + bytes > byteLength) throw ScriptRuntime.rangeErrorById("msg.dataview.offset.range")

        val buf = arrayBuffer.buffer!!
        return when (bytes) {
            1 -> if (signed) ByteIo.readInt8(buf, offset + pos) else ByteIo.readUint8(buf, offset + pos)
            2 -> if (signed) ByteIo.readInt16(buf, offset + pos, littleEndian) else ByteIo.readUint16(buf, offset + pos, littleEndian)
            4 -> if (signed) ByteIo.readInt32(buf, offset + pos, littleEndian) else ByteIo.readUint32(buf, offset + pos, littleEndian)
            else -> throw AssertionError()
        }
    }

    private fun js_getFloat(bytes: Int, args: Array<Any?>): Any? {
        val pos = ScriptRuntime.toIndex(if (isArg(args, 0)) args[0] else Undefined.instance)
        val littleEndian = isArg(args, 1) && bytes > 1 && ScriptRuntime.toBoolean(args[1])

        if (isDataViewOutOfBounds()) throw ScriptRuntime.typeErrorById("msg.dataview.bounds")
        if (pos.toLong() + bytes > byteLength) throw ScriptRuntime.rangeErrorById("msg.dataview.offset.range")

        val buf = arrayBuffer.buffer!!
        return when (bytes) {
            4 -> ByteIo.readFloat32(buf, offset + pos, littleEndian)
            8 -> ByteIo.readFloat64(buf, offset + pos, littleEndian)
            else -> throw AssertionError()
        }
    }

    private fun js_setInt(bytes: Int, signed: Boolean, args: Array<Any?>) {
        val pos = ScriptRuntime.toIndex(if (isArg(args, 0)) args[0] else Undefined.instance)
        val value: Any = if (isArg(args, 1)) ScriptRuntime.toNumber(args[1]) else ScriptRuntime.zeroObj
        val littleEndian = isArg(args, 2) && bytes > 1 && ScriptRuntime.toBoolean(args[2])

        if (isDataViewOutOfBounds()) throw ScriptRuntime.typeErrorById("msg.dataview.bounds")
        if (pos.toLong() + bytes > byteLength) throw ScriptRuntime.rangeErrorById("msg.dataview.offset.range")

        val buf = arrayBuffer.buffer!!
        when (bytes) {
            1 -> if (signed) {
                ByteIo.writeInt8(buf, offset + pos, Conversions.toInt8(value))
            } else {
                ByteIo.writeUint8(buf, offset + pos, Conversions.toUint8(value))
            }
            2 -> if (signed) {
                ByteIo.writeInt16(buf, offset + pos, Conversions.toInt16(value), littleEndian)
            } else {
                ByteIo.writeUint16(buf, offset + pos, Conversions.toUint16(value), littleEndian)
            }
            4 -> if (signed) {
                ByteIo.writeInt32(buf, offset + pos, Conversions.toInt32(value), littleEndian)
            } else {
                ByteIo.writeUint32(buf, offset + pos, Conversions.toUint32(value), littleEndian)
            }
            else -> throw AssertionError()
        }
    }

    private fun js_setFloat(bytes: Int, args: Array<Any?>) {
        val pos = ScriptRuntime.toIndex(if (isArg(args, 0)) args[0] else Undefined.instance)
        val value = if (isArg(args, 1)) ScriptRuntime.toNumber(args[1]) else Double.NaN
        val littleEndian = isArg(args, 2) && bytes > 1 && ScriptRuntime.toBoolean(args[2])

        if (isDataViewOutOfBounds()) throw ScriptRuntime.typeErrorById("msg.dataview.bounds")
        if (pos.toLong() + bytes > byteLength) throw ScriptRuntime.rangeErrorById("msg.dataview.offset.range")

        val buf = arrayBuffer.buffer!!
        when (bytes) {
            4 -> ByteIo.writeFloat32(buf, offset + pos, value, littleEndian)
            8 -> ByteIo.writeFloat64(buf, offset + pos, value, littleEndian)
            else -> throw AssertionError()
        }
    }

    companion object {
        const val CLASS_NAME: String = "DataView"

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val constructor = LambdaConstructor(
                scope,
                CLASS_NAME,
                1,
                LambdaConstructor.CONSTRUCTOR_NEW,
                SerializableConstructable { icx, s, args -> js_constructor(icx, s, args) },
            )
            constructor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)

            constructor.definePrototypeProperty(cx, "buffer", LambdaGetterFunction { realThis(it).arrayBuffer })
            constructor.definePrototypeProperty(cx, "byteLength", LambdaGetterFunction {
                val self = realThis(it)
                if (self.isDataViewOutOfBounds()) throw ScriptRuntime.typeErrorById("msg.dataview.bounds")
                self.byteLength
            })
            constructor.definePrototypeProperty(cx, "byteOffset", LambdaGetterFunction {
                val self = realThis(it)
                if (self.isDataViewOutOfBounds()) throw ScriptRuntime.typeErrorById("msg.dataview.bounds")
                self.offset
            })
            constructor.definePrototypeProperty(SymbolKey.TO_STRING_TAG, CLASS_NAME, DONTENUM or READONLY)

            defineGet(constructor, scope, "getInt8", 1, true)
            defineGet(constructor, scope, "getInt16", 2, true)
            defineGet(constructor, scope, "getInt32", 4, true)
            defineGet(constructor, scope, "getUint8", 1, false)
            defineGet(constructor, scope, "getUint16", 2, false)
            defineGet(constructor, scope, "getUint32", 4, false)
            constructor.definePrototypeMethod(scope, "getFloat32", 1, SerializableCallable { _, _, t, a -> realThis(t).js_getFloat(4, a) })
            constructor.definePrototypeMethod(scope, "getFloat64", 1, SerializableCallable { _, _, t, a -> realThis(t).js_getFloat(8, a) })

            defineSet(constructor, scope, "setInt8", 1, true)
            defineSet(constructor, scope, "setInt16", 2, true)
            defineSet(constructor, scope, "setInt32", 4, true)
            defineSet(constructor, scope, "setUint8", 1, false)
            defineSet(constructor, scope, "setUint16", 2, false)
            defineSet(constructor, scope, "setUint32", 4, false)
            constructor.definePrototypeMethod(scope, "setFloat32", 2, SerializableCallable { _, _, t, a ->
                realThis(t).js_setFloat(4, a)
                Undefined.instance
            })
            constructor.definePrototypeMethod(scope, "setFloat64", 2, SerializableCallable { _, _, t, a ->
                realThis(t).js_setFloat(8, a)
                Undefined.instance
            })

            if (sealed) {
                constructor.sealObject()
                (constructor.prototypeProperty as ScriptableObject).sealObject()
            }
            return constructor
        }

        private fun defineGet(ctor: LambdaConstructor, scope: Scriptable, name: String, bytes: Int, signed: Boolean) {
            ctor.definePrototypeMethod(scope, name, 1, SerializableCallable { _, _, t, a -> realThis(t).js_getInt(bytes, signed, a) })
        }

        private fun defineSet(ctor: LambdaConstructor, scope: Scriptable, name: String, bytes: Int, signed: Boolean) {
            ctor.definePrototypeMethod(scope, name, 2, SerializableCallable { _, _, t, a ->
                realThis(t).js_setInt(bytes, signed, a)
                Undefined.instance
            })
        }

        private fun realThis(thisObj: Scriptable?): NativeDataView =
            LambdaConstructor.convertThisObject<NativeDataView>(thisObj)

        private fun js_constructor(cx: Context, scope: Scriptable, args: Array<Any?>): NativeDataView {
            if (!isArg(args, 0) || args[0] !is NativeArrayBuffer) {
                throw ScriptRuntime.constructError("TypeError", "Missing parameters")
            }
            val ab = args[0] as NativeArrayBuffer
            val pos = ScriptRuntime.toIndex(if (isArg(args, 1)) args[1] else Undefined.instance)

            if (ab.isDetached()) throw ScriptRuntime.typeErrorById("msg.arraybuf.detached")

            var bufferByteLength = ab.length
            if (pos > bufferByteLength) throw ScriptRuntime.rangeErrorById("msg.dataview.offset.range")

            val len: Int
            if (isArg(args, 2)) {
                len = ScriptRuntime.toIndex(args[2])
                if (pos.toLong() + len > bufferByteLength) throw ScriptRuntime.rangeErrorById("msg.dataview.length.range")
            } else {
                len = bufferByteLength - pos
            }

            // Converting the arguments can run script, which could have detached the buffer, so
            // the checks run a second time.
            if (ab.isDetached()) throw ScriptRuntime.typeErrorById("msg.arraybuf.detached")
            bufferByteLength = ab.length
            if (pos > bufferByteLength) throw ScriptRuntime.rangeErrorById("msg.dataview.offset.range")
            if (isArg(args, 2) && pos.toLong() + len > bufferByteLength) {
                throw ScriptRuntime.rangeErrorById("msg.dataview.length.range")
            }

            return NativeDataView(ab, pos, len)
        }
    }
}
