/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * A [Slot] whose value comes from calling a Kotlin lambda. To script it looks like any ordinary
 * value property, which is what separates it from [AccessorSlot]: the descriptor shows a plain
 * value, not a getter and setter pair.
 *
 * This is how a native property gets implemented without reflection.
 */
class LambdaSlot : Slot {

    internal constructor(name: Any?, index: Int) : super(name, index, 0)

    internal constructor(oldSlot: Slot) : super(oldSlot)

    internal var getter: (() -> Any?)? = null
    internal var setter: ((Any?) -> Unit)? = null

    override fun copySlot(): LambdaSlot = LambdaSlot(this).also {
        it.value = value
        it.getter = getter
        it.setter = setter
        it.next = null
        it.orderedNext = null
    }

    override val isValueSlot: Boolean
        get() = false

    override val isSetterSlot: Boolean
        get() = false

    override fun getPropertyDescriptor(cx: Context, scope: Scriptable): ScriptableObject.DescriptorInfo {
        val g = getter
        return ScriptableObject.DescriptorInfo(if (g == null) value else g(), attributes, true)
    }

    override fun setValue(value: Any?, owner: Scriptable, start: Scriptable, isThrow: Boolean): Boolean {
        val s = setter ?: return super.setValue(value, owner, start, isThrow)
        if (owner === start) {
            s(value)
            return true
        }
        return false
    }

    override fun getValue(start: Scriptable?): Any? {
        val g = getter ?: return super.getValue(start)
        return g()
    }
}
