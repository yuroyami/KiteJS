/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The base of every function object, and also the prototype of every function defined in script
 * except the generator functions written with `function *`.
 *
 * See ECMA 15.3.
 */
open class BaseFunction : ScriptableObject, Function {

    private var prototypePropertyValue: Any? = null
    private var argumentsObj: Any? = Scriptable.NOT_FOUND
    private var nameValue: Any? = null
    private var homeObject: Scriptable? = null
    private var isGeneratorFunctionField: Boolean = false

    /**
     * Raw storage for the `prototype` property's attributes. Writing it does not touch the slot;
     * `setPrototypePropertyAttributes` does both, the way upstream's field and setter pair do (D-8).
     */
    protected var prototypePropertyAttributesField: Int = PERMANENT or DONTENUM

    constructor() {
        createProperties()
    }

    constructor(isGenerator: Boolean) {
        createProperties()
        this.isGeneratorFunctionField = isGenerator
    }

    constructor(scope: Scriptable, prototype: Scriptable?) : super(scope, prototype) {
        createProperties()
        ScriptRuntime.setBuiltinProtoAndParent(this, scope, TopLevel.Builtins.Function)
    }

    protected open fun createProperties() {
        defineBuiltInProperty(this, "length", DONTENUM or READONLY, ::lengthGetter)
        defineBuiltInProperty(this, "name", DONTENUM or READONLY, ::nameGetter, ::nameSetter)
        if (includeNonStandardProps()) {
            defineBuiltInProperty(
                this,
                "arity",
                PERMANENT or DONTENUM or READONLY,
                ::arityGetter,
            )
            defineBuiltInProperty(
                this,
                "arguments",
                PERMANENT or DONTENUM,
                ::argumentsGetter,
                ::argumentsSetter,
            )
        }
    }

    protected open fun includeNonStandardProps(): Boolean = !Context.isCurrentContextStrict()

    /** Sets the name past every readonly check. */
    internal fun setFunctionName(name: String) {
        nameValue = name
    }

    protected fun createPrototypeProperty() {
        startCompoundOp(true).use { createPrototypeProperty(it) }
    }

    protected fun createPrototypeProperty(compoundOp: CompoundOperationMap) {
        compoundOp.compute(this, compoundOp, PROTOTYPE_PROPERTY_NAME, 0) { _, _, s, _, _ ->
            s ?: BuiltInSlot(
                PROTOTYPE_PROPERTY_NAME,
                0,
                prototypePropertyAttributesField,
                this,
                ::prototypeGetter,
                ::prototypeSetter,
                ::prototypeAttrSetter,
                ::prototypeDescSetter,
            )
        }
    }

    protected fun defaultHas(name: String): Boolean = super.has(name, this)

    protected fun defaultGet(name: String): Any? = super.get(name, this)

    protected fun defaultPut(name: String, value: Any?) {
        super.put(name, this, value)
    }

    override val className: String
        get() = if (isGeneratorFunction()) GENERATOR_FUNCTION_CLASS else FUNCTION_CLASS

    /** Generated code overrides this. */
    protected open fun isGeneratorFunction(): Boolean = isGeneratorFunctionField

    /** Generated code overrides this. */
    protected open fun hasDefaultParameters(): Boolean = false

    /** "function", or "undefined" when [avoidObjectDetection] says so. */
    override val typeOf: String
        get() = if (avoidObjectDetection()) "undefined" else "function"

    /**
     * The `instanceof` operator for function objects: true when this function's `prototype`
     * property is somewhere in [instance]'s prototype chain.
     */
    override fun hasInstance(instance: Scriptable): Boolean {
        val protoProp = getProperty(this, PROTOTYPE_PROPERTY_NAME)
        if (protoProp is Scriptable) return ScriptRuntime.jsDelegatesTo(instance, protoProp)
        throw ScriptRuntime.typeErrorById("msg.instanceof.bad.prototype", getFunctionName())
    }

    /** Makes [value] a non-enumerable, non-deletable, read-only `prototype` on this function. */
    fun setImmunePrototypeProperty(value: Any?) {
        check((prototypePropertyAttributesField and READONLY) == 0)
        prototypePropertyValue = value ?: UniqueTag.NULL_VALUE
        createPrototypeProperty()
        setAttributes(PROTOTYPE_PROPERTY_NAME, DONTENUM or PERMANENT or READONLY)
    }

    protected open fun getClassPrototype(): Scriptable? =
        prototypeProperty as? Scriptable ?: getObjectPrototype(this)

    /** Subclasses override this. The base does nothing. */
    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
        Undefined.instance

    override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
        if (cx.languageVersion >= Context.VERSION_ES6 && homeObject != null) {
            // Only a method has a home object, and a method is not a constructor.
            throw ScriptRuntime.typeErrorById("msg.not.ctor", getFunctionName())
        }
        var result = createObject(cx, scope)
        if (result == null) {
            val value = call(cx, scope, null, args)
            // When createObject returns null, call has to return the new object itself.
            check(value is Scriptable) {
                "Bad implementation of call as constructor, name=${getFunctionName()}"
            }
            result = value
            if (result.prototype == null) {
                val proto = getClassPrototype()
                if (result !== proto) result.prototype = proto
            }
            if (result.parentScope == null) {
                val parent = parentScope
                if (result !== parent) result.parentScope = parent
            }
        } else {
            val value = call(cx, scope, result, args)
            if (value is Scriptable) result = value
        }
        return result
    }

    /**
     * Builds the object that [construct] passes to [call] as `this`. Returning null says that
     * [call] will make the object itself, and [construct] then fixes up its scope and prototype.
     */
    open fun createObject(cx: Context, scope: Scriptable): Scriptable? {
        val newInstance = NativeObject()
        newInstance.prototype = getClassPrototype()
        newInstance.parentScope = parentScope
        return newInstance
    }

    /** Turns this function back into source. A native function has no body to show. */
    internal open fun decompile(indent: Int, flags: Set<DecompilerFlag>): String {
        val sb = StringBuilder()
        val justbody = flags.contains(DecompilerFlag.ONLY_BODY)
        if (!justbody) {
            sb.append("function ").append(getFunctionName()).append("() {\n\t")
        }
        sb.append("[native code]\n")
        if (!justbody) sb.append("}\n")
        return sb.toString()
    }

    open fun getArity(): Int = 0

    open fun getLength(): Int = 0

    open fun getFunctionName(): String = ""

    /** Sets the attributes of `name`, `length` and `arity`, which differ across the natives. */
    fun setStandardPropertyAttributes(attributes: Int) {
        setAttributes("name", attributes)
        setAttributes("length", attributes)
        setAttributes("arity", attributes)
    }

    fun setPrototypePropertyAttributes(attributes: Int) {
        prototypePropertyAttributesField = attributes
        map.compute(this, PROTOTYPE_PROPERTY_NAME, 0) { _, _, s, _, _ ->
            s?.also { it.attributes = attributes }
        }
    }

    protected open fun hasPrototypeProperty(): Boolean =
        prototypePropertyValue != null && prototypePropertyValue !== UniqueTag.NOT_FOUND

    /** The `prototype` property: `undefined` when unset, null when explicitly set to null. */
    open val prototypeProperty: Any?
        get() = when (val result = prototypePropertyValue) {
            null, UniqueTag.NOT_FOUND -> Undefined.instance
            UniqueTag.NULL_VALUE -> null
            else -> result
        }

    protected open fun setPrototypeProperty(prototype: Any?) {
        if (prototype != null) {
            createPrototypeProperty()
            prototypePropertyValue = prototype
        } else {
            prototypePropertyValue = UniqueTag.NOT_FOUND
        }
    }

    protected open fun setupDefaultPrototype(scope: Scriptable): Any {
        if (!has(PROTOTYPE_PROPERTY_NAME, this)) createPrototypeProperty()
        val obj = NativeObject()
        obj.parentScope = parentScope
        // The property is set before the prototype is worked out, so a script that defines its own
        // Object() does not send this into an endless loop.
        prototypePropertyValue = obj

        val proto: Scriptable? = if (isGeneratorFunction()) {
            // TODO(P3.4): a generator function's prototype should be %GeneratorPrototype%, which
            // needs ES6Generator.GENERATOR_TAG. Falling back to Object.prototype until it lands.
            getObjectPrototype(this)
        } else {
            getObjectPrototype(this)
        }
        // The object just made has to stay grounded.
        if (proto !== obj) obj.prototype = proto
        obj.defineProperty("constructor", this, DONTENUM)
        return obj
    }

    fun setHomeObject(homeObject: Scriptable?) {
        this.homeObject = homeObject
    }

    fun getHomeObject(): Scriptable? = homeObject

    companion object {
        private const val FUNCTION_CLASS = "Function"

        internal const val GENERATOR_FUNCTION_CLASS = "__GeneratorFunction"

        private const val PROTOTYPE_PROPERTY_NAME = "prototype"

        private val APPLY_TAG: Any = "APPLY_TAG"
        private val CALL_TAG: Any = "CALL_TAG"

        // ---- The built-in property accessors -------------------------------------------------

        private fun lengthGetter(function: BaseFunction, start: Scriptable?): Any = function.getLength()

        private fun arityGetter(function: BaseFunction, start: Scriptable?): Any = function.getArity()

        private fun argumentsGetter(function: BaseFunction, start: Scriptable?): Any? =
            function.getArguments()

        private fun argumentsSetter(
            function: BaseFunction,
            value: Any?,
            owner: Scriptable,
            start: Scriptable,
            isThrow: Boolean,
        ): Boolean {
            function.argumentsObj = value
            return true
        }

        private fun nameGetter(function: BaseFunction, start: Scriptable?): Any? =
            function.nameValue ?: function.getFunctionName()

        private fun nameSetter(
            function: BaseFunction,
            value: Any?,
            owner: Scriptable,
            start: Scriptable,
            isThrow: Boolean,
        ): Boolean {
            function.nameValue = value
            return true
        }

        private fun prototypeGetter(function: BaseFunction, start: Scriptable?): Any? =
            function.prototypeProperty

        private fun prototypeSetter(
            function: BaseFunction,
            value: Any?,
            owner: Scriptable,
            start: Scriptable,
            isThrow: Boolean,
        ): Boolean {
            function.prototypePropertyValue = value ?: UniqueTag.NULL_VALUE
            return true
        }

        private fun prototypeAttrSetter(function: BaseFunction, attributes: Int) {
            function.prototypePropertyAttributesField = attributes
        }

        internal fun prototypeDescSetter(
            builtIn: BaseFunction,
            current: BuiltInSlot<BaseFunction>,
            id: Any?,
            info: DescriptorInfo,
            checkValid: Boolean,
            key: Any?,
            index: Int,
        ): Boolean = builtIn.startCompoundOp(true).use { map ->
            defineOrdinaryProperty(
                { _, i, _, _, _, s ->
                    if (i.value !== Scriptable.NOT_FOUND) {
                        builtIn.prototypePropertyValue = i.value ?: UniqueTag.NULL_VALUE
                    }
                    s
                },
                builtIn,
                map,
                id,
                info,
                checkValid,
                key,
                index,
            )
        }

        internal fun isApply(f: KnownBuiltInFunction): Boolean = f.tag === APPLY_TAG

        internal fun isApplyOrCall(f: KnownBuiltInFunction): Boolean {
            val tag = f.tag
            return tag === APPLY_TAG || tag === CALL_TAG
        }

        /**
         * TODO(P3.4): builds the `Function` constructor and its prototype. It needs the `apply`,
         * `call`, `bind` and `toString` implementations, which in turn need BoundFunction,
         * ScriptRuntime.applyOrCall and the interpreter's compileFunction.
         */
        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): LambdaConstructor =
            TODO("the Function constructor lands with the natives in phase 3.4")

        /** TODO(P3.4): needs ES6Generator.GENERATOR_TAG. */
        internal fun initAsGeneratorFunction(scope: Scriptable, sealed: Boolean): Any =
            TODO("GeneratorFunction lands with ES6Generator in phase 3.4")
    }

    /**
     * `<function>.arguments`, which is deprecated. Reading it walks the activation stack rather
     * than costing anything on every call.
     */
    private fun getArguments(): Any? {
        // A value assigned to .arguments wins over the live activation.
        if (argumentsObj !== Scriptable.NOT_FOUND) return argumentsObj
        // TODO(P3.4): the live value needs NativeCall and Arguments, which hold a JSFunction.
        return null
    }
}
