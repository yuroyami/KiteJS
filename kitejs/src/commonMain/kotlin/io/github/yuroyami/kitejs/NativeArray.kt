/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.ArrayLikeAbstractOperations.IterativeOperation
import io.github.yuroyami.kitejs.ArrayLikeAbstractOperations.ReduceOperation
import io.github.yuroyami.kitejs.ArrayLikeAbstractOperations.getRawElem
import io.github.yuroyami.kitejs.ScriptableObject.Companion.PropDescValueSetter
import io.github.yuroyami.kitejs.ScriptableObject.DescriptorInfo
import kotlin.reflect.KClass

/**
 * The JavaScript `Array`.
 *
 * Small arrays keep their elements in a plain Kotlin array (`dense`) and only fall back to the
 * slot map once something makes that impossible: a huge index, a getter, a non-array prototype.
 * Upstream also makes every array a `java.util.List`; that view is not ported.
 */
public class NativeArray : ScriptableObject {

    public var length: Long = 0
        private set
    private var lengthAttr = DONTENUM or PERMANENT
    private var modCount = 0
    private var dense: Array<Any?>? = null
    internal var denseOnly = false
        private set

    public constructor(lengthArg: Long) : super() {
        denseOnly = lengthArg <= maximumInitialCapacity
        if (denseOnly) {
            var intLength = lengthArg.toInt()
            if (intLength < DEFAULT_INITIAL_CAPACITY) intLength = DEFAULT_INITIAL_CAPACITY
            dense = Array(intLength) { Scriptable.NOT_FOUND }
        }
        length = lengthArg
        createLengthProp()
    }

    public constructor(array: Array<Any?>) : super() {
        denseOnly = true
        dense = array
        length = array.size.toLong()
        createLengthProp()
    }

    override val className: String
        get() = "Array"

    override var prototype: Scriptable?
        get() = super.prototype
        set(p) {
            super.prototype = p
            if (p !is NativeArray) setDenseOnly(false)
        }

    override fun get(index: Int, start: Scriptable): Any? {
        val slot = if (denseOnly) null else map.query(null, index)
        if (!denseOnly && slot != null && slot.isSetterSlot) return slot.getValue(start)
        val d = dense
        if (d != null && index >= 0 && index < d.size) return d[index]
        return if (slot == null) Scriptable.NOT_FOUND else slot.getValue(start)
    }

    override fun has(index: Int, start: Scriptable): Boolean {
        val slot = if (denseOnly) null else map.query(null, index)
        if (slot != null) return true
        val d = dense
        if (d != null && index >= 0 && index < d.size) return d[index] !== Scriptable.NOT_FOUND
        return false
    }

    override fun put(name: String, start: Scriptable, value: Any?) {
        super.put(name, start, value)
        if (start === this) {
            val index = toArrayIndex(name)
            if (index >= length) {
                length = index + 1
                modCount++
                denseOnly = false
            }
        }
    }

    private fun ensureCapacity(capacityIn: Int): Boolean {
        var capacity = capacityIn
        val d = dense!!
        if (capacity > d.size) {
            if (capacity > MAX_PRE_GROW_SIZE) {
                denseOnly = false
                return false
            }
            capacity = maxOf(capacity, (d.size * GROW_FACTOR).toInt())
            val newDense = arrayOfNulls<Any?>(capacity)
            d.copyInto(newDense, 0, 0, d.size)
            newDense.fill(Scriptable.NOT_FOUND, d.size, newDense.size)
            dense = newDense
        }
        return true
    }

    override fun put(index: Int, start: Scriptable, value: Any?) {
        val slot = if (denseOnly) null else map.query(null, index)
        val d = dense
        if (start === this &&
            !isSealed &&
            d != null &&
            index >= 0 &&
            (denseOnly || (slot == null || !slot.isSetterSlot))
        ) {
            if (!isExtensible && this.length <= index) {
                return
            } else if (index < d.size) {
                d[index] = value
                if (this.length <= index) {
                    this.length = index.toLong() + 1
                    this.modCount++
                }
                return
            } else if (denseOnly && index < d.size * GROW_FACTOR && ensureCapacity(index + 1)) {
                dense!![index] = value
                this.length = index.toLong() + 1
                this.modCount++
                return
            } else {
                denseOnly = false
            }
        }
        super.put(index, start, value)
        if (start === this && (lengthAttr and READONLY) == 0) {
            if (this.length <= index) {
                this.length = index.toLong() + 1
                this.modCount++
            }
        }
    }

    override fun delete(index: Int) {
        val slot = if (denseOnly) null else map.query(null, index)
        val d = dense
        if (d != null && index >= 0 && index < d.size && !isSealed && (denseOnly || (slot == null || !slot.isSetterSlot))) {
            d[index] = Scriptable.NOT_FOUND
        } else {
            super.delete(index)
        }
    }

    public fun deleteInternal(compoundOp: CompoundOperationMap, id: String) {
        compoundOp.compute(this, id, 0, ::checkSlotRemoval)
    }

    public fun deleteInternal(compoundOp: CompoundOperationMap, index: Int) {
        val slot = if (denseOnly) null else compoundOp.query(null, index)
        val d = dense
        if (d != null && index >= 0 && index < d.size && !isSealed && (denseOnly || (slot == null || !slot.isSetterSlot))) {
            d[index] = Scriptable.NOT_FOUND
        } else {
            compoundOp.compute(this, null, index, ::checkSlotRemoval)
        }
    }

    override fun getIds(map: CompoundOperationMap, getNonEnumerable: Boolean, getSymbols: Boolean): Array<Any?> {
        val superIds = super.getIds(map, getNonEnumerable, getSymbols)
        val d = dense ?: return superIds
        var n = d.size
        val currentLength = length
        if (n > currentLength) n = currentLength.toInt()
        if (n == 0) return superIds
        val superLength = superIds.size
        var ids = arrayOfNulls<Any?>(n + superLength)
        var presentCount = 0
        for (i in 0 until n) {
            if (d[i] !== Scriptable.NOT_FOUND) {
                ids[presentCount] = i
                ++presentCount
            }
        }
        if (presentCount != n) {
            // dense contains deleted elems, need to shrink the result
            val tmp = arrayOfNulls<Any?>(presentCount + superLength)
            ids.copyInto(tmp, 0, 0, presentCount)
            ids = tmp
        }
        superIds.copyInto(ids, presentCount, 0, superLength)
        return ids
    }

    public val indexIds: List<Int>

        get() {
        val ids = getIds()
        val indices = ArrayList<Int>(ids.size)
        for (id in ids) {
            val int32Id = ScriptRuntime.toInt32(id)
            if (int32Id >= 0 && ScriptRuntime.toString(int32Id) == ScriptRuntime.toString(id)) {
                indices.add(int32Id)
            }
        }
        return indices
    }

    override fun getDefaultValue(hint: KClass<*>?): Any? {
        if (hint == ScriptRuntime.NumberClass) {
            val cx = Context.getContext()
            if (cx.languageVersion == Context.VERSION_1_2) return length
        }
        return super.getDefaultValue(hint)
    }

    private fun defaultIndexPropertyDescriptor(value: Any?): DescriptorInfo =
        DescriptorInfo(true, true, true, Scriptable.NOT_FOUND, Scriptable.NOT_FOUND, value)

    override fun getAttributes(index: Int): Int {
        val d = dense
        if (d != null && index >= 0 && index < d.size && d[index] !== Scriptable.NOT_FOUND) {
            return EMPTY
        }
        return super.getAttributes(index)
    }

    override fun getOwnPropertyDescriptor(cx: Context, id: Any?): DescriptorInfo? {
        val d = dense
        if (d != null) {
            val index = toDenseIndex(id)
            if (index >= 0 && index < d.size && d[index] !== Scriptable.NOT_FOUND) {
                return defaultIndexPropertyDescriptor(d[index])
            }
        }
        return super.getOwnPropertyDescriptor(cx, id)
    }

    override fun defineOwnProperty(cx: Context, id: Any?, desc: DescriptorInfo, checkValid: Boolean): Boolean {
        val index = toArrayIndex(id)
        if (index >= length) {
            length = index + 1
            modCount++
        }
        val d = dense
        if (index != -1L && d != null) {
            // Move everything into the slot map, keeping the attributes intact.
            dense = null
            denseOnly = false
            for (i in d.indices) {
                if (d[i] !== Scriptable.NOT_FOUND) {
                    if (!isExtensible) setAttributes(i, 0)
                    put(i, this, d[i])
                }
            }
        }
        super.defineOwnProperty(cx, id, desc, checkValid)
        if ("length" == id) {
            lengthAttr = getAttributes("length") // Update cached attributes value for length property
        }
        return true
    }

    private fun createLengthProp() {
        defineBuiltInProperty(
            this,
            "length",
            DONTENUM or PERMANENT,
            BuiltInSlot.Getter { array, start -> lengthGetter(array, start) },
            BuiltInSlot.Setter { array, value, owner, start, isThrow -> lengthSetter(array, value, owner, start, isThrow) },
            BuiltInSlot.AttributeSetter { array, attrs -> lengthAttrSetter(array, attrs) },
            BuiltInSlot.PropDescriptionSetter { array, current, id, info, checkValid, key, index ->
                arraySetLength(array, current, id, info, checkValid, key, index)
            },
        )
    }

    internal fun setDenseOnly(denseOnly: Boolean) {
        if (denseOnly && !this.denseOnly) throw IllegalArgumentException()
        this.denseOnly = denseOnly
    }

    private fun setLength(compoundOp: CompoundOperationMap, d: Double): Boolean {
        val longVal = ScriptRuntime.toUint32(d)
        if ((lengthAttr and READONLY) != 0) return false
        if (longVal.toDouble() != d) {
            val msg = ScriptRuntime.getMessageById("msg.arraylength.bad")
            throw ScriptRuntime.rangeError(msg)
        }
        if (denseOnly) {
            val dense = this.dense!!
            if (longVal < length) {
                // downcast okay because denseOnly
                dense.fill(Scriptable.NOT_FOUND, longVal.toInt(), dense.size)
                length = longVal
                modCount++
                return true
            } else if (longVal < MAX_PRE_GROW_SIZE && longVal < (length * GROW_FACTOR) && ensureCapacity(longVal.toInt())) {
                length = longVal
                modCount++
                return true
            } else {
                denseOnly = false
            }
        }
        if (longVal < length) {
            // remove all properties between longVal and length
            if (length - longVal > 0x1000) {
                // assume that the representation is sparse
                val e = getIds(compoundOp, false, false) // will only find in object itself
                for (id in e) {
                    if (id is String) {
                        // > MAXINT will appear as string
                        val index = toArrayIndex(id)
                        if (index >= longVal) deleteInternal(compoundOp, id)
                    } else {
                        val index = id as Int
                        if (index >= longVal) deleteInternal(compoundOp, index)
                    }
                }
            } else {
                // assume a dense representation
                for (i in longVal until length) {
                    deleteElem(compoundOp, this, i)
                }
            }
        }
        length = longVal
        modCount++
        return true
    }

    /** The elements as Kotlin sees them: holes and `undefined` become null. */
    public fun toArray(): Array<Any?> {
        val len = size()
        return Array(len) { i -> get(i.toLong()) }
    }

    override fun size(): Int {
        val longLen = length
        if (longLen > Int.MAX_VALUE) {
            throw IllegalStateException("list.length ($length) exceeds Integer.MAX_VALUE")
        }
        return longLen.toInt()
    }

    override fun isEmpty(): Boolean = length == 0L

    public fun get(index: Long): Any? {
        if (index < 0 || index >= length) {
            throw IndexOutOfBoundsException("Index: $index, length: $length")
        }
        val value = getRawElem(this, index)
        return when {
            value === Scriptable.NOT_FOUND || value === Undefined.instance -> null
            value is Wrapper -> value.unwrap()
            else -> value
        }
    }

    public companion object {
        internal const val MAX_ARRAY_INDEX = 0xfffffffeL
        private const val ARRAY_TAG = "Array"
        private const val CLASS_NAME = "Array"
        private const val NEGATIVE_ONE = -1L
        private val UNSCOPABLES = arrayOf(
            "at", "copyWithin", "entries", "fill", "find", "findIndex", "findLast", "findLastIndex", "flat",
            "flatMap", "includes", "keys", "toReversed", "toSorted", "toSpliced", "values",
        )

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean) {
            val ctor = LambdaConstructor(scope, CLASS_NAME, 1, ::jsConstructor)
            val proto = NativeArray(0)
            ctor.setPrototypeScriptable(proto)
            defineMethodOnConstructor(ctor, scope, "of", 0, ::js_of)
            defineMethodOnConstructor(ctor, scope, "from", 1, ::js_from)
            defineMethodOnConstructor(ctor, scope, "isArray", 1, ::js_isArrayMethod)
            exposeMethodOnConstructor(ctor, scope, "join", 1, ::js_join)
            exposeMethodOnConstructor(ctor, scope, "reverse", 0, ::js_reverse)
            exposeMethodOnConstructor(ctor, scope, "sort", 1, ::js_sort)
            exposeMethodOnConstructor(ctor, scope, "push", 1, ::js_push)
            exposeMethodOnConstructor(ctor, scope, "pop", 0, ::js_pop)
            exposeMethodOnConstructor(ctor, scope, "shift", 0, ::js_shift)
            exposeMethodOnConstructor(ctor, scope, "unshift", 1, ::js_unshift)
            exposeMethodOnConstructor(ctor, scope, "splice", 2, ::js_splice)
            exposeMethodOnConstructor(ctor, scope, "concat", 1, ::js_concat)
            exposeMethodOnConstructor(ctor, scope, "slice", 2, ::js_slice)
            exposeMethodOnConstructor(ctor, scope, "indexOf", 1, ::js_indexOf)
            exposeMethodOnConstructor(ctor, scope, "lastIndexOf", 1, ::js_lastIndexOf)
            exposeMethodOnConstructor(ctor, scope, "every", 1, ::js_every)
            exposeMethodOnConstructor(ctor, scope, "filter", 1, ::js_filter)
            exposeMethodOnConstructor(ctor, scope, "forEach", 1, ::js_forEach)
            exposeMethodOnConstructor(ctor, scope, "map", 1, ::js_map)
            exposeMethodOnConstructor(ctor, scope, "some", 1, ::js_some)
            exposeMethodOnConstructor(ctor, scope, "find", 1, ::js_find)
            exposeMethodOnConstructor(ctor, scope, "findIndex", 1, ::js_findIndex)
            exposeMethodOnConstructor(ctor, scope, "findLast", 1, ::js_findLast)
            exposeMethodOnConstructor(ctor, scope, "findLastIndex", 1, ::js_findLastIndex)
            exposeMethodOnConstructor(ctor, scope, "reduce", 1, ::js_reduce)
            exposeMethodOnConstructor(ctor, scope, "reduceRight", 1, ::js_reduceRight)
            defineMethodOnPrototype(ctor, scope, "toString", 0, ::js_toString)
            defineMethodOnPrototype(ctor, scope, "toLocaleString", 0, ::js_toLocaleString)
            defineMethodOnPrototype(ctor, scope, "toSource", 0, ::js_toSource)
            defineMethodOnPrototype(ctor, scope, "join", 1, ::js_join)
            defineMethodOnPrototype(ctor, scope, "reverse", 0, ::js_reverse)
            defineMethodOnPrototype(ctor, scope, "sort", 1, ::js_sort)
            defineMethodOnPrototype(ctor, scope, "push", 1, ::js_push)
            defineMethodOnPrototype(ctor, scope, "pop", 0, ::js_pop)
            defineMethodOnPrototype(ctor, scope, "shift", 0, ::js_shift)
            defineMethodOnPrototype(ctor, scope, "unshift", 1, ::js_unshift)
            defineMethodOnPrototype(ctor, scope, "splice", 2, ::js_splice)
            defineMethodOnPrototype(ctor, scope, "concat", 1, ::js_concat)
            defineMethodOnPrototype(ctor, scope, "slice", 2, ::js_slice)
            defineMethodOnPrototype(ctor, scope, "indexOf", 1, ::js_indexOf)
            defineMethodOnPrototype(ctor, scope, "lastIndexOf", 1, ::js_lastIndexOf)
            defineMethodOnPrototype(ctor, scope, "includes", 1, ::js_includes)
            defineMethodOnPrototype(ctor, scope, "fill", 1, ::js_fill)
            defineMethodOnPrototype(ctor, scope, "copyWithin", 2, ::js_copyWithin)
            defineMethodOnPrototype(ctor, scope, "at", 1, ::js_at)
            defineMethodOnPrototype(ctor, scope, "flat", 0, ::js_flat)
            defineMethodOnPrototype(ctor, scope, "flatMap", 1, ::js_flatMap)
            defineMethodOnPrototype(ctor, scope, "every", 1, ::js_every)
            defineMethodOnPrototype(ctor, scope, "filter", 1, ::js_filter)
            defineMethodOnPrototype(ctor, scope, "forEach", 1, ::js_forEach)
            defineMethodOnPrototype(ctor, scope, "map", 1, ::js_map)
            defineMethodOnPrototype(ctor, scope, "some", 1, ::js_some)
            defineMethodOnPrototype(ctor, scope, "find", 1, ::js_find)
            defineMethodOnPrototype(ctor, scope, "findIndex", 1, ::js_findIndex)
            defineMethodOnPrototype(ctor, scope, "findLast", 1, ::js_findLast)
            defineMethodOnPrototype(ctor, scope, "findLastIndex", 1, ::js_findLastIndex)
            defineMethodOnPrototype(ctor, scope, "reduce", 1, ::js_reduce)
            defineMethodOnPrototype(ctor, scope, "reduceRight", 1, ::js_reduceRight)
            defineMethodOnPrototype(ctor, scope, "keys", 0, ::js_keys)
            defineMethodOnPrototype(ctor, scope, "entries", 0, ::js_entries)
            defineMethodOnPrototype(ctor, scope, "values", 0, ::js_values)
            defineMethodOnPrototype(ctor, scope, "toReversed", 0, ::js_toReversed)
            defineMethodOnPrototype(ctor, scope, "toSorted", 1, ::js_toSorted)
            defineMethodOnPrototype(ctor, scope, "toSpliced", 2, ::js_toSpliced)
            defineMethodOnPrototype(ctor, scope, "with", 2, ::js_with)
            ctor.definePrototypeAlias("values", SymbolKey.ITERATOR, DONTENUM)
            ScriptRuntimeES6.addSymbolSpecies(cx, scope, ctor)
            ScriptRuntimeES6.addSymbolUnscopables(
                cx,
                scope,
                proto,
                LazilyLoadedCtor(proto, "", false, Initializable { c, s, _ -> makeUnscopables(c, s) }),
            )
            ctor.setPrototypePropertyAttributes(PERMANENT or READONLY or DONTENUM)
            defineProperty(scope, CLASS_NAME, ctor, DONTENUM)
            if (sealed) {
                ctor.sealObject()
                (ctor.prototypeProperty as NativeArray).sealObject()
            }
        }

        private fun defineMethodOnConstructor(constructor: LambdaConstructor, scope: Scriptable, name: String, length: Int, target: SerializableCallable) {
            constructor.defineConstructorMethod(scope, name, length, target)
        }

        private fun defineMethodOnPrototype(constructor: LambdaConstructor, scope: Scriptable, name: String, length: Int, target: SerializableCallable) {
            constructor.definePrototypeMethod(scope, name, length, target)
        }

        /** The non-standard `Array.join(arr, sep)` style statics: the first argument is `this`. */
        private fun exposeMethodOnConstructor(constructor: LambdaConstructor, scope: Scriptable, name: String, length: Int, target: SerializableCallable) {
            constructor.defineConstructorMethod(
                scope,
                name,
                length,
                SerializableCallable { cx, s, _, args ->
                    val realThis = ScriptRuntime.toObject(cx, scope, args[0])
                    val realArgs = args.copyOfRange(1, args.size)
                    target.call(cx, s, realThis, realArgs)
                },
            )
        }

        private fun makeUnscopables(cx: Context, scope: Scriptable): Any? {
            val obj = cx.newObject(scope) as NativeObject
            val desc = buildDataDescriptor(true, EMPTY)
            for (k in UNSCOPABLES) {
                obj.defineOwnProperty(cx, k, desc)
            }
            obj.prototype = null // unscopables don't have any prototype
            return obj
        }

        private fun toArrayIndex(id: Any?): Long = when (id) {
            is String -> toArrayIndex(id)
            is Number -> toArrayIndex(id.toDouble())
            else -> -1
        }

        // Returns -1 if a string is not an index.
        private fun toArrayIndex(id: String): Long {
            val index = toArrayIndex(ScriptRuntime.toNumber(id))
            // Assume that ScriptRuntime.toString(index) is the same as id, since it was a valid index
            if (index.toString() == id) return index
            return -1
        }

        private fun toArrayIndex(d: Double): Long {
            if (!d.isNaN()) {
                val index = ScriptRuntime.toUint32(d)
                if (index.toDouble() == d && index != 4294967295L) return index
            }
            return -1
        }

        private fun toDenseIndex(id: Any?): Int {
            val index = toArrayIndex(id)
            return if (index >= 0 && index < Int.MAX_VALUE) index.toInt() else -1
        }

        internal fun jsConstructor(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
            if (args.isEmpty()) return NativeArray(0)
            val res: NativeArray
            if (cx.languageVersion == Context.VERSION_1_2) {
                res = NativeArray(args)
            } else {
                val arg0 = args[0]
                if (args.size > 1 || arg0 !is Number) {
                    res = NativeArray(args)
                } else {
                    val len = ScriptRuntime.toUint32(arg0.toDouble())
                    if (len.toDouble() != arg0.toDouble()) {
                        val msg = ScriptRuntime.getMessageById("msg.arraylength.bad")
                        throw ScriptRuntime.rangeError(msg)
                    }
                    res = NativeArray(len)
                }
            }
            return res
        }

        private fun lengthGetter(array: NativeArray, start: Scriptable?): Any? =
            ScriptRuntime.wrapNumber(array.length.toDouble())

        private fun lengthSetter(builtIn: NativeArray, value: Any?, owner: Scriptable, start: Scriptable, isThrow: Boolean): Boolean {
            val d = ScriptRuntime.toNumber(value)
            builtIn.startCompoundOp(true).use { builtIn.setLength(it, d) }
            return true
        }

        private fun lengthAttrSetter(builtIn: NativeArray, attrs: Int) {
            builtIn.lengthAttr = attrs
        }

        private fun lengthDescSetValue(
            owner: ScriptableObject,
            info: DescriptorInfo,
            key: Any?,
            existing: Slot?,
            map: CompoundOperationMap,
            slot: Slot,
        ): Slot {
            (owner as NativeArray).setLength(map, info.value as Double)
            return slot
        }

        internal fun arraySetLength(
            builtIn: NativeArray,
            current: BuiltInSlot<NativeArray>,
            id: Any?,
            info: DescriptorInfo,
            checkValid: Boolean,
            key: Any?,
            index: Int,
        ): Boolean {
            val descSetter = PropDescValueSetter { o, i, k, e, m, s -> lengthDescSetValue(o, i, k, e, m, s) }
            val value = info.value
            if (value === Scriptable.NOT_FOUND) {
                return builtIn.startCompoundOp(true).use { map ->
                    defineOrdinaryProperty(PropDescValueSetter { _, _, _, _, _, s -> s }, builtIn, map, id, info, checkValid, key, index)
                }
            }
            val newLength = checkLength(value)
            info.value = newLength.toDouble()
            val writable = info.writable
            builtIn.startCompoundOp(true).use { map ->
                if (newLength >= builtIn.length) {
                    return defineOrdinaryProperty(descSetter, builtIn, map, id, info, checkValid, key, index)
                }
                val currentWritable = (current.attributes and READONLY) == 0
                if (!currentWritable) {
                    throw ScriptRuntime.typeErrorById("msg.change.value.with.writable.false", id)
                }
                var newWritable = true
                if (writable !== Scriptable.NOT_FOUND) {
                    newWritable = isTrue(writable)
                    info.writable = true
                }
                if (defineOrdinaryProperty(descSetter, builtIn, map, id, info, checkValid, key, index)) {
                    val currentAttrs = current.attributes
                    val newAttrs = if (newWritable) (currentAttrs and READONLY.inv()) else (currentAttrs or READONLY)
                    current.attributes = newAttrs
                    return true
                }
            }
            return false
        }

        private fun callConstructorOrCreateArray(cx: Context, scope: Scriptable, arg: Scriptable?, length: Long, lengthAlways: Boolean): Scriptable {
            var result: Scriptable? = null
            if (arg is Constructable) {
                try {
                    val args: Array<Any?> = if (lengthAlways || length > 0) arrayOf(length) else ScriptRuntime.emptyArgs
                    result = arg.construct(cx, scope, args)
                } catch (ee: EcmaError) {
                    if ("TypeError" != ee.name) throw ee
                    // If we get here then it is likely that the caller is a non-constructor
                    // or "arg" is not a valid array constructor and we can drop through.
                }
            }
            if (result == null) {
                result = cx.newArray(scope, if (length > Int.MAX_VALUE) 0 else length.toInt())
            }
            return result
        }

        private fun js_from(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val items = ScriptRuntime.toObject(scope, if (args.isNotEmpty()) args[0] else Undefined.instance)
            val mapArg = if (args.size >= 2) args[1] else Undefined.instance
            var thisArg: Scriptable? = null
            val mapping = !Undefined.isUndefined(mapArg)
            var mapFn: Function? = null
            if (mapping) {
                if (mapArg !is Function) {
                    throw ScriptRuntime.typeErrorById("msg.map.function.not")
                }
                mapFn = mapArg
                val callThisArg = if (args.size >= 3) args[2] else Undefined.SCRIPTABLE_UNDEFINED
                thisArg = ScriptRuntime.getApplyOrCallThis(cx, scope, callThisArg, 1, mapFn)
            }
            val iteratorProp = ScriptableObject.getProperty(items, SymbolKey.ITERATOR)
            if (iteratorProp !== Scriptable.NOT_FOUND && !Undefined.isUndefined(iteratorProp)) {
                val iterator = ScriptRuntime.callIterator(items, cx, scope)
                if (!Undefined.isUndefined(iterator)) {
                    val result = callConstructorOrCreateArray(cx, scope, thisObj, 0, false)
                    var k = 0L
                    IteratorLikeIterable(cx, scope, iterator).use { it ->
                        for (item in it) {
                            var temp = item
                            if (mapping) {
                                temp = mapFn!!.call(cx, scope, thisArg, arrayOf(temp, k))
                            }
                            ArrayLikeAbstractOperations.defineElem(cx, result, k, temp)
                            k++
                        }
                    }
                    setLengthProperty(cx, result, k)
                    return result
                }
            }
            val length = getLengthProperty(cx, items)
            val result = callConstructorOrCreateArray(cx, scope, thisObj, length, true)
            for (k in 0 until length) {
                var temp = getElem(cx, items, k)
                if (mapping) {
                    temp = mapFn!!.call(cx, scope, thisArg, arrayOf(temp, k))
                }
                ArrayLikeAbstractOperations.defineElem(cx, result, k, temp)
            }
            setLengthProperty(cx, result, length)
            return result
        }

        private fun js_of(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val result = callConstructorOrCreateArray(cx, scope, thisObj, args.size.toLong(), true)
            if (cx.languageVersion >= Context.VERSION_ES6 && result is ScriptableObject) {
                val desc = buildDataDescriptor(null, EMPTY)
                for (i in args.indices) {
                    desc.value = args[i]
                    result.defineOwnProperty(cx, i, desc)
                }
            } else {
                for (i in args.indices) {
                    ArrayLikeAbstractOperations.defineElem(cx, result, i.toLong(), args[i])
                }
            }
            setLengthProperty(cx, result, args.size.toLong())
            return result
        }

        private fun checkLength(value: Any?): Long {
            // Both conversions run on the value itself, as upstream does. Passing `d` to the
            // second would be one coercion, and the spec has two: a valueOf that watches is
            // entitled to be called twice.
            val d = ScriptRuntime.toNumber(value)
            val longVal = ScriptRuntime.toUint32(value)
            if (longVal.toDouble() != d) {
                val msg = ScriptRuntime.getMessageById("msg.arraylength.bad")
                throw ScriptRuntime.rangeError(msg)
            }
            return longVal
        }

        /** The `length` of any array-like, clamped to a safe integer. */
        internal fun getLengthProperty(cx: Context, obj: Scriptable): Long {
            if (obj is NativeString) return obj.length.toLong()
            if (obj is NativeArray) return obj.length
            val len = ScriptableObject.getProperty(obj, "length")
            if (len === Scriptable.NOT_FOUND) {
                // toInt32(undefined) == 0
                return 0
            }
            val doubleLen = ScriptRuntime.toNumber(len)
            if (doubleLen > NativeNumber.MAX_SAFE_INTEGER) {
                return NativeNumber.MAX_SAFE_INTEGER.toLong()
            }
            if (doubleLen < 0) return 0
            return doubleLen.toLong()
        }

        private fun setLengthProperty(cx: Context, target: Scriptable, length: Long): Any? {
            val len = ScriptRuntime.wrapNumber(length.toDouble())
            ScriptableObject.putProperty(target, "length", len)
            return len
        }

        private fun deleteElem(target: Scriptable, index: Long) {
            val i = index.toInt()
            if (i.toLong() == index) {
                target.delete(i)
            } else {
                target.delete(index.toString())
            }
        }

        private fun deleteElem(compoundOp: CompoundOperationMap, target: NativeArray, index: Long) {
            val i = index.toInt()
            if (i.toLong() == index) {
                checkNotSealed(target, null, i)
                target.deleteInternal(compoundOp, i)
            } else {
                val strIndex = index.toString()
                checkNotSealed(target, strIndex, 0)
                compoundOp.compute(target, strIndex, 0, ::checkSlotRemoval)
            }
        }

        internal fun getElem(cx: Context, target: Scriptable, index: Long): Any? {
            val elem = getRawElem(target, index)
            return if (elem !== Scriptable.NOT_FOUND) elem else Undefined.instance
        }

        private fun defineElemOrThrow(cx: Context, target: Scriptable, index: Long, value: Any?) {
            if (index > NativeNumber.MAX_SAFE_INTEGER) {
                throw ScriptRuntime.typeErrorById("msg.arraylength.too.big", index.toString())
            }
            ArrayLikeAbstractOperations.defineElem(cx, target, index, value)
        }

        private fun setElem(cx: Context, target: Scriptable, index: Long, value: Any?) {
            if (index > Int.MAX_VALUE) {
                val id = index.toString()
                ScriptableObject.putProperty(target, id, value)
            } else {
                ScriptableObject.putProperty(target, index.toInt(), value)
            }
        }

        // Similar as setElem(), but triggers deleteElem() if value is NOT_FOUND
        private fun setRawElem(cx: Context, target: Scriptable, index: Long, value: Any?) {
            if (value === Scriptable.NOT_FOUND) {
                deleteElem(target, index)
            } else {
                setElem(cx, target, index, value)
            }
        }

        private fun js_toString(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            toStringHelper(cx, scope, thisObj, cx.hasFeature(Context.FEATURE_TO_STRING_AS_SOURCE), false)

        private fun js_toLocaleString(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            toStringHelper(cx, scope, thisObj, false, true)

        private fun js_toSource(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            toStringHelper(cx, scope, thisObj, true, false)

        private fun toStringHelper(cx: Context, scope: Scriptable, thisObj: Scriptable?, toSource: Boolean, toLocale: Boolean): String {
            // toLocaleString is ECMA 15.4.4.3. toSource is a Mozilla extension.
            val o = ScriptRuntime.toObject(cx, scope, thisObj)
            val length = getLengthProperty(cx, o)
            val result = StringBuilder(256)
            val separator: String
            if (toSource) {
                result.append('[')
                separator = ", "
            } else {
                separator = ","
            }
            var haslast = false
            var i = 0L
            val toplevel: Boolean
            val iterating: Boolean
            var cxIterating = cx.iterating
            if (cxIterating == null) {
                toplevel = true
                iterating = false
                cxIterating = mutableSetOf()
                cx.iterating = cxIterating
            } else {
                toplevel = false
                iterating = cxIterating.contains(o)
            }
            try {
                if (!iterating) {
                    cxIterating.add(o) // stop recursion
                    // make toSource print null and undefined values in recent versions
                    val skipUndefinedAndNull = !toSource || cx.languageVersion < Context.VERSION_1_5
                    while (i < length) {
                        if (i > 0) result.append(separator)
                        var elem = getRawElem(o, i)
                        if (elem === Scriptable.NOT_FOUND || (skipUndefinedAndNull && (elem == null || elem === Undefined.instance))) {
                            haslast = false
                            i++
                            continue
                        }
                        haslast = true
                        if (toSource) {
                            result.append(ScriptRuntime.uneval(cx, scope, elem))
                        } else if (elem is String) {
                            result.append(elem)
                        } else {
                            if (toLocale) {
                                val fn = ScriptRuntime.getPropAndThis(elem, "toLocaleString", cx, scope)!!
                                elem = fn.call(cx, scope, ScriptRuntime.emptyArgs)
                            }
                            result.append(ScriptRuntime.toString(elem))
                        }
                        i++
                    }
                    // processing of thisObj done, remove it from the recursion detector
                    cxIterating.remove(o)
                }
            } finally {
                if (toplevel) cx.iterating = null
            }
            if (toSource) {
                // for [,,].length behavior; we want toString to be symmetric with literal syntax
                if (!haslast && i > 0) result.append(", ]") else result.append(']')
            }
            return result.toString()
        }

        private fun js_join(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val o = ScriptRuntime.toObject(cx, scope, thisObj)
            val llength = getLengthProperty(cx, o)
            val length = llength.toInt()
            if (llength != length.toLong()) {
                throw Context.reportRuntimeErrorById("msg.arraylength.too.big", llength.toString())
            }
            // if no args, use "," as separator
            val separator = if (args.isEmpty() || args[0] === Undefined.instance) "," else ScriptRuntime.toString(args[0])
            if (o is NativeArray) {
                if (o.denseOnly) {
                    val dense = o.dense!!
                    val sb = StringBuilder()
                    for (i in 0 until length) {
                        if (i != 0) sb.append(separator)
                        if (i < dense.size) {
                            val temp = dense[i]
                            if (temp != null && temp !== Undefined.instance && temp !== Scriptable.NOT_FOUND) {
                                sb.append(ScriptRuntime.toString(temp))
                            }
                        }
                    }
                    return sb.toString()
                }
            }
            if (length == 0) return ""
            val buf = arrayOfNulls<String>(length)
            var totalSize = 0
            for (i in 0 until length) {
                val temp = getElem(cx, o, i.toLong())
                if (temp != null && temp !== Undefined.instance) {
                    val str = ScriptRuntime.toString(temp)
                    totalSize += str.length
                    buf[i] = str
                }
            }
            totalSize += (length - 1) * separator.length
            val sb = StringBuilder(totalSize)
            for (i in 0 until length) {
                if (i != 0) sb.append(separator)
                val str = buf[i]
                if (str != null) sb.append(str)
            }
            return sb.toString()
        }

        private fun js_reverse(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val o = ScriptRuntime.toObject(cx, scope, thisObj)
            if (o is NativeArray) {
                if (o.denseOnly) {
                    val dense = o.dense!!
                    var i = 0
                    var j = o.length.toInt() - 1
                    while (i < j) {
                        val temp = dense[i]
                        dense[i] = dense[j]
                        dense[j] = temp
                        i++
                        j--
                    }
                    return o
                }
            }
            val len = getLengthProperty(cx, o)
            val half = len / 2
            for (i in 0 until half) {
                val j = len - i - 1
                val temp1 = getRawElem(o, i)
                val temp2 = getRawElem(o, j)
                setRawElem(cx, o, i, temp2)
                setRawElem(cx, o, j, temp1)
            }
            return o
        }

        private fun js_sort(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val o = ScriptRuntime.toObject(cx, scope, thisObj)
            val comparator = ArrayLikeAbstractOperations.getSortComparator(cx, scope, args)
            return sort(cx, o, comparator)
        }

        private fun sort(cx: Context, o: Scriptable, comparator: Comparator<Any?>): Scriptable {
            val llength = getLengthProperty(cx, o)
            val length = llength.toInt()
            if (llength != length.toLong()) {
                throw Context.reportRuntimeErrorById("msg.arraylength.too.big", llength.toString())
            }
            // copy the JS array into a working array, so it can be sorted cheaply.
            val working = arrayOfNulls<Any?>(length)
            for (i in 0 until length) {
                working[i] = getRawElem(o, i.toLong())
            }
            try {
                working.sortWith(comparator)
            } catch (e: IllegalArgumentException) {
                // A comparator that breaks its own contract; leave the array as it was.
                return o
            }
            // copy the working array back into thisObj
            for (i in 0 until length) {
                setRawElem(cx, o, i.toLong(), working[i])
            }
            return o
        }

        private fun js_push(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val o = ScriptRuntime.toObject(cx, scope, thisObj)
            if (o is NativeArray) {
                if (o.denseOnly && o.ensureCapacity(o.length.toInt() + args.size)) {
                    for (arg in args) {
                        o.dense!![o.length.toInt()] = arg
                        o.length++
                        o.modCount++
                    }
                    return ScriptRuntime.wrapNumber(o.length.toDouble())
                }
            }
            var length = getLengthProperty(cx, o)
            for (i in args.indices) {
                setElem(cx, o, length + i, args[i])
            }
            length += args.size
            val lengthObj = setLengthProperty(cx, o, length)
            /*
             * If JS1.2, follow Perl4 by returning the last thing pushed.
             * Otherwise, return the new array length.
             */
            if (cx.languageVersion == Context.VERSION_1_2) {
                // if JS1.2 && no arguments, return undefined.
                return if (args.isEmpty()) Undefined.instance else args[args.size - 1]
            }
            return lengthObj
        }

        private fun js_pop(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val o = ScriptRuntime.toObject(cx, scope, thisObj)
            val result: Any?
            if (o is NativeArray) {
                if (o.denseOnly && o.length > 0) {
                    o.length--
                    o.modCount++
                    val dense = o.dense!!
                    result = dense[o.length.toInt()]
                    dense[o.length.toInt()] = Scriptable.NOT_FOUND
                    return result
                }
            }
            var length = getLengthProperty(cx, o)
            if (length > 0) {
                length--
                // Get the to-be-deleted property's value.
                result = getElem(cx, o, length)
                // We need to delete the last property, because 'thisObj' may not
                // have setLength which does that for us.
                deleteElem(o, length)
            } else {
                result = Undefined.instance
            }
            // necessary to match js even when length < 0; js pop will give a
            // length property to any object it is called on.
            setLengthProperty(cx, o, length)
            return result
        }

        private fun js_shift(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val o = ScriptRuntime.toObject(cx, scope, thisObj)
            if (o is NativeArray) {
                if (o.denseOnly && o.length > 0) {
                    o.length--
                    o.modCount++
                    val dense = o.dense!!
                    val result = dense[0]
                    dense.copyInto(dense, 0, 1, 1 + o.length.toInt())
                    dense[o.length.toInt()] = Scriptable.NOT_FOUND
                    return if (result === Scriptable.NOT_FOUND) Undefined.instance else result
                }
            }
            val result: Any?
            var length = getLengthProperty(cx, o)
            if (length > 0) {
                var i = 0L
                length--
                // Get the to-be-deleted property's value.
                result = getElem(cx, o, i)
                /*
                 * Slide down the array above the first element.  Leave i
                 * set to point to the last element.
                 */
                if (length > 0) {
                    i = 1
                    while (i <= length) {
                        val temp = getRawElem(o, i)
                        setRawElem(cx, o, i - 1, temp)
                        i++
                    }
                }
                // We need to delete the last property, because 'thisObj' may not
                // have setLength which does that for us.
                deleteElem(o, length)
            } else {
                result = Undefined.instance
            }
            setLengthProperty(cx, o, length)
            return result
        }

        private fun js_unshift(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val o = ScriptRuntime.toObject(cx, scope, thisObj)
            if (o is NativeArray) {
                if (o.denseOnly && o.ensureCapacity(o.length.toInt() + args.size)) {
                    val dense = o.dense!!
                    dense.copyInto(dense, args.size, 0, o.length.toInt())
                    args.copyInto(dense, 0, 0, args.size)
                    o.length += args.size
                    o.modCount++
                    return ScriptRuntime.wrapNumber(o.length.toDouble())
                }
            }
            var length = getLengthProperty(cx, o)
            val argc = args.size
            if (argc > 0) {
                if (length + argc > NativeNumber.MAX_SAFE_INTEGER) {
                    throw ScriptRuntime.typeErrorById("msg.arraylength.too.big", length + argc)
                }
                /*  Slide up the array to make room for args at the bottom */
                if (length > 0) {
                    var last = length - 1
                    while (last >= 0) {
                        val temp = getRawElem(o, last)
                        setRawElem(cx, o, last + argc, temp)
                        last--
                    }
                }
                /* Copy from argv to the bottom of the array. */
                for (i in args.indices) {
                    setElem(cx, o, i.toLong(), args[i])
                }
            }
            /* Follow Perl by returning the new array length. */
            length += argc
            return setLengthProperty(cx, o, length)
        }

        private fun js_splice(cx: Context, scopeIn: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            var scope = scopeIn
            val o = ScriptRuntime.toObject(cx, scope, thisObj)
            var na: NativeArray? = null
            var result: Any? = ArrayLikeAbstractOperations.arraySpeciesCreate(cx, scope, o, 0)
            var nar: NativeArray? = null
            var denseFrom = false
            var denseRes = false
            if (o is NativeArray) {
                na = o
                denseFrom = na.denseOnly
            }
            if (result is NativeArray) {
                nar = result
                denseRes = nar.denseOnly
            }
            /* create an empty Array to return. */
            scope = getTopLevelScope(scope)
            var argc = args.size
            if (argc == 0) return cx.newArray(scope, 0)
            val length = getLengthProperty(cx, o)
            /* Convert the first argument into a starting index. */
            val begin = ArrayLikeAbstractOperations.toSliceIndex(ScriptRuntime.toInteger(args[0]), length)
            argc--
            /* Convert the second argument into count */
            val actualDeleteCount: Long
            if (args.size == 1) {
                actualDeleteCount = length - begin
            } else {
                val dcount = ScriptRuntime.toInteger(args[1])
                actualDeleteCount = if (dcount < 0) {
                    0
                } else if (dcount > (length - begin)) {
                    length - begin
                } else {
                    dcount.toLong()
                }
                argc--
            }
            val end = begin + actualDeleteCount
            val delta = argc - actualDeleteCount
            if (length + delta > NativeNumber.MAX_SAFE_INTEGER) {
                throw ScriptRuntime.typeErrorById("msg.arraylength.too.big", length + delta)
            }
            if (actualDeleteCount > Int.MAX_VALUE) {
                val msg = ScriptRuntime.getMessageById("msg.arraylength.bad")
                throw ScriptRuntime.rangeError(msg)
            }
            /* If there are elements to remove, put them into the return value. */
            if (actualDeleteCount != 0L) {
                if (actualDeleteCount == 1L && cx.languageVersion == Context.VERSION_1_2) {
                    /*
                     * JS lacks "list context", whereby in Perl one turns the
                     * single scalar that's spliced out into an array just by
                     * assigning it to @single instead of $single, or by using it
                     * as Perl push's first argument, for instance.
                     *
                     * JS1.2 emulated Perl too closely and returned a non-Array for
                     * the single-splice-out case, requiring callers to test and wrap
                     * in [] if necessary.  So JS1.3, default, and other versions all
                     * return an array of length 1 for uniformity.
                     */
                    result = getElem(cx, o, begin)
                } else {
                    if (denseFrom && denseRes) {
                        val intLen = (end - begin).toInt()
                        val copy = arrayOfNulls<Any?>(intLen)
                        na!!.dense!!.copyInto(copy, 0, begin.toInt(), begin.toInt() + intLen)
                        nar!!.dense = copy
                        nar.startCompoundOp(true).use { nar.setLength(it, intLen.toDouble()) }
                    } else {
                        for (last in begin until end) {
                            val temp = getRawElem(o, last)
                            if (temp !== Scriptable.NOT_FOUND) {
                                ArrayLikeAbstractOperations.defineElem(cx, result as ScriptableObject, last - begin, temp)
                            }
                        }
                        setLengthProperty(cx, result as ScriptableObject, end - begin)
                    }
                }
            } else { // (count == 0)
                if (cx.languageVersion == Context.VERSION_1_2) {
                    /* Emulate C JS1.2; if no elements are removed, return undefined. */
                    result = Undefined.instance
                }
            }
            /* Find the direction (up or down) to copy and make way for argv. */
            if (denseFrom && length + delta < Int.MAX_VALUE && na!!.ensureCapacity((length + delta).toInt())) {
                val dense = na.dense!!
                dense.copyInto(dense, (begin + argc).toInt(), end.toInt(), (end + (length - end)).toInt())
                if (argc > 0) {
                    args.copyInto(dense, begin.toInt(), 2, 2 + argc)
                }
                if (delta < 0) {
                    dense.fill(Scriptable.NOT_FOUND, (length + delta).toInt(), length.toInt())
                }
                na.length = length + delta
                na.modCount++
                return result
            }
            if (delta > 0) {
                var last = length - 1
                while (last >= end) {
                    val temp = getRawElem(o, last)
                    setRawElem(cx, o, last + delta, temp)
                    last--
                }
            } else if (delta < 0) {
                for (last in end until length) {
                    val temp = getRawElem(o, last)
                    setRawElem(cx, o, last + delta, temp)
                }
                // Do this backwards because some implementations might use a
                // non-sparse array and delete from the end.
                var k = length - 1
                while (k >= length + delta) {
                    deleteElem(o, k)
                    --k
                }
            }
            /* Copy from argv into the hole to complete the splice. */
            val argoffset = args.size - argc
            for (i in 0 until argc) {
                setElem(cx, o, begin + i, args[i + argoffset])
            }
            setLengthProperty(cx, o, length + delta)
            return result
        }

        private fun isConcatSpreadable(cx: Context, scope: Scriptable, value: Any?): Boolean {
            // First, look for the new @@isConcatSpreadable test as per ECMAScript 6 and up
            if (value is Scriptable) {
                val spreadable = ScriptableObject.getProperty(value, SymbolKey.IS_CONCAT_SPREADABLE)
                if (spreadable !== Scriptable.NOT_FOUND && !Undefined.isUndefined(spreadable)) {
                    // If @@isConcatSpreadable was undefined, we have to fall back to testing for an
                    // array. Otherwise, we found some value
                    return ScriptRuntime.toBoolean(spreadable)
                }
            }
            if (cx.languageVersion < Context.VERSION_ES6) {
                // Otherwise, for older Rhino versions and strict mode, fall back to the old algorithm,
                // which treats things with the Array constructor as arrays. However, this is
                // contrary to ES6!
                val ctor = ScriptRuntime.getExistingCtor(cx, scope, "Array")
                if (ScriptRuntime.instanceOf(value, ctor, cx)) return true
            }
            // Otherwise, spec says to only spread things that are arrays.
            return js_isArray(value)
        }

        // Concatenate arrays in a way that is not sensitive to their length
        private fun concatSpreadArg(cx: Context, result: Scriptable, arg: Scriptable, offset: Long): Long {
            val srclen = getLengthProperty(cx, arg)
            val newlen = srclen + offset
            // First, optimize for a pair of native, dense arrays
            if (newlen > NativeNumber.MAX_SAFE_INTEGER) {
                throw ScriptRuntime.typeErrorById("msg.arraylength.too.big", newlen)
            }
            if (newlen <= Int.MAX_VALUE && result is NativeArray) {
                if (result.denseOnly && arg is NativeArray) {
                    if (arg.denseOnly) {
                        result.ensureCapacity(newlen.toInt())
                        arg.dense!!.copyInto(result.dense!!, offset.toInt(), 0, srclen.toInt())
                        return newlen
                    }
                    // We could also optimize here if we know the result and the argument are both
                    // dense, but that gets complicated because the source array may not be dense
                }
            }
            // Otherwise, copy everything, one by one
            var dstpos = offset
            for (srcpos in 0 until srclen) {
                val temp = getRawElem(arg, srcpos)
                if (temp !== Scriptable.NOT_FOUND) {
                    ArrayLikeAbstractOperations.defineElem(cx, result, dstpos, temp)
                }
                dstpos++
            }
            return newlen
        }

        private fun doConcat(cx: Context, scope: Scriptable, result: Scriptable, arg: Any?, offset: Long): Long {
            if (isConcatSpreadable(cx, scope, arg)) {
                return concatSpreadArg(cx, result, arg as Scriptable, offset)
            }
            ArrayLikeAbstractOperations.defineElem(cx, result, offset, arg)
            return offset + 1
        }

        /*
         * See Ecma 262v3 15.4.4.4 [[Concat]]
         */
        private fun js_concat(cx: Context, scopeIn: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            var scope = scopeIn
            val o = ScriptRuntime.toObject(cx, scope, thisObj)
            scope = getTopLevelScope(scope)
            val result = ArrayLikeAbstractOperations.arraySpeciesCreate(cx, scope, o, 0)
            var length = doConcat(cx, scope, result, o, 0)
            for (arg in args) {
                length = doConcat(cx, scope, result, arg, length)
            }
            setLengthProperty(cx, result, length)
            return result
        }

        private fun js_slice(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val o = ScriptRuntime.toObject(cx, scope, thisObj)
            val len = getLengthProperty(cx, o)
            val begin: Long
            val end: Long
            if (args.isEmpty()) {
                begin = 0
                end = len
            } else {
                begin = ArrayLikeAbstractOperations.toSliceIndex(ScriptRuntime.toInteger(args[0]), len)
                end = if (args.size == 1 || args[1] === Undefined.instance) {
                    len
                } else {
                    ArrayLikeAbstractOperations.toSliceIndex(ScriptRuntime.toInteger(args[1]), len)
                }
            }
            if (end - begin > Int.MAX_VALUE) {
                val msg = ScriptRuntime.getMessageById("msg.arraylength.bad")
                throw ScriptRuntime.rangeError(msg)
            }
            val result = ArrayLikeAbstractOperations.arraySpeciesCreate(cx, scope, o, 0)
            for (slot in begin until end) {
                val temp = getRawElem(o, slot)
                if (temp !== Scriptable.NOT_FOUND) {
                    ArrayLikeAbstractOperations.defineElem(cx, result, slot - begin, temp)
                }
            }
            setLengthProperty(cx, result, maxOf(0, end - begin))
            return result
        }

        private fun js_indexOf(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val compareTo = if (args.isNotEmpty()) args[0] else Undefined.instance
            val o = ScriptRuntime.toObject(cx, scope, thisObj)
            val length = getLengthProperty(cx, o)
            /*
             * From http://developer.mozilla.org/en/docs/Core_JavaScript_1.5_Reference:Objects:Array:indexOf
             * The index at which to begin the search. Defaults to 0, i.e. the
             * whole array will be searched. If the index is greater than or
             * equal to the length of the array, -1 is returned, i.e. the array
             * will not be searched. If negative, it is taken as the offset from
             * the end of the array. Note that even when the index is negative,
             * the array is still searched from front to back. If the calculated
             * index is less than 0, the whole array will be searched.
             */
            var start: Long
            if (args.size < 2) {
                // default
                start = 0
            } else {
                start = ScriptRuntime.toInteger(args[1]).toLong()
                if (start < 0) {
                    start += length
                    if (start < 0) start = 0
                }
                if (start > length - 1) return NEGATIVE_ONE
            }
            if (o is NativeArray) {
                if (o.denseOnly) {
                    val proto = o.prototype
                    val dense = o.dense!!
                    for (i in start.toInt() until length.toInt()) {
                        var v = dense[i]
                        if (v === Scriptable.NOT_FOUND && proto != null) {
                            v = ScriptableObject.getProperty(proto, i)
                        }
                        if (v !== Scriptable.NOT_FOUND && ScriptRuntime.shallowEq(v, compareTo)) {
                            return i.toLong()
                        }
                    }
                    return NEGATIVE_ONE
                }
            }
            for (i in start until length) {
                val v = getRawElem(o, i)
                if (v !== Scriptable.NOT_FOUND && ScriptRuntime.shallowEq(v, compareTo)) {
                    return i
                }
            }
            return NEGATIVE_ONE
        }

        private fun js_lastIndexOf(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val compareTo = if (args.isNotEmpty()) args[0] else Undefined.instance
            val o = ScriptRuntime.toObject(cx, scope, thisObj)
            val length = getLengthProperty(cx, o)
            /*
             * From http://developer.mozilla.org/en/docs/Core_JavaScript_1.5_Reference:Objects:Array:lastIndexOf
             * The index at which to start searching backwards. Defaults to the
             * array's length, i.e. the whole array will be searched. If the
             * index is greater than or equal to the length of the array, the
             * whole array will be searched. If negative, it is taken as the
             * offset from the end of the array. Note that even when the index
             * is negative, the array is still searched from back to front. If
             * the calculated index is less than 0, -1 is returned, i.e. the
             * array will not be searched.
             */
            var start: Long
            if (args.size < 2) {
                // default
                start = length - 1
            } else {
                start = ScriptRuntime.toInteger(args[1]).toLong()
                if (start >= length) start = length - 1 else if (start < 0) start += length
                if (start < 0) return NEGATIVE_ONE
            }
            if (o is NativeArray) {
                if (o.denseOnly) {
                    val proto = o.prototype
                    val dense = o.dense!!
                    var i = start.toInt()
                    while (i >= 0) {
                        var v = dense[i]
                        if (v === Scriptable.NOT_FOUND && proto != null) {
                            v = ScriptableObject.getProperty(proto, i)
                        }
                        if (v !== Scriptable.NOT_FOUND && ScriptRuntime.shallowEq(v, compareTo)) {
                            return i.toLong()
                        }
                        i--
                    }
                    return NEGATIVE_ONE
                }
            }
            var i = start
            while (i >= 0) {
                val v = getRawElem(o, i)
                if (v !== Scriptable.NOT_FOUND && ScriptRuntime.shallowEq(v, compareTo)) {
                    return i
                }
                i--
            }
            return NEGATIVE_ONE
        }

        /*
           See ECMA-262 22.1.3.13
        */
        private fun js_includes(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val o = ScriptRuntime.toObject(cx, scope, thisObj)
            val len = getLengthProperty(cx, o)
            if (len == 0L) return false
            var k: Long
            if (args.size < 2) {
                k = 0
            } else {
                k = ScriptRuntime.toInteger(args[1]).toLong()
                if (k < 0) {
                    k += len
                    if (k < 0) k = 0
                }
                if (k > len - 1) return false
            }
            val compareTo = if (args.isNotEmpty()) args[0] else Undefined.instance
            if (o is NativeArray) {
                if (o.denseOnly) {
                    val proto = o.prototype
                    val dense = o.dense!!
                    for (i in k.toInt() until len.toInt()) {
                        var elementK = dense[i]
                        if (elementK === Scriptable.NOT_FOUND && proto != null) {
                            elementK = ScriptableObject.getProperty(proto, i)
                        }
                        if (elementK === Scriptable.NOT_FOUND) elementK = Undefined.instance
                        if (ScriptRuntime.sameZero(elementK, compareTo)) return true
                    }
                    return false
                }
            }
            while (k < len) {
                var elementK = getRawElem(o, k)
                if (elementK === Scriptable.NOT_FOUND) elementK = Undefined.instance
                if (ScriptRuntime.sameZero(elementK, compareTo)) return true
                k++
            }
            return false
        }

        private fun js_fill(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val o = ScriptRuntime.toObject(cx, scope, thisObj)
            val len = getLengthProperty(cx, o)
            var relativeStart = 0L
            if (args.size >= 2) {
                relativeStart = ScriptRuntime.toInteger(args[1]).toLong()
            }
            val k = if (relativeStart < 0) maxOf(len + relativeStart, 0) else minOf(relativeStart, len)
            var relativeEnd = len
            if (args.size >= 3 && !Undefined.isUndefined(args[2])) {
                relativeEnd = ScriptRuntime.toInteger(args[2]).toLong()
            }
            val fin = if (relativeEnd < 0) maxOf(len + relativeEnd, 0) else minOf(relativeEnd, len)
            val value = if (args.isNotEmpty()) args[0] else Undefined.instance
            for (i in k until fin) {
                setRawElem(cx, thisObj!!, i, value)
            }
            return thisObj
        }

        private fun js_copyWithin(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val o = ScriptRuntime.toObject(cx, scope, thisObj)
            val len = getLengthProperty(cx, o)
            val targetArg = if (args.isNotEmpty()) args[0] else Undefined.instance
            val relativeTarget = ScriptRuntime.toInteger(targetArg).toLong()
            var to = if (relativeTarget < 0) maxOf(len + relativeTarget, 0) else minOf(relativeTarget, len)
            val startArg = if (args.size >= 2) args[1] else Undefined.instance
            val relativeStart = ScriptRuntime.toInteger(startArg).toLong()
            var from = if (relativeStart < 0) maxOf(len + relativeStart, 0) else minOf(relativeStart, len)
            var relativeEnd = len
            if (args.size >= 3 && !Undefined.isUndefined(args[2])) {
                relativeEnd = ScriptRuntime.toInteger(args[2]).toLong()
            }
            val fin = if (relativeEnd < 0) maxOf(len + relativeEnd, 0) else minOf(relativeEnd, len)
            var count = minOf(fin - from, len - to)
            var direction = 1
            if (from < to && to < from + count) {
                direction = -1
                from = from + count - 1
                to = to + count - 1
            }
            // Optimize for a native array
            if (o is NativeArray && count <= Int.MAX_VALUE) {
                if (o.denseOnly) {
                    val dense = o.dense!!
                    while (count > 0) {
                        dense[to.toInt()] = dense[from.toInt()]
                        from += direction
                        to += direction
                        count--
                    }
                    return thisObj
                }
            }
            // Otherwise, do the generic thing
            while (count > 0) {
                val temp = getRawElem(o, from)
                if (temp === Scriptable.NOT_FOUND || Undefined.isUndefined(temp)) {
                    deleteElem(o, to)
                } else {
                    setElem(cx, o, to, temp)
                }
                from += direction
                to += direction
                count--
            }
            return thisObj
        }

        private fun js_at(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val o = ScriptRuntime.toObject(cx, scope, thisObj)
            val len = getLengthProperty(cx, o)
            var relativeIndex = 0L
            if (args.isNotEmpty()) {
                relativeIndex = ScriptRuntime.toInteger(args[0]).toLong()
            }
            val k = if (relativeIndex >= 0) relativeIndex else len + relativeIndex
            if (k < 0 || k >= len) return Undefined.instance
            return getElem(cx, thisObj!!, k)
        }

        private fun js_flat(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val o = ScriptRuntime.toObject(cx, scope, thisObj)
            val depth = if (args.isEmpty() || Undefined.isUndefined(args[0])) 1.0 else ScriptRuntime.toInteger(args[0])
            return flat(cx, scope, o, depth)
        }

        private fun flat(cx: Context, scope: Scriptable, source: Scriptable, depth: Double): Scriptable {
            val length = getLengthProperty(cx, source)
            val result = ArrayLikeAbstractOperations.arraySpeciesCreate(cx, scope, source, 0)
            var j = 0L
            for (i in 0 until length) {
                val elem = getRawElem(source, i)
                if (elem === Scriptable.NOT_FOUND) continue
                if (depth >= 1 && js_isArray(elem)) {
                    val arr = flat(cx, scope, elem as Scriptable, depth - 1)
                    val arrLength = getLengthProperty(cx, arr)
                    for (k in 0 until arrLength) {
                        val temp = getRawElem(arr, k)
                        defineElemOrThrow(cx, result, j++, temp)
                    }
                } else {
                    defineElemOrThrow(cx, result, j++, elem)
                }
            }
            setLengthProperty(cx, result, j)
            return result
        }

        private fun js_flatMap(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val o = ScriptRuntime.toObject(cx, scope, thisObj)
            val callbackArg = if (args.isNotEmpty()) args[0] else Undefined.instance
            val f = ArrayLikeAbstractOperations.getCallbackArg(cx, callbackArg)
            val parent = ScriptableObject.getTopLevelScope(f)
            val thisArg: Scriptable = if (args.size < 2 || args[1] == null || args[1] === Undefined.instance) {
                parent
            } else {
                ScriptRuntime.toObject(cx, scope, args[1])
            }
            val length = getLengthProperty(cx, o)
            val result = ArrayLikeAbstractOperations.arraySpeciesCreate(cx, scope, o, 0)
            var j = 0L
            for (i in 0 until length) {
                val elem = getRawElem(o, i)
                if (elem === Scriptable.NOT_FOUND) continue
                val innerArgs = arrayOf(elem, i, o)
                val mapCall = f.call(cx, parent, thisArg, innerArgs)
                if (js_isArray(mapCall)) {
                    val arr = mapCall as Scriptable
                    val arrLength = getLengthProperty(cx, arr)
                    for (k in 0 until arrLength) {
                        val temp = getRawElem(arr, k)
                        defineElemOrThrow(cx, result, j++, temp)
                    }
                } else {
                    defineElemOrThrow(cx, result, j++, mapCall)
                }
            }
            setLengthProperty(cx, result, j)
            return result
        }

        private fun iterative(name: String, operation: IterativeOperation, cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            ArrayLikeAbstractOperations.iterativeMethod(cx, ARRAY_TAG, name, operation, scope, thisObj, args, ArrayLikeAbstractOperations.LengthAccessor { c, o -> getLengthProperty(c, o) })

        private fun js_every(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            iterative("every", IterativeOperation.EVERY, cx, scope, thisObj, args)

        private fun js_filter(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            iterative("filter", IterativeOperation.FILTER, cx, scope, thisObj, args)

        private fun js_forEach(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            iterative("forEach", IterativeOperation.FOR_EACH, cx, scope, thisObj, args)

        private fun js_map(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            iterative("map", IterativeOperation.MAP, cx, scope, thisObj, args)

        private fun js_some(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            iterative("some", IterativeOperation.SOME, cx, scope, thisObj, args)

        private fun js_find(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            iterative("find", IterativeOperation.FIND, cx, scope, thisObj, args)

        private fun js_findIndex(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            iterative("findIndex", IterativeOperation.FIND_INDEX, cx, scope, thisObj, args)

        private fun js_findLast(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            iterative("findLast", IterativeOperation.FIND_LAST, cx, scope, thisObj, args)

        private fun js_findLastIndex(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            iterative("findLastIndex", IterativeOperation.FIND_LAST_INDEX, cx, scope, thisObj, args)

        private fun js_reduce(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            ArrayLikeAbstractOperations.reduceMethod(cx, ReduceOperation.REDUCE, scope, thisObj, args)

        private fun js_reduceRight(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            ArrayLikeAbstractOperations.reduceMethod(cx, ReduceOperation.REDUCE_RIGHT, scope, thisObj, args)

        private fun js_keys(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val o = ScriptRuntime.toObject(cx, scope, thisObj)
            return NativeArrayIterator(scope, o, NativeArrayIterator.ARRAY_ITERATOR_TYPE.KEYS)
        }

        private fun js_entries(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val o = ScriptRuntime.toObject(cx, scope, thisObj)
            return NativeArrayIterator(scope, o, NativeArrayIterator.ARRAY_ITERATOR_TYPE.ENTRIES)
        }

        private fun js_values(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val o = ScriptRuntime.toObject(cx, scope, thisObj)
            return NativeArrayIterator(scope, o, NativeArrayIterator.ARRAY_ITERATOR_TYPE.VALUES)
        }

        private fun js_isArrayMethod(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            args.isNotEmpty() && js_isArray(args[0])

        private fun js_isArray(o: Any?): Boolean {
            if (o !is Scriptable) return false
            if (o is NativeProxy) return js_isArray(o.getTargetThrowIfRevoked())
            return "Array" == o.className
        }

        private fun js_toSorted(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val comparator = ArrayLikeAbstractOperations.getSortComparator(cx, scope, args)
            val source = ScriptRuntime.toObject(cx, scope, thisObj)
            val len = getLengthProperty(cx, source)
            if (len > Int.MAX_VALUE) {
                val msg = ScriptRuntime.getMessageById("msg.arraylength.bad")
                throw ScriptRuntime.rangeError(msg)
            }
            val result = cx.newArray(scope, len.toInt())
            for (k in 0 until len.toInt()) {
                val fromValue = getElem(cx, source, k.toLong())
                setElem(cx, result, k.toLong(), fromValue)
            }
            sort(cx, result, comparator)
            return result
        }

        private fun js_toReversed(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val source = ScriptRuntime.toObject(cx, scope, thisObj)
            val len = getLengthProperty(cx, source)
            if (len > Int.MAX_VALUE) {
                val msg = ScriptRuntime.getMessageById("msg.arraylength.bad")
                throw ScriptRuntime.rangeError(msg)
            }
            val result = cx.newArray(scope, len.toInt())
            for (k in 0 until len.toInt()) {
                val from = len.toInt() - k - 1
                val fromValue = getElem(cx, source, from.toLong())
                setElem(cx, result, k.toLong(), fromValue)
            }
            return result
        }

        private fun js_toSpliced(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val source = ScriptRuntime.toObject(cx, scope, thisObj)
            val len = getLengthProperty(cx, source)
            var actualStart = 0L
            if (args.isNotEmpty()) {
                actualStart = ArrayLikeAbstractOperations.toSliceIndex(ScriptRuntime.toInteger(args[0]), len)
            }
            val insertCount = if (args.size > 2) (args.size - 2).toLong() else 0L
            val actualSkipCount: Long = if (args.isEmpty()) {
                0
            } else if (args.size == 1) {
                len - actualStart
            } else {
                val sc = ScriptRuntime.toLength(args, 1)
                maxOf(0, minOf(sc, len - actualStart))
            }
            val newLen = len + insertCount - actualSkipCount
            if (newLen > NativeNumber.MAX_SAFE_INTEGER) {
                throw ScriptRuntime.typeErrorById("msg.arraylength.too.big", newLen)
            }
            if (newLen > Int.MAX_VALUE) {
                val msg = ScriptRuntime.getMessageById("msg.arraylength.bad")
                throw ScriptRuntime.rangeError(msg)
            }
            val result = cx.newArray(scope, newLen.toInt())
            var i = 0L
            var r = actualStart + actualSkipCount
            while (i < actualStart) {
                val e = getElem(cx, source, i)
                setElem(cx, result, i, e)
                i++
            }
            for (j in 2 until args.size) {
                setElem(cx, result, i, args[j])
                i++
            }
            while (i < newLen) {
                val e = getElem(cx, source, r)
                setElem(cx, result, i, e)
                i++
                r++
            }
            return result
        }

        private fun js_with(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val source = ScriptRuntime.toObject(cx, scope, thisObj)
            val len = getLengthProperty(cx, source)
            val relativeIndex: Long = if (args.isNotEmpty()) ScriptRuntime.toInteger(args[0]).toInt().toLong() else 0
            val actualIndex = if (relativeIndex >= 0) relativeIndex else len + relativeIndex
            if (actualIndex < 0 || actualIndex >= len) {
                throw ScriptRuntime.rangeError("index out of range")
            }
            if (len > Int.MAX_VALUE) {
                val msg = ScriptRuntime.getMessageById("msg.arraylength.bad")
                throw ScriptRuntime.rangeError(msg)
            }
            val result = cx.newArray(scope, len.toInt())
            for (k in 0 until len) {
                val value = if (k == actualIndex) {
                    if (args.size > 1) args[1] else Undefined.instance
                } else {
                    getElem(cx, source, k)
                }
                setElem(cx, result, k, value)
            }
            return result
        }

        internal var maximumInitialCapacity = 10000
        private const val DEFAULT_INITIAL_CAPACITY = 10
        private const val GROW_FACTOR = 1.5
        private val MAX_PRE_GROW_SIZE = (Int.MAX_VALUE / GROW_FACTOR).toInt()
    }
}
