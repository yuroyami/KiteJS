/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** A function compiled from script source. Everything about it lives in its [descriptor]. */
public open class JSFunction(
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
        // A class constructor gets its read-only `prototype` from the class definition.
        if (!descriptor.isShorthand && !descriptor.isClassConstructor) setupDefaultPrototype(scope)
        // Strict functions, which every class constructor and method is, have no own `arguments`
        // (ECMAScript 2015, 16.1), nor Rhino's `arity`, which upstream gave them all the same.
        if (descriptor.isStrict) {
            for (name in arrayOf("arity", "arguments")) {
                if (has(name, this)) {
                    setAttributes(name, DONTENUM)
                    delete(name)
                }
            }
        }
    }

    /**
     * For an arrow function made in a derived class constructor, or in an arrow made there, the
     * constructor's `this` binding, which super() may fill after the arrow is made.
     */
    internal var lexicalThisBinding: ThisBinding? = null

    /** For an arrow function, the new.target of the function it was made in. */
    internal var lexicalNewTarget: Any? = Undefined.instance

    /** For a class constructor, the instance fields its class declares, in order. */
    internal var classFields: Array<ClassField>? = null

    /** For a class constructor, the private methods and accessors each instance gets. */
    internal var classPrivateMethods: Array<PrivateName>? = null

    override val declarationScope: Scriptable?
        get() = parentScope

    override fun decompile(indent: Int, flags: Set<DecompilerFlag>): String = descriptor.rawSource

    public val isShorthand: Boolean
        get() = descriptor.isShorthand

    public val isStrict: Boolean
        get() = descriptor.isStrict

    override val arity: Int get() = descriptor.arity

    protected val languageVersion: Int get() = descriptor.languageVersion

    override fun hasPrototypeProperty(): Boolean = true

    override val isGeneratorFunction: Boolean get() = descriptor.isES6Generator

    override val length: Int
        get() {
            val declared = descriptor.arity
            if (languageVersion != Context.VERSION_1_2) return declared
            val activation = ScriptRuntime.findFunctionActivation(Context.getContext(), this) ?: return declared
            return activation.originalArgs.size
        }

    internal val paramAndVarCount: Int get() = descriptor.paramAndVarCount

    internal val paramCount: Int

        get() {
        val count = descriptor.paramCount
        return if (descriptor.hasRestArg) count - 1 else count
    }

    internal fun getParamOrVarConst(index: Int): Boolean = descriptor.getParamOrVarConst(index)

    internal fun getParamOrVarName(index: Int): String = descriptor.getParamOrVarName(index)

    public val rawSource: String get() = descriptor.rawSource

    override fun createPrototypeProperty() {
        if (descriptor.hasPrototype) super.createPrototypeProperty()
    }

    internal val code: JSCode<JSFunction>
        get() = descriptor.code!!

    internal val constructorCode: JSCode<JSFunction>?
        get() = descriptor.constructor

    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        if (descriptor.isClassConstructor) throw ScriptRuntime.typeErrorById("msg.class.not.new", functionName)
        if (!ScriptRuntime.hasTopCall(cx)) return ScriptRuntime.doTopCall(this, cx, scope, thisObj, args, isStrict)
        val realThis = if (descriptor.hasLexicalThis) lexicalThis else thisObj
        return descriptor.code!!.execute(cx, this, Undefined.instance, scope, realThis, args)
    }

    override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
        if (descriptor.isClassConstructor) return constructClass(cx, scope, args, this)
        val ctor = descriptor.constructor ?: throw ScriptRuntime.typeErrorById("msg.not.ctor", functionName)
        var thisObj = if (homeObject == null) createObject(cx, scope) else null
        val res = ctor.execute(cx, this, this, scope, thisObj, args)
        if (res is Scriptable) thisObj = res
        return thisObj!!
    }

    /** Arrow functions, methods, accessors and generators have no constructor code. */
    override val isConstructor: Boolean get() = descriptor.isClassConstructor || (descriptor.constructor != null && homeObject == null)

    /**
     * An ordinary function's [[Construct]] with another [newTarget]: `this` inherits from
     * newTarget's `prototype`, or from %Object.prototype% of newTarget's realm when that is not an
     * object (OrdinaryCreateFromConstructor), and is made before the body runs.
     */
    override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>, newTarget: Scriptable): Scriptable {
        if (newTarget === this) return construct(cx, scope, args)
        if (descriptor.isClassConstructor) return constructClass(cx, scope, args, newTarget)
        val ctor = descriptor.constructor
        if (ctor == null || homeObject != null) throw ScriptRuntime.typeErrorById("msg.not.ctor", functionName)
        val thisObj = NativeObject()
        thisObj.prototype = AbstractEcmaObjectOperations.getPrototypeFromConstructor(cx, newTarget) { getObjectPrototype(it) }
        thisObj.parentScope = parentScope
        val res = ctor.execute(cx, this, newTarget, scope, thisObj, args)
        return res as? Scriptable ?: thisObj
    }

    /**
     * [[Construct]] of a class constructor (ECMAScript 2015, 9.2.2; ECMAScript 2022, 10.2.2). A
     * base class makes `this` from newTarget's prototype and puts its fields on it before the body
     * runs; a derived class leaves `this` to super() and checks what the body returns. A made-up
     * constructor has no body to run.
     */
    private fun constructClass(cx: Context, scope: Scriptable, args: Array<Any?>, newTarget: Scriptable): Scriptable {
        if (descriptor.isDerivedConstructor) {
            if (descriptor.isDefaultConstructor) return ClassRuntime.defaultDerivedConstruct(cx, scope, this, args, newTarget)
            // The interpreter checks the result against the `this` binding super() filled.
            return descriptor.code!!.execute(cx, this, newTarget, scope, null, args) as Scriptable
        }
        val thisObj = NativeObject()
        thisObj.prototype = AbstractEcmaObjectOperations.getPrototypeFromConstructor(cx, newTarget) { getObjectPrototype(it) }
        thisObj.parentScope = parentScope
        ClassRuntime.initializeInstanceElements(cx, thisObj, this)
        if (descriptor.isDefaultConstructor) return thisObj
        val res = descriptor.code!!.execute(cx, this, newTarget, scope, thisObj, args)
        return if (res is Scriptable && ScriptRuntime.isObject(res)) res else thisObj
    }

    public val isScript: Boolean
        get() = descriptor.isScript

    override fun hasDefaultParameters(): Boolean = descriptor.hasDefaultParameters

    public fun hasFunctionNamed(name: String): Boolean = descriptor.hasFunctionNamed(name)

    override val functionName: String get() = descriptor.name

    public fun resumeGenerator(cx: Context, scope: Scriptable, operation: Int, state: Any?, value: Any?): Any? =
        descriptor.code!!.resume(cx, this, state, scope, operation, value)

    /** The `this` a call sees: the captured one for an arrow function, [functionThis] otherwise. */
    public fun getFunctionThis(functionThis: Scriptable?): Scriptable? =
        if (descriptor.hasLexicalThis) lexicalThis else functionThis

    public companion object {
        public fun createScript(desc: JSDescriptor<JSScript>, homeObject: Scriptable?, staticSecurityDomain: Any?): JSScript {
            check(desc.isScript)
            return JSScript(desc, homeObject)
        }

        public fun createFunction(cx: Context, scope: Scriptable, desc: JSDescriptor<JSFunction>, homeObject: Scriptable?, staticSecurityDomain: Any?): JSFunction =
            JSFunction(cx, scope, desc, null, homeObject)

        internal fun createFunction(cx: Context, scope: Scriptable, parent: JSDescriptor<*>, index: Int, homeObject: Scriptable?): JSFunction =
            JSFunction(cx, scope, parent.getFunction(index), null, homeObject)
    }
}
