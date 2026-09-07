/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.reflect.KClass

/**
 * Forwards every object operation to another object. Subclass it to wrap a host object: override
 * only what you want to change and the rest keeps working.
 */
open class Delegator : Function, SymbolScriptable {

    /** The object every operation is forwarded to. */
    open var delegee: Scriptable? = null

    /** A prototype-shaped delegator with nothing behind it yet. */
    constructor()

    constructor(obj: Scriptable?) {
        this.delegee = obj
    }

    /** The object behind this one, or a bug if there is none. */
    private fun required(): Scriptable = delegee ?: throw Kit.codeBug()

    /**
     * A fresh delegator of this same type, used when a prototype delegator is called with `new`.
     * Upstream builds it by reflection; common Kotlin has none, so a subclass overrides this.
     */
    protected open fun newInstance(): Delegator =
        throw IllegalStateException("${this::class.simpleName} must override newInstance()")

    override val className: String
        get() = required().className

    override fun get(name: String, start: Scriptable): Any? = required().get(name, start)

    override fun get(index: Int, start: Scriptable): Any? = required().get(index, start)

    override fun get(key: Symbol, start: Scriptable): Any? {
        val d = required()
        return if (d is SymbolScriptable) d.get(key, start) else Scriptable.NOT_FOUND
    }

    override fun has(name: String, start: Scriptable): Boolean = required().has(name, start)

    override fun has(index: Int, start: Scriptable): Boolean = required().has(index, start)

    override fun has(key: Symbol, start: Scriptable): Boolean {
        val d = required()
        return if (d is SymbolScriptable) d.has(key, start) else false
    }

    override fun put(name: String, start: Scriptable, value: Any?) {
        val d = required()
        d.put(name, receiverFor(start, d), value)
    }

    override fun put(index: Int, start: Scriptable, value: Any?) {
        val d = required()
        d.put(index, receiverFor(start, d), value)
    }

    override fun put(key: Symbol, start: Scriptable, value: Any?) {
        val d = required()
        if (d is SymbolScriptable) d.put(key, receiverFor(start, d), value)
    }

    /**
     * A write aimed at this wrapper lands on the object behind it. Handing the wrapper on as the
     * receiver is what upstream does, and it loops: the delegee does not own the property, so it
     * bounces the write back to the receiver, which forwards it to the delegee again (D-65).
     */
    private fun receiverFor(start: Scriptable, delegee: Scriptable): Scriptable =
        if (start === this) delegee else start

    override fun delete(name: String) = required().delete(name)

    override fun delete(index: Int) = required().delete(index)

    override fun delete(key: Symbol) {
        val d = required()
        if (d is SymbolScriptable) d.delete(key)
    }

    override var prototype: Scriptable?
        get() = required().prototype
        set(value) { required().prototype = value }

    override var parentScope: Scriptable?
        get() = required().parentScope
        set(value) { required().parentScope = value }

    override fun getIds(): Array<Any?> = required().getIds()

    /** A delegator is its own primitive when asked for an object, and defers otherwise. */
    override fun getDefaultValue(hint: KClass<*>?): Any? =
        if (hint == null || hint == ScriptRuntime.ScriptableClass || hint == ScriptRuntime.FunctionClass) this
        else required().getDefaultValue(hint)

    override fun hasInstance(instance: Scriptable): Boolean = required().hasInstance(instance)

    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
        (required() as Function).call(cx, scope, thisObj, args)

    /** With nothing behind it this acts as a prototype: `new` builds a delegator over a fresh object. */
    override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
        val d = delegee ?: run {
            val fresh = newInstance()
            fresh.delegee =
                if (args.isEmpty()) cx.newObject(scope) else ScriptRuntime.toObject(cx, scope, args[0])
            return fresh
        }
        return (d as Constructable).construct(cx, scope, args)
    }
}
