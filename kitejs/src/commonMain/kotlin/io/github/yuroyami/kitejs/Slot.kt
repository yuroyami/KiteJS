/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * One property of a [ScriptableObject]. This base class is an ordinary property holding a value;
 * the subclasses cover the ones backed by getters and setters.
 */
open class Slot {

    /** The property key: a string, a [Symbol], or null when the property is an index. */
    internal var name: Any?

    /** The key's hash, or the index itself when [name] is null. Caching changes it. */
    internal var indexOrHash: Int

    private var attributesField: Short

    internal var value: Any? = null

    /** Next slot in the same hash bucket. */
    internal var next: Slot? = null

    /** Next slot in definition order. */
    internal var orderedNext: Slot? = null

    internal constructor(name: Any?, index: Int, attributes: Int) {
        this.name = name
        this.indexOrHash = if (name == null) index else name.hashCode()
        this.attributesField = attributes.toShort()
    }

    internal constructor(oldSlot: Slot) {
        name = oldSlot.name
        indexOrHash = oldSlot.indexOrHash
        attributesField = oldSlot.attributesField
        value = oldSlot.value
        next = oldSlot.next
        orderedNext = oldSlot.orderedNext
    }

    internal open fun copySlot(): Slot = Slot(this).also {
        it.next = null
        it.orderedNext = null
    }

    /**
     * True only for this base class. Upstream notes that too much code breaks if this is done any
     * other way.
     */
    internal open val isValueSlot: Boolean
        get() = true

    /** True for a setter slot, which some legacy paths need to know about. */
    internal open val isSetterSlot: Boolean
        get() = false

    internal open var attributes: Int
        get() = attributesField.toInt()
        set(value) {
            ScriptableObject.checkValidAttributes(value)
            attributesField = value.toShort()
        }

    fun setValue(value: Any?, owner: Scriptable, start: Scriptable): Boolean =
        setValue(value, owner, start, Context.isCurrentContextStrict())

    open fun setValue(value: Any?, owner: Scriptable, start: Scriptable, isThrow: Boolean): Boolean {
        if ((attributes and ScriptableObject.READONLY) != 0) {
            if (isThrow) throw ScriptRuntime.typeErrorById("msg.modify.readonly", name)
            return true
        }
        if (owner === start) {
            this.value = value
            return true
        }
        return false
    }

    open fun getValue(start: Scriptable?): Any? = value

    internal open fun getPropertyDescriptor(cx: Context, scope: Scriptable): ScriptableObject.DescriptorInfo =
        ScriptableObject.buildDataDescriptor(value, attributes)

    protected fun throwNoSetterException(start: Scriptable, newValue: Any?) {
        val cx = Context.getContext()
        // TC39 ES3.1 draft of 9 Feb 2009, 8.12.4 step 2 says this is a TypeError.
        if (cx.isStrictMode() || cx.hasFeature(Context.FEATURE_STRICT_MODE)) {
            val prop = if (name != null) "[${start.className}].$name" else ""
            throw ScriptRuntime.typeErrorById(
                "msg.set.prop.no.setter",
                prop,
                Context.toString(newValue),
            )
        }
    }

    /** The setter as a script function, or null when there is none. Used by legacy paths. */
    internal open fun getSetterFunction(name: String?, scope: Scriptable): Function? = null

    /** The getter as a script function, or null when there is none. */
    internal open fun getGetterFunction(name: String?, scope: Scriptable): Function? = null

    /**
     * Whether the setter is the given function. Asking this instead of building the function object
     * avoids creating one that would only be thrown away: a cached function that has not been built
     * yet cannot be the one passed in.
     */
    internal open fun isSameSetterFunction(function: Any?): Boolean = false

    /** The getter counterpart of [isSameSetterFunction]. */
    internal open fun isSameGetterFunction(function: Any?): Boolean = false
}
