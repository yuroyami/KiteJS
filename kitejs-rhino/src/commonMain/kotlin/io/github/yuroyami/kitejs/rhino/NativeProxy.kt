/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

/**
 * `Proxy`: a wrapper that lets a handler object intercept the operations the engine performs on a
 * target object.
 *
 * Every override below follows the same shape: look up the trap on the handler, call it when it is
 * there, then check the answer against what the target itself reports and throw a TypeError when
 * the two contradict each other. A missing trap simply falls through to the target.
 */
internal open class NativeProxy protected constructor(target: Scriptable, handler: Scriptable) :
    ScriptableObject(), Constructable {

    private var targetObj: Scriptable? = target
    private var handlerObj: Scriptable? = handler

    private val typeOfValue: String = if (target is Callable) ScriptRuntime.typeOf(target) else super.typeOf
    /** ProxyCreate captures this capability, so revocation does not change IsConstructor. */
    internal val isConstructor: Boolean = AbstractEcmaObjectOperations.isConstructor(target)

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

    override fun has(name: String, start: Scriptable): Boolean = hasProperty(name)

    override fun has(index: Int, start: Scriptable): Boolean = hasProperty(index)

    override fun has(key: Symbol, start: Scriptable): Boolean = hasProperty(key)

    /** [[HasProperty]] (ES 10.5.7). */
    private fun hasProperty(key: Any): Boolean {
        val target = getTargetThrowIfRevoked()

        val trap = getTrap(TRAP_HAS)
        if (trap != null) {
            val p = AbstractEcmaObjectOperations.trapKey(key)
            val booleanTrapResult = ScriptRuntime.toBoolean(callTrap(trap, arrayOf(target, p)))
            if (!booleanTrapResult) {
                val targetDesc = target.getOwnPropertyDescriptor(Context.getContext(), p)
                if (targetDesc != null && (targetDesc.isConfigurable(false) || !target.isExtensible)) {
                    throw ScriptRuntime.typeError(
                        "proxy can't report an existing own property '$p' as non-existent on a non-configurable property or a non-extensible object",
                    )
                }
            }
            return booleanTrapResult
        }

        return when (key) {
            is Symbol -> ScriptableObject.hasProperty(target, key)
            is Int -> ScriptableObject.hasProperty(target, key)
            else -> ScriptableObject.hasProperty(target, key as String)
        }
    }

    /**
     * The keys a caller asks for, filtered the way EnumerableOwnProperties and the key lists built
     * on [[OwnPropertyKeys]] filter them: symbols dropped unless [getSymbols], and, unless
     * [getNonEnumerable], every key the proxy's own [[GetOwnProperty]] does not report as an
     * enumerable property, so a getOwnPropertyDescriptor trap decides what Object.keys, for-in and
     * JSON.stringify see. Upstream handed the trap's list back unfiltered, so Object.keys of a
     * proxy listed non-enumerable keys and threw on a symbol, and JSON.stringify crashed with a
     * host ClassCastException (D-91).
     */
    override fun getIds(map: CompoundOperationMap, getNonEnumerable: Boolean, getSymbols: Boolean): Array<Any?> {
        val keys = ownPropertyKeys()
        if (getNonEnumerable && getSymbols) return Array(keys.size) { idKey(keys[it]) }
        val cx = Context.getContext()
        val result = ArrayList<Any?>(keys.size)
        for (key in keys) {
            if (key is Symbol && !getSymbols) continue
            if (!getNonEnumerable && getOwnPropertyDescriptor(cx, key)?.isEnumerable != true) continue
            result.add(idKey(key))
        }
        return result.toTypedArray()
    }

    /**
     * An id in the form [getIds] hands out for every other object: an array index as an Int, so a
     * caller that goes on to ask has(index) or get(index), as for-in does, reaches an array target's
     * elements, which are not found under their string names.
     */
    private fun idKey(key: Any?): Any? {
        if (key !is String) return key
        val id = ScriptRuntime.toStringIdOrIndex(key)
        return id.stringId ?: id.index
    }

    /**
     * [[OwnPropertyKeys]] (ES 10.5.11): the trap's list, in the trap's order, once it is checked
     * against the target. Keys are compared as property keys, so an index the target lists as a
     * number matches the string the trap returns; upstream compared the two kinds as they came,
     * which made every non-configurable index look skipped, and after the checks for a
     * non-extensible target it answered with the target's own keys in place of the trap's (D-91).
     */
    override fun ownPropertyKeys(): Array<Any?> {
        val target = getTargetThrowIfRevoked()

        val trap = getTrap(TRAP_OWN_KEYS)
            ?: return target.ownPropertyKeys().map { AbstractEcmaObjectOperations.trapKey(it!!) }.toTypedArray()

        val res = callTrap(trap, arrayOf(target))
        if (!ScriptRuntime.isObject(res)) throw ScriptRuntime.typeError("ownKeys trap must return an object")

        val cx = Context.getContext()

        val trapResult = AbstractEcmaObjectOperations.createListFromArrayLike(
            cx,
            res as Scriptable,
            { o -> o is CharSequence || ScriptRuntime.isSymbol(o) },
            "proxy [[OwnPropertyKeys]] must return an array with only string and symbol elements",
        ).map { if (it is CharSequence) it.toString() else it }

        val uncheckedResultKeys = LinkedHashSet<Any?>(trapResult)
        if (uncheckedResultKeys.size != trapResult.size) {
            throw ScriptRuntime.typeError("ownKeys trap result must not contain duplicates")
        }

        val extensibleTarget = target.isExtensible
        val targetKeys = target.ownPropertyKeys()

        val targetConfigurableKeys = ArrayList<Any?>()
        val targetNonconfigurableKeys = ArrayList<Any?>()
        for (targetKey in targetKeys) {
            val key = AbstractEcmaObjectOperations.trapKey(targetKey!!)
            val desc = target.getOwnPropertyDescriptor(cx, key)
            if (desc != null && desc.isConfigurable(false)) {
                targetNonconfigurableKeys.add(key)
            } else {
                targetConfigurableKeys.add(key)
            }
        }

        if (extensibleTarget && targetNonconfigurableKeys.isEmpty()) return trapResult.toTypedArray()

        for (key in targetNonconfigurableKeys) {
            if (!uncheckedResultKeys.remove(key)) {
                throw ScriptRuntime.typeError("proxy can't skip a non-configurable property '$key'")
            }
        }
        if (extensibleTarget) return trapResult.toTypedArray()

        for (key in targetConfigurableKeys) {
            if (!uncheckedResultKeys.remove(key)) {
                throw ScriptRuntime.typeError("proxy can't skip the property '$key' of a non-extensible target")
            }
        }
        if (uncheckedResultKeys.isNotEmpty()) {
            throw ScriptRuntime.typeError("proxy can't report a new property on a non-extensible target")
        }
        return trapResult.toTypedArray()
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
    private fun checkDeleteInvariants(target: Scriptable, key: Any, targetDesc: DescriptorInfo?) {
        if (targetDesc == null) return
        if (targetDesc.isConfigurable(false) || !target.isExtensible) {
            throw ScriptRuntime.typeError(
                "proxy can't delete an existing own property '${AbstractEcmaObjectOperations.trapKey(key)}' on an not configurable or not extensible object",
            )
        }
    }

    // ---- Property descriptors ------------------------------------------------------------------

    /**
     * [[GetOwnProperty]] (ES 10.5.5). The trap's answer goes through ToPropertyDescriptor and
     * CompletePropertyDescriptor and is checked against the target's own property before it is
     * believed. Upstream read only `value` and the three flags out of it, so an accessor came back
     * as a data property holding undefined, an invalid descriptor was accepted, and a trap could
     * report a property non-configurable or read-only that the target does not have as such (D-91).
     */
    override fun getOwnPropertyDescriptor(cx: Context, id: Any?): DescriptorInfo? {
        val target = getTargetThrowIfRevoked()
        val key = propertyKey(id)

        val trap = getTrap(TRAP_GET_OWN_PROPERTY_DESCRIPTOR) ?: return target.getOwnPropertyDescriptor(cx, key)

        val trapResultObj = callTrap(trap, arrayOf(target, key))
        if (!Undefined.isUndefined(trapResultObj) && !ScriptRuntime.isObject(trapResultObj)) {
            throw ScriptRuntime.typeError("getOwnPropertyDescriptor trap has to return undefined or an object")
        }

        val targetDesc = target.getOwnPropertyDescriptor(cx, key)

        if (Undefined.isUndefined(trapResultObj)) {
            if (targetDesc == null) return null
            if (targetDesc.isConfigurable(false) || !target.isExtensible) {
                throw ScriptRuntime.typeError(
                    "proxy can't report an existing own property '$key' as non-existent on a non-extensible object",
                )
            }
            return null
        }

        val extensibleTarget = target.isExtensible
        val resultDesc = DescriptorInfo(trapResultObj as Scriptable)
        checkPropertyDefinition(resultDesc)
        completePropertyDescriptor(resultDesc)

        if (!AbstractEcmaObjectOperations.isCompatiblePropertyDescriptor(cx, extensibleTarget, resultDesc, targetDesc)) {
            throw ScriptRuntime.typeError("proxy can't report an incompatible property descriptor for '$key'")
        }
        if (resultDesc.isConfigurable(false)) {
            if (targetDesc == null || targetDesc.isConfigurable) {
                throw ScriptRuntime.typeError("proxy can't report the configurable or missing property '$key' as non-configurable")
            }
            if (resultDesc.isWritable(false) && targetDesc.isWritable) {
                throw ScriptRuntime.typeError("proxy can't report the writable property '$key' as non-configurable and non-writable")
            }
        }
        return resultDesc
    }

    override fun defineOwnPropertyOrFalse(cx: Context, id: Any?, desc: DescriptorInfo): Boolean =
        defineOwnProperty(cx, id, desc)

    override fun defineOwnProperty(cx: Context, id: Any?, desc: DescriptorInfo): Boolean {
        val target = getTargetThrowIfRevoked()

        val trap = getTrap(TRAP_DEFINE_PROPERTY)
        if (trap != null) {
            val key = propertyKey(id)
            val booleanTrapResult = ScriptRuntime.toBoolean(
                callTrap(trap, arrayOf(target, key, desc.toObject(cx.currentRealm ?: ScriptRuntime.getTopCallScope(cx)))),
            )
            if (!booleanTrapResult) return false

            val targetDesc = target.getOwnPropertyDescriptor(cx, key)
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

    /**
     * [[GetPrototypeOf]] (ES 10.5.1): the trap's answer has to be an object or null, and for a
     * non-extensible target the target's own prototype. Writing goes through [setPrototypeOf],
     * so an engine path that assigns the property still asks the trap, and a refusal throws.
     */
    override var prototype: Scriptable?
        get() {
            val target = getTargetThrowIfRevoked()

            val trap = getTrap(TRAP_GET_PROTOTYPE_OF) ?: return target.prototype

            val handlerProto = callTrap(trap, arrayOf(target))
            if (handlerProto != null && (handlerProto !is Scriptable || !ScriptRuntime.isObject(handlerProto))) {
                throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeOf(handlerProto))
            }
            val handlerProtoScriptable = handlerProto as Scriptable?

            if (target.isExtensible) return handlerProtoScriptable
            if (handlerProtoScriptable !== target.prototype) {
                throw ScriptRuntime.typeError("getPrototypeOf trap has to return the original prototype")
            }
            return handlerProtoScriptable
        }
        set(value) {
            if (!setPrototypeOf(Context.getContext(), value)) {
                throw ScriptRuntime.typeError("proxy refused to set the prototype")
            }
        }

    /**
     * [[SetPrototypeOf]] (ES 10.5.2): false when the trap says so, and a TypeError when it claims
     * success on a non-extensible target whose prototype is not the one asked for. Upstream threw
     * the trap's answer away, so Object.setPrototypeOf succeeded and Reflect.setPrototypeOf
     * answered true whatever the trap said, and nothing was checked against the target (D-91).
     */
    override fun setPrototypeOf(cx: Context, proto: Scriptable?): Boolean {
        val target = getTargetThrowIfRevoked()

        val trap = getTrap(TRAP_SET_PROTOTYPE_OF) ?: return target.setPrototypeOf(cx, proto)

        if (!ScriptRuntime.toBoolean(callTrap(trap, arrayOf(target, proto)))) return false
        if (target.isExtensible) return true
        if (proto !== target.prototype) {
            throw ScriptRuntime.typeError("setPrototypeOf trap returned true for a non-extensible target with a different prototype")
        }
        return true
    }

    /** Sets the proxy's own prototype without going through [TRAP_SET_PROTOTYPE_OF]. */
    private fun setPrototypeDirect(prototype: Scriptable?) {
        super.prototype = prototype
    }

    // ---- Traps ---------------------------------------------------------------------------------

    protected fun getTrap(trapName: String): Callable? {
        val handlerProp = getProperty(handlerObj!!, trapName)
        if (Scriptable.NOT_FOUND === handlerProp) return null
        if (handlerProp == null || Undefined.isUndefined(handlerProp)) return null
        if (handlerProp !is Callable) throw ScriptRuntime.notFunctionError(handlerProp, trapName)
        return handlerProp
    }

    protected fun callTrap(trap: Callable, args: Array<Any?>): Any? {
        val cx = Context.getContext()
        val scope = (trap as? Function)?.declarationScope
            ?: (trap as? Scriptable)?.let { getTopLevelScope(it) }
            ?: ScriptRuntime.getTopCallScope(cx)
        return trap.call(cx, scope, handlerObj, args)
    }

    /** A property key as a trap receives it: a string or a symbol, never an int index. */
    private fun propertyKey(id: Any?): Any = when (id) {
        is Symbol -> id
        is String -> id
        else -> ScriptRuntime.toString(id)
    }

    /** CompletePropertyDescriptor (ES 6.2.6.6): the fields a descriptor leaves out get their defaults. */
    private fun completePropertyDescriptor(desc: DescriptorInfo) {
        if (desc.isGenericDescriptor || desc.isDataDescriptor) {
            if (!desc.hasValue()) desc.value = Undefined.instance
            if (!desc.hasWritable()) desc.writable = false
        } else {
            if (!desc.hasGetter()) desc.getter = Undefined.instance
            if (!desc.hasSetter()) desc.setter = Undefined.instance
        }
        if (!desc.hasEnumerable()) desc.enumerable = false
        if (!desc.hasConfigurable()) desc.configurable = false
    }

    internal fun getTargetThrowIfRevoked(): Scriptable =
        targetObj ?: throw ScriptRuntime.typeError("Illegal operation attempted on a revoked proxy")

    override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable =
        construct(cx, scope, args, this)

    /** [[Construct]] also supports host constructors that do not expose [[Call]]. */
    internal fun construct(cx: Context, scope: Scriptable, args: Array<Any?>, newTarget: Scriptable): Scriptable {
        if (!isConstructor) throw ScriptRuntime.typeErrorById("msg.not.ctor", typeOf)
        val target = getTargetThrowIfRevoked()

        val trap = getTrap(TRAP_CONSTRUCT)
        if (trap != null) {
            // Upstream hands the raw argument array to the trap, which only reads as an array
            // in script because Java interop wraps it. There is no interop here, so the trap
            // gets a real JavaScript array, the same way the apply trap does (D-51).
            val result = callTrap(trap, arrayOf(target, cx.newArray(scope, args), newTarget))
            if (!ScriptRuntime.isObject(result)) {
                throw ScriptRuntime.typeError("Constructor trap has to return a scriptable.")
            }
            return result as Scriptable
        }

        return AbstractEcmaObjectOperations.construct(cx, scope, target as Constructable, args, newTarget)
    }

    /** A proxy whose target is callable, so the proxy itself is a function too. */
    internal class NativeProxyFunction(target: Scriptable, handler: Scriptable) :
        NativeProxy(target, handler), Function {
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
                return getTopLevelScope(target)
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

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val constructor = object : LambdaConstructor(
                scope,
                PROXY_TAG,
                2,
                null, // Proxy has no prototype property.
                null as SerializableCallable?, // and may not be called without new.
                SerializableConstructable { icx, s, args -> constructorImpl(icx, s, args) },
            ) {
                override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable = cx.withRealm(declarationScope!!) {
                    val realm = declarationScope!!
                    val obj = targetConstructor!!.construct(cx, realm, args) as NativeProxy
                    // Assigning through the property would hit the setPrototypeOf trap.
                    obj.setPrototypeDirect(classPrototype)
                    obj.parentScope = realm
                    obj
                }

                /** ProxyCreate never reads newTarget: a proxy has no prototype of its own to give. */
                override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>, newTarget: Scriptable): Scriptable =
                    construct(cx, scope, args)
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
            val target = AbstractEcmaObjectOperations.ensureObject(args[0])
            val handler = AbstractEcmaObjectOperations.ensureObject(args[1])

            val proxy = if (target is Callable) {
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
            // A built-in function that is not a constructor has no `prototype` (ES 10.3); upstream
            // gave the revoke function one (D-91).
            revocable.put("revoke", revocable, LambdaFunction(scope, "", 0, Revoker(proxy), false))
            return revocable
        }
    }
}
