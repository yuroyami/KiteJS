/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.reflect.KClass

/**
 * The base of every JavaScript object. It keeps the properties, the prototype and the parent scope,
 * and implements the property algorithm the whole language rests on.
 *
 * Upstream also has a reflection layer here (`defineClass`, the `@JS*` annotation scan, and the
 * `Method`-based `defineProperty` overloads) that binds Java classes into script. That is
 * LiveConnect and this port does not have it, so those members are gone.
 */
abstract class ScriptableObject :
    SlotMapOwner, Scriptable, SymbolScriptable, ConstProperties {

    private var prototypeObject: Scriptable? = null
    private var parentScopeObject: Scriptable? = null

    /** Where data living outside the engine is attached, if any. */
    private var externalData: ExternalArrayData? = null

    private var associatedValues: MutableMap<Any, Any>? = null

    /**
     * Raw storage for the extensible flag. `putImpl` reads it directly, the way upstream reads the
     * field rather than the accessor, so a subclass that overrides [isExtensible] does not change
     * the hot assignment path (D-8).
     */
    protected var isExtensibleField: Boolean = true

    var isSealed: Boolean = false
        private set

    constructor() : super(0)

    constructor(scope: Scriptable, prototype: Scriptable?) : super(0) {
        parentScopeObject = scope
        prototypeObject = prototype
    }

    /** What `typeof` says about this object. */
    open val typeOf: String
        get() = if (avoidObjectDetection()) "undefined" else "object"

    abstract override val className: String

    // ---- Reading and writing properties --------------------------------------------------------

    override fun has(name: String, start: Scriptable): Boolean = map.query(name, 0) != null

    override fun has(index: Int, start: Scriptable): Boolean {
        externalData?.let { return index < it.arrayLength }
        return map.query(null, index) != null
    }

    override fun has(key: Symbol, start: Scriptable): Boolean = map.query(key, 0) != null

    override fun get(name: String, start: Scriptable): Any? {
        val slot = map.query(name, 0) ?: return Scriptable.NOT_FOUND
        return slot.getValue(start)
    }

    override fun get(index: Int, start: Scriptable): Any? {
        externalData?.let {
            return if (index < it.arrayLength) it.getArrayElement(index)
            else Scriptable.NOT_FOUND
        }
        val slot = map.query(null, index) ?: return Scriptable.NOT_FOUND
        return slot.getValue(start)
    }

    override fun get(key: Symbol, start: Scriptable): Any? {
        val slot = map.query(key, 0) ?: return Scriptable.NOT_FOUND
        return slot.getValue(start)
    }

    override fun put(name: String, start: Scriptable, value: Any?) {
        if (putOwnProperty(name, start, value, Context.isCurrentContextStrict)) return
        if (start === this) throw Kit.codeBug()
        start.put(name, start, value)
    }

    internal open fun putOwnProperty(
        name: String,
        start: Scriptable,
        value: Any?,
        isThrow: Boolean,
    ): Boolean = putImpl(name, 0, start, value, isThrow)

    override fun put(index: Int, start: Scriptable, value: Any?) {
        val ext = externalData
        if (ext != null) {
            if (index < ext.arrayLength) {
                ext.setArrayElement(index, value)
            } else {
                throw JavaScriptException(
                    ScriptRuntime.newNativeError(
                        Context.getCurrentContext() ?: throw Kit.codeBug(),
                        this,
                        TopLevel.NativeErrors.RangeError,
                        arrayOf("External array index out of bounds "),
                    ),
                    null,
                    0,
                )
            }
            return
        }
        if (putOwnProperty(index, start, value, Context.isCurrentContextStrict)) return
        if (start === this) throw Kit.codeBug()
        start.put(index, start, value)
    }

    internal open fun putOwnProperty(
        index: Int,
        start: Scriptable,
        value: Any?,
        isThrow: Boolean,
    ): Boolean = putImpl(null, index, start, value, isThrow)

    override fun put(key: Symbol, start: Scriptable, value: Any?) {
        if (putOwnProperty(key, start, value, Context.isCurrentContextStrict)) return
        if (start === this) throw Kit.codeBug()
        ensureSymbolScriptable(start).put(key, start, value)
    }

    internal open fun putOwnProperty(
        key: Symbol,
        start: Scriptable,
        value: Any?,
        isThrow: Boolean,
    ): Boolean = putImpl(key, 0, start, value, isThrow)

    override fun delete(name: String) {
        checkNotSealed(name, 0)
        map.compute(this, name, 0, ::checkSlotRemoval)
    }

    override fun delete(index: Int) {
        checkNotSealed(null, index)
        map.compute(this, null, index, ::checkSlotRemoval)
    }

    override fun delete(key: Symbol) {
        checkNotSealed(key, 0)
        map.compute(this, key, 0, ::checkSlotRemoval)
    }

    // ---- Constants ----------------------------------------------------------------------------

    override fun putConst(name: String, start: Scriptable, value: Any?) {
        if (putConstImpl(name, 0, start, value, READONLY)) return
        if (start === this) throw Kit.codeBug()
        if (start is ConstProperties) start.putConst(name, start, value)
        else start.put(name, start, value)
    }

    override fun defineConst(name: String, start: Scriptable) {
        if (putConstImpl(name, 0, start, Undefined.instance, UNINITIALIZED_CONST)) return
        if (start === this) throw Kit.codeBug()
        if (start is ConstProperties) start.defineConst(name, start)
    }

    override fun isConst(name: String): Boolean {
        val slot = map.query(name, 0) ?: return false
        return (slot.attributes and (PERMANENT or READONLY)) == (PERMANENT or READONLY)
    }

    // ---- Attributes ---------------------------------------------------------------------------

    open fun getAttributes(name: String): Int = getAttributeSlot(name, 0).attributes

    open fun getAttributes(index: Int): Int = getAttributeSlot(null, index).attributes

    open fun getAttributes(sym: Symbol): Int = getAttributeSlot(sym).attributes

    open fun setAttributes(name: String, attributes: Int) {
        checkNotSealed(name, 0)
        map.modify(this, name, 0, 0).attributes = attributes
    }

    fun setAttributes(index: Int, attributes: Int) {
        checkNotSealed(null, index)
        map.modify(this, null, index, 0).attributes = attributes
    }

    fun setAttributes(key: Symbol, attributes: Int) {
        checkNotSealed(key, 0)
        map.modify(this, key, 0, 0).attributes = attributes
    }

    // ---- Getters and setters ------------------------------------------------------------------

    fun setGetterOrSetter(name: Any?, index: Int, getterOrSetter: Callable?, isSetter: Boolean) {
        require(!(name != null && index != 0)) { name.toString() }
        checkNotSealed(name, index)

        val aSlot: AccessorSlot
        if (isExtensible) {
            aSlot = map.compute(this, name, index, ::ensureAccessorSlot)!!
        } else {
            aSlot = map.query(name, index) as? AccessorSlot ?: return
        }

        if ((aSlot.attributes and READONLY) != 0) {
            throw Context.reportRuntimeErrorById("msg.modify.readonly", name)
        }
        // Matches the old behaviour: anything that is not a Function clears the accessor.
        if (isSetter) {
            aSlot.setter =
                if (getterOrSetter is Function) AccessorSlot.FunctionSetter(getterOrSetter) else null
        } else {
            aSlot.getter =
                if (getterOrSetter is Function) AccessorSlot.FunctionGetter(getterOrSetter) else null
        }
        aSlot.value = Undefined.instance
    }

    fun getGetterOrSetter(name: String?, index: Int, scope: Scriptable, isSetter: Boolean): Any? {
        require(!(name != null && index != 0)) { name.toString() }
        val slot = map.query(name, index) ?: return null
        val getterOrSetter =
            if (isSetter) slot.getSetterFunction(name, scope) else slot.getGetterFunction(name, scope)
        return getterOrSetter ?: Undefined.instance
    }

    protected open fun isGetterOrSetter(name: String?, index: Int, setter: Boolean): Boolean =
        startCompoundOp(false).use { isGetterOrSetter(it, name, index, setter) }

    protected open fun isGetterOrSetter(
        map: CompoundOperationMap,
        name: String?,
        index: Int,
        setter: Boolean,
    ): Boolean {
        val slot = map.query(name, index)
        return slot != null && slot.isSetterSlot
    }

    internal fun addLazilyInitializedValue(
        name: String?,
        index: Int,
        init: LazilyLoadedCtor,
        attributes: Int,
    ) {
        require(!(name != null && index != 0)) { name.toString() }
        checkNotSealed(name, index)
        val lslot = map.compute(this, name, index, ::ensureLazySlot)!!
        lslot.attributes = attributes
        lslot.value = init
    }

    internal fun addLazilyInitializedValue(
        key: Symbol?,
        index: Int,
        init: LazilyLoadedCtor,
        attributes: Int,
    ) {
        require(!(key != null && index != 0)) { key.toString() }
        checkNotSealed(key, index)
        val lslot = map.compute(this, key, index, ::ensureLazySlot)!!
        lslot.attributes = attributes
        lslot.value = init
    }

    // ---- External array data ------------------------------------------------------------------

    /**
     * Attaches data that lives outside the engine, or detaches it with null.
     *
     * KMP: upstream defines `length` through a reflected `getExternalArrayLength` method. There is
     * no reflection here, so the same property is defined with a lambda getter (D-23). Both give a
     * read-only, non-enumerable `length` whose descriptor is an accessor.
     */
    fun setExternalArrayData(array: ExternalArrayData?) {
        externalData = array
        if (array == null) {
            delete("length")
        } else {
            defineProperty(
                Context.getContext(),
                "length",
                LambdaGetterFunction { externalArrayLength },
                null,
                READONLY or DONTENUM,
            )
        }
    }

    val externalArrayData: ExternalArrayData? get() = externalData

    val externalArrayLength: Any get() = externalData?.arrayLength ?: 0

    // ---- Prototype and scope ------------------------------------------------------------------

    override var prototype: Scriptable?
        get() = prototypeObject
        set(value) {
            prototypeObject = value
        }

    override var parentScope: Scriptable?
        get() = parentScopeObject
        set(value) {
            parentScopeObject = value
        }

    override fun getIds(): Array<Any?> = startCompoundOp(false).use { getIds(it, false, false) }

    /** Every property, enumerable or not. */
    open val allIds: Array<Any?> get() = startCompoundOp(false).use { getIds(it, true, false) }

    override fun getDefaultValue(hint: KClass<*>?): Any? = getDefaultValue(this, hint)

    override fun hasInstance(instance: Scriptable): Boolean {
        // The default for a plain object is to walk the prototype chain. Function objects and the
        // non-script ones override this.
        val cx = Context.getContext()
        val hasInstance = ScriptRuntime.getObjectElem(this, SymbolKey.HAS_INSTANCE, cx)
        if (hasInstance is Function) {
            return ScriptRuntime.toBoolean(
                hasInstance.call(cx, hasInstance.declarationScope!!, this, arrayOf(this)),
            )
        }
        if (this !is Callable) throw ScriptRuntime.typeErrorById("msg.instanceof.bad.target")
        return ScriptRuntime.jsDelegatesTo(instance, this)
    }

    /** True for an object that has to pretend it is `undefined`, which only `NativeWith` does. */
    open fun avoidObjectDetection(): Boolean = false

    protected open fun equivalentValues(value: Any?): Any? =
        if (this === value) true else Scriptable.NOT_FOUND

    internal fun equivalentValuesInternal(value: Any?): Any? = equivalentValues(value)

    // ---- Defining properties ------------------------------------------------------------------

    fun defineProperty(propertyName: String, value: Any?, attributes: Int) {
        checkNotSealed(propertyName, 0)
        put(propertyName, this, value)
        setAttributes(propertyName, attributes)
    }

    fun defineProperty(key: Symbol, value: Any?, attributes: Int) {
        checkNotSealed(key, 0)
        put(key, this, value)
        setAttributes(key, attributes)
    }

    /** Defines a method backed by a Kotlin lambda. */
    fun defineProperty(
        scope: Scriptable,
        name: String,
        length: Int,
        target: SerializableCallable,
        attributes: Int = DONTENUM,
        propertyAttributes: Int = DONTENUM or READONLY,
    ) {
        val f = LambdaFunction(scope, name, length, target, true)
        f.setStandardPropertyAttributes(propertyAttributes)
        defineProperty(name, f, attributes)
    }

    /** Same as above for a built-in method, which does not get a `prototype` property. */
    fun defineBuiltinProperty(
        scope: Scriptable,
        name: String,
        length: Int,
        target: SerializableCallable,
        attributes: Int = DONTENUM,
        propertyAttributes: Int = DONTENUM or READONLY,
    ) {
        val f = LambdaFunction(scope, name, length, target, false)
        f.setStandardPropertyAttributes(propertyAttributes)
        defineProperty(name, f, attributes)
    }

    /** Defines a property whose value comes from a lambda that needs nothing but itself. */
    fun defineProperty(name: String, getter: (() -> Any?)?, setter: ((Any?) -> Unit)?, attributes: Int) {
        val slot = map.compute(this, name, 0, ::ensureLambdaSlot)!!
        slot.attributes = attributes
        slot.getter = getter
        slot.setter = setter
    }

    /** A getter that gets handed the object it is reading from. */
    fun interface LambdaGetterFunction {
        fun apply(scope: Scriptable?): Any?
    }

    /** A setter that gets handed the object it is writing to. */
    fun interface LambdaSetterFunction {
        fun accept(scope: Scriptable?, value: Any?)
    }

    fun defineProperty(
        cx: Context,
        name: String,
        getter: LambdaGetterFunction?,
        setter: LambdaSetterFunction?,
        attributes: Int,
    ) {
        if (getter == null && setter == null) {
            throw ScriptRuntime.typeError("at least one of {getter, setter} is required")
        }
        replaceLambdaAccessorSlot(cx, name, createLambdaAccessorSlot(name, 0, getter, setter, attributes))
    }

    fun defineProperty(cx: Context, name: String, getter: LambdaGetterFunction, attributes: Int) {
        defineProperty(cx, name, getter, null, attributes)
    }

    fun defineProperty(
        cx: Context,
        key: Symbol,
        getter: LambdaGetterFunction?,
        setter: LambdaSetterFunction?,
        attributes: Int,
    ) {
        if (getter == null && setter == null) {
            throw ScriptRuntime.typeError("at least one of {getter, setter} is required")
        }
        replaceLambdaAccessorSlot(cx, key, createLambdaAccessorSlot(key, 0, getter, setter, attributes))
    }

    private fun replaceLambdaAccessorSlot(cx: Context, key: Any?, newSlot: LambdaAccessorSlot) {
        val newDesc = newSlot.buildPropertyDescriptor(cx)
        checkPropertyDefinition(newDesc)
        map.compute(this, key, 0) { _, _, existing, _, _ ->
            if (existing != null) {
                // Using `this` as a scope inside compute is unsafe, so the replacement is built
                // without touching the map again.
                replaceExistingLambdaSlot(cx, key, existing, newSlot)
            } else {
                checkPropertyChangeForSlot(key, null, newDesc)
                newSlot
            }
        }
    }

    private fun replaceExistingLambdaSlot(
        cx: Context,
        key: Any?,
        existing: Slot,
        newSlot: LambdaAccessorSlot,
    ): LambdaAccessorSlot {
        val replacedSlot =
            existing as? LambdaAccessorSlot ?: LambdaAccessorSlot(existing)
        replacedSlot.replaceWith(newSlot)
        checkPropertyChangeForSlot(key, existing, replacedSlot.buildPropertyDescriptor(cx))
        return replacedSlot
    }

    private fun createLambdaAccessorSlot(
        name: Any?,
        index: Int,
        getter: LambdaGetterFunction?,
        setter: LambdaSetterFunction?,
        attributes: Int,
    ): LambdaAccessorSlot {
        val slot = LambdaAccessorSlot(name, index)
        slot.setGetter(this, getter)
        slot.setSetter(this, setter)
        slot.attributes = attributes
        return slot
    }

    // ---- Property descriptors -----------------------------------------------------------------

    fun defineOwnProperties(cx: Context, props: ScriptableObject) {
        val ids = props.startCompoundOp(false).use { props.getIds(it, false, true) }
        val descs = arrayOfNulls<DescriptorInfo>(ids.size)
        for (i in ids.indices) {
            val descObj = ScriptRuntime.getObjectElem(props, ids[i], cx)
            val desc = DescriptorInfo(ensureScriptableObject(descObj))
            checkPropertyDefinition(desc)
            descs[i] = desc
        }
        for (i in ids.indices) defineOwnProperty(cx, ids[i], descs[i]!!)
    }

    fun defineOwnProperty(cx: Context, id: Any?, desc: ScriptableObject): Boolean {
        checkPropertyDefinition(desc)
        return defineOwnProperty(cx, id, DescriptorInfo(desc), true)
    }

    open fun defineOwnProperty(cx: Context, id: Any?, desc: DescriptorInfo): Boolean =
        defineOwnProperty(cx, id, desc, true)

    internal open fun defineOwnProperty(
        cx: Context,
        id: Any?,
        desc: DescriptorInfo,
        checkValid: Boolean,
    ): Boolean {
        var key: Any? = null
        var index = 0
        if (id is Symbol) {
            key = id
        } else {
            val s = ScriptRuntime.toStringIdOrIndex(id)
            if (s.stringId == null) index = s.index else key = s.stringId
        }

        val aSlot = map.query(key, index)
        return if (aSlot is BuiltInSlot<*>) {
            // Setting array length has to validate the value and throw a range error before it
            // looks at attributes, and it treats a missing "value" differently, so slots like that
            // define their own rules. It runs outside the compound operation because applying a
            // descriptor can itself change the current one.
            aSlot.applyNewDescriptor(id, desc, checkValid, key, index)
        } else {
            startCompoundOp(true).use { m ->
                defineOrdinaryProperty(::setSlotValue, this, m, id, desc, checkValid, key, index)
            }
        }
    }

    /**
     * A property descriptor in the form the engine works with, rather than as a script object.
     * Every field holds [Scriptable.NOT_FOUND] when the descriptor does not mention it.
     */
    class DescriptorInfo {

        var enumerable: Any? = Scriptable.NOT_FOUND
        var writable: Any? = Scriptable.NOT_FOUND
        var configurable: Any? = Scriptable.NOT_FOUND
        var getter: Any? = Scriptable.NOT_FOUND
        var setter: Any? = Scriptable.NOT_FOUND
        var value: Any? = Scriptable.NOT_FOUND

        internal var accessorDescriptor: Boolean = false

        /** Reads a descriptor out of a script object. */
        constructor(desc: ScriptableObject) {
            enumerable = getProperty(desc, "enumerable")
            writable = getProperty(desc, "writable")
            configurable = getProperty(desc, "configurable")
            getter = getProperty(desc, "get")
            setter = getProperty(desc, "set")
            value = getProperty(desc, "value")
            accessorDescriptor = getter !== Scriptable.NOT_FOUND || setter !== Scriptable.NOT_FOUND
        }

        constructor(enumerable: Boolean, writable: Boolean, configurable: Boolean, value: Any?) {
            this.enumerable = enumerable
            this.writable = writable
            this.configurable = configurable
            this.value = value
        }

        constructor(
            enumerable: Any?,
            writable: Any?,
            configurable: Any?,
            getter: Any?,
            setter: Any?,
            value: Any?,
        ) {
            this.enumerable = enumerable
            this.writable = writable
            this.configurable = configurable
            this.getter = getter
            this.setter = setter
            this.value = value
            accessorDescriptor = getter !== Scriptable.NOT_FOUND || setter !== Scriptable.NOT_FOUND
        }

        internal constructor(value: Any?, attributes: Int, defineWritable: Boolean) {
            this.value = value
            if (defineWritable) writable = (attributes and READONLY) == 0
            enumerable = (attributes and DONTENUM) == 0
            configurable = (attributes and PERMANENT) == 0
        }

        val isWritable: Boolean get() = writable == true

        fun isWritable(value: Boolean): Boolean = writable == value

        fun hasWritable(): Boolean = writable !== Scriptable.NOT_FOUND

        val isEnumerable: Boolean get() = enumerable == true

        fun isEnumerable(value: Boolean): Boolean = enumerable == value

        fun hasEnumerable(): Boolean = enumerable !== Scriptable.NOT_FOUND

        val isConfigurable: Boolean get() = configurable == true

        fun isConfigurable(value: Boolean): Boolean = configurable == value

        fun hasConfigurable(): Boolean = configurable !== Scriptable.NOT_FOUND

        fun hasValue(): Boolean = value !== Scriptable.NOT_FOUND

        fun hasGetter(): Boolean = getter !== Scriptable.NOT_FOUND

        fun hasSetter(): Boolean = setter !== Scriptable.NOT_FOUND

        val isDataDescriptor: Boolean get() = hasValue() || hasWritable()

        val isAccessorDescriptor: Boolean get() = hasGetter() || hasSetter()

        val isGenericDescriptor: Boolean get() = !isDataDescriptor && !isAccessorDescriptor

        /** Renders this descriptor as the script object `Object.getOwnPropertyDescriptor` returns. */
        internal fun toObject(scope: Scriptable): Scriptable {
            val desc = NativeObject()
            ScriptRuntime.setBuiltinProtoAndParent(desc, scope, TopLevel.Builtins.Object)
            if (hasValue()) desc.defineProperty("value", value, EMPTY)
            if (hasWritable()) desc.defineProperty("writable", writable, EMPTY)
            if (hasGetter()) desc.defineProperty("get", getter, EMPTY)
            if (hasSetter()) desc.defineProperty("set", setter, EMPTY)
            if (hasEnumerable()) desc.defineProperty("enumerable", enumerable, EMPTY)
            if (hasConfigurable()) desc.defineProperty("configurable", configurable, EMPTY)
            return desc
        }
    }

    protected fun checkPropertyChangeForSlot(id: Any?, current: Slot?, desc: ScriptableObject) {
        checkPropertyChangeForSlot(id, current, DescriptorInfo(desc))
    }

    protected fun checkPropertyChangeForSlot(id: Any?, current: Slot?, info: DescriptorInfo) {
        if (current == null) {
            if (!isExtensible) throw ScriptRuntime.typeErrorById("msg.not.extensible")
            return
        }
        if ((current.attributes and PERMANENT) == 0) return

        if (isTrue(info.configurable)) {
            throw ScriptRuntime.typeErrorById("msg.change.configurable.false.to.true", id)
        }
        if (((current.attributes and DONTENUM) == 0) != isTrue(info.enumerable)) {
            throw ScriptRuntime.typeErrorById("msg.change.enumerable.with.configurable.false", id)
        }

        val isData = info.isDataDescriptor
        val isAccessor = info.accessorDescriptor
        when {
            // A generic descriptor needs no further checking.
            !isData && !isAccessor -> {}

            isData -> {
                if ((current.attributes and READONLY) != 0) {
                    if (isTrue(info.writable)) {
                        throw ScriptRuntime.typeErrorById(
                            "msg.change.writable.false.to.true.with.configurable.false",
                            id,
                        )
                    }
                    val currentValue =
                        if (current is BuiltInSlot<*>) current.getValue(null) else current.value
                    if (!sameValue(info.value, currentValue)) {
                        throw ScriptRuntime.typeErrorById("msg.change.value.with.writable.false", id)
                    }
                }
            }

            current is AccessorSlot -> {
                if (!current.isSameSetterFunction(info.setter)) {
                    throw ScriptRuntime.typeErrorById("msg.change.setter.with.configurable.false", id)
                }
                if (!current.isSameGetterFunction(info.getter)) {
                    throw ScriptRuntime.typeErrorById("msg.change.getter.with.configurable.false", id)
                }
            }

            else -> throw ScriptRuntime.typeErrorById(
                "msg.change.property.data.to.accessor.with.configurable.false",
                id,
            )
        }
    }

    /**
     * SameValue, which differs from `===` on two counts: two NaNs are the same value, and the two
     * signed zeroes are not.
     */
    protected open fun sameValue(newValue: Any?, currentValue: Any?): Boolean {
        if (newValue === Scriptable.NOT_FOUND) return true
        val current = if (currentValue === Scriptable.NOT_FOUND) Undefined.instance else currentValue
        if (current is Number && newValue is Number) {
            val d1 = current.toDouble()
            val d2 = newValue.toDouble()
            if (d1.isNaN() && d2.isNaN()) return true
            if (d1 == 0.0 && d1.toRawBits() != d2.toRawBits()) return false
        }
        return ScriptRuntime.shallowEq(current, newValue)
    }

    // ---- Extensibility and sealing -------------------------------------------------------------

    open val isExtensible: Boolean
        get() = isExtensibleField

    open fun preventExtensions(): Boolean {
        isExtensibleField = false
        return true
    }

    /**
     * Seals the object, so no property can be added, removed or changed.
     *
     * Anything still waiting to be lazily built is built first, outside the map iteration, because
     * building one can add more properties to this same object.
     */
    fun sealObject() {
        val toInitialize = mutableListOf<Slot>()
        while (!isSealed) {
            for (slot in toInitialize) {
                // Check again: building one slot may already have built another.
                val value = slot.value
                if (value is LazilyLoadedCtor) {
                    try {
                        value.init()
                    } finally {
                        slot.value = value.getValue()
                    }
                }
            }
            toInitialize.clear()
            startCompoundOp(false).use { m ->
                for (slot in m) {
                    if (slot.value is LazilyLoadedCtor) toInitialize.add(slot)
                }
                if (toInitialize.isEmpty()) isSealed = true
            }
        }
    }

    private fun checkNotSealed(key: Any?, index: Int) {
        if (!isSealed) return
        val str = key?.toString() ?: index.toString()
        throw Context.reportRuntimeErrorById("msg.modify.sealed", str)
    }

    // ---- Associated values ---------------------------------------------------------------------

    /** A value another part of the engine parked on this object under [key]. */
    fun getAssociatedValue(key: Any): Any? = associatedValues?.get(key)

    /** Parks [value] under [key], or returns what is already there. */
    fun associateValue(key: Any, value: Any): Any {
        val h = associatedValues ?: HashMap<Any, Any>().also { associatedValues = it }
        return Kit.initHash(h, key, value)
    }

    // ---- The write path ------------------------------------------------------------------------

    private fun putImpl(key: Any?, index: Int, start: Scriptable, value: Any?, isThrow: Boolean): Boolean {
        // Called on every assignment, so the extensible and sealed checks are spelled out inline
        // rather than routed through the accessors.
        val slot: Slot
        if (this !== start) {
            val found = map.query(key, index)
            if (!isExtensibleField &&
                (found == null || (found !is AccessorSlot && (found.attributes and READONLY) != 0)) &&
                isThrow
            ) {
                throw ScriptRuntime.typeErrorById("msg.not.extensible")
            }
            slot = found ?: return false
        } else if (!isExtensibleField) {
            val found = map.query(key, index)
            if ((found == null || (found !is AccessorSlot && (found.attributes and READONLY) != 0)) &&
                isThrow
            ) {
                throw ScriptRuntime.typeErrorById("msg.not.extensible")
            }
            slot = found ?: return true
        } else {
            if (isSealed) checkNotSealed(key, index)
            slot = map.modify(this, key, index, 0)
        }
        return slot.setValue(value, this, start, isThrow)
    }

    private fun putConstImpl(
        name: String?,
        index: Int,
        start: Scriptable,
        value: Any?,
        constFlag: Int,
    ): Boolean {
        check(constFlag != EMPTY)
        if (!isExtensibleField && Context.getContext().isStrictMode) {
            throw ScriptRuntime.typeErrorById("msg.not.extensible")
        }
        val slot: Slot
        if (this !== start) {
            slot = map.query(name, index) ?: return false
        } else if (!isExtensible) {
            slot = map.query(name, index) ?: return true
        } else {
            checkNotSealed(name, index)
            // Either the hoisted declaration or the initialization.
            val s = map.modify(this, name, index, CONST)
            val attr = s.attributes
            if ((attr and READONLY) == 0) {
                throw Context.reportRuntimeErrorById("msg.var.redecl", name)
            }
            if ((attr and UNINITIALIZED_CONST) != 0) {
                s.value = value
                // Initializing a const clears the bit.
                if (constFlag != UNINITIALIZED_CONST) s.attributes = attr and UNINITIALIZED_CONST.inv()
            }
            return true
        }
        return slot.setValue(value, this, start)
    }

    private fun getAttributeSlot(name: String?, index: Int): Slot =
        map.query(name, index)
            ?: throw Context.reportRuntimeErrorById("msg.prop.not.found", name ?: index.toString())

    private fun getAttributeSlot(key: Symbol): Slot =
        map.query(key, 0) ?: throw Context.reportRuntimeErrorById("msg.prop.not.found", key)

    internal open fun getIds(map: CompoundOperationMap, getNonEnumerable: Boolean, getSymbols: Boolean): Array<Any?> {
        var a: Array<Any?>
        val externalLen = externalData?.arrayLength ?: 0
        if (externalLen == 0) {
            a = ScriptRuntime.emptyArgs
        } else {
            a = arrayOfNulls(externalLen)
            for (i in 0 until externalLen) a[i] = i
        }
        if (map.isEmpty()) return a

        var c = externalLen
        for (slot in map) {
            if ((getNonEnumerable || (slot.attributes and DONTENUM) == 0) &&
                (getSymbols || slot.name !is Symbol)
            ) {
                if (c == externalLen) {
                    // Grow once, to hold the external indices plus the real properties.
                    val oldA = a
                    a = arrayOfNulls(map.dirtySize() + externalLen)
                    oldA.copyInto(a, 0, 0, externalLen)
                }
                a[c++] = slot.name ?: slot.indexOrHash
            }
        }

        val result = if (c == a.size + externalLen) a else a.copyOf(c)
        val cx = Context.getCurrentContext()
        if (cx != null && cx.hasFeature(Context.FEATURE_ENUMERATE_IDS_FIRST)) {
            // The numeric ids go first, in numeric order.
            result.sortWith(KEY_COMPARATOR)
        }
        return result
    }

    // ---- Map-like helpers ----------------------------------------------------------------------

    /** Part of what `java.util.Map` needs. `NativeObject` finishes the job. */
    open fun size(): Int = map.size()

    open fun isEmpty(): Boolean = map.isEmpty()

    operator fun get(key: Any?): Any? {
        val value = when (key) {
            is String -> get(key, this)
            is Symbol -> get(key, this)
            is Number -> get(key.toInt(), this)
            else -> null
        }
        return when {
            value === Scriptable.NOT_FOUND || value === Undefined.instance -> null
            value is Wrapper -> value.unwrap()
            else -> value
        }
    }

    internal open fun getOwnPropertyDescriptor(cx: Context, id: Any?): DescriptorInfo? =
        querySlot(cx, id)?.getPropertyDescriptor(cx, this)

    internal fun querySlot(cx: Context, id: Any?): Slot? {
        if (id is Symbol) return map.query(id, 0)
        val s = ScriptRuntime.toStringIdOrIndex(id)
        return if (s.stringId == null) map.query(null, s.index) else map.query(s.stringId, 0)
    }

    companion object {

        /** The property has no special attributes. */
        const val EMPTY = 0x00

        /** The property cannot be written to. */
        const val READONLY = 0x01

        /** The property does not show up in a `for..in` loop. */
        const val DONTENUM = 0x02

        /** The property cannot be deleted. */
        const val PERMANENT = 0x04

        /** The const was declared but not yet given a value. */
        const val UNINITIALIZED_CONST = 0x08

        const val CONST = PERMANENT or READONLY or UNINITIALIZED_CONST

        internal fun buildDataDescriptor(value: Any?, attributes: Int): DescriptorInfo =
            DescriptorInfo(value, attributes, true)

        internal fun checkValidAttributes(attributes: Int) {
            val mask = READONLY or DONTENUM or PERMANENT or UNINITIALIZED_CONST
            require((attributes and mask.inv()) == 0) { "$attributes" }
        }

        /** Refuses to remove a permanent property, and throws about it in strict mode. */
        internal fun checkSlotRemoval(
            key: Any?,
            index: Int,
            slot: Slot?,
            compoundOp: CompoundOperationMap,
            owner: SlotMapOwner?,
        ): Slot? {
            if (slot != null && (slot.attributes and PERMANENT) != 0) {
                if (Context.getContext().isStrictMode) {
                    throw ScriptRuntime.typeErrorById(
                        "msg.delete.property.with.configurable.false",
                        key,
                    )
                }
                // Returning the slot leaves it in place, which is the removal not happening.
                return slot
            }
            return null
        }

        fun defineProperty(
            destination: Scriptable,
            propertyName: String,
            value: Any?,
            attributes: Int,
        ) {
            if (destination !is ScriptableObject) {
                destination.put(propertyName, destination, value)
                return
            }
            destination.defineProperty(propertyName, value, attributes)
        }

        fun defineConstProperty(destination: Scriptable, propertyName: String) {
            if (destination is ConstProperties) {
                destination.defineConst(propertyName, destination)
            } else {
                defineProperty(destination, propertyName, Undefined.instance, CONST)
            }
        }

        /** Runs the ordinary [defineOwnProperty] algorithm over one key. */
        internal fun defineOrdinaryProperty(
            descValueSetter: PropDescValueSetter,
            owner: ScriptableObject,
            compoundOp: CompoundOperationMap,
            id: Any?,
            info: DescriptorInfo,
            checkValid: Boolean,
            key: Any?,
            index: Int,
        ): Boolean {
            // Whether the key exists and what to do about it are settled in one map operation.
            compoundOp.compute(owner, compoundOp, key, index) { k, ix, existing, m, _ ->
                if (checkValid) owner.checkPropertyChangeForSlot(id, existing, info)

                var slot: Slot
                val attributes: Int
                if (existing == null) {
                    slot = Slot(k, ix, 0)
                    attributes = applyDescriptorToAttributeBitset(
                        DONTENUM or READONLY or PERMANENT,
                        info.enumerable,
                        info.writable,
                        info.configurable,
                    )
                } else {
                    slot = existing
                    attributes = applyDescriptorToAttributeBitset(
                        existing.attributes,
                        info.enumerable,
                        info.writable,
                        info.configurable,
                    )
                }
                slot = descValueSetter.execute(owner, info, key, existing, m, slot)
                // Whatever comes back here is what ends up in the map.
                slot.attributes = attributes
                slot
            }
            return true
        }

        internal fun interface PropDescValueSetter {
            fun execute(
                owner: ScriptableObject,
                info: DescriptorInfo,
                key: Any?,
                existing: Slot?,
                map: CompoundOperationMap,
                slot: Slot,
            ): Slot
        }

        /** Writes the descriptor's value into [slot], swapping the slot's kind when it has to. */
        internal fun setSlotValue(
            owner: ScriptableObject,
            info: DescriptorInfo,
            key: Any?,
            existing: Slot?,
            map: CompoundOperationMap,
            slot: Slot,
        ): Slot {
            var s = slot
            if (info.accessorDescriptor) {
                val fslot: AccessorSlot
                if (s is AccessorSlot) {
                    fslot = s
                } else {
                    fslot =
                        if (s is LambdaAccessorSlot && NativeObject.PROTO_PROPERTY == key) s.asAccessorSlot()
                        else AccessorSlot(s)
                    s = fslot
                }
                if (info.getter !== Scriptable.NOT_FOUND) {
                    fslot.getter = AccessorSlot.FunctionGetter(info.getter)
                }
                if (info.setter !== Scriptable.NOT_FOUND) {
                    fslot.setter = AccessorSlot.FunctionSetter(info.setter)
                }
                fslot.value = Undefined.instance
            } else if (s is BuiltInSlot<*>) {
                if (info.value !== Scriptable.NOT_FOUND) {
                    s.setValueFromDescriptor(info.value, owner, owner, true)
                }
            } else {
                if (!s.isValueSlot && info.isDataDescriptor) {
                    // Turn a slot that is not a plain value slot back into one.
                    s = Slot(s)
                }
                if (info.value !== Scriptable.NOT_FOUND) {
                    s.value = info.value
                } else if (existing == null) {
                    // Without this a switched slot would keep a stale value.
                    s.value = Undefined.instance
                }
            }
            return s
        }

        internal fun checkPropertyDefinition(desc: ScriptableObject) {
            val getter = getProperty(desc, "get")
            if (getter !== Scriptable.NOT_FOUND && getter !== Undefined.instance && getter !is Callable) {
                throw ScriptRuntime.notFunctionError(getter)
            }
            val setter = getProperty(desc, "set")
            if (setter !== Scriptable.NOT_FOUND && setter !== Undefined.instance && setter !is Callable) {
                throw ScriptRuntime.notFunctionError(setter)
            }
            if (isDataDescriptor(desc) && isAccessorDescriptor(desc)) {
                throw ScriptRuntime.typeErrorById("msg.both.data.and.accessor.desc")
            }
        }

        internal fun checkPropertyDefinition(desc: DescriptorInfo) {
            val getter = desc.getter
            if (getter !== Scriptable.NOT_FOUND && getter !== Undefined.instance && getter !is Callable) {
                throw ScriptRuntime.notFunctionError(getter)
            }
            val setter = desc.setter
            if (setter !== Scriptable.NOT_FOUND && setter !== Undefined.instance && setter !is Callable) {
                throw ScriptRuntime.notFunctionError(setter)
            }
            if (desc.isDataDescriptor && desc.isAccessorDescriptor) {
                throw ScriptRuntime.typeErrorById("msg.both.data.and.accessor.desc")
            }
        }

        internal fun isTrue(value: Any?): Boolean =
            value !== Scriptable.NOT_FOUND && ScriptRuntime.toBoolean(value)

        internal fun isFalse(value: Any?): Boolean = !isTrue(value)

        internal fun applyDescriptorToAttributeBitset(
            attributes: Int,
            enumerable: Any?,
            writable: Any?,
            configurable: Any?,
        ): Int {
            var a = attributes
            if (enumerable !== Scriptable.NOT_FOUND) {
                a = if (ScriptRuntime.toBoolean(enumerable)) a and DONTENUM.inv() else a or DONTENUM
            }
            if (writable !== Scriptable.NOT_FOUND) {
                a = if (ScriptRuntime.toBoolean(writable)) a and READONLY.inv() else a or READONLY
            }
            if (configurable !== Scriptable.NOT_FOUND) {
                a = if (ScriptRuntime.toBoolean(configurable)) a and PERMANENT.inv() else a or PERMANENT
            }
            return a
        }

        internal fun isDataDescriptor(desc: ScriptableObject): Boolean =
            hasProperty(desc, "value") || hasProperty(desc, "writable")

        internal fun isAccessorDescriptor(desc: ScriptableObject): Boolean =
            hasProperty(desc, "get") || hasProperty(desc, "set")

        internal fun isAccessorDescriptor(desc: DescriptorInfo): Boolean =
            desc.hasGetter() || desc.hasSetter()

        internal fun isGenericDescriptor(desc: ScriptableObject): Boolean =
            !isDataDescriptor(desc) && !isAccessorDescriptor(desc)

        // Upstream's condition here reads oddly, but it is copied as written.
        internal fun isGenericDescriptor(desc: DescriptorInfo): Boolean =
            desc.isDataDescriptor && !desc.isAccessorDescriptor

        fun ensureScriptable(arg: Any?): Scriptable =
            arg as? Scriptable
                ?: throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeOf(arg))

        fun ensureSymbolScriptable(arg: Any?): SymbolScriptable =
            arg as? SymbolScriptable
                ?: throw ScriptRuntime.typeErrorById(
                    "msg.object.not.symbolscriptable",
                    ScriptRuntime.typeOf(arg),
                )

        fun ensureScriptableObject(arg: Any?): ScriptableObject =
            // Upstream also unwraps a Delegator here. There is no Delegator in this port.
            arg as? ScriptableObject
                ?: throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeOf(arg))

        fun ensureScriptableObjectButNotSymbol(arg: Any?): ScriptableObject {
            if (arg is Symbol) {
                throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeOf(arg))
            }
            return ensureScriptableObject(arg)
        }

        /** Checks that [obj] is a [T], and says which function complained if it is not. */
        inline fun <reified T : Any> ensureType(obj: Any?, functionName: String): T {
            if (obj is T) return obj
            throw ScriptRuntime.typeErrorById(
                "msg.incompat.call.details",
                functionName,
                if (obj == null) "null" else obj::class.simpleName,
                T::class.simpleName,
            )
        }

        // ---- Well-known prototypes ---------------------------------------------------------

        fun getObjectPrototype(scope: Scriptable): Scriptable? =
            TopLevel.getBuiltinPrototype(getTopLevelScope(scope), TopLevel.Builtins.Object)

        fun getFunctionPrototype(scope: Scriptable): Scriptable? =
            TopLevel.getBuiltinPrototype(getTopLevelScope(scope), TopLevel.Builtins.Function)

        fun getGeneratorFunctionPrototype(scope: Scriptable): Scriptable? =
            TopLevel.getBuiltinPrototype(
                getTopLevelScope(scope),
                TopLevel.Builtins.GeneratorFunction,
            )

        fun getArrayPrototype(scope: Scriptable): Scriptable? =
            TopLevel.getBuiltinPrototype(getTopLevelScope(scope), TopLevel.Builtins.Array)

        fun getClassPrototype(scope: Scriptable, className: String): Scriptable? {
            val top = getTopLevelScope(scope)
            val proto = when (val ctor = getProperty(top, className)) {
                is BaseFunction -> ctor.prototypeProperty
                is Scriptable -> ctor.get("prototype", ctor)
                else -> return null
            }
            return proto as? Scriptable
        }

        /** Walks up the parent scopes to the global object. */
        fun getTopLevelScope(obj: Scriptable): Scriptable {
            var o = obj
            while (true) {
                o = o.parentScope ?: return o
            }
        }

        internal fun checkNotSealed(obj: ScriptableObject, key: Any?, index: Int) {
            obj.checkNotSealed(key, index)
        }

        // ---- Reading and writing through the prototype chain --------------------------------

        fun getProperty(obj: Scriptable, name: String): Any? =
            getPropWalkingPrototypeChain(obj, name, obj)

        fun getSuperProperty(superObj: Scriptable, thisObj: Scriptable, name: String): Any? =
            getPropWalkingPrototypeChain(superObj, name, thisObj)

        private fun getPropWalkingPrototypeChain(
            obj: Scriptable,
            name: String,
            start: Scriptable,
        ): Any? {
            var o: Scriptable? = obj
            var result: Any?
            do {
                result = o!!.get(name, start)
                if (result !== Scriptable.NOT_FOUND) break
                o = o.prototype
            } while (o != null)
            return result
        }

        fun getProperty(obj: Scriptable, key: Symbol): Any? =
            getPropWalkingPrototypeChain(obj, obj, key)

        fun getSuperProperty(superObj: Scriptable, thisObj: Scriptable, key: Symbol): Any? =
            getPropWalkingPrototypeChain(superObj, thisObj, key)

        private fun getPropWalkingPrototypeChain(
            obj: Scriptable,
            start: Scriptable,
            key: Symbol,
        ): Any? {
            var o: Scriptable? = obj
            var result: Any?
            do {
                result = ensureSymbolScriptable(o!!).get(key, start)
                if (result !== Scriptable.NOT_FOUND) break
                o = o.prototype
            } while (o != null)
            return result
        }

        fun getProperty(obj: Scriptable, index: Int): Any? =
            getPropWalkingPrototypeChain(obj, index, obj)

        fun getSuperProperty(superObj: Scriptable, thisObj: Scriptable, index: Int): Any? =
            getPropWalkingPrototypeChain(superObj, index, thisObj)

        private fun getPropWalkingPrototypeChain(
            obj: Scriptable,
            index: Int,
            start: Scriptable,
        ): Any? {
            var o: Scriptable? = obj
            var result: Any?
            do {
                result = o!!.get(index, start)
                if (result !== Scriptable.NOT_FOUND) break
                o = o.prototype
            } while (o != null)
            return result
        }

        fun hasProperty(obj: Scriptable, name: String): Boolean = getBase(obj, name) != null

        fun hasProperty(obj: Scriptable, index: Int): Boolean = getBase(obj, index) != null

        fun hasProperty(obj: Scriptable, key: Symbol): Boolean = getBase(obj, key) != null

        fun redefineProperty(obj: Scriptable, name: String, isConst: Boolean) {
            val base = getBase(obj, name) ?: return
            if (base is ConstProperties && base.isConst(name)) {
                throw ScriptRuntime.typeErrorById("msg.const.redecl", name)
            }
            if (isConst) throw ScriptRuntime.typeErrorById("msg.var.redecl", name)
        }

        fun putProperty(obj: Scriptable, name: String, value: Any?) {
            val base = getBase(obj, name) ?: obj
            base.put(name, obj, value)
        }

        fun putSuperProperty(superObj: Scriptable, thisObj: Scriptable, name: String, value: Any?) {
            // Unlike putProperty, the search starts at superObj.
            val base = getBase(superObj, name) ?: superObj
            base.put(name, thisObj, value)
        }

        fun putProperty(obj: Scriptable, key: Symbol, value: Any?) {
            val base = getBase(obj, key) ?: obj
            ensureSymbolScriptable(base).put(key, obj, value)
        }

        fun putSuperProperty(superObj: Scriptable, thisObj: Scriptable, key: Symbol, value: Any?) {
            val base = getBase(superObj, key) ?: superObj
            ensureSymbolScriptable(base).put(key, thisObj, value)
        }

        fun putConstProperty(obj: Scriptable, name: String, value: Any?) {
            val base = getBase(obj, name) ?: obj
            if (base is ConstProperties) base.putConst(name, obj, value)
        }

        fun putProperty(obj: Scriptable, index: Int, value: Any?) {
            val base = getBase(obj, index) ?: obj
            base.put(index, obj, value)
        }

        fun putSuperProperty(superObj: Scriptable, thisObj: Scriptable, index: Int, value: Any?) {
            val base = getBase(superObj, index) ?: superObj
            base.put(index, thisObj, value)
        }

        fun deleteProperty(obj: Scriptable, name: String): Boolean {
            val base = getBase(obj, name) ?: return true
            base.delete(name)
            return !base.has(name, obj)
        }

        fun deleteProperty(obj: Scriptable, index: Int): Boolean {
            val base = getBase(obj, index) ?: return true
            base.delete(index)
            return !base.has(index, obj)
        }

        fun deleteProperty(obj: Scriptable, key: Symbol): Boolean {
            val base = getBase(obj, key) ?: return true
            val scriptable = ensureSymbolScriptable(base)
            scriptable.delete(key)
            return !scriptable.has(key, obj)
        }

        /** Every property name on [obj] and everything in its prototype chain, without repeats. */
        fun getPropertyIds(obj: Scriptable?): Array<Any?> {
            if (obj == null) return ScriptRuntime.emptyArgs
            var result = obj.getIds()
            var seen: LinkedHashSet<Any?>? = null
            var o = obj.prototype
            while (o != null) {
                val ids = o.getIds()
                if (ids.isNotEmpty()) {
                    if (seen == null) {
                        if (result.isEmpty()) {
                            result = ids
                            o = o.prototype
                            continue
                        }
                        seen = LinkedHashSet()
                        for (id in result) seen.add(id)
                    }
                    for (id in ids) seen.add(id)
                }
                o = o.prototype
            }
            return seen?.toTypedArray() ?: result
        }

        fun callMethod(cx: Context, obj: Scriptable, methodName: String, args: Array<Any?>): Any? {
            val funObj = getProperty(obj, methodName)
            if (funObj !is Function) throw ScriptRuntime.notFunctionError(obj, methodName)
            // The scope stored on the object is favoured over the function's own, which is more
            // useful under a dynamic scope setup.
            return funObj.call(cx, getTopLevelScope(obj), obj, args)
        }

        internal fun getBase(start: Scriptable, name: String): Scriptable? {
            var obj: Scriptable? = start
            do {
                if (obj!!.has(name, start)) break
                obj = obj.prototype
            } while (obj != null)
            return obj
        }

        internal fun getBase(start: Scriptable, index: Int): Scriptable? {
            var obj: Scriptable? = start
            do {
                if (obj!!.has(index, start)) break
                obj = obj.prototype
            } while (obj != null)
            return obj
        }

        internal fun getBase(start: Scriptable, key: Symbol): Scriptable? {
            var obj: Scriptable? = start
            do {
                if (ensureSymbolScriptable(obj!!).has(key, start)) break
                obj = obj.prototype
            } while (obj != null)
            return obj
        }

        /** The value parked under [key] on the nearest scope in the chain that has one. */
        fun getTopScopeValue(scope: Scriptable, key: Any): Any? {
            var s: Scriptable? = getTopLevelScope(scope)
            while (s != null) {
                if (s is ScriptableObject) {
                    s.getAssociatedValue(key)?.let { return it }
                }
                s = s.prototype
            }
            return null
        }

        // ---- Default value -------------------------------------------------------------------

        /**
         * OrdinaryToPrimitive: tries `toString` and `valueOf` in the order the hint asks for, and
         * takes the first one that gives back a primitive.
         */
        fun getDefaultValue(obj: Scriptable, typeHint: KClass<*>?): Any? {
            var cx: Context? = null
            for (i in 0..1) {
                val tryToString =
                    if (typeHint == ScriptRuntime.StringClass) i == 0 else i == 1
                val methodName = if (tryToString) "toString" else "valueOf"

                var v = getProperty(obj, methodName)
                if (v !is Function) continue
                if (cx == null) cx = Context.getContext()
                v = v.call(cx, v.declarationScope!!, obj, ScriptRuntime.emptyArgs)
                if (v != null) {
                    if (v !is Scriptable) return v
                    if (typeHint == ScriptRuntime.ScriptableClass ||
                        typeHint == ScriptRuntime.FunctionClass
                    ) {
                        return v
                    }
                    // A wrapped string counts as a primitive string.
                    if (tryToString && v is Wrapper) {
                        val u = v.unwrap()
                        if (u is String) return u
                    }
                }
            }
            val arg = typeHint?.simpleName ?: "undefined"
            throw ScriptRuntime.typeErrorById("msg.default.value", arg)
        }

        // ---- Slot upgrades -------------------------------------------------------------------

        private fun ensureAccessorSlot(
            name: Any?,
            index: Int,
            existing: Slot?,
            compoundOp: CompoundOperationMap,
            owner: SlotMapOwner?,
        ): AccessorSlot = when {
            existing == null -> AccessorSlot(name, index)
            existing is AccessorSlot -> existing
            else -> AccessorSlot(existing)
        }

        private fun ensureLazySlot(
            name: Any?,
            index: Int,
            existing: Slot?,
            compoundOp: CompoundOperationMap,
            owner: SlotMapOwner?,
        ): LazyLoadSlot = when {
            existing == null -> LazyLoadSlot(name, index)
            existing is LazyLoadSlot -> existing
            else -> LazyLoadSlot(existing)
        }

        private fun ensureLambdaSlot(
            name: Any?,
            index: Int,
            existing: Slot?,
            compoundOp: CompoundOperationMap,
            owner: SlotMapOwner?,
        ): LambdaSlot = when {
            existing == null -> LambdaSlot(name, index)
            existing is LambdaSlot -> existing
            else -> LambdaSlot(existing)
        }

        // ---- Built-in properties -------------------------------------------------------------

        fun <T : ScriptableObject> defineBuiltInProperty(
            owner: T,
            name: Any?,
            attributes: Int,
            getter: BuiltInSlot.Getter<T>,
        ) {
            owner.map.add(owner, BuiltInSlot(name, 0, attributes, owner, getter))
        }

        fun <T : ScriptableObject> defineBuiltInProperty(
            owner: T,
            name: String,
            attributes: Int,
            getter: BuiltInSlot.Getter<T>,
            setter: BuiltInSlot.Setter<T>,
        ) {
            owner.map.add(owner, BuiltInSlot(name, 0, attributes, owner, getter, setter))
        }

        fun <T : ScriptableObject> defineBuiltInProperty(
            owner: T,
            name: Any?,
            attributes: Int,
            getter: BuiltInSlot.Getter<T>,
            setter: BuiltInSlot.Setter<T>,
            attrSetter: BuiltInSlot.AttributeSetter<T>,
        ) {
            owner.map.add(owner, BuiltInSlot(name, 0, attributes, owner, getter, setter, attrSetter))
        }

        fun <T : ScriptableObject> defineBuiltInProperty(
            owner: T,
            name: String,
            attributes: Int,
            getter: BuiltInSlot.Getter<T>,
            setter: BuiltInSlot.Setter<T>,
            attrSetter: BuiltInSlot.AttributeSetter<T>,
            propDescSetter: BuiltInSlot.PropDescriptionSetter<T>,
        ) {
            owner.map.add(
                owner,
                BuiltInSlot(name, 0, attributes, owner, getter, setter, attrSetter, propDescSetter),
            )
        }

        /** Numeric ids sort ahead of everything else, in numeric order. */
        private val KEY_COMPARATOR = Comparator<Any?> { o1, o2 ->
            when {
                o1 is Int && o2 is Int -> o1.compareTo(o2)
                o1 is Int -> -1
                o2 is Int -> 1
                else -> 0
            }
        }
    }
}
