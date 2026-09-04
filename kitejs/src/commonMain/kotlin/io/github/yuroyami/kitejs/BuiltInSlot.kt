/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * A [Slot] for a property of a built-in object that gets created very often. It exposes a field on
 * a native object through plain functions, and lets the owner take over attribute changes and
 * redefinition (array `length` behaves unusually on both counts).
 *
 * The descriptor it produces is a plain data descriptor, because the properties worth putting here
 * do not necessarily have getter and setter functions.
 *
 * The owner is held in the slot's `value` field, which a built-in slot never uses for a real value.
 * That is a deliberate trade: the slot API does not pass the owning map's owner in, and keeping it
 * here avoids allocating anything per access.
 */
class BuiltInSlot<T : ScriptableObject> : Slot {

    fun interface Getter<U : ScriptableObject> {
        fun apply(builtIn: U, start: Scriptable?): Any?
    }

    fun interface Setter<U : ScriptableObject> {
        fun apply(
            builtIn: U,
            value: Any?,
            owner: Scriptable,
            start: Scriptable,
            isThrow: Boolean,
        ): Boolean
    }

    fun interface AttributeSetter<U : ScriptableObject> {
        fun apply(builtIn: U, attributes: Int)
    }

    fun interface PropDescriptionSetter<U : ScriptableObject> {
        fun apply(
            builtIn: U,
            current: BuiltInSlot<U>,
            id: Any?,
            info: ScriptableObject.DescriptorInfo,
            checkValid: Boolean,
            key: Any?,
            index: Int,
        ): Boolean
    }

    private val getter: Getter<T>
    private val setter: Setter<T>
    private val attrUpdater: AttributeSetter<T>
    private val propDescSetter: PropDescriptionSetter<T>

    internal constructor(
        name: Any?,
        index: Int,
        attr: Int,
        builtIn: T,
        getter: Getter<T>,
        setter: Setter<T> = Setter { _, _, _, _, _ -> true },
        attrUpdater: AttributeSetter<T> = AttributeSetter { _, _ -> },
        propDescSetter: PropDescriptionSetter<T> = defaultPropDescSetter(),
    ) : super(name, index, attr) {
        this.value = builtIn
        this.getter = getter
        this.setter = setter
        this.attrUpdater = attrUpdater
        this.propDescSetter = propDescSetter
    }

    internal constructor(slot: BuiltInSlot<T>) : super(slot) {
        getter = slot.getter
        setter = slot.setter
        attrUpdater = slot.attrUpdater
        propDescSetter = slot.propDescSetter
    }

    @Suppress("UNCHECKED_CAST")
    private val builtIn: T
        get() = value as T

    override fun copySlot(): Slot = BuiltInSlot(this).also {
        it.next = null
        it.orderedNext = null
    }

    override fun getValue(start: Scriptable?): Any? = getter.apply(builtIn, start)

    override fun setValue(value: Any?, owner: Scriptable, start: Scriptable, isThrow: Boolean): Boolean {
        if ((attributes and ScriptableObject.READONLY) != 0) {
            if (isThrow) throw ScriptRuntime.typeErrorById("msg.modify.readonly", name)
            return true
        }
        if (owner === start) return setter.apply(builtIn, value, owner, start, isThrow)
        return false
    }

    /** Writing through a property descriptor skips the readonly check, so it gets its own path. */
    fun setValueFromDescriptor(value: Any?, owner: Scriptable, start: Scriptable, isThrow: Boolean) {
        setter.apply(builtIn, value, owner, start, isThrow)
    }

    override var attributes: Int
        get() = super.attributes
        set(value) {
            attrUpdater.apply(builtIn, value)
            super.attributes = value
        }

    override fun getPropertyDescriptor(cx: Context, scope: Scriptable): ScriptableObject.DescriptorInfo =
        ScriptableObject.buildDataDescriptor(getValue(builtIn), attributes)

    internal fun applyNewDescriptor(
        id: Any?,
        info: ScriptableObject.DescriptorInfo,
        checkValid: Boolean,
        key: Any?,
        index: Int,
    ): Boolean = propDescSetter.apply(builtIn, this, id, info, checkValid, key, index)

    companion object {
        private fun <T : ScriptableObject> defaultPropDescSetter(): PropDescriptionSetter<T> =
            PropDescriptionSetter { builtIn, _, id, info, checkValid, key, index ->
                builtIn.startCompoundOp(true).use { map ->
                    ScriptableObject.defineOrdinaryProperty(
                        ScriptableObject::setSlotValue,
                        builtIn,
                        map,
                        id,
                        info,
                        checkValid,
                        key,
                        index,
                    )
                }
            }
    }
}
