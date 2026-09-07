/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.typedarrays

import io.github.yuroyami.kitejs.AbstractEcmaObjectOperations
import io.github.yuroyami.kitejs.Context
import io.github.yuroyami.kitejs.LambdaConstructor
import io.github.yuroyami.kitejs.ScriptRuntime
import io.github.yuroyami.kitejs.Scriptable
import io.github.yuroyami.kitejs.ScriptableObject
import io.github.yuroyami.kitejs.SerializableCallable
import io.github.yuroyami.kitejs.SerializableConstructable
import io.github.yuroyami.kitejs.SymbolKey
import io.github.yuroyami.kitejs.TopLevel
import io.github.yuroyami.kitejs.Undefined

/**
 * The bytes a typed array sits on. Several views can share one buffer, and a write through any of
 * them is visible through all of them.
 */
public class NativeArrayBuffer : ScriptableObject {

    /** The real bytes, not a copy: a write here shows up in every view. */
    public var buffer: ByteArray? = EMPTY_BUF
        internal set

    override val className: String
        get() = CLASS_NAME

    /** An empty buffer. */
    public constructor() : super() {
        buffer = EMPTY_BUF
    }

    /** A zeroed buffer of [len] bytes. */
    public constructor(len: Double) : super() {
        if (len >= Int.MAX_VALUE.toDouble()) {
            throw ScriptRuntime.rangeError("length parameter ($len) is too large ")
        }
        if (len == Double.NEGATIVE_INFINITY) throw ScriptRuntime.rangeError("Negative array length $len")
        // Rounding is allowed, so anything down to -1 exclusive is still zero.
        if (len <= -1) throw ScriptRuntime.rangeError("Negative array length $len")

        val intLen = ScriptRuntime.toInt32(len)
        if (intLen < 0) throw ScriptRuntime.rangeError("Negative array length $len")
        buffer = if (intLen == 0) EMPTY_BUF else ByteArray(intLen)
    }

    public constructor(len: Int) : this(len.toDouble())

    public val length: Int
        get() = buffer?.size ?: 0

    public fun detach() {
        buffer = null
    }

    public val isDetached: Boolean get() = buffer == null

    /**
     * A copy of the bytes between [s] and [e], with both clamped into range the way the spec says.
     */
    public fun slice(s: Double, e: Double): NativeArrayBuffer {
        val len0 = length
        val end = ScriptRuntime.toInt32(maxOf(0.0, minOf(len0.toDouble(), if (e < 0) len0 + e else e)))
        val start = ScriptRuntime.toInt32(minOf(end.toDouble(), maxOf(0.0, if (s < 0) len0 + s else s)))
        val len = end - start

        val newBuf = NativeArrayBuffer(len)
        buffer!!.copyInto(newBuf.buffer!!, 0, start, start + len)
        return newBuf
    }

    public companion object {
        public const val CLASS_NAME: String = "ArrayBuffer"
        private val EMPTY_BUF = ByteArray(0)

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val constructor = LambdaConstructor(
                scope,
                "ArrayBuffer",
                1,
                LambdaConstructor.CONSTRUCTOR_NEW,
                SerializableConstructable { icx, s, args -> js_constructor(icx, s, args) },
            )
            constructor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)

            constructor.defineConstructorMethod(scope, "isView", 1, SerializableCallable { _, _, _, args -> js_isView(args) })
            constructor.definePrototypeMethod(scope, "slice", 2, SerializableCallable { icx, s, thisObj, args -> js_slice(icx, s, thisObj, args) })
            constructor.definePrototypeMethod(scope, "transfer", 0, SerializableCallable { icx, s, thisObj, args -> js_transfer(icx, s, thisObj, args) })
            constructor.definePrototypeMethod(scope, "transferToFixedLength", 0, SerializableCallable { icx, s, thisObj, args -> js_transfer(icx, s, thisObj, args) })
            constructor.definePrototypeProperty(cx, "byteLength", LambdaGetterFunction { thisObj -> getSelf(thisObj).length })
            constructor.definePrototypeProperty(cx, "detached", LambdaGetterFunction { thisObj -> getSelf(thisObj).isDetached })
            constructor.definePrototypeProperty(SymbolKey.TO_STRING_TAG, "ArrayBuffer", DONTENUM or READONLY)

            if (sealed) {
                constructor.sealObject()
                (constructor.prototypeProperty as ScriptableObject).sealObject()
            }
            return constructor
        }

        private fun getSelf(thisObj: Scriptable?): NativeArrayBuffer =
            LambdaConstructor.convertThisObject<NativeArrayBuffer>(thisObj)

        private fun js_constructor(cx: Context, scope: Scriptable, args: Array<Any?>): NativeArrayBuffer {
            val length = if (isArg(args, 0)) ScriptRuntime.toNumber(args[0]) else 0.0
            return NativeArrayBuffer(length)
        }

        private fun js_isView(args: Array<Any?>): Boolean = isArg(args, 0) && args[0] is NativeArrayBufferView

        private fun js_slice(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): NativeArrayBuffer {
            val self = getSelf(thisObj)
            if (self.isDetached) throw ScriptRuntime.typeErrorById("msg.arraybuf.detached")

            val start = if (isArg(args, 0)) ScriptRuntime.toNumber(args[0]) else 0.0
            val end = if (isArg(args, 1)) ScriptRuntime.toNumber(args[1]) else self.length.toDouble()
            val len0 = self.length
            val endI = ScriptRuntime.toInt32(maxOf(0.0, minOf(len0.toDouble(), if (end < 0) len0 + end else end)))
            val startI = ScriptRuntime.toInt32(minOf(endI.toDouble(), maxOf(0.0, if (start < 0) len0 + start else start)))
            val len = endI - startI

            val buf = constructNew(cx, scope, thisObj!!, len)
            if (buf === self) throw ScriptRuntime.typeErrorById("msg.arraybuf.same")

            val actualLength = buf.length
            if (actualLength < len) throw ScriptRuntime.typeErrorById("msg.arraybuf.smaller.len", len, actualLength)

            self.buffer!!.copyInto(buf.buffer!!, 0, startI, startI + len)
            return buf
        }

        /** `transfer` and `transferToFixedLength` do the same thing here: copy, then detach. */
        private fun js_transfer(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Scriptable {
            val self = getSelf(thisObj)
            if (self.isDetached) throw ScriptRuntime.typeErrorById("msg.arraybuf.detached")

            val newByteLength = validateNewByteLength(args, self.length)
            val newBuffer = constructNew(cx, scope, thisObj!!, newByteLength)

            val copyLength = minOf(newByteLength, self.length)
            if (copyLength > 0) self.buffer!!.copyInto(newBuffer.buffer!!, 0, 0, copyLength)

            self.detach()
            return newBuffer
        }

        /** Builds the result through the species constructor, as every one of these methods does. */
        private fun constructNew(cx: Context, scope: Scriptable, thisObj: Scriptable, byteLength: Int): NativeArrayBuffer {
            val ctor = AbstractEcmaObjectOperations.speciesConstructor(
                cx,
                thisObj,
                TopLevel.getBuiltinCtor(cx, ScriptableObject.getTopLevelScope(scope), TopLevel.Builtins.ArrayBuffer)!!,
            )
            val newBuf = ctor.construct(cx, scope, arrayOf<Any?>(byteLength))
            if (newBuf !is NativeArrayBuffer) throw ScriptRuntime.typeErrorById("msg.species.invalid.ctor")
            return newBuf
        }

        internal fun isArg(args: Array<Any?>, i: Int): Boolean = args.size > i && Undefined.instance != args[i]

        private fun validateNewByteLength(args: Array<Any?>, defaultLength: Int): Int {
            var newLength = if (isArg(args, 0)) ScriptRuntime.toNumber(args[0]) else defaultLength.toDouble()
            if (newLength.isNaN()) newLength = 0.0
            if (newLength < 0 || newLength.isInfinite()) throw ScriptRuntime.rangeError("Invalid array buffer length")
            if (newLength >= Int.MAX_VALUE.toDouble()) throw ScriptRuntime.rangeError("Array buffer length too large")
            return newLength.toInt()
        }
    }
}

/**
 * The parent of every view onto a [NativeArrayBuffer]. Several views may share one buffer, and a
 * write through any of them is seen by all.
 */
public abstract class NativeArrayBufferView : ScriptableObject {

    /** The buffer this view reads and writes. */
    protected val arrayBuffer: NativeArrayBuffer

    /** Where in the buffer the view starts, in bytes. Upstream's getByteOffset (D-7). */
    public val offset: Int

    /** How much of the buffer the view covers, in bytes. Upstream's getByteLength (D-7). */
    public val byteLength: Int

    /** True when the view no longer fits inside its buffer. */
    protected val outOfRange: Boolean

    protected constructor() : super() {
        arrayBuffer = NativeArrayBuffer()
        offset = 0
        byteLength = 0
        outOfRange = false
    }

    protected constructor(ab: NativeArrayBuffer, offset: Int, byteLength: Int) : super() {
        this.offset = offset
        this.byteLength = byteLength
        this.arrayBuffer = ab

        val bufferByteLength = ab.length
        val byteOffsetEnd = offset + byteLength
        outOfRange = offset > bufferByteLength || byteOffsetEnd > bufferByteLength
    }

    public val buffer: NativeArrayBuffer get() = arrayBuffer

    public companion object {
        private var useLittleEndianCache: Boolean? = null

        internal fun useLittleEndian(): Boolean {
            val cached = useLittleEndianCache
            if (cached != null) return cached
            val cx = Context.getCurrentContext() ?: return false
            val value = cx.hasFeature(Context.FEATURE_LITTLE_ENDIAN)
            useLittleEndianCache = value
            return value
        }

        internal fun isArg(args: Array<Any?>, i: Int): Boolean = args.size > i && Undefined.instance != args[i]
    }
}
