/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * `Proxy`: a wrapper that lets a handler object intercept the operations the engine performs on a
 * target object.
 *
 * Every override below follows the same shape: look up the trap on the handler, call it when it is
 * there, then check the answer against what the target itself reports and throw a TypeError when
 * the two contradict each other. A missing trap simply falls through to the target.
 */
internal open class NativeProxy protected constructor(target: ScriptableObject, handler: Scriptable) :
    ScriptableObject() {

    private var targetObj: ScriptableObject? = target
    private var handlerObj: Scriptable? = handler

    private val typeOfValue: String = if (target is Callable) target.typeOf else super.typeOf

    /** Revoking clears both slots, which makes every later operation throw. */
    private class Revoker(private var revocableProxy: NativeProxy?) : SerializableCallable {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            revocableProxy?.let {
                it.handlerObj = null
                it.targetObj = null
            }
            revocableProxy = null
            return Undefined.instance
        }
    }

    override val className: String
        get() = getTargetThrowIfRevoked().className

    override val typeOf: String
        get() = typeOfValue

    // ---- Property access -----------------------------------------------------------------------

    override fun has(name: String, start: Scriptable): Boolean {
        val target = getTargetThrowIfRevoked()

        val trap = getTrap(TRAP_HAS)
        if (trap != null) {
            val booleanTrapResult = ScriptRuntime.toBoolean(callTrap(trap, arrayOf(target, name)))
            if (!booleanTrapResult) {
                val targetDesc = target.getOwnPropertyDescriptor(Context.getContext(), name)
                if (targetDesc != null) {
                    if (targetDesc.isConfigurable(false) || !target.isExtensible) {
                        throw ScriptRuntime.typeError(
                            "proxy can't report an existing own property '$name' as non-existent on a non-extensible object",
                        )
                    }
                }
            }
            return booleanTrapResult
        }

        return ScriptableObject.hasProperty(target, name)
    }

    override fun has(index: Int, start: Scriptable): Boolean {
        val target = getTargetThrowIfRevoked()

        val trap = getTrap(TRAP_HAS)
        if (trap != null) {
            val booleanTrapResult =
                ScriptRuntime.toBoolean(callTrap(trap, arrayOf(target, ScriptRuntime.toString(index))))
            if (!booleanTrapResult) {
                val targetDesc = target.getOwnPropertyDescriptor(Context.getContext(), index)
                if (targetDesc != null) {
                    if (targetDesc.isConfigurable(false) || !target.isExtensible) {
                        throw ScriptRuntime.typeError(NOT_CONFIGURABLE_EXISTENCE)
                    }
                }
            }
            return booleanTrapResult
        }

        return ScriptableObject.hasProperty(target, index)
    }

    override fun has(key: Symbol, start: Scriptable): Boolean {
        val target = getTargetThrowIfRevoked()

        val trap = getTrap(TRAP_HAS)
        if (trap != null) {
            val booleanTrapResult = ScriptRuntime.toBoolean(callTrap(trap, arrayOf(target, key)))
            if (!booleanTrapResult) {
                val targetDesc = target.getOwnPropertyDescriptor(Context.getContext(), key)
                if (targetDesc != null) {
                    if (targetDesc.isConfigurable(false) || !target.isExtensible) {
                        throw ScriptRuntime.typeError(NOT_CONFIGURABLE_EXISTENCE)
                    }
                }
            }
            return booleanTrapResult
        }

        return ScriptableObject.hasProperty(target, key)
    }

    override fun getIds(map: CompoundOperationMap, getNonEnumerable: Boolean, getSymbols: Boolean): Array<Any?> {
        val target = getTargetThrowIfRevoked()

        val trap = getTrap(TRAP_OWN_KEYS)
        if (trap != null) {
            val res = callTrap(trap, arrayOf(target))
            if (res !is Scriptable) throw ScriptRuntime.typeError("ownKeys trap must be an object")
            if (!ScriptRuntime.isArrayLike(res)) {
                throw ScriptRuntime.typeError("ownKeys trap must be an array like object")
            }

            val cx = Context.getContext()

            val trapResult = AbstractEcmaObjectOperations.createListFromArrayLike(
                cx,
                res,
                { o -> o is CharSequence || o is NativeString || ScriptRuntime.isSymbol(o) },
                "proxy [[OwnPropertyKeys]] must return an array with only string and symbol elements",
            )

            val extensibleTarget = target.isExtensible
            // The flags passed in are ignored here: every key has to be looked at.
            val targetKeys = target.startCompoundOp(false).use { target.getIds(it, true, true) }

            val uncheckedResultKeys = HashSet<Any?>(trapResult)
            if (uncheckedResultKeys.size != trapResult.size) {
                throw ScriptRuntime.typeError("ownKeys trap result must not contain duplicates")
            }

            val targetConfigurableKeys = ArrayList<Any?>()
            val targetNonconfigurableKeys = ArrayList<Any?>()
            for (targetKey in targetKeys) {
                val desc = target.getOwnPropertyDescriptor(cx, targetKey)
                if (desc != null && desc.isConfigurable(false)) {
                    targetNonconfigurableKeys.add(targetKey)
                } else {
                    targetConfigurableKeys.add(targetKey)
                }
            }

            if (extensibleTarget && targetNonconfigurableKeys.size == 0) {
                return trapResult.toTypedArray()
            }

            for (key in targetNonconfigurableKeys) {
                if (!uncheckedResultKeys.contains(key)) {
                    throw ScriptRuntime.typeError("proxy can't skip a non-configurable property '$key'")
                }
                uncheckedResultKeys.remove(key)
            }
            if (extensibleTarget) {
                return trapResult.toTypedArray()
            }

            for (key in targetConfigurableKeys) {
                if (!uncheckedResultKeys.contains(key)) {
                    throw ScriptRuntime.typeError("proxy can't skip a configurable property $key")
                }
                uncheckedResultKeys.remove(key)
            }

            if (uncheckedResultKeys.size > 0) {
                throw ScriptRuntime.typeError("proxy can't skip properties")
            }

            // The target is not extensible, so the answer comes from the target itself.
        }

        return target.startCompoundOp(false).use { target.getIds(it, getNonEnumerable, getSymbols) }
    }

    override fun get(name: String, start: Scriptable): Any? = get(Context.getContext(), name, start)

    override fun get(index: Int, start: Scriptable): Any? = get(Context.getContext(), index, start)

    override fun get(key: Symbol, start: Scriptable): Any? = get(Context.getContext(), key, start)

    /**
     * [[Get]] (ECMAScript 2015, 9.5.8). The trap is handed the receiver the read started from,
     * which is the proxy only when the proxy itself was read, and without a trap the target's own
     * [[Get]] runs with that receiver, through the target's whole prototype chain. Upstream handed
     * the trap the proxy every time and read the target with the target as `this` (D-89).
     */
    internal fun get(cx: Context, key: Any, receiver: Any?): Any? {
        val target = getTargetThrowIfRevoked()
        val trap = getTrap(TRAP_GET) ?: return AbstractEcmaObjectOperations.get(cx, target, key, receiver)
        val trapResult = callTrap(trap, arrayOf(target, AbstractEcmaObjectOperations.trapKey(key), receiver))
        checkGetInvariants(target.getOwnPropertyDescriptor(cx, key), trapResult)
        return trapResult
    }

    /** A `get` trap may not contradict a non-configurable property on the target. */
    private fun checkGetInvariants(targetDesc: DescriptorInfo?, trapResult: Any?) {
        if (targetDesc == null || !targetDesc.isConfigurable(false)) return
        if (targetDesc.isDataDescriptor && targetDesc.isWritable(false)) {
            if (!AbstractEcmaObjectOperations.sameValue(trapResult, targetDesc.value)) throw ScriptRuntime.typeError(GET_MUST_MATCH)
        }
        if (targetDesc.isAccessorDescriptor && Undefined.isUndefined(targetDesc.getter)) {
            if (!Undefined.isUndefined(trapResult)) throw ScriptRuntime.typeError(GET_MUST_MATCH)
        }
    }

    override fun put(name: String, start: Scriptable, value: Any?) = putOrThrow(name, start, value)

    override fun put(index: Int, start: Scriptable, value: Any?) = putOrThrow(index, start, value)

    override fun put(key: Symbol, start: Scriptable, value: Any?) = putOrThrow(key, start, value)

    /** An assignment: a refused write is a TypeError in strict code and ignored otherwise. */
    private fun putOrThrow(key: Any, start: Scriptable, value: Any?) {
        val cx = Context.getContext()
        if (!set(cx, key, value, start) && cx.isStrictMode) {
            throw ScriptRuntime.typeError("proxy refused to set the property '${AbstractEcmaObjectOperations.trapKey(key)}'")
        }
    }

    /**
     * [[Set]] (ECMAScript 2015, 9.5.9), answering whether the write was made. The trap is handed
     * the receiver as its fourth argument, and without a trap the target's own [[Set]] runs with
     * that receiver, so a write through a proxy with no traps still asks the proxy for the
     * receiver's descriptor and defines through it. Upstream called the trap without a receiver,
     * ignored a false answer even in strict code, and wrote to the target as its own receiver
     * (D-89).
     */
    internal fun set(cx: Context, key: Any, value: Any?, receiver: Any?): Boolean {
        val target = getTargetThrowIfRevoked()
        val trap = getTrap(TRAP_SET) ?: return AbstractEcmaObjectOperations.set(cx, target, key, value, receiver)
        val trapArgs = arrayOf(target, AbstractEcmaObjectOperations.trapKey(key), value, receiver)
        if (!ScriptRuntime.toBoolean(callTrap(trap, trapArgs))) return false
        checkSetInvariants(target.getOwnPropertyDescriptor(cx, key), value)
        return true
    }

    /** A `set` trap that claims success may not contradict a non-configurable property. */
    private fun checkSetInvariants(targetDesc: DescriptorInfo?, value: Any?) {
        if (targetDesc == null || !targetDesc.isConfigurable(false)) return
        if (targetDesc.isDataDescriptor && targetDesc.isWritable(false)) {
            if (!AbstractEcmaObjectOperations.sameValue(value, targetDesc.value)) {
                throw ScriptRuntime.typeError("proxy set has to use the same value as the plain call")
            }
        }
        if (targetDesc.isAccessorDescriptor && Undefined.isUndefined(targetDesc.setter)) {
            throw ScriptRuntime.typeError("proxy set has to be available")
        }
    }

    override fun delete(name: String) {
        deleteOrThrow(name)
    }

    override fun delete(index: Int) {
        deleteOrThrow(index)
    }

    override fun delete(key: Symbol) {
        deleteOrThrow(key)
    }

    /**
     * The `delete` operator: [[Delete]], whose false answer is a TypeError in strict code. The
     * answer is the result, so nothing asks the proxy afterwards whether the property is still
     * there; upstream did, which called a `has` trap after every delete (D-89).
     */
    internal fun deleteOrThrow(key: Any): Boolean {
        val cx = Context.getContext()
        val deleted = delete(cx, key)
        if (!deleted && cx.isStrictMode) {
            throw ScriptRuntime.typeError("proxy refused to delete the property '${AbstractEcmaObjectOperations.trapKey(key)}'")
        }
        return deleted
    }

    /** [[Delete]] (ECMAScript 2015, 9.5.10), answering whether the property is gone. */
    internal fun delete(cx: Context, key: Any): Boolean {
        val target = getTargetThrowIfRevoked()
        val trap = getTrap(TRAP_DELETE_PROPERTY) ?: return AbstractEcmaObjectOperations.delete(cx, target, key)
        if (!ScriptRuntime.toBoolean(callTrap(trap, arrayOf(target, AbstractEcmaObjectOperations.trapKey(key))))) return false
        checkDeleteInvariants(target, key, target.getOwnPropertyDescriptor(cx, key))
        return true
    }

    /**
     * A proxy answers [[HasProperty]], [[Get]] and [[Set]] for its whole chain, so a lookup that
     * reaches it never goes on to the proxy's own prototype (D-89).
     */
    override fun endsLookup(name: String): Boolean = true

    override fun endsLookup(index: Int): Boolean = true

    override fun endsLookup(key: Symbol): Boolean = true

    /**
     * A `deleteProperty` trap that claims success may not remove a property the target still has
     * when that property is non-configurable or the target is non-extensible. Upstream's message
     * printed the literal text `' + name + '` where the key belongs.
     */
    private fun checkDeleteInvariants(target: ScriptableObject, key: Any, targetDesc: DescriptorInfo?) {
        if (targetDesc == null) return
        if (targetDesc.isConfigurable(false) || !target.isExtensible) {
            throw ScriptRuntime.typeError(
                "proxy can't delete an existing own property '${AbstractEcmaObjectOperations.trapKey(key)}' on an not configurable or not extensible object",
            )
        }
    }

    // ---- Property descriptors ------------------------------------------------------------------

    override fun getOwnPropertyDescriptor(cx: Context, id: Any?): DescriptorInfo? {
        val target = getTargetThrowIfRevoked()

        val trap = getTrap(TRAP_GET_OWN_PROPERTY_DESCRIPTOR)
        if (trap != null) {
            val trapResultObj = callTrap(trap, arrayOf(target, id))
            if (!Undefined.isUndefined(trapResultObj) &&
                !(trapResultObj is Scriptable && !ScriptRuntime.isSymbol(trapResultObj))
            ) {
                throw ScriptRuntime.typeError(
                    "getOwnPropertyDescriptor trap has to return undefined or an object",
                )
            }

            val targetDesc =
                if (ScriptRuntime.isSymbol(id)) {
                    target.getOwnPropertyDescriptor(cx, id)
                } else {
                    target.getOwnPropertyDescriptor(cx, ScriptRuntime.toString(id))
                }

            if (Undefined.isUndefined(trapResultObj)) {
                if (targetDesc == null) return null

                if (targetDesc.isConfigurable(false) || !target.isExtensible) {
                    throw ScriptRuntime.typeError(
                        "proxy can't report an existing own property '$id' as non-existent on a non-extensible object",
                    )
                }
                return null
            }

            val trapResult = trapResultObj as Scriptable
            val value = getProperty(trapResult, "value")
            val attributes = applyDescriptorToAttributeBitset(
                DONTENUM or READONLY or PERMANENT,
                getProperty(trapResult, "enumerable"),
                getProperty(trapResult, "writable"),
                getProperty(trapResult, "configurable"),
            )
            return buildDataDescriptor(value, attributes)
        }

        if (ScriptRuntime.isSymbol(id)) return target.getOwnPropertyDescriptor(cx, id)

        return target.getOwnPropertyDescriptor(cx, ScriptRuntime.toString(id))
    }

    override fun defineOwnProperty(cx: Context, id: Any?, desc: DescriptorInfo): Boolean {
        val target = getTargetThrowIfRevoked()

        val trap = getTrap(TRAP_DEFINE_PROPERTY)
        if (trap != null) {
            val booleanTrapResult = ScriptRuntime.toBoolean(
                callTrap(trap, arrayOf(target, id, desc.toObject(trap.declarationScope!!))),
            )
            if (!booleanTrapResult) return false

            val targetDesc = target.getOwnPropertyDescriptor(Context.getContext(), id)
            val extensibleTarget = target.isExtensible

            val settingConfigFalse = desc.isConfigurable(false)

            if (targetDesc == null) {
                if (!extensibleTarget || settingConfigFalse) {
                    throw ScriptRuntime.typeError(INCOMPATIBLE_DESCRIPTOR)
                }
            } else {
                if (!AbstractEcmaObjectOperations.isCompatiblePropertyDescriptor(
                        cx,
                        extensibleTarget,
                        desc,
                        targetDesc,
                    )
                ) {
                    throw ScriptRuntime.typeError(INCOMPATIBLE_DESCRIPTOR)
                }

                if (settingConfigFalse && targetDesc.isConfigurable) {
                    throw ScriptRuntime.typeError(INCOMPATIBLE_DESCRIPTOR)
                }

                if (targetDesc.isDataDescriptor && targetDesc.isConfigurable(false) && targetDesc.isWritable) {
                    if (desc.isWritable(false)) {
                        throw ScriptRuntime.typeError(INCOMPATIBLE_DESCRIPTOR)
                    }
                }
            }
            return true
        }

        // [[DefineOwnProperty]] answers false for a refused definition, and a trapless proxy
        // passes that answer on; the target's own define throws instead, which upstream let
        // through, so a write that should only fail threw (D-89).
        return AbstractEcmaObjectOperations.defineOwnPropertyOrFalse(cx, target, id!!, desc)
    }

    // ---- Extensibility and prototype -----------------------------------------------------------

    override val isExtensible: Boolean
        get() {
            val target = getTargetThrowIfRevoked()

            val trap = getTrap(TRAP_IS_EXTENSIBLE) ?: return target.isExtensible

            val booleanTrapResult = ScriptRuntime.toBoolean(callTrap(trap, arrayOf(target)))
            if (booleanTrapResult != target.isExtensible) {
                throw ScriptRuntime.typeError("IsExtensible trap has to return the same value as the target")
            }
            return booleanTrapResult
        }

    override fun preventExtensions(): Boolean {
        val target = getTargetThrowIfRevoked()

        val trap = getTrap(TRAP_PREVENT_EXTENSIONS) ?: return target.preventExtensions()

        val booleanTrapResult = ScriptRuntime.toBoolean(callTrap(trap, arrayOf(target)))
        if (booleanTrapResult && target.isExtensible) {
            throw ScriptRuntime.typeError("target is extensible but trap returned true")
        }
        return booleanTrapResult
    }

    override var prototype: Scriptable?
        get() {
            val target = getTargetThrowIfRevoked()

            val trap = getTrap(TRAP_GET_PROTOTYPE_OF) ?: return target.prototype

            val handlerProto = callTrap(trap, arrayOf(target))
            if (Undefined.isUndefined(handlerProto) || ScriptRuntime.isSymbol(handlerProto)) {
                throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeOf(handlerProto))
            }

            val handlerProtoScriptable = ensureScriptable(handlerProto)

            if (target.isExtensible) return handlerProtoScriptable
            if (handlerProto !== target.prototype) {
                throw ScriptRuntime.typeError("getPrototypeOf trap has to return the original prototype")
            }
            return handlerProtoScriptable
        }
        set(value) {
            val target = getTargetThrowIfRevoked()

            val trap = getTrap(TRAP_SET_PROTOTYPE_OF)
            if (trap != null) {
                // The trap's answer decides nothing beyond whether it succeeded: the proxy never
                // stores a prototype of its own.
                ScriptRuntime.toBoolean(callTrap(trap, arrayOf(target, value)))
                return
            }

            target.prototype = value
        }

    /** Sets the proxy's own prototype without going through [TRAP_SET_PROTOTYPE_OF]. */
    private fun setPrototypeDirect(prototype: Scriptable?) {
        super.prototype = prototype
    }

    // ---- Traps ---------------------------------------------------------------------------------

    protected fun getTrap(trapName: String): Function? {
        val handlerProp = getProperty(handlerObj!!, trapName)
        if (Scriptable.NOT_FOUND === handlerProp) return null
        if (handlerProp == null || Undefined.isUndefined(handlerProp)) return null
        if (handlerProp !is Callable) throw ScriptRuntime.notFunctionError(handlerProp, trapName)
        return handlerProp as Function
    }

    protected fun callTrap(trap: Function, args: Array<Any?>): Any? =
        trap.call(Context.getContext(), trap.declarationScope!!, handlerObj, args)

    internal fun getTargetThrowIfRevoked(): ScriptableObject =
        targetObj ?: throw ScriptRuntime.typeError("Illegal operation attempted on a revoked proxy")

    /** A proxy whose target is callable, so the proxy itself is a function too. */
    internal class NativeProxyFunction(target: ScriptableObject, handler: Scriptable) :
        NativeProxy(target, handler), Function {

        override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
            val target = getTargetThrowIfRevoked()

            val trap = getTrap(TRAP_CONSTRUCT)
            if (trap != null) {
                // Upstream hands the raw argument array to the trap, which only reads as an array
                // in script because Java interop wraps it. There is no interop here, so the trap
                // gets a real JavaScript array, the same way the apply trap does (D-51).
                val result = callTrap(trap, arrayOf(target, cx.newArray(scope, args), this))
                if (result !is Scriptable || ScriptRuntime.isSymbol(result)) {
                    throw ScriptRuntime.typeError("Constructor trap has to return a scriptable.")
                }
                return result as ScriptableObject
            }

            return (target as Constructable).construct(cx, scope, args)
        }

        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val target = getTargetThrowIfRevoked()

            val argumentsList = cx.newArray(scope, args)

            val trap = getTrap(TRAP_APPLY)
            if (trap != null) {
                return callTrap(trap, arrayOf(target, thisObj, argumentsList))
            }

            return ScriptRuntime.applyOrCall(true, cx, scope, target, arrayOf(thisObj, argumentsList))
        }

        override val declarationScope: Scriptable?
            get() {
                val target = getTargetThrowIfRevoked()
                if (target is Function) return target.declarationScope
                throw Kit.codeBug()
            }
    }

    companion object {
        private const val PROXY_TAG = "Proxy"

        private const val TRAP_GET_PROTOTYPE_OF = "getPrototypeOf"
        private const val TRAP_SET_PROTOTYPE_OF = "setPrototypeOf"
        private const val TRAP_IS_EXTENSIBLE = "isExtensible"
        private const val TRAP_PREVENT_EXTENSIONS = "preventExtensions"
        private const val TRAP_GET_OWN_PROPERTY_DESCRIPTOR = "getOwnPropertyDescriptor"
        private const val TRAP_DEFINE_PROPERTY = "defineProperty"
        private const val TRAP_HAS = "has"
        private const val TRAP_GET = "get"
        private const val TRAP_SET = "set"
        private const val TRAP_DELETE_PROPERTY = "deleteProperty"
        private const val TRAP_OWN_KEYS = "ownKeys"
        private const val TRAP_APPLY = "apply"
        private const val TRAP_CONSTRUCT = "construct"

        private const val GET_MUST_MATCH = "proxy get has to return the same value as the plain call"
        private const val INCOMPATIBLE_DESCRIPTOR = "proxy can't define an incompatible property descriptor"
        private const val NOT_CONFIGURABLE_EXISTENCE =
            "proxy can't check an existing property ' + name + ' existance on an not configurable or not extensible object"

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val constructor = object : LambdaConstructor(
                scope,
                PROXY_TAG,
                2,
                null, // Proxy has no prototype property.
                null as SerializableCallable?, // and may not be called without new.
                SerializableConstructable { icx, s, args -> constructorImpl(icx, s, args) },
            ) {
                override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
                    val obj = targetConstructor!!.construct(cx, scope, args) as NativeProxy
                    // Assigning through the property would hit the setPrototypeOf trap.
                    obj.setPrototypeDirect(classPrototype)
                    obj.parentScope = scope
                    return obj
                }
            }

            constructor.defineConstructorMethod(
                scope,
                "revocable",
                2,
                SerializableCallable { icx, s, thisObj, args -> revocable(icx, s, thisObj, args) },
            )
            if (sealed) constructor.sealObject()
            return constructor
        }

        private fun constructorImpl(cx: Context, scope: Scriptable, args: Array<Any?>): NativeProxy {
            if (args.size < 2) {
                throw ScriptRuntime.typeErrorById(
                    "msg.method.missing.parameter",
                    "Proxy.ctor",
                    "2",
                    args.size.toString(),
                )
            }
            val target = ensureScriptableObjectButNotSymbol(args[0])
            val handler = ensureScriptableObjectButNotSymbol(args[1])

            val proxy = if (target is Function) {
                NativeProxyFunction(target, handler)
            } else {
                NativeProxy(target, handler)
            }

            proxy.setPrototypeDirect(getClassPrototype(scope, PROXY_TAG))
            proxy.parentScope = scope
            return proxy
        }

        private fun revocable(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any {
            if (!ScriptRuntime.isObject(thisObj)) {
                throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeOf(thisObj))
            }
            val proxy = constructorImpl(cx, scope, args)

            val revocable = cx.newObject(scope) as NativeObject

            revocable.put("proxy", revocable, proxy)
            revocable.put("revoke", revocable, LambdaFunction(scope, "", 0, Revoker(proxy)))
            return revocable
        }
    }
}
