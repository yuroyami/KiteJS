/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * A [Slot] whose value is produced by a getter and consumed by a setter. Unlike [LambdaSlot], the
 * property descriptor shows that this is what is happening.
 */
public class AccessorSlot : Slot {

    internal constructor(name: Any?, index: Int) : super(name, index, 0)

    internal constructor(oldSlot: Slot) : super(oldSlot)

    // The getter and setter can each be a script function or nothing at all, so each side gets its
    // own small interface.
    internal var getter: Getter? = null
    internal var setter: Setter? = null

    override fun copySlot(): AccessorSlot = AccessorSlot(this).also {
        it.value = value
        it.getter = getter
        it.setter = setter
        it.next = null
        it.orderedNext = null
    }

    override val isValueSlot: Boolean
        get() = false

    override val isSetterSlot: Boolean
        get() = true

    override fun getPropertyDescriptor(cx: Context, scope: Scriptable): ScriptableObject.DescriptorInfo {
        // This looks like it should match the plain Slot version, but the spec is exact about the
        // order the descriptor's properties come out in, so it is spelled out here.
        val attr = attributes
        val es6 = cx.languageVersion >= Context.VERSION_ES6
        val desc: ScriptableObject.DescriptorInfo
        if (es6) {
            desc = ScriptableObject.DescriptorInfo(Scriptable.NOT_FOUND, attr, false)
            if (getter == null && setter == null) {
                desc.writable = (attr and ScriptableObject.READONLY) == 0
            }
        } else {
            desc = ScriptableObject.DescriptorInfo(
                Scriptable.NOT_FOUND,
                attr,
                getter == null && setter == null,
            )
        }

        val fName = name?.toString() ?: "f"
        getter?.let { desc.getter = it.asGetterFunction(fName, scope) ?: Undefined.instance }
        when {
            setter != null -> desc.setter = setter!!.asSetterFunction(fName, scope) ?: Undefined.instance
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
            return s.setValue(value, owner, start)
        }
        return super.setValue(value, owner, start, isThrow)
    }

    override fun getValue(start: Scriptable?): Any? {
        // Not an elvis: a getter that returns null returns null, it does not fall through.
        val g = getter ?: return super.getValue(start)
        return g.getValue(start)
    }

    override fun getSetterFunction(name: String?, scope: Scriptable): Function? =
        setter?.asSetterFunction(name, scope)

    override fun getGetterFunction(name: String?, scope: Scriptable): Function? =
        getter?.asGetterFunction(name, scope)

    override fun isSameGetterFunction(function: Any?): Boolean {
        if (function === Scriptable.NOT_FOUND) return true
        val g = getter ?: return ScriptRuntime.shallowEq(Undefined.instance, function)
        return g.isSameGetterFunction(function)
    }

    override fun isSameSetterFunction(function: Any?): Boolean {
        if (function === Scriptable.NOT_FOUND) return true
        val s = setter ?: return ScriptRuntime.shallowEq(Undefined.instance, function)
        return s.isSameSetterFunction(function)
    }

    internal interface Getter {
        fun getValue(start: Scriptable?): Any?
        fun asGetterFunction(name: String?, scope: Scriptable): Function?
        fun isSameGetterFunction(getter: Any?): Boolean
    }

    /** A getter that calls a script function. */
    internal class FunctionGetter(
        // The target can genuinely be `undefined`, so it is not typed as a Function.
        val target: Any?,
    ) : Getter {

        override fun getValue(start: Scriptable?): Any? {
            if (target is Function) {
                val cx = Context.getContext()
                // A bound function has no declaration scope. Upstream passes the null along; the
                // port's call needs one, so the caller's top level stands in.
                val scope = target.declarationScope ?: ScriptableObject.getTopLevelScope(start ?: target)
                return target.call(cx, scope, start, ScriptRuntime.emptyArgs)
            }
            return Undefined.instance
        }

        override fun asGetterFunction(name: String?, scope: Scriptable): Function? =
            target as? Function

        override fun isSameGetterFunction(getter: Any?): Boolean =
            ScriptRuntime.shallowEq(target as? Function ?: Undefined.instance, getter)
    }

    internal interface Setter {
        fun setValue(value: Any?, owner: Scriptable, start: Scriptable): Boolean
        fun asSetterFunction(name: String?, scope: Scriptable): Function?
        fun isSameSetterFunction(getter: Any?): Boolean
    }

    /** A setter that calls a script function, which may itself be `undefined`. */
    internal class FunctionSetter(val target: Any?) : Setter {

        override fun setValue(value: Any?, owner: Scriptable, start: Scriptable): Boolean {
            if (target is Function) {
                val cx = Context.getContext()
                val scope = target.declarationScope ?: ScriptableObject.getTopLevelScope(start ?: target)
                target.call(cx, scope, start, arrayOf(value))
            }
            return true
        }

        override fun asSetterFunction(name: String?, scope: Scriptable): Function? =
            target as? Function

        override fun isSameSetterFunction(getter: Any?): Boolean =
            ScriptRuntime.shallowEq(target as? Function ?: Undefined.instance, getter)
    }
}
