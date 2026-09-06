/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.typedarrays

import io.github.yuroyami.kitejs.ScriptRuntime
import kotlin.math.floor

/** The numeric conversions from section 7 of the ES6 standard. */
internal object Conversions {

    fun toInt8(arg: Any?): Int = ScriptRuntime.toInt32(arg).toByte().toInt()

    fun toUint8(arg: Any?): Int = ScriptRuntime.toInt32(arg) and 0xFF

    /** Clamping rounds half to even, which is not what any other conversion here does. */
    fun toUint8Clamp(arg: Any?): Int {
        val d = ScriptRuntime.toNumber(arg)
        if (d <= 0.0) return 0
        if (d >= 255.0) return 255

        val f = floor(d)
        if ((f + 0.5) < d) return (f + 1.0).toInt()
        if (d < (f + 0.5)) return f.toInt()
        if ((f.toInt() % 2) != 0) return f.toInt() + 1
        return f.toInt()
    }

    fun toInt16(arg: Any?): Int = ScriptRuntime.toInt32(arg).toShort().toInt()

    fun toUint16(arg: Any?): Int = ScriptRuntime.toInt32(arg) and 0xFFFF

    fun toInt32(arg: Any?): Int = ScriptRuntime.toInt32(arg)

    fun toUint32(arg: Any?): Long = ScriptRuntime.toUint32(arg)
}

/**
 * Reads and writes numbers in a byte array, in either endianness.
 *
 * Upstream hands back `Byte` and `Short` boxes; this returns `Int` instead, because that is the
 * integer the rest of the engine speaks (D-48). Anything wider than an `Int` comes back as a
 * `Double`, for the same reason.
 */
internal object ByteIo {

    fun readInt8(buf: ByteArray, offset: Int): Any = buf[offset].toInt()

    fun writeInt8(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = value.toByte()
    }

    fun readUint8(buf: ByteArray, offset: Int): Any = buf[offset].toInt() and 0xFF

    fun writeUint8(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
    }

    private fun doReadInt16(buf: ByteArray, offset: Int, littleEndian: Boolean): Short {
        // Narrowed to Short so the sign comes out right.
        return if (littleEndian) {
            (((buf[offset].toInt() and 0xFF)) or ((buf[offset + 1].toInt() and 0xFF) shl 8)).toShort()
        } else {
            (((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)).toShort()
        }
    }

    private fun doWriteInt16(buf: ByteArray, offset: Int, value: Int, littleEndian: Boolean) {
        if (littleEndian) {
            buf[offset] = (value and 0xFF).toByte()
            buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        } else {
            buf[offset] = ((value ushr 8) and 0xFF).toByte()
            buf[offset + 1] = (value and 0xFF).toByte()
        }
    }

    fun readInt16(buf: ByteArray, offset: Int, littleEndian: Boolean): Any =
        doReadInt16(buf, offset, littleEndian).toInt()

    fun writeInt16(buf: ByteArray, offset: Int, value: Int, littleEndian: Boolean) {
        doWriteInt16(buf, offset, value, littleEndian)
    }

    fun readUint16(buf: ByteArray, offset: Int, littleEndian: Boolean): Any =
        doReadInt16(buf, offset, littleEndian).toInt() and 0xFFFF

    fun writeUint16(buf: ByteArray, offset: Int, value: Int, littleEndian: Boolean) {
        doWriteInt16(buf, offset, value and 0xFFFF, littleEndian)
    }

    fun readInt32(buf: ByteArray, offset: Int, littleEndian: Boolean): Any =
        readInt32Primitive(buf, offset, littleEndian)

    fun readInt32Primitive(buf: ByteArray, offset: Int, littleEndian: Boolean): Int =
        if (littleEndian) {
            (buf[offset].toInt() and 0xFF) or
                ((buf[offset + 1].toInt() and 0xFF) shl 8) or
                ((buf[offset + 2].toInt() and 0xFF) shl 16) or
                ((buf[offset + 3].toInt() and 0xFF) shl 24)
        } else {
            ((buf[offset].toInt() and 0xFF) shl 24) or
                ((buf[offset + 1].toInt() and 0xFF) shl 16) or
                ((buf[offset + 2].toInt() and 0xFF) shl 8) or
                (buf[offset + 3].toInt() and 0xFF)
        }

    fun writeInt32(buf: ByteArray, offset: Int, value: Int, littleEndian: Boolean) {
        if (littleEndian) {
            buf[offset] = (value and 0xFF).toByte()
            buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        } else {
            buf[offset] = ((value ushr 24) and 0xFF).toByte()
            buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
            buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
            buf[offset + 3] = (value and 0xFF).toByte()
        }
    }

    fun readUint32Primitive(buf: ByteArray, offset: Int, littleEndian: Boolean): Long =
        if (littleEndian) {
            ((buf[offset].toLong() and 0xFFL) or
                ((buf[offset + 1].toLong() and 0xFFL) shl 8) or
                ((buf[offset + 2].toLong() and 0xFFL) shl 16) or
                ((buf[offset + 3].toLong() and 0xFFL) shl 24)) and 0xFFFFFFFFL
        } else {
            (((buf[offset].toLong() and 0xFFL) shl 24) or
                ((buf[offset + 1].toLong() and 0xFFL) shl 16) or
                ((buf[offset + 2].toLong() and 0xFFL) shl 8) or
                (buf[offset + 3].toLong() and 0xFFL)) and 0xFFFFFFFFL
        }

    fun writeUint32(buf: ByteArray, offset: Int, value: Long, littleEndian: Boolean) {
        if (littleEndian) {
            buf[offset] = (value and 0xFFL).toByte()
            buf[offset + 1] = ((value ushr 8) and 0xFFL).toByte()
            buf[offset + 2] = ((value ushr 16) and 0xFFL).toByte()
            buf[offset + 3] = ((value ushr 24) and 0xFFL).toByte()
        } else {
            buf[offset] = ((value ushr 24) and 0xFFL).toByte()
            buf[offset + 1] = ((value ushr 16) and 0xFFL).toByte()
            buf[offset + 2] = ((value ushr 8) and 0xFFL).toByte()
            buf[offset + 3] = (value and 0xFFL).toByte()
        }
    }

    /** A uint32 may not fit an Int, so it comes back as whichever number type holds it. */
    fun readUint32(buf: ByteArray, offset: Int, littleEndian: Boolean): Any {
        val v = readUint32Primitive(buf, offset, littleEndian)
        return if (v <= Int.MAX_VALUE) v.toInt() else v.toDouble()
    }

    fun readUint64Primitive(buf: ByteArray, offset: Int, littleEndian: Boolean): Long =
        if (littleEndian) {
            (buf[offset].toLong() and 0xFFL) or
                ((buf[offset + 1].toLong() and 0xFFL) shl 8) or
                ((buf[offset + 2].toLong() and 0xFFL) shl 16) or
                ((buf[offset + 3].toLong() and 0xFFL) shl 24) or
                ((buf[offset + 4].toLong() and 0xFFL) shl 32) or
                ((buf[offset + 5].toLong() and 0xFFL) shl 40) or
                ((buf[offset + 6].toLong() and 0xFFL) shl 48) or
                ((buf[offset + 7].toLong() and 0xFFL) shl 56)
        } else {
            ((buf[offset].toLong() and 0xFFL) shl 56) or
                ((buf[offset + 1].toLong() and 0xFFL) shl 48) or
                ((buf[offset + 2].toLong() and 0xFFL) shl 40) or
                ((buf[offset + 3].toLong() and 0xFFL) shl 32) or
                ((buf[offset + 4].toLong() and 0xFFL) shl 24) or
                ((buf[offset + 5].toLong() and 0xFFL) shl 16) or
                ((buf[offset + 6].toLong() and 0xFFL) shl 8) or
                (buf[offset + 7].toLong() and 0xFFL)
        }

    fun writeUint64(buf: ByteArray, offset: Int, value: Long, littleEndian: Boolean) {
        if (littleEndian) {
            buf[offset] = (value and 0xFFL).toByte()
            buf[offset + 1] = ((value ushr 8) and 0xFFL).toByte()
            buf[offset + 2] = ((value ushr 16) and 0xFFL).toByte()
            buf[offset + 3] = ((value ushr 24) and 0xFFL).toByte()
            buf[offset + 4] = ((value ushr 32) and 0xFFL).toByte()
            buf[offset + 5] = ((value ushr 40) and 0xFFL).toByte()
            buf[offset + 6] = ((value ushr 48) and 0xFFL).toByte()
            buf[offset + 7] = ((value ushr 56) and 0xFFL).toByte()
        } else {
            buf[offset] = ((value ushr 56) and 0xFFL).toByte()
            buf[offset + 1] = ((value ushr 48) and 0xFFL).toByte()
            buf[offset + 2] = ((value ushr 40) and 0xFFL).toByte()
            buf[offset + 3] = ((value ushr 32) and 0xFFL).toByte()
            buf[offset + 4] = ((value ushr 24) and 0xFFL).toByte()
            buf[offset + 5] = ((value ushr 16) and 0xFFL).toByte()
            buf[offset + 6] = ((value ushr 8) and 0xFFL).toByte()
            buf[offset + 7] = (value and 0xFFL).toByte()
        }
    }

    fun readFloat32(buf: ByteArray, offset: Int, littleEndian: Boolean): Any {
        val base = readUint32Primitive(buf, offset, littleEndian)
        return Float.fromBits(base.toInt()).toDouble()
    }

    fun writeFloat32(buf: ByteArray, offset: Int, value: Double, littleEndian: Boolean) {
        writeUint32(buf, offset, value.toFloat().toBits().toLong() and 0xFFFFFFFFL, littleEndian)
    }

    fun readFloat64(buf: ByteArray, offset: Int, littleEndian: Boolean): Any =
        Double.fromBits(readUint64Primitive(buf, offset, littleEndian))

    fun writeFloat64(buf: ByteArray, offset: Int, value: Double, littleEndian: Boolean) {
        writeUint64(buf, offset, value.toBits(), littleEndian)
    }
}
