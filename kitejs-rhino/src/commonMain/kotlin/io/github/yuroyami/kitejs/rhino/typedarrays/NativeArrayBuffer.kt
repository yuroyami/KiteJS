/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino.typedarrays

import io.github.yuroyami.kitejs.rhino.AbstractEcmaObjectOperations
import io.github.yuroyami.kitejs.rhino.Context
import io.github.yuroyami.kitejs.rhino.Intrinsics
import io.github.yuroyami.kitejs.rhino.LambdaConstructor
import io.github.yuroyami.kitejs.rhino.NativeNumber
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

    /** The most bytes [resize] may grow to, or -1 when the length is fixed. */
    public var maxByteLength: Int = -1
        internal set

    /** True for a buffer made with a `maxByteLength` option, whose length [resize] can change. */
    public val isResizable: Boolean get() = maxByteLength >= 0

    /**
     * Changes the length of a resizable buffer to [newLength] bytes. Bytes past the old end read as
     * zero, and every view sees the new length at once.
     */
    public fun resize(newLength: Int) {
        check(isResizable) { "the buffer is not resizable" }
        require(newLength in 0..maxByteLength) { "length $newLength is outside 0..$maxByteLength" }
        val old = buffer ?: error("the buffer is detached")
        buffer = if (newLength == 0) EMPTY_BUF else old.copyOf(newLength)
    }

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
            constructor.definePrototypeMethod(scope, "resize", 1, SerializableCallable { _, _, thisObj, args -> js_resize(thisObj, args) })
            constructor.definePrototypeMethod(scope, "transfer", 0, SerializableCallable { icx, s, thisObj, args -> js_transfer(icx, s, thisObj, args, true) })
            constructor.definePrototypeMethod(scope, "transferToFixedLength", 0, SerializableCallable { icx, s, thisObj, args -> js_transfer(icx, s, thisObj, args, false) })
            constructor.definePrototypeProperty(cx, "byteLength", LambdaGetterFunction { thisObj -> getSelf(thisObj).length })
            constructor.definePrototypeProperty(cx, "detached", LambdaGetterFunction { thisObj -> getSelf(thisObj).isDetached })
            constructor.definePrototypeProperty(cx, "maxByteLength", LambdaGetterFunction { thisObj ->
                val self = getSelf(thisObj)
                when {
                    self.isDetached -> 0
                    self.isResizable -> self.maxByteLength
                    else -> self.length
                }
            })
            constructor.definePrototypeProperty(cx, "resizable", LambdaGetterFunction { thisObj -> getSelf(thisObj).isResizable })
            constructor.definePrototypeProperty(SymbolKey.TO_STRING_TAG, "ArrayBuffer", DONTENUM or READONLY)

            if (sealed) {
                constructor.sealObject()
                (constructor.prototypeProperty as ScriptableObject).sealObject()
            }
            return constructor
        }

        private fun getSelf(thisObj: Scriptable?): NativeArrayBuffer =
            LambdaConstructor.convertThisObject<NativeArrayBuffer>(thisObj)

        /**
         * `new ArrayBuffer(length, options)` (ECMAScript 2024, 25.1.4.1). The length is converted
         * before `options.maxByteLength` is read, and a length over that maximum is a RangeError.
         */
        private fun js_constructor(cx: Context, scope: Scriptable, args: Array<Any?>): NativeArrayBuffer {
            val number = if (isArg(args, 0)) ScriptRuntime.toNumber(args[0]) else 0.0
            if (number <= -1) throw ScriptRuntime.rangeError("Negative array length $number")
            if (number > NativeNumber.MAX_SAFE_INTEGER) throw ScriptRuntime.rangeError("length parameter ($number) is too large ")
            val length = if (number.isNaN()) 0.0 else kotlin.math.truncate(number)
            val options = args.getOrElse(1) { Undefined.instance }
            var max = -1
            if (options is Scriptable && !Undefined.isUndefined(options) && !ScriptRuntime.isSymbol(options)) {
                val maxArg = ScriptableObject.getProperty(options, "maxByteLength")
                if (maxArg !== Scriptable.NOT_FOUND && !Undefined.isUndefined(maxArg)) {
                    max = ScriptRuntime.toIndex(maxArg)
                    // toIndex clamps to Int.MAX_VALUE, and no byte array can grow that far.
                    if (max == Int.MAX_VALUE) throw ScriptRuntime.rangeError("maxByteLength is too large")
                    if (length > max) throw ScriptRuntime.rangeErrorById("msg.arraybuf.max.length", length.toLong(), max)
                }
            }
            return NativeArrayBuffer(length).also { it.maxByteLength = max }
        }

        /** ArrayBuffer.prototype.resize (ECMAScript 2024, 25.1.6.6). */
        private fun js_resize(thisObj: Scriptable?, args: Array<Any?>): Any {
            val self = getSelf(thisObj)
            if (!self.isResizable) throw ScriptRuntime.typeErrorById("msg.arraybuf.not.resizable")
            val newLength = ScriptRuntime.toIndex(args.getOrElse(0) { Undefined.instance })
            if (self.isDetached) throw ScriptRuntime.typeErrorById("msg.arraybuf.detached")
            if (newLength > self.maxByteLength) {
                throw ScriptRuntime.rangeErrorById("msg.arraybuf.max.length", newLength, self.maxByteLength)
            }
            self.resize(newLength)
            return Undefined.instance
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
         * `transfer` and `transferToFixedLength`: ArrayBufferCopyAndDetach (ECMAScript 2024,
         * 25.1.3.3). Only `transfer` keeps a resizable source resizable. The new length is
         * converted before the source is checked, and the copy is always a plain ArrayBuffer of
         * the realm that defined the method, so species is never read.
         */
        private fun js_transfer(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>, preserveResizability: Boolean): Scriptable {
            val self = getSelf(thisObj)
            val lengthArg = args.getOrElse(0) { Undefined.instance }
            val newByteLength = if (Undefined.isUndefined(lengthArg)) self.length else ScriptRuntime.toIndex(lengthArg)
            if (self.isDetached) throw ScriptRuntime.typeErrorById("msg.arraybuf.detached")
            val newMax = if (preserveResizability) self.maxByteLength else -1
            if (newMax >= 0 && newByteLength > newMax) {
                throw ScriptRuntime.rangeErrorById("msg.arraybuf.max.length", newByteLength, newMax)
            }

            val newBuffer = Intrinsics.constructor(cx, scope, CLASS_NAME)
                .construct(cx, scope, arrayOf<Any?>(newByteLength)) as NativeArrayBuffer
            newBuffer.maxByteLength = newMax

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

    /** The byte length given at construction, or -1 when the view follows a resizable buffer. */
    private val fixedByteLength: Int

    /** Whether the view started outside its buffer, which stays true while the buffer never resizes. */
    private val startedOutOfRange: Boolean

    /**
     * True when the view has no length of its own and covers its resizable buffer from [offset]
     * to the end, however the buffer grows or shrinks.
     */
    public val isLengthTracking: Boolean get() = fixedByteLength < 0

    /** How much of the buffer the view covers, in bytes. Upstream's getByteLength (D-7). */
    public val byteLength: Int
        get() = when {
            fixedByteLength >= 0 -> fixedByteLength
            outOfRange -> 0
            else -> trackedByteLength(arrayBuffer.length - offset)
        }

    /** The part of the [available] bytes that a length-tracking view covers. */
    protected open fun trackedByteLength(available: Int): Int = available

    /** True when the view no longer fits inside its buffer. */
    protected val outOfRange: Boolean
        get() {
            if (!arrayBuffer.isResizable) return startedOutOfRange
            val bufferByteLength = arrayBuffer.length
            return offset > bufferByteLength ||
                (fixedByteLength >= 0 && offset.toLong() + fixedByteLength > bufferByteLength)
        }

    protected constructor() : super() {
        arrayBuffer = NativeArrayBuffer()
        offset = 0
        fixedByteLength = 0
        startedOutOfRange = false
    }

    /** A negative [byteLength] makes a length-tracking view, which needs a resizable [ab]. */
    protected constructor(ab: NativeArrayBuffer, offset: Int, byteLength: Int) : super() {
        this.offset = offset
        this.fixedByteLength = if (byteLength < 0) -1 else byteLength
        this.arrayBuffer = ab

        val bufferByteLength = ab.length
        startedOutOfRange = offset > bufferByteLength ||
            (fixedByteLength >= 0 && offset.toLong() + fixedByteLength > bufferByteLength)
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
