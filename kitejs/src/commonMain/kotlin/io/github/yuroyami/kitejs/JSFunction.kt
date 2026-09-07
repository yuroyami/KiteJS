/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** A function compiled from script source. Everything about it lives in its [descriptor]. */
open class JSFunction(
    cx: Context,
    scope: Scriptable,
    override val descriptor: JSDescriptor<JSFunction>,
    private val lexicalThis: Scriptable?,
    private val homeObjectValue: Scriptable?,
) : BaseFunction(), ScriptOrFn<JSFunction> {

    override var homeObject: Scriptable?
        get() = homeObjectValue
        set(_) = throw UnsupportedOperationException("Cannot set home object on JS function.")

    init {
        ScriptRuntime.setFunctionProtoAndParent(this, cx, scope, descriptor.isES6Generator)
        if (!descriptor.isShorthand) setupDefaultPrototype(scope)
    }

    override val declarationScope: Scriptable?
        get() = parentScope

    override fun decompile(indent: Int, flags: Set<DecompilerFlag>): String = descriptor.getRawSource()

    val isShorthand: Boolean
        get() = descriptor.isShorthand

    val isStrict: Boolean
        get() = descriptor.isStrict

    override val arity: Int get() = descriptor.arity

    protected fun getLanguageVersion(): Int = descriptor.languageVersion

    override fun hasPrototypeProperty(): Boolean = true

    override fun isGeneratorFunction(): Boolean = descriptor.isES6Generator

    override val length: Int
        get() {
            val declared = descriptor.arity
            if (getLanguageVersion() != Context.VERSION_1_2) return declared
            val activation = ScriptRuntime.findFunctionActivation(Context.getContext(), this) ?: return declared
            return activation.originalArgs.size
        }

    internal fun getParamAndVarCount(): Int = descriptor.paramAndVarCount

    internal fun getParamCount(): Int {
        val count = descriptor.paramCount
        return if (descriptor.hasRestArg) count - 1 else count
    }

    internal fun getParamOrVarConst(index: Int): Boolean = descriptor.getParamOrVarConst(index)

    internal fun getParamOrVarName(index: Int): String = descriptor.getParamOrVarName(index)

    fun getRawSource(): String = descriptor.getRawSource()

    override fun createPrototypeProperty() {
        if (descriptor.hasPrototype) super.createPrototypeProperty()
    }

    internal val code: JSCode<JSFunction>
        get() = descriptor.code!!

    internal val constructorCode: JSCode<JSFunction>?
        get() = descriptor.constructor

    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        if (!ScriptRuntime.hasTopCall(cx)) return ScriptRuntime.doTopCall(this, cx, scope, thisObj, args, isStrict)
        val realThis = if (descriptor.hasLexicalThis) lexicalThis else thisObj
        return descriptor.code!!.execute(cx, this, Undefined.instance, scope, realThis, args)
    }

    override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
        val ctor = descriptor.constructor ?: throw ScriptRuntime.typeErrorById("msg.not.ctor", functionName)
        var thisObj = if (homeObject == null) createObject(cx, scope) else null
        val res = ctor.execute(cx, this, this, scope, thisObj, args)
        if (res is Scriptable) thisObj = res
        return thisObj!!
    }

    val isScript: Boolean
        get() = descriptor.isScript

    override fun hasDefaultParameters(): Boolean = descriptor.hasDefaultParameters

    fun hasFunctionNamed(name: String): Boolean = descriptor.hasFunctionNamed(name)

    override val functionName: String get() = descriptor.name

    fun resumeGenerator(cx: Context, scope: Scriptable, operation: Int, state: Any?, value: Any?): Any? =
        descriptor.code!!.resume(cx, this, state, scope, operation, value)

    /** The `this` a call sees: the captured one for an arrow function, [functionThis] otherwise. */
    fun getFunctionThis(functionThis: Scriptable?): Scriptable? =
        if (descriptor.hasLexicalThis) lexicalThis else functionThis

    companion object {
        fun createScript(desc: JSDescriptor<JSScript>, homeObject: Scriptable?, staticSecurityDomain: Any?): JSScript {
            check(desc.isScript)
            return JSScript(desc, homeObject)
        }

        fun createFunction(cx: Context, scope: Scriptable, desc: JSDescriptor<JSFunction>, homeObject: Scriptable?, staticSecurityDomain: Any?): JSFunction =
            JSFunction(cx, scope, desc, null, homeObject)

        internal fun createFunction(cx: Context, scope: Scriptable, parent: JSDescriptor<*>, index: Int, homeObject: Scriptable?): JSFunction =
            JSFunction(cx, scope, parent.getFunction(index), null, homeObject)
    }
}
