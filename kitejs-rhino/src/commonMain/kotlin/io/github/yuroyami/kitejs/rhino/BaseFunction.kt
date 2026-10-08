/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

/**
 * The base of every function object, and also the prototype of every function defined in script
 * except the generator functions written with `function *`.
 *
 * See ECMA 15.3.
 */
public open class BaseFunction : ScriptableObject, Function {

    private var prototypePropertyValue: Any? = null
    private var argumentsObj: Any? = Scriptable.NOT_FOUND
    private var nameValue: Any? = null

    /** A value defined over `length`, such as a class's static `length` method, or NOT_FOUND. */
    private var lengthValue: Any? = Scriptable.NOT_FOUND
    private var isGeneratorFunctionField: Boolean = false

    /**
     * Raw storage for the `prototype` property's attributes. Writing it does not touch the slot;
     * `setPrototypePropertyAttributes` does both, the way upstream's field and setter pair do (D-8).
     */
    protected var prototypePropertyAttributesField: Int = PERMANENT or DONTENUM

    public constructor() {
        createProperties()
    }

    public constructor(isGenerator: Boolean) {
        createProperties()
        this.isGeneratorFunctionField = isGenerator
    }

    public constructor(scope: Scriptable, prototype: Scriptable?) : super(scope, prototype) {
        createProperties()
        ScriptRuntime.setBuiltinProtoAndParent(this, scope, TopLevel.Builtins.Function)
    }

    protected open fun createProperties() {
        defineBuiltInProperty(this, "length", DONTENUM or READONLY, ::lengthGetter, ::lengthSetter)
        defineBuiltInProperty(this, "name", DONTENUM or READONLY, ::nameGetter, ::nameSetter)
        if (includeNonStandardProps()) createLegacyProperties()
    }

    /** Source functions decide from their own code, not from the context that creates them. */
    internal fun createLegacyProperties() {
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

    protected open fun includeNonStandardProps(): Boolean =
        (Context.getCurrentContext()?.languageVersion ?: Context.VERSION_ES6) < Context.VERSION_ES6 &&
            !Context.isCurrentContextStrict

    /** Sets the name past every readonly check. */
    internal fun setFunctionName(name: String) {
        nameValue = name
    }

    protected open fun createPrototypeProperty() {
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
        get() = if (isGeneratorFunction) GENERATOR_FUNCTION_CLASS else FUNCTION_CLASS

    /** Generated code overrides this. */
    protected open val isGeneratorFunction: Boolean get() = isGeneratorFunctionField

    /** Generated code overrides this. */
    internal open fun hasDefaultParameters(): Boolean = false

    /** "function", or "undefined" when [avoidObjectDetection] says so. */
    override val typeOf: String
        get() = if (avoidObjectDetection()) "undefined" else "function"

    /**
     * OrdinaryHasInstance for this function, which `instanceof` falls back to when there is no
     * `Symbol.hasInstance` to ask: true when this function's `prototype` is somewhere in
     * [instance]'s prototype chain, and for a bound function whatever `instanceof` says of its
     * target (D-92).
     */
    override fun hasInstance(instance: Scriptable): Boolean =
        ScriptRuntime.ordinaryHasInstance(Context.getContext(), this, instance)

    /** Makes [value] a non-enumerable, non-deletable, read-only `prototype` on this function. */
    public fun setImmunePrototypeProperty(value: Any?) {
        check((prototypePropertyAttributesField and READONLY) == 0)
        prototypePropertyValue = value ?: UniqueTag.NULL_VALUE
        createPrototypeProperty()
        setAttributes(PROTOTYPE_PROPERTY_NAME, DONTENUM or PERMANENT or READONLY)
    }

    protected open val classPrototype: Scriptable? get() = prototypeProperty as? Scriptable ?: getObjectPrototype(this)

    /** Subclasses override this. The base does nothing. */
    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
        Undefined.instance

    override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
        if (cx.languageVersion >= Context.VERSION_ES6 && homeObject != null) {
            // Only a method has a home object, and a method is not a constructor.
            throw ScriptRuntime.typeErrorById("msg.not.ctor", functionName)
        }
        var result = createObject(cx, scope)
        if (result == null) {
            val value = call(cx, scope, null, args)
            // When createObject returns null, call has to return the new object itself.
            check(value is Scriptable) {
                "Bad implementation of call as constructor, name=${functionName}"
            }
            result = value
            if (result.prototype == null) {
                val proto = classPrototype
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
     * IsConstructor for this function: whether [construct] gets past its own check. A home object
     * refuses `new` here, since only a method has one; every function kind that refuses it for
     * another reason says so in its override (D-91).
     */
    public open val isConstructor: Boolean get() = homeObject == null

    /**
     * [[Construct]] with a [newTarget] that need not be this function, as Reflect.construct, a
     * proxy forwarding to its target and a bound function pass on. The object is made the usual
     * way and then given the prototype newTarget names, defaulting to this constructor's own as
     * found in newTarget's realm, which is the spec's OrdinaryCreateFromConstructor for every
     * built-in that makes its object in [construct]. Upstream set newTarget's prototype on whatever
     * came back, after calling a constructor that also allows a plain call as a function, so
     * `Reflect.construct(Array, [], F)` made an array that ignored F and `Map` threw (D-91).
     */
    internal open fun construct(cx: Context, scope: Scriptable, args: Array<Any?>, newTarget: Scriptable): Scriptable {
        if (newTarget === this) return construct(cx, scope, args)
        val result = construct(cx, scope, args)
        result.prototype = AbstractEcmaObjectOperations.getPrototypeFromConstructor(cx, newTarget) {
            AbstractEcmaObjectOperations.intrinsicPrototype(cx, it, this)
        }
        return result
    }

    /**
     * Builds the object that [construct] passes to [call] as `this`. Returning null says that
     * [call] will make the object itself, and [construct] then fixes up its scope and prototype.
     */
    public open fun createObject(cx: Context, scope: Scriptable): Scriptable? {
        val newInstance = NativeObject()
        newInstance.prototype = classPrototype
        newInstance.parentScope = parentScope
        return newInstance
    }

    /** Turns this function back into source. A native function has no body to show. */
    internal open fun decompile(indent: Int, flags: Set<DecompilerFlag>): String {
        val sb = StringBuilder()
        val justbody = flags.contains(DecompilerFlag.ONLY_BODY)
        if (!justbody) {
            sb.append("function ").append(functionName).append("() {\n\t")
        }
        sb.append("[native code]\n")
        if (!justbody) sb.append("}\n")
        return sb.toString()
    }

    /** What the `arity` property answers. */
    public open val arity: Int get() = 0

    /** What the `length` property answers: how many arguments the function declares. */
    public open val length: Int get() = 0

    /** What the `name` property answers. Empty for an anonymous function. */
    public open val functionName: String get() = ""

    /** Sets the attributes of `name`, `length` and `arity`, which differ across the natives. */
    public fun setStandardPropertyAttributes(attributes: Int) {
        setAttributes("name", attributes)
        setAttributes("length", attributes)
        if (has("arity", this)) setAttributes("arity", attributes)
    }

    public fun setPrototypePropertyAttributes(attributes: Int) {
        prototypePropertyAttributesField = attributes
        map.compute(this, PROTOTYPE_PROPERTY_NAME, 0) { _, _, s, _, _ ->
            s?.also { it.attributes = attributes }
        }
    }

    protected open fun hasPrototypeProperty(): Boolean =
        prototypePropertyValue != null && prototypePropertyValue !== UniqueTag.NOT_FOUND

    /** The `prototype` property: `undefined` when unset, null when explicitly set to null. */
    public open val prototypeProperty: Any?
        get() = when (val result = prototypePropertyValue) {
            null, UniqueTag.NOT_FOUND -> Undefined.instance
            UniqueTag.NULL_VALUE -> null
            else -> result
        }

    internal open fun setPrototypeProperty(prototype: Any?) {
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

        val proto: Scriptable? = if (isGeneratorFunction) {
            // A generator function's prototype hangs off %GeneratorPrototype%, not Object.prototype.
            val top = getTopLevelScope(scope)
            getTopScopeValue(top, ES6Generator.GENERATOR_TAG) as? Scriptable ?: getObjectPrototype(this)
        } else {
            getObjectPrototype(this)
        }
        // The object just made has to stay grounded.
        if (proto !== obj) obj.prototype = proto
        // A generator function's prototype is an empty object with no constructor back to the
        // function (ECMAScript 2015, 14.4.13); upstream gives it one.
        if (!isGeneratorFunction) obj.defineProperty("constructor", this, DONTENUM)
        return obj
    }

    /** The object a method was defined on, which `super` resolves against. Null for a plain function. */
    public open var homeObject: Scriptable? = null

    public companion object {
        private const val FUNCTION_CLASS = "Function"

        internal const val GENERATOR_FUNCTION_CLASS = "__GeneratorFunction"

        /** Where %AsyncFunction% is parked on the global, which has no such global (D-97). */
        internal const val ASYNC_FUNCTION_CLASS = "__AsyncFunction"

        private const val PROTOTYPE_PROPERTY_NAME = "prototype"

        private val APPLY_TAG: Any = "APPLY_TAG"
        private val CALL_TAG: Any = "CALL_TAG"
        private val HAS_INSTANCE_TAG: Any = "HAS_INSTANCE_TAG"

        // ---- The built-in property accessors -------------------------------------------------

        private fun lengthGetter(function: BaseFunction, start: Scriptable?): Any? =
            if (function.lengthValue !== Scriptable.NOT_FOUND) function.lengthValue else function.length

        /**
         * `length` is configurable, so defining it anew, as Object.defineProperty or a class's
         * static `length` method does, replaces its value; upstream kept the computed one.
         */
        private fun lengthSetter(
            function: BaseFunction,
            value: Any?,
            owner: Scriptable,
            start: Scriptable,
            isThrow: Boolean,
        ): Boolean {
            function.lengthValue = value
            return true
        }

        private fun arityGetter(function: BaseFunction, start: Scriptable?): Any = function.arity

        private fun argumentsGetter(function: BaseFunction, start: Scriptable?): Any? =
            function.arguments

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
            function.nameValue ?: function.functionName

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

        /** Whether [f] is some realm's `Function.prototype[Symbol.hasInstance]`. */
        internal fun isOrdinaryHasInstance(f: Any?): Boolean = f is KnownBuiltInFunction && f.tag === HAS_INSTANCE_TAG

        internal fun isApplyOrCall(f: KnownBuiltInFunction): Boolean {
            val tag = f.tag
            return tag === APPLY_TAG || tag === CALL_TAG
        }

        /** Builds the `Function` constructor and `Function.prototype`. */
        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): LambdaConstructor {
            val ctor = LambdaConstructor(scope, FUNCTION_CLASS, 1, ::js_constructorCall, ::js_constructor)
            val proto = LambdaFunction(scope, "", 0, null, SerializableCallable { _, _, _, _ -> Undefined.instance })
            proto.defineProperty("constructor", ctor, DONTENUM)
            // ctor.prototype.constructor has to be ctor itself.
            ctor.setPrototypeProperty(proto)
            // Defined early so the prototype's own functions pick up the right prototype.
            defineProperty(scope, FUNCTION_CLASS, ctor, DONTENUM)
            ctor.prototype = ctor.prototypeProperty as Scriptable
            ctor.defineKnownBuiltInPrototypeMethod(APPLY_TAG, scope, "apply", 2, null, ::js_apply, DONTENUM, DONTENUM or READONLY)
            ctor.definePrototypeMethod(scope, "bind", 1, ::js_bind)
            ctor.defineKnownBuiltInPrototypeMethod(CALL_TAG, scope, "call", 1, null, ::js_call, DONTENUM, DONTENUM or READONLY)
            ctor.definePrototypeMethod(scope, "toSource", 1, ::js_toSource)
            ctor.definePrototypeMethod(scope, "toString", 0, ::js_toString)
            // Tagged so `instanceof` can go straight to OrdinaryHasInstance when this is the method
            // it finds, which is what it would do anyway.
            ctor.defineKnownBuiltInPrototypeMethod(
                HAS_INSTANCE_TAG, scope, SymbolKey.HAS_INSTANCE, 1, null, ::js_hasInstance,
                DONTENUM or READONLY or PERMANENT, DONTENUM or READONLY,
            )
            // Function.prototype attributes, ECMA 15.3.3.1.
            ctor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)
            if (cx.languageVersion >= Context.VERSION_ES6) ctor.setStandardPropertyAttributes(READONLY or DONTENUM)
            defineProperty(scope, FUNCTION_CLASS, ctor, DONTENUM)
            if (cx.languageVersion >= Context.VERSION_ES6) {
                val thrower = ScriptRuntime.typeErrorThrower(scope)
                val restricted = DescriptorInfo(false, Scriptable.NOT_FOUND, true, thrower, thrower, Scriptable.NOT_FOUND)
                proto.defineOwnProperty(cx, "caller", restricted)
                proto.defineOwnProperty(cx, "arguments", restricted)
            }
            if (sealed) {
                ctor.sealObject()
                (ctor.prototypeProperty as ScriptableObject).sealObject()
            }
            return ctor
        }

        /** Builds `GeneratorFunction`, which never appears in the global scope under that name. */
        internal fun initAsGeneratorFunction(scope: Scriptable, sealed: Boolean): Any {
            val proto = NativeObject()
            val function = getProperty(scope, FUNCTION_CLASS) as Scriptable
            val functionProto = getProperty(function, PROTOTYPE_PROPERTY_NAME) as Scriptable
            proto.prototype = functionProto
            val top = getTopLevelScope(scope)
            // %GeneratorFunction.prototype% and %GeneratorPrototype% name each other, read-only,
            // hidden and configurable (ES 25.2.3.2, 25.3.1.1). Upstream put an ordinary
            // `prototype`, which Object.keys listed, and gave %GeneratorPrototype% no
            // `constructor`, so it inherited Object's (D-93).
            val generatorPrototype = getTopScopeValue(top, ES6Generator.GENERATOR_TAG)
            proto.defineProperty(PROTOTYPE_PROPERTY_NAME, generatorPrototype, READONLY or DONTENUM)
            (generatorPrototype as? ScriptableObject)?.let {
                it.defineProperty("constructor", proto, READONLY or DONTENUM)
                // ES6Generator.init leaves the sealing to here, once the link is in.
                if (sealed) it.sealObject()
            }
            val ctor = LambdaConstructor(scope, GENERATOR_FUNCTION_CLASS, 1, proto, ::js_gen_constructorCall, ::js_gen_constructor)
            proto.defineProperty("constructor", ctor, READONLY or DONTENUM)
            ctor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)
            proto.defineProperty(SymbolKey.TO_STRING_TAG, "GeneratorFunction", READONLY or DONTENUM)
            // Parked on the scope, as %GeneratorPrototype% is, and not a global: upstream put it on
            // the global as an enumerable __GeneratorFunction, which Object.keys and for-in listed
            // (D-79).
            return (scope as? ScriptableObject)?.associateValue(GENERATOR_FUNCTION_CLASS, ctor) ?: ctor
        }

        /**
         * Builds %AsyncFunction% and its prototype, which every async function inherits from
         * (ECMAScript 2017, 25.5.1 and 25.5.3): a constructor named AsyncFunction that makes async
         * functions from source text, never a global, and a prototype with a `constructor` and a
         * `Symbol.toStringTag` but no `prototype`, since async functions make no objects.
         */
        internal fun initAsAsyncFunction(scope: Scriptable, sealed: Boolean): Any {
            val proto = NativeObject()
            val function = getProperty(scope, FUNCTION_CLASS) as Scriptable
            proto.prototype = getProperty(function, PROTOTYPE_PROPERTY_NAME) as Scriptable
            proto.parentScope = getTopLevelScope(scope)
            val ctor = LambdaConstructor(scope, "AsyncFunction", 1, proto, ::js_async_constructorCall, ::js_async_constructor)
            // The constructor inherits from %Function% itself (ECMAScript 2017, 25.5.2).
            ctor.prototype = function
            proto.defineProperty("constructor", ctor, READONLY or DONTENUM)
            ctor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)
            proto.defineProperty(SymbolKey.TO_STRING_TAG, "AsyncFunction", READONLY or DONTENUM)
            if (sealed) {
                ctor.sealObject()
                proto.sealObject()
            }
            return (scope as? ScriptableObject)?.associateValue(ASYNC_FUNCTION_CLASS, ctor) ?: ctor
        }

        private fun js_async_constructorCall(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            js_async_constructor(cx, scope, args)

        private fun js_async_constructor(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable =
            withoutStrictMode(cx) { jsConstructor(cx, scope, args, "async function ") }

        /**
         * `Function.prototype[Symbol.hasInstance]`, which is OrdinaryHasInstance. Upstream cast a
         * bound function's target to JSFunction, so a bound built-in crashed with a host
         * ClassCastException, and it read the bound target's `prototype` where the spec asks
         * `instanceof` of the target, so the target's own `Symbol.hasInstance` was skipped (D-92).
         */
        private fun js_hasInstance(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            ScriptRuntime.ordinaryHasInstance(cx, thisObj, args.getOrElse(0) { Undefined.instance })

        private fun js_bind(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (thisObj !is Callable) throw ScriptRuntime.notFunctionError(thisObj)
            val argc = args.size
            val boundThis: Scriptable?
            val boundArgs: Array<Any?>
            if (argc > 0) {
                boundThis = if (Undefined.isUndefined(args[0])) Undefined.SCRIPTABLE_UNDEFINED
                    else ScriptRuntime.toObjectOrNull(cx, args[0], scope)
                boundArgs = args.copyOfRange(1, argc)
            } else {
                boundThis = Undefined.SCRIPTABLE_UNDEFINED
                boundArgs = ScriptRuntime.emptyArgs
            }
            return BoundFunction(cx, scope, thisObj, boundThis, boundArgs)
        }

        private fun js_apply(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            ScriptRuntime.applyOrCall(true, cx, scope, thisObj, args)

        private fun js_call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            ScriptRuntime.applyOrCall(false, cx, scope, thisObj, args)

        private fun js_toSource(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val realf = realFunction(thisObj, "toSource")
            var indent = 0
            var flags: Set<DecompilerFlag> = setOf(DecompilerFlag.TO_SOURCE)
            if (args.isNotEmpty()) {
                indent = ScriptRuntime.toInt32(args[0])
                if (indent >= 0) flags = emptySet() else indent = 0
            }
            return realf.decompile(indent, flags)
        }

        private fun js_toString(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val realf = realFunction(thisObj, "toString")
            return realf.decompile(ScriptRuntime.toInt32(args, 0), emptySet())
        }

        private fun js_gen_constructorCall(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            js_gen_constructor(cx, scope, args)

        private fun js_constructor(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable =
            withoutStrictMode(cx) { jsConstructor(cx, scope, args, "function ") }

        private fun js_constructorCall(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            js_constructor(cx, scope, args)

        private fun js_gen_constructor(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable =
            withoutStrictMode(cx) { jsConstructor(cx, scope, args, "function * ") }

        /**
         * The Function constructor makes sloppy code even when called from strict code. The frame
         * that calls it reads as sloppy until it returns, and code it calls on the way, such as a
         * `toString` of an argument, still runs as its own code says (D-80).
         */
        private inline fun withoutStrictMode(cx: Context, block: () -> Scriptable): Scriptable {
            if (!cx.isStrictMode) return block()
            val activation = cx.currentActivationCall
            val strictMode = cx.isTopLevelStrict
            val sloppyFrame = cx.sloppyFrame
            try {
                cx.currentActivationCall = null
                cx.isTopLevelStrict = false
                cx.sloppyFrame = cx.lastInterpreterFrame
                return block()
            } finally {
                cx.sloppyFrame = sloppyFrame
                cx.isTopLevelStrict = strictMode
                cx.currentActivationCall = activation
            }
        }

        private fun realFunction(thisObj: Scriptable?, functionName: String): BaseFunction {
            if (thisObj == null) throw ScriptRuntime.notFunctionError(null)
            val x = thisObj.getDefaultValue(ScriptRuntime.FunctionClass)
            return ensureType<BaseFunction>(x, functionName)
        }

        /** `new Function(p1, p2, body)`: builds source text and compiles it in the global scope. */
        private fun jsConstructor(cx: Context, scope: Scriptable, args: Array<Any?>, prefix: String): Scriptable {
            val arglen = args.size
            val sourceBuf = StringBuilder()
            sourceBuf.append(prefix)
            // Every version but 1.2 names the function "anonymous", which is closer to the spec.
            if (cx.languageVersion != Context.VERSION_1_2) sourceBuf.append("anonymous")
            sourceBuf.append('(')
            for (i in 0 until arglen - 1) {
                if (i > 0) sourceBuf.append(',')
                sourceBuf.append(ScriptRuntime.toString(args[i]))
            }
            sourceBuf.append(") {")
            if (arglen != 0) sourceBuf.append(ScriptRuntime.toString(args[arglen - 1]))
            sourceBuf.append("\n}")
            val source = sourceBuf.toString()

            val linep = IntArray(1)
            var filename = Context.getSourcePositionFromStack(linep)
            if (filename == null) {
                filename = "<eval'ed string>"
                linep[0] = 1
            }
            val sourceURI = ScriptRuntime.makeUrlForGeneratedScript(false, filename, linep[0])
            val global = getTopLevelScope(scope)
            val reporter = DefaultErrorReporter.forEval(cx.errorReporter)
            val evaluator = Context.createInterpreter()
            // Compiled with an explicit interpreter, which forces interpreted mode.
            return cx.compileFunction(global, source, evaluator, reporter, sourceURI, 1, null)
        }
    }

    /**
     * `<function>.arguments`, which is deprecated. Reading it walks the activation stack rather
     * than costing anything on every call.
     */
    private val arguments: Any?
        get() {
        // A value assigned to .arguments wins over the live activation. This assumes the activation
        // should not stay reachable after that assignment.
        if (argumentsObj !== Scriptable.NOT_FOUND) return argumentsObj
        val cx = Context.getContext()
        val activation = ScriptRuntime.findFunctionActivation(cx, this) ?: return null
        val arguments = activation.get("arguments", activation)
        if (arguments is Arguments && cx.languageVersion >= Context.VERSION_ES6) {
            return Arguments.ReadonlyArguments(arguments, cx)
        }
        return arguments
    }
}
