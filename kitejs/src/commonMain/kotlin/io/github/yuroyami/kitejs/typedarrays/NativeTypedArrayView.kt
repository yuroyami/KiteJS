/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.typedarrays

import io.github.yuroyami.kitejs.AbstractEcmaObjectOperations
import io.github.yuroyami.kitejs.ArrayLikeAbstractOperations
import io.github.yuroyami.kitejs.Callable
import io.github.yuroyami.kitejs.Constructable
import io.github.yuroyami.kitejs.Context
import io.github.yuroyami.kitejs.KBigInt
import io.github.yuroyami.kitejs.ExternalArrayData
import io.github.yuroyami.kitejs.Function
import io.github.yuroyami.kitejs.IteratorLikeIterable
import io.github.yuroyami.kitejs.LambdaConstructor
import io.github.yuroyami.kitejs.Messages
import io.github.yuroyami.kitejs.NativeArray
import io.github.yuroyami.kitejs.NativeArrayIterator
import io.github.yuroyami.kitejs.ScriptRuntime
import io.github.yuroyami.kitejs.Scriptable
import io.github.yuroyami.kitejs.ScriptableObject
import io.github.yuroyami.kitejs.SerializableCallable
import io.github.yuroyami.kitejs.SymbolKey
import io.github.yuroyami.kitejs.Undefined

/**
 * The parent of the nine numeric views. Each shows one buffer through one element type, and writes
 * through any view are seen by all of them.
 *
 * The spec calls these "integer-indexed exotic objects": an index outside the array is not an
 * error, it simply reads as `undefined` and writes nowhere. That is why `get`, `put`, `has` and
 * `delete` are all overridden.
 */
public abstract class NativeTypedArrayView : NativeArrayBufferView, ExternalArrayData {

    /** How many elements the view holds. */
    protected val length: Int

    protected constructor() : super() {
        length = 0
    }

    protected constructor(ab: NativeArrayBuffer, off: Int, len: Int, byteLen: Int) : super(ab, off, byteLen) {
        length = len
    }

    // ---- The exotic index behaviour -------------------------------------------------------------

    override fun get(index: Int, start: Scriptable): Any? = js_get(index)

    override fun get(name: String, start: Scriptable): Any? {
        val num = ScriptRuntime.canonicalNumericIndexString(name)
        if (num != null) {
            val ix = toIndex(num)
            if (ix >= 0) return js_get(ix)
        }
        return super.get(name, start)
    }

    override fun has(index: Int, start: Scriptable): Boolean = !checkIndex(index)

    override fun has(name: String, start: Scriptable): Boolean {
        val num = ScriptRuntime.canonicalNumericIndexString(name)
        if (num != null) {
            val ix = toIndex(num)
            if (ix >= 0) return !checkIndex(ix)
        }
        return super.has(name, start)
    }

    override fun put(index: Int, start: Scriptable, value: Any?) {
        js_set(index, value)
    }

    override fun put(name: String, start: Scriptable, value: Any?) {
        val num = ScriptRuntime.canonicalNumericIndexString(name)
        if (num != null) {
            val ix = toIndex(num)
            if (ix >= 0) js_set(ix, value)
        } else {
            super.put(name, start, value)
        }
    }

    override fun delete(index: Int) {}

    override fun delete(name: String) {
        // Elements cannot be deleted; anything that is not an index is an ordinary property.
        if (ScriptRuntime.canonicalNumericIndexString(name) == null) super.delete(name)
    }

    override fun getIds(): Array<Any?> = Array(length) { it }

    override fun defineOwnProperty(cx: Context, id: Any?, desc: DescriptorInfo, checkValid: Boolean): Boolean {
        if (id is CharSequence) {
            val num = ScriptRuntime.canonicalNumericIndexString(id.toString())
            if (num != null) {
                val idx = num.toInt()
                if (checkIndex(idx)) return false
                // An element is always writable, enumerable and non-configurable, and never an
                // accessor, so a descriptor that says otherwise is refused.
                if (desc.isConfigurable(false)) return false
                if (desc.isEnumerable(false)) return false
                if (desc.isAccessorDescriptor) return false
                if (desc.isWritable(false)) return false
                if (desc.hasValue()) js_set(idx, desc.value)
                return true
            }
        }
        return super.defineOwnProperty(cx, id, desc, checkValid)
    }

    /** True when the index is not usable. */
    protected fun checkIndex(index: Int): Boolean = isTypedArrayOutOfBounds || index < 0 || index >= length

    public abstract val bytesPerElement: Int
    protected abstract fun js_get(index: Int): Any?

    protected abstract fun js_set(index: Int, c: Any?): Any?

    protected open fun toNumeric(num: Any?): Any? = ScriptRuntime.toNumber(num)

    public val isTypedArrayOutOfBounds: Boolean get() = arrayBuffer.isDetached || outOfRange

    /** The spec's ValidateTypedArray, reduced to the length it hands back. */
    private fun validateAndGetLength(): Long {
        if (isTypedArrayOutOfBounds) throw ScriptRuntime.typeErrorById("msg.typed.array.out.of.bounds")
        return length.toLong()
    }

    // ---- ExternalArrayData ---------------------------------------------------------------------

    override fun getArrayElement(index: Int): Any? = js_get(index)

    override fun setArrayElement(index: Int, value: Any?) {
        js_set(index, value)
    }

    override val arrayLength: Int get() = length

    // ---- Copying between views -----------------------------------------------------------------

    private fun setRange(source: NativeTypedArrayView, dbloff: Double) {
        if (isTypedArrayOutOfBounds) throw ScriptRuntime.typeErrorById("msg.typed.array.out.of.bounds")
        val targetLength = length
        if (source.isTypedArrayOutOfBounds) throw ScriptRuntime.typeErrorById("msg.typed.array.out.of.bounds")

        val srcLength = source.length
        if (dbloff > targetLength) throw ScriptRuntime.rangeErrorById("msg.typed.array.bad.offset", dbloff)
        if (srcLength + dbloff > targetLength) throw ScriptRuntime.rangeErrorById("msg.typed.array.bad.source.array")

        // A bigint view and a number view hold different kinds of element and cannot be copied
        // between, however well the lengths line up.
        if ((this is NativeBigIntArrayView) != (source is NativeBigIntArrayView)) {
            throw ScriptRuntime.typeErrorById("msg.typed.array.type.mismatch")
        }

        val targetOffset = dbloff.toInt()
        if (source.arrayBuffer === arrayBuffer) {
            // Overlapping views share bytes, so the source is read out in full first.
            val tmp = arrayOfNulls<Any?>(srcLength)
            for (i in 0 until srcLength) tmp[i] = source.js_get(i)
            for (i in 0 until srcLength) js_set(i + targetOffset, tmp[i])
        } else {
            for (i in 0 until srcLength) js_set(i + targetOffset, source.js_get(i))
        }
    }

    private fun setRange(cx: Context, scope: Scriptable, source: Scriptable, dbloff: Double) {
        if (isTypedArrayOutOfBounds) throw ScriptRuntime.typeErrorById("msg.typed.array.out.of.bounds")
        val targetLength = length
        val src = ScriptRuntime.toObject(scope, source)
        val srcLength = AbstractEcmaObjectOperations.lengthOfArrayLike(cx, src)

        if (dbloff > targetLength) throw ScriptRuntime.rangeErrorById("msg.typed.array.bad.offset", dbloff)
        if (srcLength + dbloff > targetLength) throw ScriptRuntime.rangeErrorById("msg.typed.array.bad.source.array")

        val targetOffset = dbloff.toInt()
        for (k in 0 until srcLength.toInt()) {
            js_set(k + targetOffset, source.get(k, source))
        }
    }

    private fun getElemForToString(cx: Context, scope: Scriptable, index: Int, useLocale: Boolean): Any? {
        val elem = js_get(index)
        if (!useLocale) return elem
        return ScriptRuntime.getPropAndThis(elem, "toLocaleString", cx, scope)!!.call(cx, scope, ScriptRuntime.emptyArgs)
    }

    private fun sortTemporaryArray(cx: Context, scope: Scriptable, args: Array<Any?>): Array<Any?> {
        val working = Array<Any?>(length) { js_get(it) }
        if (args.isNotEmpty() && Undefined.instance !== args[0]) {
            val comparator = ArrayLikeAbstractOperations.getSortComparator(cx, scope, args)
            sortStable(working) { a, b -> comparator.compare(a, b) }
        } else {
            // The default order for a typed array is numeric, not the string order Array uses.
            // compareTo, not <, so it is the total order: -0 before 0, and NaN last.
            sortStable(working) { a, b ->
                if (a is KBigInt && b is KBigInt) {
                    a.compareTo(b)
                } else {
                    (a as Number).toDouble().compareTo((b as Number).toDouble())
                }
            }
        }
        return working
    }

    /** Builds the result of a method through the species constructor. */
    private fun typedArraySpeciesCreate(cx: Context, scope: Scriptable, args: Array<Any?>, methodName: String): NativeTypedArrayView {
        val topLevelScope = getTopLevelScope(scope)
        val defaultConstructor = ScriptRuntime.getExistingCtor(cx, topLevelScope, className)
        val constructable = AbstractEcmaObjectOperations.speciesConstructor(cx, this, defaultConstructor)

        val newArray = constructable.construct(cx, scope, args)
        if (newArray is NativeTypedArrayView) {
            val len = newArray.validateAndGetLength()
            if (args.size == 1 && args[0] is Number) {
                if (len < (args[0] as Number).toLong()) {
                    throw ScriptRuntime.rangeErrorById("msg.typed.array.bad.length", len)
                }
            }
        } else {
            throw ScriptRuntime.typeErrorById("msg.typed.array.receiver.incompatible", "prototype.$methodName")
        }
        return newArray
    }

    /** A same-typed array of the same length, for the methods that never consult species. */
    private fun sameTypeCopy(cx: Context, scope: Scriptable): Scriptable = cx.newObject(
        scope,
        className,
        arrayOf<Any?>(NativeArrayBuffer(length * bytesPerElement), 0, length, bytesPerElement),
    )

    public companion object {
        private val TYPED_ARRAY_TAG: Any = "%TypedArray.prototype%"

        /** How a concrete view builds itself, so the shared code can make one of any type. */
        public fun interface TypedArrayConstructable {
            public fun construct(ab: NativeArrayBuffer, off: Int, len: Int): NativeTypedArrayView
        }

        /**
         * Installs the shared `%TypedArray%` intrinsic once, then hangs [constructor] off it. Every
         * concrete view inherits its prototype methods from there, which is what makes
         * `Object.getPrototypeOf(Int8Array)` the same object for all of them.
         */
        internal fun init(cx: Context, scope: Scriptable, constructor: LambdaConstructor) {
            val s = scope as ScriptableObject

            var ta = s.getAssociatedValue(TYPED_ARRAY_TAG) as LambdaConstructor?
            if (ta == null) {
                val proto = cx.newObject(s) as ScriptableObject
                ta = LambdaConstructor(
                    s,
                    "TypedArray",
                    0,
                    proto,
                    null,
                    SerializableConstructableThrowing,
                )
                proto.defineProperty("constructor", ta, DONTENUM)

                ta.definePrototypeProperty(cx, "buffer", LambdaGetterFunction { realThis(it).arrayBuffer }, null, DONTENUM or READONLY)
                ta.definePrototypeProperty(cx, "byteLength", LambdaGetterFunction { js_byteLength(it) }, null, DONTENUM or READONLY)
                ta.definePrototypeProperty(cx, "byteOffset", LambdaGetterFunction { js_byteOffset(it) }, null, DONTENUM or READONLY)
                ta.definePrototypeProperty(cx, "length", LambdaGetterFunction { js_length(it) }, null, DONTENUM or READONLY)
                ta.definePrototypeProperty(cx, SymbolKey.TO_STRING_TAG, LambdaGetterFunction { js_toStringTag(it) }, null, DONTENUM)

                defineMethod(ta, s, "at", 1) { cx2, s2, t, a -> js_at(cx2, s2, t, a) }
                defineMethod(ta, s, "copyWithin", 2) { cx2, s2, t, a -> js_copyWithin(cx2, s2, t, a) }
                defineMethod(ta, s, "entries", 0) { cx2, s2, t, a -> js_iteratorOf(s2, t, NativeArrayIterator.ARRAY_ITERATOR_TYPE.ENTRIES) }
                defineMethod(ta, s, "every", 1) { cx2, s2, t, a -> iterative(cx2, s2, t, a, ArrayLikeAbstractOperations.IterativeOperation.EVERY) }
                defineMethod(ta, s, "fill", 1) { cx2, s2, t, a -> js_fill(cx2, s2, t, a) }
                defineMethod(ta, s, "filter", 1) { cx2, s2, t, a -> js_filter(cx2, s2, t, a) }
                defineMethod(ta, s, "find", 1) { cx2, s2, t, a -> iterative(cx2, s2, t, a, ArrayLikeAbstractOperations.IterativeOperation.FIND) }
                defineMethod(ta, s, "findIndex", 1) { cx2, s2, t, a -> iterative(cx2, s2, t, a, ArrayLikeAbstractOperations.IterativeOperation.FIND_INDEX) }
                defineMethod(ta, s, "findLast", 1) { cx2, s2, t, a -> iterative(cx2, s2, t, a, ArrayLikeAbstractOperations.IterativeOperation.FIND_LAST) }
                defineMethod(ta, s, "findLastIndex", 1) { cx2, s2, t, a -> iterative(cx2, s2, t, a, ArrayLikeAbstractOperations.IterativeOperation.FIND_LAST_INDEX) }
                defineMethod(ta, s, "forEach", 1) { cx2, s2, t, a -> iterative(cx2, s2, t, a, ArrayLikeAbstractOperations.IterativeOperation.FOR_EACH) }
                defineMethod(ta, s, "includes", 1) { cx2, s2, t, a -> js_includes(t, a) }
                defineMethod(ta, s, "indexOf", 1) { cx2, s2, t, a -> js_indexOf(t, a) }
                defineMethod(ta, s, "join", 1) { cx2, s2, t, a -> js_join(t, a) }
                defineMethod(ta, s, "keys", 0) { cx2, s2, t, a -> js_iteratorOf(s2, t, NativeArrayIterator.ARRAY_ITERATOR_TYPE.KEYS) }
                defineMethod(ta, s, "lastIndexOf", 1) { cx2, s2, t, a -> js_lastIndexOf(t, a) }
                defineMethod(ta, s, "map", 1) { cx2, s2, t, a -> js_map(cx2, s2, t, a) }
                defineMethod(ta, s, "reduce", 1) { cx2, s2, t, a -> reduce(cx2, s2, t, a, ArrayLikeAbstractOperations.ReduceOperation.REDUCE) }
                defineMethod(ta, s, "reduceRight", 1) { cx2, s2, t, a -> reduce(cx2, s2, t, a, ArrayLikeAbstractOperations.ReduceOperation.REDUCE_RIGHT) }
                defineMethod(ta, s, "reverse", 0) { cx2, s2, t, a -> js_reverse(t) }
                defineMethod(ta, s, "set", 1) { cx2, s2, t, a -> js_setMethod(cx2, s2, t, a) }
                defineMethod(ta, s, "slice", 2) { cx2, s2, t, a -> js_slice(cx2, s2, t, a) }
                defineMethod(ta, s, "some", 1) { cx2, s2, t, a -> iterative(cx2, s2, t, a, ArrayLikeAbstractOperations.IterativeOperation.SOME) }
                defineMethod(ta, s, "sort", 1) { cx2, s2, t, a -> js_sort(cx2, s2, t, a) }
                defineMethod(ta, s, "subarray", 2) { cx2, s2, t, a -> js_subarray(cx2, s2, t, a) }
                defineMethod(ta, s, "toLocaleString", 0) { cx2, s2, t, a -> js_toStringInternal(cx2, s2, t, true) }
                defineMethod(ta, s, "toReversed", 0) { cx2, s2, t, a -> js_toReversed(cx2, s2, t) }
                defineMethod(ta, s, "toSorted", 1) { cx2, s2, t, a -> js_toSorted(cx2, s2, t, a) }
                defineMethod(ta, s, "toString", 0) { cx2, s2, t, a -> js_toStringInternal(cx2, s2, t, false) }
                defineMethod(ta, s, "values", 0) { cx2, s2, t, a -> js_iteratorOf(s2, t, NativeArrayIterator.ARRAY_ITERATOR_TYPE.VALUES) }
                defineMethod(ta, s, "with", 2) { cx2, s2, t, a -> js_with(cx2, s2, t, a) }
                ta.definePrototypeMethod(scope, SymbolKey.ITERATOR, 0, SerializableCallable { cx2, s2, t, _ -> js_iteratorOf(s2, t, NativeArrayIterator.ARRAY_ITERATOR_TYPE.VALUES) })

                ta.defineConstructorMethod(scope, "from", 1, SerializableCallable { cx2, s2, t, a -> js_from(cx2, s2, t, a) })
                ta.defineConstructorMethod(scope, "of", 0, SerializableCallable { cx2, s2, t, a -> js_of(cx2, s2, t, a) })

                ta = s.associateValue(TYPED_ARRAY_TAG, ta) as LambdaConstructor
            }
            constructor.prototype = ta
            (constructor.prototypeProperty as ScriptableObject).prototype = ta.prototypeProperty as Scriptable
        }

        /** `%TypedArray%` itself cannot be called or constructed. */
        private val SerializableConstructableThrowing = io.github.yuroyami.kitejs.SerializableConstructable { _, _, _ ->
            throw ScriptRuntime.typeErrorById("msg.typed.array.abstract.ctor")
        }

        private fun defineMethod(
            typedArray: LambdaConstructor,
            scope: Scriptable,
            name: String,
            length: Int,
            target: (Context, Scriptable, Scriptable?, Array<Any?>) -> Any?,
        ) {
            typedArray.definePrototypeMethod(scope, name, length, SerializableCallable { cx, s, thisObj, args -> target(cx, s, thisObj, args) })
        }

        /** A positive index when the double names one, and -1 otherwise. */
        private fun toIndex(num: Double): Int {
            val ix = num.toInt()
            return if (ix.toDouble() == num && ix >= 0) ix else -1
        }

        private fun realThis(thisObj: Scriptable?): NativeTypedArrayView =
            LambdaConstructor.convertThisObject<NativeTypedArrayView>(thisObj)

        private fun js_toStringTag(thisObj: Scriptable?): Any? =
            if (thisObj is NativeTypedArrayView) thisObj.className else Undefined.instance

        private fun js_byteLength(thisObj: Scriptable?): Any {
            val o = realThis(thisObj)
            return if (o.isTypedArrayOutOfBounds) 0 else o.byteLength
        }

        private fun js_byteOffset(thisObj: Scriptable?): Any {
            val o = realThis(thisObj)
            return if (o.isTypedArrayOutOfBounds) 0 else o.offset
        }

        private fun js_length(thisObj: Scriptable?): Any {
            val o = realThis(thisObj)
            return if (o.isTypedArrayOutOfBounds) 0 else o.length
        }

        private fun makeArrayBuffer(cx: Context, scope: Scriptable, length: Int, bytesPerElement: Int): NativeArrayBuffer =
            cx.newObject(scope, NativeArrayBuffer.CLASS_NAME, arrayOf<Any?>(length.toDouble() * bytesPerElement)) as NativeArrayBuffer

        /** The shared constructor body: every concrete view calls this with its own factory. */
        internal fun js_constructor(
            cx: Context,
            scope: Scriptable,
            args: Array<Any?>,
            constructable: TypedArrayConstructable,
            bytesPerElement: Int,
        ): NativeTypedArrayView {
            if (!NativeArrayBuffer.isArg(args, 0)) return constructable.construct(NativeArrayBuffer(), 0, 0)

            val arg0 = args[0] ?: return constructable.construct(NativeArrayBuffer(), 0, 0)

            if (arg0 is Number || arg0 is String) {
                // A length, so the array starts out zeroed.
                val length = ScriptRuntime.toInt32(arg0)
                return constructable.construct(makeArrayBuffer(cx, scope, length, bytesPerElement), 0, length)
            }

            if (arg0 is NativeTypedArrayView) {
                // Another view, converted element by element into this type.
                val na = makeArrayBuffer(cx, scope, arg0.length, bytesPerElement)
                val v = constructable.construct(na, 0, arg0.length)
                for (i in 0 until arg0.length) v.js_set(i, arg0.js_get(i))
                return v
            }

            if (arg0 is NativeArrayBuffer) {
                // A window onto an existing buffer, sharing its bytes.
                val byteOff = if (NativeArrayBuffer.isArg(args, 1)) ScriptRuntime.toIndex(args[1]) else 0
                if ((byteOff % bytesPerElement) != 0) {
                    throw ScriptRuntime.rangeErrorById("msg.typed.array.bad.offset.byte.size", byteOff, bytesPerElement)
                }

                var newLength = 0
                if (NativeArrayBuffer.isArg(args, 2)) newLength = ScriptRuntime.toIndex(args[2])

                if (arg0.isDetached) throw ScriptRuntime.typeErrorById("msg.arraybuf.detached")
                val bufferByteLength = arg0.length

                val newByteLength: Int
                if (!NativeArrayBuffer.isArg(args, 2)) {
                    newByteLength = bufferByteLength - byteOff
                    if ((bufferByteLength % bytesPerElement) != 0) {
                        throw ScriptRuntime.rangeErrorById("msg.typed.array.bad.buffer.length.byte.size", newByteLength, bytesPerElement)
                    }
                    if (newByteLength < 0) throw ScriptRuntime.rangeErrorById("msg.typed.array.bad.offset", byteOff)
                } else {
                    newByteLength = newLength * bytesPerElement
                    if (byteOff + newByteLength > bufferByteLength) {
                        throw ScriptRuntime.rangeErrorById("msg.typed.array.bad.length", newByteLength)
                    }
                }

                if (byteOff < 0 || byteOff > arg0.length) {
                    throw ScriptRuntime.rangeErrorById("msg.typed.array.bad.offset", byteOff)
                }
                return constructable.construct(arg0, byteOff, newByteLength / bytesPerElement)
            }

            if (arg0 is NativeArray) {
                val size = arg0.size()
                val na = makeArrayBuffer(cx, scope, size, bytesPerElement)
                val v = constructable.construct(na, 0, size)
                for (i in 0 until size) {
                    // Read raw so a hole stays a hole rather than becoming zero.
                    val value = arg0.get(i, arg0)
                    if (value === Scriptable.NOT_FOUND || value === Undefined.instance) {
                        v.js_set(i, ScriptRuntime.NaNobj)
                    } else {
                        v.js_set(i, value)
                    }
                }
                return v
            }

            if (ScriptRuntime.isArrayObject(arg0)) {
                val arrayElements = ScriptRuntime.getArrayElements(arg0 as Scriptable)
                val na = makeArrayBuffer(cx, scope, arrayElements.size, bytesPerElement)
                val v = constructable.construct(na, 0, arrayElements.size)
                for (i in arrayElements.indices) v.js_set(i, arrayElements[i])
                return v
            }

            throw ScriptRuntime.constructError("Error", "invalid argument")
        }

        // ---- The prototype methods ---------------------------------------------------------------

        private fun iterative(
            cx: Context,
            scope: Scriptable,
            thisObj: Scriptable?,
            args: Array<Any?>,
            op: ArrayLikeAbstractOperations.IterativeOperation,
        ): Any? {
            val self = realThis(thisObj)
            return ArrayLikeAbstractOperations.coercibleIterativeMethod(cx, op, scope, self, args, self.validateAndGetLength())
        }

        private fun reduce(
            cx: Context,
            scope: Scriptable,
            thisObj: Scriptable?,
            args: Array<Any?>,
            op: ArrayLikeAbstractOperations.ReduceOperation,
        ): Any? {
            val self = realThis(thisObj)
            return ArrayLikeAbstractOperations.reduceMethodWithLength(cx, op, scope, self, args, self.validateAndGetLength())
        }

        private fun js_iteratorOf(scope: Scriptable, thisObj: Scriptable?, type: NativeArrayIterator.ARRAY_ITERATOR_TYPE): Any {
            val self = realThis(thisObj)
            if (self.isTypedArrayOutOfBounds) throw ScriptRuntime.typeErrorById("msg.typed.array.out.of.bounds")
            return NativeArrayIterator(scope, self, type)
        }

        private fun js_toStringInternal(cx: Context, scope: Scriptable, thisObj: Scriptable?, useLocale: Boolean): String {
            val self = realThis(thisObj)
            if (self.isTypedArrayOutOfBounds) throw ScriptRuntime.typeErrorById("msg.typed.array.out.of.bounds")

            val builder = StringBuilder()
            if (self.length > 0) builder.append(ScriptRuntime.toString(self.getElemForToString(cx, scope, 0, useLocale)))
            for (i in 1 until self.length) {
                builder.append(',')
                builder.append(ScriptRuntime.toString(self.getElemForToString(cx, scope, i, useLocale)))
            }
            return builder.toString()
        }

        private fun js_filter(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val self = realThis(thisObj)
            val array = ArrayLikeAbstractOperations.coercibleIterativeMethod(
                cx, ArrayLikeAbstractOperations.IterativeOperation.FILTER, scope, self, args, self.validateAndGetLength(),
            )
            return self.typedArraySpeciesCreate(cx, scope, arrayOf<Any?>(array), "filter")
        }

        private fun js_map(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val self = realThis(thisObj)
            val array = ArrayLikeAbstractOperations.coercibleIterativeMethod(
                cx, ArrayLikeAbstractOperations.IterativeOperation.MAP, scope, thisObj!!, args, self.validateAndGetLength(),
            )
            return self.typedArraySpeciesCreate(cx, scope, arrayOf<Any?>(array), "map")
        }

        private fun js_includes(thisObj: Scriptable?, args: Array<Any?>): Boolean {
            val self = realThis(thisObj)
            val len = self.validateAndGetLength()
            val compareTo = if (args.isNotEmpty()) args[0] else Undefined.instance
            if (len == 0L) return false

            var start: Long
            if (args.size < 2) {
                start = 0
            } else {
                start = ScriptRuntime.toInteger(args[1]).toLong()
                if (start < 0) {
                    start += len
                    if (start < 0) start = 0
                }
                if (start > len - 1) return false
            }
            for (i in start.toInt() until len.toInt()) {
                if (ScriptRuntime.sameZero(self.js_get(i), compareTo)) return true
            }
            return false
        }

        private fun js_indexOf(thisObj: Scriptable?, args: Array<Any?>): Any {
            val self = realThis(thisObj)
            val len = self.validateAndGetLength()
            val compareTo = if (args.isNotEmpty()) args[0] else Undefined.instance
            if (len == 0L) return -1

            var start: Long
            if (args.size < 2) {
                start = 0
            } else {
                start = ScriptRuntime.toInteger(args[1]).toLong()
                if (start < 0) {
                    start += len
                    if (start < 0) start = 0
                }
                if (start > len - 1) return -1
            }
            for (i in start.toInt() until len.toInt()) {
                if (self.has(i, self)) {
                    val v = self.js_get(i)
                    if (v !== Scriptable.NOT_FOUND && ScriptRuntime.shallowEq(v, compareTo)) return i.toLong()
                }
            }
            return -1
        }

        private fun js_lastIndexOf(thisObj: Scriptable?, args: Array<Any?>): Any {
            val self = realThis(thisObj)
            val len = self.validateAndGetLength()
            val compareTo = if (args.isNotEmpty()) args[0] else Undefined.instance
            if (len == 0L) return -1

            var start: Long
            if (args.size < 2) {
                start = len - 1L
            } else {
                start = ScriptRuntime.toInteger(args[1]).toLong()
                if (start >= len) start = len - 1L else if (start < 0) start += len
                if (start < 0) return -1
            }
            for (i in start.toInt() downTo 0) {
                if (self.has(i, self)) {
                    val v = self.js_get(i)
                    if (v !== Scriptable.NOT_FOUND && ScriptRuntime.shallowEq(v, compareTo)) return i.toLong()
                }
            }
            return -1
        }

        private fun js_slice(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Scriptable {
            val self = realThis(thisObj)
            val srcLength = self.validateAndGetLength()

            val begin: Long
            var end: Long
            if (args.isEmpty()) {
                begin = 0
                end = srcLength
            } else {
                begin = ArrayLikeAbstractOperations.toSliceIndex(ScriptRuntime.toInteger(args[0]), srcLength)
                end = if (args.size == 1 || args[1] === Undefined.instance) {
                    srcLength
                } else {
                    ArrayLikeAbstractOperations.toSliceIndex(ScriptRuntime.toInteger(args[1]), srcLength)
                }
            }

            if (end - begin > Int.MAX_VALUE) throw ScriptRuntime.rangeError(Messages.getMessageById("msg.arraylength.bad"))

            val count = maxOf(end - begin, 0)
            val a = self.typedArraySpeciesCreate(cx, scope, arrayOf<Any?>(count), "slice")

            if (count > 0) {
                if (self.isTypedArrayOutOfBounds) throw ScriptRuntime.typeErrorById("msg.typed.array.out.of.bounds")
                end = minOf(end, self.length.toLong())
                var n = 0
                for (i in begin.toInt() until end.toInt()) {
                    a.js_set(n, self.js_get(i))
                    n++
                }
            }
            return a
        }

        private fun js_join(thisObj: Scriptable?, args: Array<Any?>): String {
            val self = realThis(thisObj)
            val len = self.validateAndGetLength().toInt()
            val separator = if (args.isEmpty() || args[0] === Undefined.instance) "," else ScriptRuntime.toString(args[0])
            if (len == 0) return ""

            val sb = StringBuilder()
            for (i in 0 until len) {
                if (i != 0) sb.append(separator)
                val temp = self.js_get(i)
                // null and undefined contribute nothing, as in Array.prototype.join.
                if (temp != null && temp !== Undefined.instance) sb.append(ScriptRuntime.toString(temp))
            }
            return sb.toString()
        }

        private fun js_reverse(thisObj: Scriptable?): NativeTypedArrayView {
            val self = realThis(thisObj)
            val len = self.validateAndGetLength().toInt()
            var i = 0
            var j = len - 1
            while (i < j) {
                val temp = self.js_get(i)
                self.js_set(i, self.js_get(j))
                self.js_set(j, temp)
                i++
                j--
            }
            return self
        }

        private fun js_fill(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): NativeTypedArrayView {
            val self = realThis(thisObj)
            val len = self.validateAndGetLength()
            val value = self.toNumeric(if (args.isNotEmpty()) args[0] else Undefined.instance)

            var relativeStart = 0L
            if (args.size >= 2) relativeStart = ScriptRuntime.toInteger(args[1]).toLong()
            val k = if (relativeStart < 0) maxOf(len + relativeStart, 0) else minOf(relativeStart, len)

            var relativeEnd = len
            if (args.size > 2 && !Undefined.isUndefined(args[2])) relativeEnd = ScriptRuntime.toInteger(args[2]).toLong()
            val fin = if (relativeEnd < 0) maxOf(len + relativeEnd, 0) else minOf(relativeEnd, len)

            if (self.isTypedArrayOutOfBounds) throw ScriptRuntime.typeErrorById("msg.typed.array.out.of.bounds")

            for (i in k.toInt() until fin.toInt()) self.js_set(i, value)
            return self
        }

        private fun js_sort(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Scriptable {
            if (NativeArrayBuffer.isArg(args, 0) && args[0] !is Callable) {
                throw ScriptRuntime.typeErrorById("msg.function.expected")
            }
            val self = realThis(thisObj)
            val len = self.validateAndGetLength()
            val working = self.sortTemporaryArray(cx, scope, args)
            for (i in 0 until len.toInt()) self.js_set(i, working[i])
            return self
        }

        private fun js_copyWithin(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any {
            val self = realThis(thisObj)
            val len = self.validateAndGetLength()

            val relativeTarget = ScriptRuntime.toInteger(if (args.isNotEmpty()) args[0] else Undefined.instance).toLong()
            var to = if (relativeTarget < 0) maxOf(len + relativeTarget, 0) else minOf(relativeTarget, len)

            val relativeStart = ScriptRuntime.toInteger(if (args.size >= 2) args[1] else Undefined.instance).toLong()
            var from = if (relativeStart < 0) maxOf(len + relativeStart, 0) else minOf(relativeStart, len)

            var relativeEnd = len
            if (NativeArrayBuffer.isArg(args, 2)) relativeEnd = ScriptRuntime.toInteger(args[2]).toLong()
            val fin = if (relativeEnd < 0) maxOf(len + relativeEnd, 0) else minOf(relativeEnd, len)

            var count = minOf(fin - from, len - to)
            if (count > 0) {
                if (self.isTypedArrayOutOfBounds) throw ScriptRuntime.typeErrorById("msg.typed.array.out.of.bounds")
                var direction = 1
                // Overlapping ranges have to be walked backwards or the copy eats its own source.
                if (from < to && to < from + count) {
                    direction = -1
                    from += count - 1
                    to += count - 1
                }
                while (count > 0) {
                    self.js_set(to.toInt(), self.js_get(from.toInt()))
                    from += direction
                    to += direction
                    count--
                }
            }
            return self
        }

        private fun js_setMethod(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any {
            val self = realThis(thisObj)
            val offset = if (NativeArrayBuffer.isArg(args, 1)) ScriptRuntime.toIntegerOrInfinity(args[1]) else 0.0
            if (offset < 0) throw ScriptRuntime.rangeErrorById("msg.typed.array.bad.offset", offset)
            val source = args[0]
            if (source is NativeTypedArrayView) {
                self.setRange(source, offset)
            } else {
                self.setRange(cx, scope, ensureScriptable(source), offset)
            }
            return Undefined.instance
        }

        private fun js_subarray(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (args.isEmpty() && cx.languageVersion < Context.VERSION_ES6) {
                throw ScriptRuntime.constructError("Error", "invalid arguments")
            }
            val self = realThis(thisObj)
            val srcLength = if (self.isTypedArrayOutOfBounds) 0 else self.length

            var start = if (NativeArrayBuffer.isArg(args, 0)) ScriptRuntime.toInt32(args[0]) else 0
            var end = if (NativeArrayBuffer.isArg(args, 1)) ScriptRuntime.toInt32(args[1]) else srcLength
            start = if (start < 0) srcLength + start else start
            end = if (end < 0) srcLength + end else end

            start = maxOf(0, start)
            start = minOf(start, srcLength)
            end = minOf(srcLength, end)
            val len = maxOf(0, end - start)
            val byteOff = self.offset + start * self.bytesPerElement

            return self.typedArraySpeciesCreate(cx, scope, arrayOf<Any?>(self.arrayBuffer, byteOff, len), "subarray")
        }

        private fun js_at(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val self = realThis(thisObj)
            var relativeIndex = 0L
            if (args.isNotEmpty()) relativeIndex = ScriptRuntime.toInteger(args[0]).toLong()
            val k = if (relativeIndex >= 0) relativeIndex else self.length + relativeIndex
            if (k < 0 || k >= self.length) return Undefined.instance
            return getProperty(thisObj!!, k.toInt())
        }

        private fun js_toReversed(cx: Context, scope: Scriptable, thisObj: Scriptable?): Any {
            val self = realThis(thisObj)
            val result = self.sameTypeCopy(cx, scope)
            for (k in 0 until self.length) {
                result.put(k, result, self.js_get(self.length - k - 1))
            }
            return result
        }

        private fun js_toSorted(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any {
            val self = realThis(thisObj)
            val working = self.sortTemporaryArray(cx, scope, args)
            val result = self.sameTypeCopy(cx, scope)
            for (k in 0 until self.length) result.put(k, result, working[k])
            return result
        }

        private fun js_with(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any {
            val self = realThis(thisObj)
            val relativeIndex = if (args.isNotEmpty()) ScriptRuntime.toInteger(args[0]).toLong() else 0L
            val actualIndex = if (relativeIndex >= 0) relativeIndex else self.length + relativeIndex
            val argsValue: Any = if (args.size > 1) ScriptRuntime.toNumber(args[1]) else 0.0

            if (actualIndex < 0 || actualIndex >= self.length) {
                throw ScriptRuntime.rangeError(
                    Messages.getMessageById("msg.typed.array.index.out.of.bounds", relativeIndex, self.length * -1, self.length - 1),
                )
            }

            val result = self.sameTypeCopy(cx, scope)
            for (k in 0 until self.length) {
                result.put(k, result, if (k.toLong() == actualIndex) argsValue else self.js_get(k))
            }
            return result
        }

        private fun js_from(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (args.isEmpty()) throw ScriptRuntime.typeErrorById("msg.missing.argument")
            val items = ScriptRuntime.toObject(scope, args[0])
            if (!AbstractEcmaObjectOperations.isConstructor(cx, thisObj)) {
                throw ScriptRuntime.typeErrorById("msg.constructor.expected")
            }
            val constructable = thisObj as Constructable

            var mapFn: Function? = null
            val mapArg = if (args.size >= 2) args[1] else Undefined.instance
            var mapFnThisArg: Scriptable = Undefined.SCRIPTABLE_UNDEFINED
            if (!Undefined.isUndefined(mapArg)) {
                if (mapArg !is Function) throw ScriptRuntime.typeErrorById("msg.map.function.not")
                mapFn = mapArg
                if (args.size >= 3) mapFnThisArg = ensureScriptable(args[2])
            }

            var listFromIterator: MutableList<Any?>? = null
            val iteratorProp = getProperty(items, SymbolKey.ITERATOR)
            // A typed array or a NativeArray is read by index instead, which avoids copying it.
            if (iteratorProp !== Scriptable.NOT_FOUND &&
                items !is NativeArray && items !is NativeTypedArrayView &&
                !Undefined.isUndefined(iteratorProp)
            ) {
                val iterator = ScriptRuntime.callIterator(items, cx, scope)
                if (!Undefined.isUndefined(iterator)) {
                    IteratorLikeIterable(cx, scope, iterator).use { it ->
                        val list = ArrayList<Any?>()
                        for (temp in it) list.add(temp)
                        listFromIterator = list
                    }
                }
            }

            val size: Int
            if (listFromIterator != null) {
                size = listFromIterator.size
            } else {
                val sizeLong = AbstractEcmaObjectOperations.lengthOfArrayLike(cx, items)
                if (sizeLong > Int.MAX_VALUE) throw ScriptRuntime.rangeErrorById("msg.arraylength.bad")
                size = sizeLong.toInt()
            }

            val result = constructable.construct(cx, scope, arrayOf<Any?>(size))
            if (result !is NativeTypedArrayView) throw ScriptRuntime.typeErrorById("msg.typed.array.receiver.incompatible", "from")
            if (result.length < size) throw ScriptRuntime.typeErrorById("msg.typed.array.length.too.small")

            for (k in 0 until size) {
                var temp: Any? = if (listFromIterator != null) {
                    listFromIterator[k]
                } else if (items is NativeTypedArrayView) {
                    items.js_get(k)
                } else {
                    ScriptRuntime.getObjectIndex(items, k.toDouble(), cx, scope)
                }
                if (mapFn != null) temp = mapFn.call(cx, scope, mapFnThisArg, arrayOf(temp, k))
                result.setArrayElement(k, temp)
            }
            return result
        }

        private fun js_of(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (!AbstractEcmaObjectOperations.isConstructor(cx, thisObj)) {
                throw ScriptRuntime.typeErrorById("msg.constructor.expected")
            }
            val constructable = thisObj as Constructable
            val result = constructable.construct(cx, scope, arrayOf<Any?>(args.size))
            if (result !is NativeTypedArrayView) throw ScriptRuntime.typeErrorById("msg.typed.array.receiver.incompatible", "of")
            if (result.length < args.size) throw ScriptRuntime.typeErrorById("msg.typed.array.length.too.small")
            for (k in args.indices) result.setArrayElement(k, args[k])
            return result
        }

        /** A stable insertion sort, matching the stability of the sort upstream uses. */
        private fun sortStable(a: Array<Any?>, compare: (Any?, Any?) -> Int) {
            for (i in 1 until a.size) {
                val key = a[i]
                var j = i - 1
                while (j >= 0 && compare(a[j], key) > 0) {
                    a[j + 1] = a[j]
                    j--
                }
                a[j + 1] = key
            }
        }
    }
}
