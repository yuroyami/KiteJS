/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** A function identified by a number, which its [IdFunctionCall] master dispatches on. */
open class IdFunctionObject : BaseFunction {

    private val idcall: IdFunctionCall
    val tag: Any?
    private val methodIdField: Int
    private val declaredArity: Int
    private var useCallAsConstructor = false
    private var name: String? = null

    constructor(idcall: IdFunctionCall, tag: Any?, id: Int, arity: Int) : super() {
        require(arity >= 0)
        this.idcall = idcall
        this.tag = tag
        this.methodIdField = id
        this.declaredArity = arity
    }

    constructor(idcall: IdFunctionCall, tag: Any?, id: Int, name: String, arity: Int, scope: Scriptable) :
        super(scope, null) {
        require(arity >= 0)
        this.idcall = idcall
        this.tag = tag
        this.methodIdField = id
        this.declaredArity = arity
        this.name = name
    }

    fun initFunction(name: String, scope: Scriptable) {
        this.name = name
        parentScope = scope
    }

    fun hasTag(tag: Any?): Boolean = tag == this.tag

    fun methodId(): Int = methodIdField

    fun markAsConstructor(prototypeProperty: Scriptable?) {
        useCallAsConstructor = true
        setImmunePrototypeProperty(prototypeProperty)
    }

    fun addAsProperty(target: Scriptable) {
        defineProperty(target, name!!, this, DONTENUM)
    }

    open fun exportAsScopeProperty() {
        addAsProperty(declarationScope!!)
    }

    override var prototype: Scriptable?
        get() {
            var proto = super.prototype
            if (proto == null) {
                proto = getFunctionPrototype(declarationScope!!)
                super.prototype = proto
            }
            return proto
        }
        set(value) {
            super.prototype = value
        }

    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
        idcall.execIdCall(this, cx, scope, thisObj, args)

    override fun createObject(cx: Context, scope: Scriptable): Scriptable? {
        if (useCallAsConstructor) return null
        throw ScriptRuntime.typeErrorById("msg.not.ctor", functionName)
    }

    override val arity: Int get() = declaredArity

    override val length: Int get() = arity

    override val functionName: String get() = name ?: ""

    fun unknown(): RuntimeException =
        IllegalArgumentException("BAD FUNCTION ID=$methodIdField MASTER=$idcall")
}
