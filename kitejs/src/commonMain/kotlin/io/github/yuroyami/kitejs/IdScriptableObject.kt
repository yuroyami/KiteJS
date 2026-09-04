/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The base for a native object that exposes its methods through [IdFunctionObject]s hung off its
 * prototype.
 *
 * A subclass implements at least `findInstanceIdInfo`, `getInstanceIdName`, `execIdCall` and the
 * prototype id lookups. For plain value properties it overrides `getInstanceIdValue` and
 * `setInstanceIdValue`. To customise how the constructor and prototype are built it overrides
 * `fillConstructorProperties`.
 */
abstract class IdScriptableObject : ScriptableObject, IdFunctionCall {

    private var prototypeValues: PrototypeValues? = null

    /** The prototype's id-indexed slots, built lazily one id at a time. */
    private class PrototypeValues(private val obj: IdScriptableObject, private val maxId: Int) {

        private var valueArray: Array<Any?>? = null
        private var attributeArray: ShortArray? = null
        var constructorId: Int = 0
        private var constructor: IdFunctionObject? = null
        private var constructorAttrs: Short = 0

        init {
            require(maxId >= 1) { "maxId < 1" }
        }

        fun getMaxId(): Int = maxId

        fun initValue(id: Int, name: String, value: Any?, attributes: Int) {
            require(id in 1..maxId) { "!(1 <= id && id <= maxId)" }
            require(value !== Scriptable.NOT_FOUND) { "value == NOT_FOUND" }
            checkValidAttributes(attributes)
            require(obj.findPrototypeId(name) == id) { name }
            if (id == constructorId) {
                require(value is IdFunctionObject) { "constructor should be initialized with IdFunctionObject" }
                constructor = value
                constructorAttrs = attributes.toShort()
                return
            }
            initSlot(id, name, value, attributes)
        }

        fun initValue(id: Int, key: Symbol, value: Any?, attributes: Int) {
            require(id in 1..maxId) { "!(1 <= id && id <= maxId)" }
            require(value !== Scriptable.NOT_FOUND) { "value == NOT_FOUND" }
            checkValidAttributes(attributes)
            require(obj.findPrototypeId(key) == id) { key.toString() }
            if (id == constructorId) {
                require(value is IdFunctionObject) { "constructor should be initialized with IdFunctionObject" }
                constructor = value
                constructorAttrs = attributes.toShort()
                return
            }
            initSlot(id, key, value, attributes)
        }

        private fun initSlot(id: Int, name: Any, value: Any?, attributes: Int) {
            val array = valueArray ?: throw IllegalStateException()
            val v = value ?: UniqueTag.NULL_VALUE
            val index = (id - 1) * SLOT_SPAN
            val value2 = array[index]
            if (value2 == null) {
                array[index] = v
                array[index + NAME_SLOT] = name
                attributeArray!![id - 1] = attributes.toShort()
            } else {
                check(name == array[index + NAME_SLOT])
            }
        }

        fun createPrecachedConstructor(): IdFunctionObject {
            check(constructorId == 0)
            constructorId = obj.findPrototypeId("constructor")
            check(constructorId != 0) { "No id for constructor property" }
            obj.initPrototypeId(constructorId)
            val ctor = constructor
                ?: throw IllegalStateException(
                    "${obj::class.simpleName}.initPrototypeId() did not initialize id=$constructorId",
                )
            ctor.initFunction(obj.className, getTopLevelScope(obj))
            ctor.markAsConstructor(obj)
            return ctor
        }

        fun findId(name: String): Int = obj.findPrototypeId(name)

        fun findId(key: Symbol): Int = obj.findPrototypeId(key)

        fun has(id: Int): Boolean {
            val array = valueArray ?: return true
            val value = array[(id - 1) * SLOT_SPAN] ?: return true
            return value !== Scriptable.NOT_FOUND
        }

        fun get(id: Int): Any? {
            val value = ensureId(id)
            return if (value === UniqueTag.NULL_VALUE) null else value
        }

        fun set(id: Int, start: Scriptable, value: Any?) {
            require(value !== Scriptable.NOT_FOUND) { "value == NOT_FOUND" }
            ensureId(id)
            val attr = attributeArray!![id - 1].toInt()
            if ((attr and READONLY) != 0) return
            if (start === obj) {
                valueArray!![(id - 1) * SLOT_SPAN] = value ?: UniqueTag.NULL_VALUE
            } else {
                val name = valueArray!![(id - 1) * SLOT_SPAN + NAME_SLOT]
                if (name is Symbol) {
                    if (start is SymbolScriptable) start.put(name, start, value)
                } else {
                    start.put(name as String, start, value)
                }
            }
        }

        fun delete(id: Int) {
            ensureId(id)
            val attr = attributeArray!![id - 1].toInt()
            if ((attr and PERMANENT) != 0) {
                if (Context.getContext().isStrictMode()) {
                    val name = when (val n = valueArray!![(id - 1) * SLOT_SPAN + NAME_SLOT]) {
                        is String -> n
                        is Symbol -> n.toString()
                        else -> null
                    }
                    throw ScriptRuntime.typeErrorById("msg.delete.property.with.configurable.false", name)
                }
            } else {
                valueArray!![(id - 1) * SLOT_SPAN] = Scriptable.NOT_FOUND
                attributeArray!![id - 1] = EMPTY.toShort()
            }
        }

        fun getAttributes(id: Int): Int {
            ensureId(id)
            return attributeArray!![id - 1].toInt()
        }

        fun setAttributes(id: Int, attributes: Int) {
            checkValidAttributes(attributes)
            ensureId(id)
            attributeArray!![id - 1] = attributes.toShort()
        }

        fun getNames(getAll: Boolean, getSymbols: Boolean, extraEntries: Array<Any?>?): Array<Any?>? {
            var names: Array<Any?>? = null
            var count = 0
            for (id in 1..maxId) {
                val value = ensureId(id)
                if (getAll || (attributeArray!![id - 1].toInt() and DONTENUM) == 0) {
                    if (value !== Scriptable.NOT_FOUND) {
                        val name = valueArray!![(id - 1) * SLOT_SPAN + NAME_SLOT]
                        if (name is String) {
                            if (names == null) names = arrayOfNulls(maxId)
                            names[count++] = name
                        } else if (getSymbols && name is Symbol) {
                            if (names == null) names = arrayOfNulls(maxId)
                            names[count++] = name.toString()
                        }
                    }
                }
            }
            if (count == 0) return extraEntries
            if (extraEntries == null || extraEntries.isEmpty()) {
                return if (count != names!!.size) names.copyOf(count) else names
            }
            val extra = extraEntries.size
            val tmp = arrayOfNulls<Any?>(extra + count)
            extraEntries.copyInto(tmp, 0)
            names!!.copyInto(tmp, extra, 0, count)
            return tmp
        }

        private fun ensureId(id: Int): Any? {
            var array = valueArray
            if (array == null) {
                array = arrayOfNulls(maxId * SLOT_SPAN)
                valueArray = array
                attributeArray = ShortArray(maxId)
            }
            val valueSlot = (id - 1) * SLOT_SPAN
            var value = array[valueSlot]
            if (value == null) {
                if (id == constructorId) {
                    initSlot(constructorId, "constructor", constructor, constructorAttrs.toInt())
                    // Let the constructor be collected once the slot holds it.
                    constructor = null
                } else {
                    obj.initPrototypeId(id)
                }
                value = array[valueSlot]
                    ?: throw IllegalStateException(
                        "${obj::class.simpleName}.initPrototypeId(int id) did not initialize id=$id",
                    )
            }
            return value
        }

        private companion object {
            const val NAME_SLOT = 1
            const val SLOT_SPAN = 2
        }
    }

    constructor() : super()

    constructor(scope: Scriptable, prototype: Scriptable?) : super(scope, prototype)

    protected fun defaultHas(name: String): Boolean = super.has(name, this)

    protected fun defaultGet(name: String): Any? = super.get(name, this)

    protected fun defaultPut(name: String, value: Any?) {
        super.put(name, this, value)
    }

    override fun has(name: String, start: Scriptable): Boolean {
        val info = findInstanceIdInfo(name)
        if (info != 0) {
            val attr = info ushr 16
            if ((attr and PERMANENT) != 0) return true
            return Scriptable.NOT_FOUND !== getInstanceIdValue(info and 0xFFFF)
        }
        prototypeValues?.let { pv ->
            val id = pv.findId(name)
            if (id != 0) return pv.has(id)
        }
        return super.has(name, start)
    }

    override fun has(key: Symbol, start: Scriptable): Boolean {
        val info = findInstanceIdInfo(key)
        if (info != 0) {
            val attr = info ushr 16
            if ((attr and PERMANENT) != 0) return true
            return Scriptable.NOT_FOUND !== getInstanceIdValue(info and 0xFFFF)
        }
        prototypeValues?.let { pv ->
            val id = pv.findId(key)
            if (id != 0) return pv.has(id)
        }
        return super.has(key, start)
    }

    override fun get(name: String, start: Scriptable): Any? {
        var value = super.get(name, start)
        if (value !== Scriptable.NOT_FOUND) return value
        val info = findInstanceIdInfo(name)
        if (info != 0) {
            value = getInstanceIdValue(info and 0xFFFF)
            if (value !== Scriptable.NOT_FOUND) return value
        }
        prototypeValues?.let { pv ->
            val id = pv.findId(name)
            if (id != 0) {
                value = pv.get(id)
                if (value !== Scriptable.NOT_FOUND) return value
            }
        }
        return Scriptable.NOT_FOUND
    }

    override fun get(key: Symbol, start: Scriptable): Any? {
        var value = super.get(key, start)
        if (value !== Scriptable.NOT_FOUND) return value
        val info = findInstanceIdInfo(key)
        if (info != 0) {
            value = getInstanceIdValue(info and 0xFFFF)
            if (value !== Scriptable.NOT_FOUND) return value
        }
        prototypeValues?.let { pv ->
            val id = pv.findId(key)
            if (id != 0) {
                value = pv.get(id)
                if (value !== Scriptable.NOT_FOUND) return value
            }
        }
        return Scriptable.NOT_FOUND
    }

    override fun put(name: String, start: Scriptable, value: Any?) {
        val info = findInstanceIdInfo(name)
        if (info != 0) {
            if (start === this && isSealed) throw Context.reportRuntimeErrorById("msg.modify.sealed", name)
            val attr = info ushr 16
            if ((attr and READONLY) == 0) {
                if (start === this) setInstanceIdValue(info and 0xFFFF, value)
                else start.put(name, start, value)
            }
            return
        }
        prototypeValues?.let { pv ->
            val id = pv.findId(name)
            if (id != 0) {
                if (start === this && isSealed) throw Context.reportRuntimeErrorById("msg.modify.sealed", name)
                pv.set(id, start, value)
                return
            }
        }
        super.put(name, start, value)
    }

    override fun put(key: Symbol, start: Scriptable, value: Any?) {
        val info = findInstanceIdInfo(key)
        if (info != 0) {
            if (start === this && isSealed) throw Context.reportRuntimeErrorById("msg.modify.sealed")
            val attr = info ushr 16
            if ((attr and READONLY) == 0) {
                if (start === this) setInstanceIdValue(info and 0xFFFF, value)
                else ensureSymbolScriptable(start).put(key, start, value)
            }
            return
        }
        prototypeValues?.let { pv ->
            val id = pv.findId(key)
            if (id != 0) {
                if (start === this && isSealed) throw Context.reportRuntimeErrorById("msg.modify.sealed")
                pv.set(id, start, value)
                return
            }
        }
        super.put(key, start, value)
    }

    override fun delete(name: String) {
        val info = findInstanceIdInfo(name)
        if (info != 0) {
            if (!isSealed) {
                val attr = info ushr 16
                if ((attr and PERMANENT) != 0) {
                    if (Context.getContext().isStrictMode()) {
                        throw ScriptRuntime.typeErrorById("msg.delete.property.with.configurable.false", name)
                    }
                } else {
                    setInstanceIdValue(info and 0xFFFF, Scriptable.NOT_FOUND)
                }
                return
            }
        }
        prototypeValues?.let { pv ->
            val id = pv.findId(name)
            if (id != 0) {
                if (!isSealed) pv.delete(id)
                return
            }
        }
        super.delete(name)
    }

    override fun delete(key: Symbol) {
        val info = findInstanceIdInfo(key)
        if (info != 0) {
            if (!isSealed) {
                val attr = info ushr 16
                if ((attr and PERMANENT) != 0) {
                    if (Context.getContext().isStrictMode()) {
                        throw ScriptRuntime.typeErrorById("msg.delete.property.with.configurable.false")
                    }
                } else {
                    setInstanceIdValue(info and 0xFFFF, Scriptable.NOT_FOUND)
                }
                return
            }
        }
        prototypeValues?.let { pv ->
            val id = pv.findId(key)
            if (id != 0) {
                if (!isSealed) pv.delete(id)
                return
            }
        }
        super.delete(key)
    }

    override fun getAttributes(name: String): Int {
        val info = findInstanceIdInfo(name)
        if (info != 0) return info ushr 16
        prototypeValues?.let { pv ->
            val id = pv.findId(name)
            if (id != 0) return pv.getAttributes(id)
        }
        return super.getAttributes(name)
    }

    override fun getAttributes(sym: Symbol): Int {
        val info = findInstanceIdInfo(sym)
        if (info != 0) return info ushr 16
        prototypeValues?.let { pv ->
            val id = pv.findId(sym)
            if (id != 0) return pv.getAttributes(id)
        }
        return super.getAttributes(sym)
    }

    override fun setAttributes(name: String, attributes: Int) {
        checkValidAttributes(attributes)
        val info = findInstanceIdInfo(name)
        if (info != 0) {
            val currentAttributes = info ushr 16
            if (attributes != currentAttributes) setInstanceIdAttributes(info and 0xFFFF, attributes)
            return
        }
        prototypeValues?.let { pv ->
            val id = pv.findId(name)
            if (id != 0) {
                pv.setAttributes(id, attributes)
                return
            }
        }
        super.setAttributes(name, attributes)
    }

    override fun getIds(map: CompoundOperationMap, getNonEnumerable: Boolean, getSymbols: Boolean): Array<Any?> {
        var result = super.getIds(map, getNonEnumerable, getSymbols)
        prototypeValues?.let { result = it.getNames(getNonEnumerable, getSymbols, result)!! }
        val maxInstanceId = getMaxInstanceId()
        if (maxInstanceId != 0) {
            var ids: Array<Any?>? = null
            var count = 0
            var id = maxInstanceId
            while (id != 0) {
                val name = getInstanceIdName(id)
                val info = findInstanceIdInfo(name)
                if (info != 0) {
                    val attr = info ushr 16
                    if ((attr and PERMANENT) == 0 && Scriptable.NOT_FOUND === getInstanceIdValue(id)) {
                        --id
                        continue
                    }
                    if (getNonEnumerable || (attr and DONTENUM) == 0) {
                        if (count == 0) ids = arrayOfNulls(id)
                        ids!![count++] = name
                    }
                }
                --id
            }
            if (count != 0) {
                result =
                    if (result.isEmpty() && ids!!.size == count) ids
                    else {
                        val tmp = arrayOfNulls<Any?>(result.size + count)
                        result.copyInto(tmp, 0)
                        ids!!.copyInto(tmp, result.size, 0, count)
                        tmp
                    }
            }
        }
        return result
    }

    /** The largest id [findInstanceIdInfo] can return. */
    protected open fun getMaxInstanceId(): Int = 0

    /** Maps a name to its instance id, or 0. The result comes from [instanceIdInfo]. */
    protected open fun findInstanceIdInfo(name: String): Int = 0

    protected open fun findInstanceIdInfo(key: Symbol): Int = 0

    /** Maps an instance id back to its name. */
    protected open fun getInstanceIdName(id: Int): String = throw IllegalArgumentException("$id")

    protected open fun getInstanceIdValue(id: Int): Any? = throw IllegalStateException("$id")

    /** Sets or, with [Scriptable.NOT_FOUND], deletes an instance value. */
    protected open fun setInstanceIdValue(id: Int, value: Any?) {
        throw IllegalStateException("$id")
    }

    /**
     * Changes an instance property's attributes. A class that wants `Object.defineProperty` to
     * work on its instance properties overrides this; the default refuses.
     */
    protected open fun setInstanceIdAttributes(id: Int, attr: Int) {
        throw ScriptRuntime.constructError(
            "InternalError",
            "Changing attributes not supported for $className ${getInstanceIdName(id)} property",
        )
    }

    override fun execIdCall(f: IdFunctionObject, cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
        throw f.unknown()

    fun exportAsJSClass(maxPrototypeId: Int, scope: Scriptable?, sealed: Boolean): IdFunctionObject {
        if (scope !== this && scope != null) {
            parentScope = scope
            prototype = getObjectPrototype(scope)
        }
        activatePrototypeMap(maxPrototypeId)
        val ctor = prototypeValues!!.createPrecachedConstructor()
        if (sealed) sealObject()
        fillConstructorProperties(ctor)
        if (sealed) ctor.sealObject()
        ctor.exportAsScopeProperty()
        return ctor
    }

    fun hasPrototypeMap(): Boolean = prototypeValues != null

    fun activatePrototypeMap(maxPrototypeId: Int) {
        check(prototypeValues == null)
        prototypeValues = PrototypeValues(this, maxPrototypeId)
    }

    fun initPrototypeMethod(tag: Any?, id: Int, name: String, arity: Int): IdFunctionObject =
        initPrototypeMethod(tag, id, name, name, arity)

    fun initPrototypeMethod(tag: Any?, id: Int, propertyName: String, functionName: String?, arity: Int): IdFunctionObject {
        val scope = getTopLevelScope(this)
        val function = newIdFunction(tag, id, functionName ?: propertyName, arity, scope)
        prototypeValues!!.initValue(id, propertyName, function, DONTENUM)
        return function
    }

    fun initPrototypeMethod(tag: Any?, id: Int, key: Symbol, functionName: String, arity: Int): IdFunctionObject {
        val scope = getTopLevelScope(this)
        val function = newIdFunction(tag, id, functionName, arity, scope)
        prototypeValues!!.initValue(id, key, function, DONTENUM)
        return function
    }

    fun initPrototypeMethod(tag: Any?, id: Int, key: Symbol, functionName: String, arity: Int, attributes: Int): IdFunctionObject {
        val scope = getTopLevelScope(this)
        val function = newIdFunction(tag, id, functionName, arity, scope)
        prototypeValues!!.initValue(id, key, function, attributes)
        return function
    }

    fun initPrototypeConstructor(f: IdFunctionObject) {
        val id = prototypeValues!!.constructorId
        check(id != 0)
        require(f.methodId() == id)
        if (isSealed) f.sealObject()
        prototypeValues!!.initValue(id, "constructor", f, DONTENUM)
    }

    fun initPrototypeValue(id: Int, name: String, value: Any?, attributes: Int) {
        prototypeValues!!.initValue(id, name, value, attributes)
    }

    fun initPrototypeValue(id: Int, key: Symbol, value: Any?, attributes: Int) {
        prototypeValues!!.initValue(id, key, value, attributes)
    }

    protected open fun initPrototypeId(id: Int) {
        throw IllegalStateException("$id")
    }

    protected open fun findPrototypeId(name: String): Int = throw IllegalStateException(name)

    protected open fun findPrototypeId(key: Symbol): Int = 0

    protected open fun fillConstructorProperties(ctor: IdFunctionObject) {}

    protected fun addIdFunctionProperty(obj: Scriptable, tag: Any?, id: Int, name: String, arity: Int) {
        val scope = getTopLevelScope(obj)
        newIdFunction(tag, id, name, arity, scope).addAsProperty(obj)
    }

    private fun newIdFunction(tag: Any?, id: Int, name: String, arity: Int, scope: Scriptable): IdFunctionObject {
        val function = IdFunctionObject(this, tag, id, name, arity, scope)
        if (isSealed) function.sealObject()
        return function
    }

    override fun defineOwnProperty(cx: Context, id: Any?, desc: DescriptorInfo, checkValid: Boolean): Boolean {
        if (id is CharSequence) {
            val name = id.toString()
            val info = findInstanceIdInfo(name)
            if (info != 0) {
                val instanceId = info and 0xFFFF
                if (desc.isAccessorDescriptor()) {
                    // Upstream passes the id where an index is expected. Copied as written.
                    delete(instanceId)
                } else {
                    checkPropertyDefinition(desc)
                    val slot = queryOrFakeSlot(cx, id)
                    checkPropertyChangeForSlot(name, slot, desc)
                    var attr = info ushr 16
                    val value = desc.value
                    if (value !== Scriptable.NOT_FOUND && ((attr and READONLY) == 0 || (attr and PERMANENT) == 0)) {
                        val currentValue = getInstanceIdValue(instanceId)
                        if (!sameValue(value, currentValue)) setInstanceIdValue(instanceId, value)
                    }
                    attr = applyDescriptorToAttributeBitset(attr, desc.enumerable, desc.writable, desc.configurable)
                    setAttributes(name, attr)
                    return true
                }
            }
            prototypeValues?.let { pv ->
                val pid = pv.findId(name)
                if (pid != 0) {
                    if (desc.isAccessorDescriptor()) {
                        pv.delete(pid)
                    } else {
                        checkPropertyDefinition(desc)
                        val slot = queryOrFakeSlot(cx, id)
                        checkPropertyChangeForSlot(name, slot, desc)
                        val attr = pv.getAttributes(pid)
                        val value = desc.value
                        if (value !== Scriptable.NOT_FOUND && (attr and READONLY) == 0) {
                            val currentValue = pv.get(pid)
                            if (!sameValue(value, currentValue)) pv.set(pid, this, value)
                        }
                        pv.setAttributes(
                            pid,
                            applyDescriptorToAttributeBitset(attr, desc.enumerable, desc.writable, desc.configurable),
                        )
                        if (super.has(name, this)) super.delete(name)
                        return true
                    }
                }
            }
        }
        return super.defineOwnProperty(cx, id, desc, checkValid)
    }

    override fun getOwnPropertyDescriptor(cx: Context, id: Any?): DescriptorInfo? {
        val desc = super.getOwnPropertyDescriptor(cx, id)
        if (desc == null) {
            if (id is String) return getBuiltInDataDescriptor(id)
            if (ScriptRuntime.isSymbol(id)) {
                // TODO(P4): a NativeSymbol resolves to its key here.
                if (id is SymbolKey) return getBuiltInDataDescriptor(id)
            }
        }
        return desc
    }

    private fun queryOrFakeSlot(cx: Context, id: Any?): Slot? {
        val slot = querySlot(cx, id)
        if (slot == null) {
            if (id is String) return getBuiltInSlot(id)
            if (ScriptRuntime.isSymbol(id)) {
                // TODO(P4): a NativeSymbol resolves to its key here.
                if (id is SymbolKey) return getBuiltInSlot(id)
            }
        }
        return slot
    }

    private fun getBuiltInDataDescriptor(name: String): DescriptorInfo? {
        val slot = getBuiltInSlot(name) ?: return null
        return DescriptorInfo(slot.value, slot.attributes, true)
    }

    private fun getBuiltInSlot(name: String): Slot? {
        val info = findInstanceIdInfo(name)
        if (info != 0) {
            val slot = Slot(name, 0, info ushr 16)
            slot.value = getInstanceIdValue(info and 0xFFFF)
            return slot
        }
        prototypeValues?.let { pv ->
            val id = pv.findId(name)
            if (id != 0) {
                val slot = Slot(name, 0, pv.getAttributes(id))
                slot.value = pv.get(id)
                return slot
            }
        }
        return null
    }

    private fun getBuiltInDataDescriptor(key: Symbol): DescriptorInfo? {
        val slot = getBuiltInSlot(key) ?: return null
        return DescriptorInfo(slot.value, slot.attributes, true)
    }

    private fun getBuiltInSlot(key: Symbol): Slot? {
        prototypeValues?.let { pv ->
            val id = pv.findId(key)
            if (id != 0) {
                val slot = Slot(key, 0, pv.getAttributes(id))
                slot.value = pv.get(id)
                return slot
            }
        }
        return null
    }

    companion object {
        /** Packs attributes and an id into the int [findInstanceIdInfo] returns. */
        fun instanceIdInfo(attributes: Int, id: Int): Int = (attributes shl 16) or id

        /** [ensureType] that names the function complaining. */
        inline fun <reified T : Any> ensureType(obj: Any?, f: IdFunctionObject): T =
            ensureType(obj, f.getFunctionName())
    }
}
