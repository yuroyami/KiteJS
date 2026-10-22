/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino.typedarrays

import io.github.yuroyami.kitejs.rhino.AbstractEcmaObjectOperations
import io.github.yuroyami.kitejs.rhino.ArrayLikeAbstractOperations
import io.github.yuroyami.kitejs.rhino.Callable
import io.github.yuroyami.kitejs.rhino.CompoundOperationMap
import io.github.yuroyami.kitejs.rhino.Constructable
import io.github.yuroyami.kitejs.rhino.Context
import io.github.yuroyami.kitejs.api.KBigInt
import io.github.yuroyami.kitejs.rhino.ExternalArrayData
import io.github.yuroyami.kitejs.rhino.Function
import io.github.yuroyami.kitejs.rhino.Intrinsics
import io.github.yuroyami.kitejs.rhino.LambdaConstructor
import io.github.yuroyami.kitejs.rhino.Messages
import io.github.yuroyami.kitejs.rhino.NativeArrayIterator
import io.github.yuroyami.kitejs.rhino.NativeNumber
import io.github.yuroyami.kitejs.rhino.ScriptRuntime
import io.github.yuroyami.kitejs.rhino.Scriptable
import io.github.yuroyami.kitejs.rhino.ScriptableObject
import io.github.yuroyami.kitejs.rhino.SerializableCallable
import io.github.yuroyami.kitejs.rhino.SymbolScriptable
import io.github.yuroyami.kitejs.rhino.SymbolKey
import io.github.yuroyami.kitejs.rhino.Undefined
import kotlin.math.truncate

/**
 * The parent of the nine numeric views. Each shows one buffer through one element type, and writes
 * through any view are seen by all of them.
 *
 * The spec calls these "integer-indexed exotic objects": an index outside the array is not an
 * error, it simply reads as `undefined` and writes nowhere. That is why `get`, `put`, `has` and
 * `delete` are all overridden.
 */
public abstract class NativeTypedArrayView : NativeArrayBufferView, ExternalArrayData {

    /** The element count given at construction, or -1 for a length-tracking view. */
    private val fixedLength: Int

    /** How many elements the view holds. A length-tracking view counts what fits in its buffer now. */
    protected val length: Int
        get() = when {
            fixedLength >= 0 -> fixedLength
            isTypedArrayOutOfBounds -> 0
            else -> (arrayBuffer.length - offset) / bytesPerElement
        }

    protected constructor() : super() {
        fixedLength = 0
    }

    /** A negative [len] makes a length-tracking view of the resizable [ab]. */
    protected constructor(ab: NativeArrayBuffer, off: Int, len: Int, byteLen: Int) : super(ab, off, byteLen) {
        fixedLength = if (len < 0) -1 else len
    }

    override fun trackedByteLength(available: Int): Int = available / bytesPerElement * bytesPerElement

    // ---- The exotic index behaviour -------------------------------------------------------------
    //
    // Every canonical numeric name belongs to the view (ECMAScript 2024, 10.4.5). A valid index is
    // an element, writable, enumerable and configurable, and any other numeric name, such as "-1",
    // "1.5", "-0", "NaN" or "Infinity", is absent without the prototype ever being asked; a value
    // written to one is still converted. Names that only look numeric, such as "01", are ordinary
    // properties. Upstream let the invalid ones fall through to ordinary lookup, truncated a
    // fraction to an element, and kept elements out of the descriptor and own-key operations
    // (D-88).

    override fun get(index: Int, start: Scriptable): Any? = js_get(index)

    override fun get(name: String, start: Scriptable): Any? {
        val num = ScriptRuntime.canonicalNumericIndexString(name) ?: return super.get(name, start)
        return if (isValidIntegerIndex(num)) js_get(num.toInt()) else Undefined.instance
    }

    override fun has(index: Int, start: Scriptable): Boolean = !checkIndex(index)

    override fun has(name: String, start: Scriptable): Boolean {
        val num = ScriptRuntime.canonicalNumericIndexString(name) ?: return super.has(name, start)
        return isValidIntegerIndex(num)
    }

    /**
     * [[Set]] for an index: written here when this view is the receiver, which converts the value
     * even when the index is out of range, and otherwise an ordinary property of the receiver,
     * made only when the index is valid here.
     */
    override fun put(index: Int, start: Scriptable, value: Any?) {
        if (start === this) {
            js_set(index, value)
        } else if (!checkIndex(index)) {
            start.put(index, start, value)
        }
    }

    override fun put(name: String, start: Scriptable, value: Any?) {
        val num = ScriptRuntime.canonicalNumericIndexString(name) ?: return super.put(name, start, value)
        if (start === this) {
            setElement(num, value)
        } else if (isValidIntegerIndex(num)) {
            start.put(num.toInt(), start, value)
        }
    }

    override fun delete(index: Int) {
        if (!checkIndex(index)) refuseDelete(index)
    }

    override fun delete(name: String) {
        // An element cannot be deleted and an invalid numeric name is never there, so only an
        // ordinary name can go.
        val num = ScriptRuntime.canonicalNumericIndexString(name) ?: return super.delete(name)
        if (isValidIntegerIndex(num)) refuseDelete(name)
    }

    /** Strict code is told that an element cannot be deleted, as for any property that stays. */
    private fun refuseDelete(key: Any) {
        if (Context.getContext().isStrictMode) {
            throw ScriptRuntime.typeErrorById("msg.delete.property.with.configurable.false", key)
        }
    }

    override fun endsLookup(name: String): Boolean = ScriptRuntime.canonicalNumericIndexString(name) != null

    override fun endsLookup(index: Int): Boolean = true

    /** The valid indices in order, then the ordinary properties, as [[OwnPropertyKeys]] says. */
    override fun getIds(map: CompoundOperationMap, getNonEnumerable: Boolean, getSymbols: Boolean): Array<Any?> {
        val ordinary = super.getIds(map, getNonEnumerable, getSymbols)
        val elements = if (isTypedArrayOutOfBounds) 0 else length
        if (elements == 0) return ordinary
        val ids = arrayOfNulls<Any?>(elements + ordinary.size)
        for (i in 0 until elements) ids[i] = i
        ordinary.copyInto(ids, elements)
        return ids
    }

    override fun getOwnPropertyDescriptor(cx: Context, id: Any?): DescriptorInfo? {
        val num = numericKey(id) ?: return super.getOwnPropertyDescriptor(cx, id)
        if (!isValidIntegerIndex(num)) return null
        return DescriptorInfo(true, true, true, js_get(num.toInt()))
    }

    override fun defineOwnProperty(cx: Context, id: Any?, desc: DescriptorInfo, checkValid: Boolean): Boolean {
        val num = numericKey(id) ?: return super.defineOwnProperty(cx, id, desc, checkValid)
        if (!isValidIntegerIndex(num)) return false
        // An element is always a writable, enumerable and configurable data property, so a
        // descriptor that says otherwise is refused.
        if (desc.isConfigurable(false) || desc.isEnumerable(false)) return false
        if (desc.isAccessorDescriptor || desc.isWritable(false)) return false
        if (desc.hasValue()) setElement(num, desc.value)
        return true
    }

    /**
     * [[Set]] for a canonical numeric key (ECMAScript 2024, 10.4.5.5): written here when this view
     * is the receiver, ignored when the index is invalid, and otherwise null, which asks for
     * OrdinarySet onto the receiver. Any other key is null too.
     */
    internal fun set(cx: Context, key: Any, value: Any?, receiver: Any?): Boolean? {
        val num = numericKey(key) ?: return null
        if (receiver === this) {
            setElement(num, value)
            return true
        }
        return if (isValidIntegerIndex(num)) null else true
    }

    /** TypedArraySetElement: the value is converted first, then written if the index is valid. */
    private fun setElement(index: Double, value: Any?) {
        val converted = toNumeric(value)
        if (isValidIntegerIndex(index)) js_set(index.toInt(), converted)
    }

    /** IsValidIntegerIndex: an integral, non-negative, in-range number on an attached view. */
    private fun isValidIntegerIndex(index: Double): Boolean {
        if (isTypedArrayOutOfBounds) return false
        if (index.isNaN() || index.isInfinite() || index != truncate(index)) return false
        if (index == 0.0 && 1.0 / index < 0) return false
        return index >= 0 && index < length
    }

    /** The number a property key spells when it is a canonical numeric name, or null. */
    private fun numericKey(id: Any?): Double? = when {
        ScriptRuntime.isSymbol(id) -> null
        id is Int -> id.toDouble()
        else -> ScriptRuntime.canonicalNumericIndexString(ScriptRuntime.toString(id))
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

    /** Element access for `Atomics`, which validates the index itself. */
    internal val atomicLength: Int get() = length

    internal val atomicBuffer: NativeArrayBuffer get() = arrayBuffer

    internal fun atomicRead(index: Int): Any? = js_get(index)

    internal fun atomicWrite(index: Int, value: Any?) {
        js_set(index, value)
    }

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

    /**
     * The spec's SetTypedArrayFromArrayLike, for a [source] that is not a typed array: whatever it
     * is goes through ToObject, so a string sets its characters and undefined or null is a
     * TypeError, and each element is read with Get, so one the source inherits counts (D-86).
     */
    private fun setRange(cx: Context, scope: Scriptable, source: Any?, dbloff: Double) {
        if (isTypedArrayOutOfBounds) throw ScriptRuntime.typeErrorById("msg.typed.array.out.of.bounds")
        val targetLength = length
        val src = ScriptRuntime.toObject(cx, scope, source)
        val srcLength = AbstractEcmaObjectOperations.lengthOfArrayLike(cx, src)

        if (dbloff > targetLength) throw ScriptRuntime.rangeErrorById("msg.typed.array.bad.offset", dbloff)
        if (srcLength + dbloff > targetLength) throw ScriptRuntime.rangeErrorById("msg.typed.array.bad.source.array")

        val targetOffset = dbloff.toInt()
        for (k in 0 until srcLength.toInt()) {
            val value = getProperty(src, k)
            js_set(k + targetOffset, if (value === Scriptable.NOT_FOUND) Undefined.instance else value)
        }
    }

    /** An element as `join` and `toLocaleString` write it: undefined, from a shrunk buffer, is empty. */
    private fun getElemForToString(cx: Context, scope: Scriptable, index: Int, useLocale: Boolean): String {
        val elem = js_get(index)
        if (elem == null || elem === Undefined.instance) return ""
        if (!useLocale) return ScriptRuntime.toString(elem)
        return ScriptRuntime.toString(ScriptRuntime.getPropAndThis(elem, "toLocaleString", cx, scope)!!.call(cx, scope, ScriptRuntime.emptyArgs))
    }

    /**
     * The spec's SortIndexedProperties over the first [len] elements, which the caller has just
     * validated: every element is read before the comparator first runs, so a comparator that
     * detaches the buffer changes nothing that is sorted.
     */
    private fun sortTemporaryArray(cx: Context, scope: Scriptable, args: Array<Any?>, len: Int): Array<Any?> {
        val working = Array<Any?>(len) { js_get(it) }
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
        val defaultConstructor = Intrinsics.constructor(cx, scope, className)
        val constructable = AbstractEcmaObjectOperations.speciesConstructor(cx, this, defaultConstructor)

        val newArray = constructable.construct(cx, scope, args)
        if (newArray is NativeTypedArrayView) {
            val len = newArray.validateAndGetLength()
            if (args.size == 1 && args[0] is Number) {
                if (len < (args[0] as Number).toLong()) {
                    throw ScriptRuntime.typeErrorById("msg.typed.array.bad.length", len)
                }
            }
        } else {
            throw ScriptRuntime.typeErrorById("msg.typed.array.receiver.incompatible", "prototype.$methodName")
        }
        return newArray
    }

    /**
     * The spec's TypedArrayCreateSameType, for the methods that never consult species: a new view
     * of this type with [len] elements, made by the realm's own constructor for the type.
     */
    private fun typedArrayCreateSameType(cx: Context, scope: Scriptable, len: Int): NativeTypedArrayView =
        Intrinsics.constructor(cx, scope, className).construct(cx, scope, arrayOf<Any?>(len)) as NativeTypedArrayView

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
            Intrinsics.register(scope, constructor.functionName, constructor)
        }

        /** `%TypedArray%` itself cannot be called or constructed. */
        private val SerializableConstructableThrowing = io.github.yuroyami.kitejs.rhino.SerializableConstructable { _, _, _ ->
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

        /** The spec's AllocateTypedArrayBuffer, which always uses the realm's own `ArrayBuffer`. */
        private fun makeArrayBuffer(cx: Context, scope: Scriptable, length: Int, bytesPerElement: Int): NativeArrayBuffer =
            Intrinsics.constructor(cx, scope, NativeArrayBuffer.CLASS_NAME)
                .construct(cx, scope, arrayOf<Any?>(length.toDouble() * bytesPerElement)) as NativeArrayBuffer

        /** ToIndex stays wide until the caller has checked the backing-store bounds. */
        private fun toIndex(value: Any?): Double {
            val index = ScriptRuntime.toIntegerOrInfinity(value)
            if (index < 0 || index > NativeNumber.MAX_SAFE_INTEGER) {
                throw ScriptRuntime.rangeErrorById("msg.out.of.range.index", index)
            }
            return index
        }

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

            if (arg0 !is Scriptable || ScriptRuntime.isSymbol(arg0)) {
                // A length, so the array starts out zeroed.
                val index = toIndex(arg0)
                if (index >= Int.MAX_VALUE.toDouble()) throw ScriptRuntime.rangeErrorById("msg.typed.array.bad.length", index)
                val length = index.toInt()
                return constructable.construct(makeArrayBuffer(cx, scope, length, bytesPerElement), 0, length)
            }

            if (arg0 is NativeTypedArrayView) {
                // A validated internal copy; a custom iterator on the source is irrelevant.
                val length = arg0.validateAndGetLength().toInt()
                val na = makeArrayBuffer(cx, scope, length, bytesPerElement)
                val v = constructable.construct(na, 0, length)
                if ((v is NativeBigIntArrayView) != (arg0 is NativeBigIntArrayView)) {
                    throw ScriptRuntime.typeErrorById("msg.typed.array.type.mismatch")
                }
                if (v::class == arg0::class) {
                    // Same-type copies preserve every bit, including floating-point NaN payloads.
                    arg0.arrayBuffer.buffer!!.copyInto(na.buffer!!, 0, arg0.offset, arg0.offset + length * bytesPerElement)
                } else {
                    for (i in 0 until length) v.js_set(i, arg0.js_get(i))
                }
                return v
            }

            if (arg0 is NativeArrayBuffer) {
                // A window onto an existing buffer, sharing its bytes.
                val byteOff = if (NativeArrayBuffer.isArg(args, 1)) toIndex(args[1]) else 0.0
                if ((byteOff % bytesPerElement) != 0.0) {
                    throw ScriptRuntime.rangeErrorById("msg.typed.array.bad.offset.byte.size", byteOff, bytesPerElement)
                }

                val newLength = if (NativeArrayBuffer.isArg(args, 2)) toIndex(args[2]) else 0.0

                if (arg0.isDetached) throw ScriptRuntime.typeErrorById("msg.arraybuf.detached")
                val bufferByteLength = arg0.length

                if (!NativeArrayBuffer.isArg(args, 2) && arg0.isResizable) {
                    // No length on a resizable buffer: the view tracks the buffer's length.
                    if (byteOff > bufferByteLength) throw ScriptRuntime.rangeErrorById("msg.typed.array.bad.offset", byteOff)
                    return constructable.construct(arg0, byteOff.toInt(), -1)
                }

                val newByteLength: Int
                if (!NativeArrayBuffer.isArg(args, 2)) {
                    val remaining = bufferByteLength.toDouble() - byteOff
                    if ((bufferByteLength % bytesPerElement) != 0) {
                        throw ScriptRuntime.rangeErrorById("msg.typed.array.bad.buffer.length.byte.size", remaining, bytesPerElement)
                    }
                    if (remaining < 0) throw ScriptRuntime.rangeErrorById("msg.typed.array.bad.offset", byteOff)
                    newByteLength = remaining.toInt()
                } else {
                    val remaining = bufferByteLength.toDouble() - byteOff
                    if (newLength > remaining / bytesPerElement) {
                        throw ScriptRuntime.rangeErrorById("msg.typed.array.bad.length", newLength * bytesPerElement)
                    }
                    newByteLength = (newLength * bytesPerElement).toInt()
                }

                if (byteOff < 0 || byteOff > arg0.length) {
                    throw ScriptRuntime.rangeErrorById("msg.typed.array.bad.offset", byteOff)
                }
                return constructable.construct(arg0, byteOff.toInt(), newByteLength / bytesPerElement)
            }

            val values = iterableToList(cx, scope, arg0)
            val size = values?.size ?: arrayLikeSize(cx, arg0)
            val v = constructable.construct(makeArrayBuffer(cx, scope, size, bytesPerElement), 0, size)
            for (i in 0 until size) {
                v.js_set(i, if (values != null) values[i] else ScriptRuntime.getObjectIndex(arg0, i.toDouble(), cx, scope))
            }
            return v
        }

        private fun arrayLikeSize(cx: Context, source: Scriptable): Int {
            val length = AbstractEcmaObjectOperations.lengthOfArrayLike(cx, source)
            if (length >= Int.MAX_VALUE) throw ScriptRuntime.rangeErrorById("msg.typed.array.bad.length", length)
            return length.toInt()
        }

        /** GetMethod(@@iterator), followed by IteratorToList. A missing method means array-like. */
        private fun iterableToList(cx: Context, scope: Scriptable, source: Scriptable): List<Any?>? {
            var objectWithMethod: Scriptable? = source
            var method: Any? = Scriptable.NOT_FOUND
            while (objectWithMethod != null) {
                method = (objectWithMethod as? SymbolScriptable)?.get(SymbolKey.ITERATOR, source) ?: Scriptable.NOT_FOUND
                if (method !== Scriptable.NOT_FOUND) break
                objectWithMethod = objectWithMethod.prototype
            }
            if (method === Scriptable.NOT_FOUND || method == null || Undefined.isUndefined(method)) return null
            if (method !is Callable) throw ScriptRuntime.notFunctionError(method, SymbolKey.ITERATOR)
            val iterator = iteratorObject(method.call(cx, scope, source, ScriptRuntime.emptyArgs))
            val next = ScriptableObject.getProperty(iterator, "next") as? Callable
                ?: throw ScriptRuntime.typeErrorById("msg.function.expected")
            val values = ArrayList<Any?>()
            while (true) {
                val result = iteratorObject(next.call(cx, scope, iterator, ScriptRuntime.emptyArgs))
                val done = ScriptableObject.getProperty(result, "done")
                if (done !== Scriptable.NOT_FOUND && ScriptRuntime.toBoolean(done)) return values
                val value = ScriptableObject.getProperty(result, "value")
                if (values.size >= Int.MAX_VALUE - 1) throw ScriptRuntime.rangeErrorById("msg.arraylength.bad")
                values.add(if (value === Scriptable.NOT_FOUND) Undefined.instance else value)
            }
        }

        private fun iteratorObject(value: Any?): Scriptable {
            if (value !is Scriptable || Undefined.isUndefined(value) || ScriptRuntime.isSymbol(value)) {
                throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeOf(value))
            }
            return value
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
            return ArrayLikeAbstractOperations.coercibleIterativeMethod(cx, op, scope, self, args, self.validateAndGetLength(), readEveryIndex = true)
        }

        private fun reduce(
            cx: Context,
            scope: Scriptable,
            thisObj: Scriptable?,
            args: Array<Any?>,
            op: ArrayLikeAbstractOperations.ReduceOperation,
        ): Any? {
            val self = realThis(thisObj)
            return ArrayLikeAbstractOperations.reduceMethodWithLength(cx, op, scope, self, args, self.validateAndGetLength(), readEveryIndex = true)
        }

        private fun js_iteratorOf(scope: Scriptable, thisObj: Scriptable?, type: NativeArrayIterator.ARRAY_ITERATOR_TYPE): Any {
            val self = realThis(thisObj)
            if (self.isTypedArrayOutOfBounds) throw ScriptRuntime.typeErrorById("msg.typed.array.out.of.bounds")
            return NativeArrayIterator(scope, self, type)
        }

        private fun js_toStringInternal(cx: Context, scope: Scriptable, thisObj: Scriptable?, useLocale: Boolean): String {
            val self = realThis(thisObj)
            if (self.isTypedArrayOutOfBounds) throw ScriptRuntime.typeErrorById("msg.typed.array.out.of.bounds")

            val len = self.length
            val builder = StringBuilder()
            for (i in 0 until len) {
                if (i > 0) builder.append(',')
                builder.append(self.getElemForToString(cx, scope, i, useLocale))
            }
            return builder.toString()
        }

        /** Calls the callback of `map` or `filter` on element [k], with `thisArg` converted per call (#78). */
        private fun callback(cx: Context, scope: Scriptable, self: NativeTypedArrayView, f: Function, args: Array<Any?>, value: Any?, k: Int): Any? {
            val thisArg = if (args.size < 2) Undefined.instance else args[1]
            val receiver = ScriptRuntime.getApplyOrCallThis(cx, scope, thisArg, 1, f)
            return f.call(cx, ScriptableObject.getTopLevelScope(f), receiver, arrayOf(value, k, self))
        }

        /** Keeps what the callback selects, then makes the result through species with that count. */
        private fun js_filter(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val self = realThis(thisObj)
            val len = self.validateAndGetLength().toInt()
            val f = ArrayLikeAbstractOperations.getCallbackArg(cx, args.getOrElse(0) { Undefined.instance })
            val kept = ArrayList<Any?>()
            for (k in 0 until len) {
                val value = ScriptableObject.getProperty(self, k)
                if (ScriptRuntime.toBoolean(callback(cx, scope, self, f, args, value, k))) kept.add(value)
            }
            val a = self.typedArraySpeciesCreate(cx, scope, arrayOf<Any?>(kept.size), "filter")
            kept.forEachIndexed { n, e -> a.put(n, a, e) }
            return a
        }

        /** Makes the result through species before the first callback, then writes each mapped value into it. */
        private fun js_map(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val self = realThis(thisObj)
            val len = self.validateAndGetLength().toInt()
            val f = ArrayLikeAbstractOperations.getCallbackArg(cx, args.getOrElse(0) { Undefined.instance })
            val a = self.typedArraySpeciesCreate(cx, scope, arrayOf<Any?>(len), "map")
            for (k in 0 until len) a.put(k, a, callback(cx, scope, self, f, args, ScriptableObject.getProperty(self, k), k))
            return a
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
            val len = self.validateAndGetLength().toInt()
            val working = self.sortTemporaryArray(cx, scope, args, len)
            // A comparator may have detached the buffer, which turns these writes into nothing.
            for (i in 0 until len) self.js_set(i, working[i])
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
                // A conversion may have shrunk a resizable buffer: only elements still inside move.
                val limit = self.length.toLong()
                var direction = 1
                // Overlapping ranges have to be walked backwards or the copy eats its own source.
                if (from < to && to < from + count) {
                    direction = -1
                    from += count - 1
                    to += count - 1
                }
                while (count > 0) {
                    if (from < limit && to < limit) self.js_set(to.toInt(), self.js_get(from.toInt()))
                    from += direction
                    to += direction
                    count--
                }
            }
            return self
        }

        private fun js_setMethod(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any {
            val self = realThis(thisObj)
            val offset = ScriptRuntime.toIntegerOrInfinity(args.getOrElse(1) { Undefined.instance })
            if (offset < 0) throw ScriptRuntime.rangeErrorById("msg.typed.array.bad.offset", offset)
            // A missing source is undefined, which the array-like path turns into a TypeError.
            val source = args.getOrElse(0) { Undefined.instance }
            if (source is NativeTypedArrayView) {
                self.setRange(source, offset)
            } else {
                self.setRange(cx, scope, source, offset)
            }
            return Undefined.instance
        }

        private fun js_subarray(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (args.isEmpty() && cx.languageVersion < Context.VERSION_ES6) {
                throw ScriptRuntime.constructError("Error", "invalid arguments")
            }
            val self = realThis(thisObj)
            val srcLength = if (self.isTypedArrayOutOfBounds) 0 else self.length

            val relativeStart = ScriptRuntime.toIntegerOrInfinity(args.getOrElse(0) { Undefined.instance })
            val start = (if (relativeStart < 0) srcLength + relativeStart else relativeStart)
                .coerceIn(0.0, srcLength.toDouble()).toInt()
            val relativeEnd = if (NativeArrayBuffer.isArg(args, 1)) ScriptRuntime.toIntegerOrInfinity(args[1]) else srcLength.toDouble()
            val end = (if (relativeEnd < 0) srcLength + relativeEnd else relativeEnd)
                .coerceIn(0.0, srcLength.toDouble()).toInt()
            val len = maxOf(0, end - start)
            val byteOff = self.offset + start * self.bytesPerElement

            // A length-tracking source with no end gives a length-tracking result.
            val viewArgs = if (self.isLengthTracking && !NativeArrayBuffer.isArg(args, 1)) {
                arrayOf<Any?>(self.arrayBuffer, byteOff)
            } else {
                arrayOf<Any?>(self.arrayBuffer, byteOff, len)
            }
            return self.typedArraySpeciesCreate(cx, scope, viewArgs, "subarray")
        }

        // at, toReversed, toSorted and with follow ES2023 step by step: the receiver is validated
        // and its length taken before anything a script supplies is converted (D-83).

        private fun js_at(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val self = realThis(thisObj)
            val len = self.validateAndGetLength().toDouble()
            val relativeIndex = ScriptRuntime.toIntegerOrInfinity(args.getOrElse(0) { Undefined.instance })
            val k = if (relativeIndex >= 0) relativeIndex else len + relativeIndex
            if (k < 0 || k >= len) return Undefined.instance
            // The conversion may have detached the buffer, and an element then reads as undefined.
            return self.js_get(k.toInt())
        }

        private fun js_toReversed(cx: Context, scope: Scriptable, thisObj: Scriptable?): Any {
            val self = realThis(thisObj)
            val len = self.validateAndGetLength().toInt()
            val result = self.typedArrayCreateSameType(cx, scope, len)
            for (k in 0 until len) result.js_set(k, self.js_get(len - k - 1))
            return result
        }

        private fun js_toSorted(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any {
            if (NativeArrayBuffer.isArg(args, 0) && args[0] !is Callable) {
                throw ScriptRuntime.typeErrorById("msg.function.expected")
            }
            val self = realThis(thisObj)
            val len = self.validateAndGetLength().toInt()
            val result = self.typedArrayCreateSameType(cx, scope, len)
            val working = self.sortTemporaryArray(cx, scope, args, len)
            for (k in 0 until len) result.js_set(k, working[k])
            return result
        }

        private fun js_with(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any {
            val self = realThis(thisObj)
            val len = self.validateAndGetLength().toInt()
            val relativeIndex = ScriptRuntime.toIntegerOrInfinity(args.getOrElse(0) { Undefined.instance })
            val actualIndex = if (relativeIndex >= 0) relativeIndex else len + relativeIndex
            // ToBigInt on a bigint view and ToNumber on the rest, with a missing value undefined.
            val numericValue = self.toNumeric(args.getOrElse(1) { Undefined.instance })

            // IsValidIntegerIndex, against the view as it is now that the conversions have run.
            if (self.isTypedArrayOutOfBounds || actualIndex < 0 || actualIndex >= self.length) {
                throw ScriptRuntime.rangeError(
                    Messages.getMessageById("msg.typed.array.index.out.of.bounds", relativeIndex.toLong(), len * -1, len - 1),
                )
            }

            val result = self.typedArrayCreateSameType(cx, scope, len)
            val index = actualIndex.toInt()
            for (k in 0 until len) result.js_set(k, if (k == index) numericValue else self.js_get(k))
            return result
        }

        private fun js_from(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (!AbstractEcmaObjectOperations.isConstructor(cx, thisObj)) {
                throw ScriptRuntime.typeErrorById("msg.constructor.expected")
            }
            val constructable = thisObj as Constructable
            val mapArg = args.getOrElse(1) { Undefined.instance }
            val mapFn = if (Undefined.isUndefined(mapArg)) null else mapArg as? Callable
                ?: throw ScriptRuntime.typeErrorById("msg.map.function.not")
            val mapThis = args.getOrElse(2) { Undefined.instance }
            val items = ScriptRuntime.toObject(cx, scope, args.getOrElse(0) { Undefined.instance })
            val values = iterableToList(cx, scope, items)
            val size = values?.size ?: arrayLikeSize(cx, items)
            val result = constructable.construct(cx, scope, arrayOf<Any?>(size))
            if (result !is NativeTypedArrayView) throw ScriptRuntime.typeErrorById("msg.typed.array.receiver.incompatible", "from")
            if (result.validateAndGetLength() < size) throw ScriptRuntime.typeErrorById("msg.typed.array.length.too.small")

            for (k in 0 until size) {
                var value = if (values != null) values[k] else ScriptRuntime.getObjectIndex(items, k.toDouble(), cx, scope)
                if (mapFn != null) value = mapFn.call(cx, scope, ScriptRuntime.getApplyOrCallThis(cx, scope, mapThis, 1, mapFn), arrayOf(value, k))
                result.setArrayElement(k, value)
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

        /** A stable merge sort. An insertion sort took minutes on 1024 elements in reverse order. */
        private fun sortStable(a: Array<Any?>, compare: (Any?, Any?) -> Int) {
            if (a.size < 2) return
            var from = a
            var to = arrayOfNulls<Any?>(a.size)
            var width = 1
            while (width < a.size) {
                var lo = 0
                while (lo < a.size) {
                    val mid = minOf(lo + width, a.size)
                    val hi = minOf(lo + 2 * width, a.size)
                    var i = lo
                    var j = mid
                    var k = lo
                    // Taking from the left run on a tie keeps equal elements in order.
                    while (i < mid && j < hi) to[k++] = if (compare(from[i], from[j]) <= 0) from[i++] else from[j++]
                    while (i < mid) to[k++] = from[i++]
                    while (j < hi) to[k++] = from[j++]
                    lo = hi
                }
                val swap = from
                from = to
                to = swap
                width *= 2
            }
            if (from !== a) from.copyInto(a)
        }
    }
}
