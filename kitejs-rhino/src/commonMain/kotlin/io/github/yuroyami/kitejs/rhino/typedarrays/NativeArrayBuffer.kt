/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino.typedarrays

import io.github.yuroyami.kitejs.rhino.AbstractEcmaObjectOperations
import io.github.yuroyami.kitejs.rhino.Context
import io.github.yuroyami.kitejs.rhino.Intrinsics
import io.github.yuroyami.kitejs.rhino.LambdaConstructor
import io.github.yuroyami.kitejs.rhino.ScriptRuntime
import io.github.yuroyami.kitejs.rhino.ScriptRuntimeES6
import io.github.yuroyami.kitejs.rhino.Scriptable
import io.github.yuroyami.kitejs.rhino.ScriptableObject
import io.github.yuroyami.kitejs.rhino.SerializableCallable
import io.github.yuroyami.kitejs.rhino.SerializableConstructable
import io.github.yuroyami.kitejs.rhino.SymbolKey
import io.github.yuroyami.kitejs.rhino.Undefined

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
            Intrinsics.register(scope, CLASS_NAME, constructor)

            constructor.defineConstructorMethod(scope, "isView", 1, SerializableCallable { _, _, _, args -> js_isView(args) })
            // A subclass reaches its own constructor through the inherited getter, which slice
            // then uses as the species (ECMAScript 2015, 24.1.3.3); upstream has none.
            ScriptRuntimeES6.addSymbolSpecies(cx, scope, constructor)
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

        /**
         * ArrayBuffer.prototype.slice (ECMAScript 2024, 25.1.6.7). The length is read once, before
         * the arguments are converted, and both buffers are checked again once the species
         * constructor has run, because a conversion or the constructor may have detached either of
         * them (issue 24, D-87).
         */
        private fun js_slice(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): NativeArrayBuffer {
            val self = getSelf(thisObj)
            if (self.isDetached) throw ScriptRuntime.typeErrorById("msg.arraybuf.detached")
            val len = self.length.toDouble()

            val relativeStart = ScriptRuntime.toIntegerOrInfinity(args.getOrElse(0) { Undefined.instance })
            val first = if (relativeStart < 0) maxOf(len + relativeStart, 0.0) else minOf(relativeStart, len)
            val endArg = args.getOrElse(1) { Undefined.instance }
            val relativeEnd = if (Undefined.isUndefined(endArg)) len else ScriptRuntime.toIntegerOrInfinity(endArg)
            val final = if (relativeEnd < 0) maxOf(len + relativeEnd, 0.0) else minOf(relativeEnd, len)
            val newLen = maxOf(final - first, 0.0).toInt()

            val ctor = AbstractEcmaObjectOperations.speciesConstructor(
                cx,
                self,
                Intrinsics.constructor(cx, scope, CLASS_NAME),
            )
            val buf = ctor.construct(cx, scope, arrayOf<Any?>(newLen))
            if (buf !is NativeArrayBuffer) throw ScriptRuntime.typeErrorById("msg.species.invalid.ctor")
            if (buf.isDetached) throw ScriptRuntime.typeErrorById("msg.arraybuf.detached")
            if (buf === self) throw ScriptRuntime.typeErrorById("msg.arraybuf.same")
            if (buf.length < newLen) throw ScriptRuntime.typeErrorById("msg.arraybuf.smaller.len", newLen, buf.length)

            // The species constructor is user code and may have detached the source.
            val from = self.buffer ?: throw ScriptRuntime.typeErrorById("msg.arraybuf.detached")
            val start = first.toInt()
            if (start < from.size) {
                val count = minOf(newLen, from.size - start)
                from.copyInto(buf.buffer!!, 0, start, start + count)
            }
            return buf
        }

        /**
         * `transfer` and `transferToFixedLength`, which are the same here because no buffer is
         * resizable: ArrayBufferCopyAndDetach (ECMAScript 2024, 25.1.3.3). The new length is
         * converted before the source is checked, and the copy is always a plain ArrayBuffer of
         * the realm that defined the method, so species is never read (issue 25, D-87).
         */
        private fun js_transfer(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Scriptable {
            val self = getSelf(thisObj)
            val lengthArg = args.getOrElse(0) { Undefined.instance }
            val newByteLength = if (Undefined.isUndefined(lengthArg)) self.length else ScriptRuntime.toIndex(lengthArg)
            if (self.isDetached) throw ScriptRuntime.typeErrorById("msg.arraybuf.detached")

            val newBuffer = Intrinsics.constructor(cx, scope, CLASS_NAME)
                .construct(cx, scope, arrayOf<Any?>(newByteLength)) as NativeArrayBuffer

            val copyLength = minOf(newByteLength, self.length)
            if (copyLength > 0) self.buffer!!.copyInto(newBuffer.buffer!!, 0, 0, copyLength)

            self.detach()
            return newBuffer
        }

        internal fun isArg(args: Array<Any?>, i: Int): Boolean = args.size > i && Undefined.instance != args[i]
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

    /**
     * The byte order this view reads and writes with, taken from the engine that created it
     * (ECMAScript 2015, 24.1.1.5 GetValueFromBuffer, which leaves the order to the
     * implementation). It is read once here, so two engines may answer differently and a view
     * keeps the answer of the one that made it.
     */
    internal val littleEndian: Boolean =
        Context.getCurrentContext()?.hasFeature(Context.FEATURE_LITTLE_ENDIAN) ?: true

    public companion object {

        internal fun isArg(args: Array<Any?>, i: Int): Boolean = args.size > i && Undefined.instance != args[i]
    }
}
