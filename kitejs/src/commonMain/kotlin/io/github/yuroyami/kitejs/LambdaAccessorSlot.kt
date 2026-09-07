/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * Like [LambdaSlot], but the lambdas also get the owning object, so the property can read and write
 * the owner's own fields. That is what makes it usable for a native property that has to behave
 * like a normal JavaScript property without reflection.
 */
public class LambdaAccessorSlot : Slot {

    private var getter: ScriptableObject.LambdaGetterFunction? = null
    private var setter: ScriptableObject.LambdaSetterFunction? = null
    private var getterFunction: LambdaFunction? = null
    private var setterFunction: LambdaFunction? = null

    internal constructor(name: Any?, index: Int) : super(name, index, 0)

    internal constructor(oldSlot: Slot) : super(oldSlot)

    internal fun asAccessorSlot(): AccessorSlot {
        val accessorSlot = AccessorSlot(this)
        getterFunction?.let { accessorSlot.getter = AccessorSlot.FunctionGetter(it) }
        setterFunction?.let { accessorSlot.setter = AccessorSlot.FunctionSetter(it) }
        return accessorSlot
    }

    override fun copySlot(): LambdaAccessorSlot = LambdaAccessorSlot(this).also {
        it.value = value
        it.getter = getter
        it.setter = setter
        it.getterFunction = getterFunction
        it.setterFunction = setterFunction
        it.next = null
        it.orderedNext = null
    }

    override val isValueSlot: Boolean
        get() = false

    override val isSetterSlot: Boolean
        get() = true

    override fun getPropertyDescriptor(cx: Context, scope: Scriptable): ScriptableObject.DescriptorInfo =
        buildPropertyDescriptor(cx)

    /**
     * Same as [getPropertyDescriptor] without the scope. The scope is genuinely unused here, and
     * saying so keeps callers from reaching for one they should not use.
     */
    public fun buildPropertyDescriptor(cx: Context): ScriptableObject.DescriptorInfo {
        val attr = attributes
        val es6 = cx.languageVersion >= Context.VERSION_ES6
        val desc: ScriptableObject.DescriptorInfo
        if (es6) {
            desc = ScriptableObject.DescriptorInfo(Scriptable.NOT_FOUND, attr, false)
            if (getterFunction == null && setterFunction == null) {
                desc.writable = (attr and ScriptableObject.READONLY) == 0
            }
        } else {
            desc = ScriptableObject.DescriptorInfo(
                Scriptable.NOT_FOUND,
                attr,
                getterFunction == null && setterFunction == null,
            )
        }
        getterFunction?.let { desc.getter = it }
        when {
            setterFunction != null -> desc.setter = setterFunction
            es6 -> desc.setter = Undefined.instance
        }
        if (es6) {
            desc.enumerable = (attr and ScriptableObject.DONTENUM) == 0
            desc.configurable = (attr and ScriptableObject.PERMANENT) == 0
        }
        return desc
    }

    override fun setValue(value: Any?, owner: Scriptable, start: Scriptable, isThrow: Boolean): Boolean {
        val s = setter
        if (s == null) {
            if (getter != null) {
                throwNoSetterException(start, value)
                return true
            }
        } else {
            s.accept(start, value)
            return true
        }
        // Upstream passes start twice here, not the owner.
        return super.setValue(value, start, start, isThrow)
    }

    override fun getValue(start: Scriptable?): Any? {
        val g = getter ?: return super.getValue(start)
        return g.apply(start)
    }

    public fun setGetter(scope: Scriptable, getter: ScriptableObject.LambdaGetterFunction?) {
        this.getter = getter
        if (getter != null) {
            getterFunction = LambdaFunction(
                scope,
                "get " + this.name,
                0,
                { _, _, thisObj, _ -> getter.apply(thisObj) },
            )
        }
    }

    public fun setSetter(scope: Scriptable, setter: ScriptableObject.LambdaSetterFunction?) {
        this.setter = setter
        if (setter != null) {
            setterFunction = LambdaFunction(
                scope,
                "set " + this.name,
                1,
                { _, _, thisObj, args ->
                    setter.accept(thisObj, if (args.isNotEmpty()) args[0] else Undefined.instance)
                    Undefined.instance
                },
            )
        }
    }

    public fun replaceWith(slot: LambdaAccessorSlot) {
        getterFunction = slot.getterFunction
        getter = slot.getter
        setterFunction = slot.setterFunction
        setter = slot.setter
        attributes = slot.attributes
    }
}
