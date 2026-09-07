/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.reflect.KClass

/** The scope a `with` statement pushes: every lookup goes to the object it wraps. */
public open class NativeWith : Scriptable, SymbolScriptable, IdFunctionCall {

    protected var prototypeField: Scriptable? = null
    protected var parentField: Scriptable? = null

    private constructor()

    protected constructor(parent: Scriptable?, prototype: Scriptable?) {
        this.parentField = parent
        this.prototypeField = prototype
    }

    override val className: String
        get() = "With"

    override fun has(name: String, start: Scriptable): Boolean = prototypeField!!.has(name, prototypeField!!)

    override fun has(key: Symbol, start: Scriptable): Boolean {
        val p = prototypeField
        return p is SymbolScriptable && p.has(key, p)
    }

    override fun has(index: Int, start: Scriptable): Boolean = prototypeField!!.has(index, prototypeField!!)

    override fun get(name: String, start: Scriptable): Any? {
        val p = prototypeField!!
        return p.get(name, if (start === this) p else start)
    }

    override fun get(key: Symbol, start: Scriptable): Any? {
        val p = prototypeField!!
        if (p is SymbolScriptable) return p.get(key, if (start === this) p else start)
        return Scriptable.NOT_FOUND
    }

    override fun get(index: Int, start: Scriptable): Any? {
        val p = prototypeField!!
        return p.get(index, if (start === this) p else start)
    }

    override fun put(name: String, start: Scriptable, value: Any?) {
        val p = prototypeField!!
        p.put(name, if (start === this) p else start, value)
    }

    override fun put(key: Symbol, start: Scriptable, value: Any?) {
        val p = prototypeField!!
        if (p is SymbolScriptable) p.put(key, if (start === this) p else start, value)
    }

    override fun put(index: Int, start: Scriptable, value: Any?) {
        val p = prototypeField!!
        p.put(index, if (start === this) p else start, value)
    }

    override fun delete(name: String) {
        prototypeField!!.delete(name)
    }

    override fun delete(key: Symbol) {
        (prototypeField as? SymbolScriptable)?.delete(key)
    }

    override fun delete(index: Int) {
        prototypeField!!.delete(index)
    }

    override var prototype: Scriptable?
        get() = prototypeField
        set(value) {
            prototypeField = value
        }

    override var parentScope: Scriptable?
        get() = parentField
        set(value) {
            parentField = value
        }

    override fun getIds(): Array<Any?> = prototypeField!!.getIds()

    override fun getDefaultValue(hint: KClass<*>?): Any? = prototypeField!!.getDefaultValue(hint)

    override fun hasInstance(instance: Scriptable): Boolean = prototypeField!!.hasInstance(instance)

    /** E4X only. Nothing reaches it in this port. */
    protected open fun updateDotQuery(value: Boolean): Any? = throw IllegalStateException()

    override fun execIdCall(f: IdFunctionObject, cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        if (f.hasTag(FTAG) && f.methodId() == Id_constructor) {
            throw Context.reportRuntimeErrorById("msg.cant.call.indirect", "With")
        }
        throw f.unknown()
    }

    public companion object {
        private val FTAG: Any = "With"
        private const val Id_constructor = 1

        internal fun init(scope: Scriptable, sealed: Boolean) {
            val obj = NativeWith()
            obj.parentScope = scope
            obj.prototype = ScriptableObject.getObjectPrototype(scope)
            val ctor = IdFunctionObject(obj, FTAG, Id_constructor, "With", 0, scope)
            ctor.markAsConstructor(obj)
            if (sealed) ctor.sealObject()
            ctor.exportAsScopeProperty()
        }

        /** Makes the scope object for `with (obj)`. */
        internal fun create(parent: Scriptable?, prototype: Scriptable?): NativeWith = NativeWith(parent, prototype)

        internal fun isWithFunction(functionObj: Any?): Boolean =
            functionObj is IdFunctionObject && functionObj.hasTag(FTAG) && functionObj.methodId() == Id_constructor

        internal fun newWithSpecial(cx: Context, scope: Scriptable, args: Array<Any?>): Any {
            ScriptRuntime.checkDeprecated(cx, "With")
            val top = ScriptableObject.getTopLevelScope(scope)
            val thisObj = NativeWith()
            thisObj.prototype =
                if (args.isEmpty()) ScriptableObject.getObjectPrototype(top) else ScriptRuntime.toObject(cx, top, args[0])
            thisObj.parentScope = top
            return thisObj
        }
    }
}
