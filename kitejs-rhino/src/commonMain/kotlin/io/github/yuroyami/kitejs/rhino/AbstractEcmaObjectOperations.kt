/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import io.github.yuroyami.kitejs.rhino.ScriptableObject.DescriptorInfo
import io.github.yuroyami.kitejs.rhino.typedarrays.NativeTypedArrayView

/** The spec's abstract operations on objects: integrity levels, species, grouping and the rest. */
public object AbstractEcmaObjectOperations {

    /** How locked down an object is: `Object.freeze` or `Object.seal`. */
    public enum class INTEGRITY_LEVEL { FROZEN, SEALED }

    /** Whether a key is coerced the way a property name is, or the way a Map key is. */
    public enum class KEY_COERCION { PROPERTY, COLLECTION }

    internal fun hasOwnProperty(cx: Context, o: Any?, property: Any?): Boolean {
        val obj = ScriptableObject.ensureScriptable(o)
        return hasOwnPropertyKey(cx, obj, ScriptRuntime.toPropertyKey(property))
    }

    /**
     * HasOwnProperty(O, P) for a key that is already a property key. A proxy answers through
     * [[GetOwnProperty]]; upstream asked its has trap, which also reports inherited properties,
     * and converted the key without ToPrimitive, so a key object whose @@toPrimitive returns a
     * symbol threw (D-91).
     */
    internal fun hasOwnPropertyKey(cx: Context, obj: Scriptable, key: Any): Boolean = when {
        obj is NativeProxy -> obj.getOwnPropertyDescriptor(cx, key) != null
        key is Symbol -> ScriptableObject.ensureSymbolScriptable(obj).has(key, obj)
        key is Int -> obj.has(key, obj)
        else -> obj.has(key.toString(), obj)
    }

    internal fun testIntegrityLevel(cx: Context, o: Any?, level: INTEGRITY_LEVEL): Boolean {
        val obj = ScriptableObject.ensureScriptableObject(o)
        if (obj.isExtensible) return false
        val ids = obj.startCompoundOp(false).use { obj.getIds(it, true, true) }
        for (name in ids) {
            val desc = obj.getOwnPropertyDescriptor(cx, name) ?: continue
            if (desc.isConfigurable) return false
            if (level == INTEGRITY_LEVEL.FROZEN && desc.isDataDescriptor && desc.isWritable) return false
        }
        return true
    }

    /**
     * SetIntegrityLevel (ECMAScript 2015, 7.3.14): seals or freezes [o], answering false when it
     * cannot be made non-extensible. Each key gets DefinePropertyOrThrow with only the fields the
     * level changes, so an object that refuses, as a typed array refuses for its elements, makes it
     * a TypeError; sealing reads no descriptors at all. Upstream redefined every key from its full
     * current descriptor and ignored a refusal (D-88).
     */
    internal fun setIntegrityLevel(cx: Context, o: Any?, level: INTEGRITY_LEVEL): Boolean {
        val obj = ScriptableObject.ensureScriptableObject(o)
        if (!obj.preventExtensions()) return false
        val ids = obj.startCompoundOp(false).use { obj.getIds(it, true, true) }
        val nf = Scriptable.NOT_FOUND
        for (key in ids) {
            val desc = if (level == INTEGRITY_LEVEL.SEALED) {
                ScriptableObject.DescriptorInfo(nf, nf, false, nf, nf, nf)
            } else {
                val current = obj.getOwnPropertyDescriptor(cx, key) ?: continue
                if (current.isAccessorDescriptor) {
                    ScriptableObject.DescriptorInfo(nf, nf, false, nf, nf, nf)
                } else {
                    ScriptableObject.DescriptorInfo(nf, false, false, nf, nf, nf)
                }
            }
            // The public form, which a proxy overrides; the internal one would define the key on
            // the proxy object itself and never reach the trap.
            if (!obj.defineOwnProperty(cx, key, desc)) {
                throw ScriptRuntime.typeErrorById(
                    "msg.define.refused",
                    if (key is Symbol) key.toString() else ScriptRuntime.toString(key),
                )
            }
        }
        return true
    }

    /** SpeciesConstructor: the constructor to derive new objects from [s], or [defaultConstructor]. */
    public fun speciesConstructor(cx: Context, s: Scriptable, defaultConstructor: Constructable): Constructable {
        val constructor = ScriptableObject.getProperty(s, "constructor")
        if (constructor === Scriptable.NOT_FOUND || Undefined.isUndefined(constructor)) {
            return defaultConstructor
        }
        if (!ScriptRuntime.isObject(constructor)) {
            throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeOf(constructor))
        }
        val species = ScriptableObject.getProperty(constructor as Scriptable, SymbolKey.SPECIES)
        if (species === Scriptable.NOT_FOUND || species == null || Undefined.isUndefined(species)) {
            return defaultConstructor
        }
        if (!isConstructor(species)) {
            throw ScriptRuntime.typeErrorById("msg.not.ctor", ScriptRuntime.typeOf(species))
        }
        return species as Constructable
    }

    // ---- [[Get]], [[Set]] and [[DefineOwnProperty]] as the spec states them ----------------------
    //
    // Rhino's get and put look at one object at a time and answer nothing about success, which is
    // enough for an assignment but not for Reflect, whose methods take a separate receiver and
    // report whether the target agreed, nor for a proxy forwarding to its target. These follow the
    // spec's algorithms on top of each object's own descriptor methods, so every exotic object
    // takes part through the overrides it already has (D-89). A key is what ToPropertyKey gives:
    // a Symbol, an array index as an Int, or a String.

    /** O.[[Get]](P, Receiver): [key] looked up from [o], with [receiver] as `this` for a getter. */
    internal fun get(cx: Context, o: Scriptable, key: Any, receiver: Any?): Any? {
        var obj: Scriptable? = o
        while (obj != null) {
            if (obj is NativeProxy) return obj.get(cx, key, receiver)
            if (obj !is ScriptableObject) {
                val v = rawGet(obj, key, ScriptRuntime.toObject(cx, ScriptableObject.getTopLevelScope(obj), receiver))
                if (v !== Scriptable.NOT_FOUND) return v
            } else {
                val desc = obj.getOwnPropertyDescriptor(cx, key)
                if (desc != null) {
                    if (!desc.isAccessorDescriptor) return desc.value.let { if (it === Scriptable.NOT_FOUND) Undefined.instance else it }
                    val getter = desc.getter
                    if (getter !is Callable) return Undefined.instance
                    return callAccessor(cx, getter, receiver, ScriptRuntime.emptyArgs)
                }
                if (key !is Symbol && endsLookup(obj, key)) return Undefined.instance
            }
            obj = obj.prototype
        }
        return Undefined.instance
    }

    /**
     * O.[[Set]](P, V, Receiver), answering whether the write was made. A proxy runs its trap, a
     * typed array keeps a numeric key to its elements, and everything else is OrdinarySet.
     */
    internal fun set(cx: Context, o: Scriptable, key: Any, value: Any?, receiver: Any?): Boolean = when {
        o is NativeProxy -> o.set(cx, key, value, receiver)
        o is NativeTypedArrayView && key !is Symbol -> o.set(cx, key, value, receiver) ?: ordinarySet(cx, o, key, value, receiver)
        o is ScriptableObject -> ordinarySet(cx, o, key, value, receiver)
        else -> {
            // An object outside the descriptor protocol takes the write the only way it can.
            rawPut(o, key, ScriptRuntime.toObject(cx, ScriptableObject.getTopLevelScope(o), receiver), value)
            true
        }
    }

    /** OrdinarySet and OrdinarySetWithOwnDescriptor (ECMAScript 2015, 9.1.9). */
    internal fun ordinarySet(cx: Context, o: ScriptableObject, key: Any, value: Any?, receiver: Any?): Boolean {
        val ownDesc = o.getOwnPropertyDescriptor(cx, key)
        if (ownDesc == null) {
            val parent = o.prototype
            if (parent != null) return set(cx, parent, key, value, receiver)
            return setOnReceiver(cx, key, value, receiver)
        }
        if (!ownDesc.isAccessorDescriptor) {
            if (!ScriptableObject.isTrue(ownDesc.writable)) return false
            return setOnReceiver(cx, key, value, receiver)
        }
        val setter = ownDesc.setter
        if (setter !is Callable) return false
        callAccessor(cx, setter, receiver, arrayOf(value))
        return true
    }

    /** The data property half of OrdinarySetWithOwnDescriptor: the write lands on [receiver]. */
    private fun setOnReceiver(cx: Context, key: Any, value: Any?, receiver: Any?): Boolean {
        if (receiver !is Scriptable || !ScriptRuntime.isObject(receiver)) return false
        if (receiver !is ScriptableObject) {
            rawPut(receiver, key, receiver, value)
            return true
        }
        val existing = receiver.getOwnPropertyDescriptor(cx, key)
            ?: return createDataProperty(cx, receiver, key, value)
        if (existing.isAccessorDescriptor || !ScriptableObject.isTrue(existing.writable)) return false
        if (receiver is NativeProxy) {
            val valueOnly = DescriptorInfo(Scriptable.NOT_FOUND, Scriptable.NOT_FOUND, Scriptable.NOT_FOUND, Scriptable.NOT_FOUND, Scriptable.NOT_FOUND, value)
            return receiver.defineOwnProperty(cx, key, valueOnly)
        }
        // Receiver.[[DefineOwnProperty]](P, {[[Value]]: V}) on a writable data property it owns.
        // Writing it through the object's own put finds that property first and keeps whatever a
        // built-in property does on a write, which redefining it would replace.
        rawPut(receiver, key, receiver, value)
        return true
    }

    /** CreateDataProperty: a writable, enumerable, configurable data property, or false. */
    internal fun createDataProperty(cx: Context, o: ScriptableObject, key: Any, value: Any?): Boolean =
        defineOwnPropertyOrFalse(cx, o, key, DescriptorInfo(true, true, true, value))

    /**
     * O.[[DefineOwnProperty]](P, Desc) as a boolean. Rhino throws for a definition it refuses, so
     * the definition is validated against the current descriptor first, the way
     * ValidateAndApplyPropertyDescriptor would, and only made when it can succeed. A proxy answers
     * through its trap, and an exotic object may still refuse with false.
     */
    internal fun defineOwnPropertyOrFalse(cx: Context, o: ScriptableObject, key: Any, desc: DescriptorInfo): Boolean {
        if (o is NativeProxy) return o.defineOwnProperty(cx, key, desc)
        val current = o.getOwnPropertyDescriptor(cx, key)
        if (!isCompatiblePropertyDescriptor(cx, o.isExtensible, desc, current)) return false
        return o.defineOwnProperty(cx, key, desc)
    }

    /** O.[[Delete]](P) as a boolean: only an own property is looked at. */
    internal fun delete(cx: Context, o: ScriptableObject, key: Any): Boolean {
        if (o is NativeProxy) return o.delete(cx, key)
        val desc = o.getOwnPropertyDescriptor(cx, key) ?: return true
        if (!ScriptableObject.isTrue(desc.configurable)) return false
        when (key) {
            is Symbol -> o.delete(key)
            is Int -> o.delete(key)
            else -> o.delete(key as String)
        }
        return o.getOwnPropertyDescriptor(cx, key) == null
    }

    /** Set(O, P, V, true): [[Set]] with O as the receiver, and a refused write is a TypeError. */
    internal fun setOrThrow(cx: Context, o: Scriptable, key: Any, value: Any?) {
        if (!set(cx, o, key, value, o)) throw ScriptRuntime.typeErrorById("msg.modify.readonly", key.toString())
    }

    // ---- Walking enumerable own properties --------------------------------------------------------

    /**
     * The keys EnumerableOwnProperties and CopyDataProperties walk: every own key of [o], with or
     * without the symbols, in [[OwnPropertyKeys]] order. Enumerability is not filtered here but by
     * [isOwnEnumerable] just before each value is read, which is where the spec asks
     * [[GetOwnProperty]]; filtering first would put every getOwnPropertyDescriptor trap call of a
     * proxy before the first get, and miss a property a getter makes enumerable or deletes.
     */
    internal fun ownKeysForEnumeration(o: Scriptable, symbols: Boolean): Array<Any?> =
        if (o is ScriptableObject) o.startCompoundOp(false).use { o.getIds(it, true, symbols) } else o.getIds()

    /**
     * Whether [key] is, at this moment, an own enumerable property of [o]. A proxy answers through
     * its [[GetOwnProperty]], so its trap is asked once per key; any other object answers from its
     * own property, which tells the same without building a descriptor.
     */
    internal fun isOwnEnumerable(cx: Context, o: Scriptable, key: Any): Boolean {
        if (o is NativeProxy) return o.getOwnPropertyDescriptor(cx, key)?.isEnumerable == true
        if (o !is ScriptableObject) {
            return when (key) {
                is Symbol -> ScriptableObject.ensureSymbolScriptable(o).has(key, o)
                is Int -> o.has(key, o)
                else -> o.has(key.toString(), o)
            }
        }
        return try {
            when (key) {
                is Symbol -> o.has(key, o) && (o.getAttributes(key) and ScriptableObject.DONTENUM) == 0
                is Int -> o.has(key, o) && (o.getAttributes(key) and ScriptableObject.DONTENUM) == 0
                else -> {
                    val name = key.toString()
                    o.has(name, o) && (o.getAttributes(name) and ScriptableObject.DONTENUM) == 0
                }
            }
        } catch (e: RhinoException) {
            // An object that cannot report attributes for a property it has lists it.
            true
        }
    }

    /** Get(O, P) for one of these walks, through the trap for a proxy and the plain lookup otherwise. */
    internal fun getForEnumeration(cx: Context, o: Scriptable, key: Any): Any? {
        val value = when {
            o is NativeProxy -> o.get(cx, key, o)
            key is Symbol -> ScriptableObject.getProperty(o, key)
            key is Int -> ScriptableObject.getProperty(o, key)
            else -> ScriptableObject.getProperty(o, key.toString())
        }
        return if (value === Scriptable.NOT_FOUND) Undefined.instance else value
    }

    /** Calls a getter or setter with [receiver] as `this`, converted the way `call` converts it. */
    private fun callAccessor(cx: Context, accessor: Callable, receiver: Any?, args: Array<Any?>): Any? {
        val scope = (accessor as? Scriptable)?.let { ScriptableObject.getTopLevelScope(it) } ?: ScriptRuntime.getTopCallScope(cx)
        val thisObj = receiver as? Scriptable ?: ScriptRuntime.getApplyOrCallThis(cx, scope, receiver, 1, accessor)
        return accessor.call(cx, scope, thisObj, args)
    }

    private fun endsLookup(o: ScriptableObject, key: Any): Boolean =
        if (key is Int) o.endsLookup(key) else o.endsLookup(key as String)

    /** The key in the form a proxy trap is handed: a Symbol or a String. */
    internal fun trapKey(key: Any): Any = if (key is Int) key.toString() else key

    internal fun rawGet(o: Scriptable, key: Any, start: Scriptable): Any? = when (key) {
        is Symbol -> ScriptableObject.ensureSymbolScriptable(o).get(key, start)
        is Int -> o.get(key, start)
        else -> o.get(key as String, start)
    }

    internal fun rawPut(o: Scriptable, key: Any, start: Scriptable, value: Any?) {
        when (key) {
            is Symbol -> ScriptableObject.ensureSymbolScriptable(o).put(key, start, value)
            is Int -> o.put(key, start, value)
            else -> o.put(key as String, start, value)
        }
    }

    internal fun put(cx: Context, o: Scriptable, p: String, v: Any?, isThrow: Boolean) {
        val base = ScriptableObject.getBase(o, p) ?: o
        if (base is ScriptableObject) {
            if (base.putOwnProperty(p, o, v, isThrow)) return
            o.put(p, o, v)
        } else {
            base.put(p, o, v)
        }
    }

    internal fun put(cx: Context, o: Scriptable, p: Int, v: Any?, isThrow: Boolean) {
        val base = ScriptableObject.getBase(o, p) ?: o
        if (base is ScriptableObject) {
            if (base.putOwnProperty(p, o, v, isThrow)) return
            o.put(p, o, v)
        } else {
            base.put(p, o, v)
        }
    }

    internal fun put(cx: Context, o: Scriptable, p: Symbol, v: Any?, isThrow: Boolean) {
        val base = ScriptableObject.getBase(o, p) ?: o
        if (base is ScriptableObject) {
            if (base.putOwnProperty(p, o, v, isThrow)) return
            ScriptableObject.ensureSymbolScriptable(o).put(p, o, v)
        } else {
            ScriptableObject.ensureSymbolScriptable(base).put(p, o, v)
        }
    }

    internal fun groupBy(
        cx: Context,
        scope: Scriptable,
        f: IdFunctionObject,
        items: Any?,
        callback: Any?,
        keyCoercion: KEY_COERCION,
    ): Map<Any?, MutableList<Any?>> = groupBy(cx, scope, f.tag, f.functionName, items, callback, keyCoercion)

    internal fun groupBy(
        cx: Context,
        scope: Scriptable,
        classTag: Any?,
        functionName: String,
        items: Any?,
        callback: Any?,
        keyCoercion: KEY_COERCION,
    ): Map<Any?, MutableList<Any?>> {
        if (cx.languageVersion >= Context.VERSION_ES6) {
            ScriptRuntimeES6.requireObjectCoercible(cx, items, classTag, functionName)
        }
        if (callback !is Callable) {
            throw ScriptRuntime.typeErrorById("msg.isnt.function", callback, ScriptRuntime.typeOf(callback))
        }
        val groups = LinkedHashMap<Any?, MutableList<Any?>>()
        val iterator = ScriptRuntime.callIterator(items, cx, scope)
        IteratorLikeIterable(cx, scope, iterator).use { it ->
            var i = 0.0
            for (o in it) {
                if (i > NativeNumber.MAX_SAFE_INTEGER) {
                    it.close()
                    throw ScriptRuntime.typeError("Too many values to iterate")
                }
                val args = arrayOf(o, i)
                var key = callback.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, args)
                if (keyCoercion == KEY_COERCION.PROPERTY) {
                    if (!ScriptRuntime.isSymbol(key)) key = ScriptRuntime.toString(key)
                } else {
                    if (key is Number && key.toDouble() == ScriptRuntime.negativeZero) key = ScriptRuntime.zeroObj
                }
                val group = groups.getOrPut(key) { mutableListOf() }
                group.add(o)
                i++
            }
        }
        return groups
    }

    internal fun createListFromArrayLike(cx: Context, o: Scriptable, elementTypesPredicate: (Any?) -> Boolean, msg: String): List<Any?> {
        val obj = ScriptableObject.ensureScriptableObject(o)
        if (obj is NativeArray) {
            val arr = obj.toArray()
            for (next in arr) {
                if (!elementTypesPredicate(next)) {
                    throw ScriptRuntime.typeError(msg)
                }
            }
            return arr.asList()
        }
        val len = lengthOfArrayLike(cx, obj)
        val list = mutableListOf<Any?>()
        var index = 0L
        while (index < len) {
            val next = ScriptableObject.getProperty(obj, index.toInt())
            if (!elementTypesPredicate(next)) {
                throw ScriptRuntime.typeError(msg)
            }
            list.add(next)
            index++
        }
        return list
    }

    public fun lengthOfArrayLike(cx: Context, o: Scriptable): Long {
        val value = ScriptableObject.getProperty(o, "length")
        return ScriptRuntime.toLength(arrayOf(value), 0)
    }

    internal fun isCompatiblePropertyDescriptor(cx: Context, extensible: Boolean, desc: DescriptorInfo, current: DescriptorInfo?): Boolean =
        validateAndApplyPropertyDescriptor(cx, Undefined.SCRIPTABLE_UNDEFINED, Undefined.SCRIPTABLE_UNDEFINED, extensible, desc, current)

    /**
     * ValidateAndApplyPropertyDescriptor, the checking half only. The "apply" steps are left to the
     * caller, same as upstream, so [o] and [p] are unused here.
     */
    internal fun validateAndApplyPropertyDescriptor(
        cx: Context,
        o: Scriptable,
        p: Scriptable,
        extensible: Boolean,
        desc: DescriptorInfo,
        current: DescriptorInfo?,
    ): Boolean {
        if (current == null || Undefined.isUndefined(current)) {
            if (!extensible) return false
            return true
        }
        if (!desc.hasEnumerable() &&
            !desc.hasConfigurable() &&
            !desc.hasWritable() &&
            !desc.hasGetter() &&
            !desc.hasSetter() &&
            !desc.hasValue()
        ) {
            return true
        }
        if (current.isConfigurable(false)) {
            if (desc.isConfigurable) return false
            if (desc.hasEnumerable() && desc.enumerable != current.enumerable) return false
        }
        if (desc.isGenericDescriptor) return true
        if (current.isDataDescriptor != desc.isDataDescriptor) {
            if (current.isConfigurable(false)) return false
        } else if (current.isDataDescriptor && desc.isDataDescriptor) {
            if (current.isConfigurable(false) && current.isWritable(false)) {
                if (desc.isWritable) return false
                if (desc.hasValue() && !sameValue(desc.value, current.value)) return false
                return true
            }
        } else {
            if (current.isConfigurable(false)) {
                if (desc.hasSetter() && desc.setter != current.setter) return false
                if (desc.hasGetter() && desc.getter != current.getter) return false
                return true
            }
        }
        return true
    }

    /** SameValue: NaN is itself, and the two zeroes differ. */
    internal fun sameValue(x: Any?, y: Any?): Boolean {
        if (x is Number && y is Number) {
            val a = x.toDouble()
            val b = y.toDouble()
            if (a.isNaN() && b.isNaN()) return true
            return a == b && (a != 0.0 || a.toRawBits() == b.toRawBits())
        }
        return ScriptRuntime.shallowEq(x, y)
    }

    /**
     * IsConstructor: does [argument] have a [[Construct]] method. Every Rhino function implements
     * [Constructable], so each function kind says for itself whether `new` would get past its own
     * check; upstream took the interface at its word, which made `Date.now`, arrow functions,
     * generators, methods and accessors constructors to Reflect.construct and species lookups (D-91).
     */
    public fun isConstructor(cx: Context, argument: Any?): Boolean = isConstructor(argument)

    internal fun isConstructor(argument: Any?): Boolean = when (argument) {
        is NativeProxy.NativeProxyFunction -> argument.isConstructor
        is BaseFunction -> argument.isConstructor
        else -> argument is Constructable
    }

    /**
     * Construct(F, argumentsList, newTarget). Rhino's [Constructable.construct] knows no newTarget,
     * which is always F itself there, so a different one goes to the function kind's own
     * newTarget-aware [[Construct]]; anything else is constructed as it is and given the prototype
     * newTarget names afterwards.
     */
    internal fun construct(cx: Context, scope: Scriptable, f: Constructable, args: Array<Any?>, newTarget: Scriptable): Scriptable =
        when {
            f is NativeProxy.NativeProxyFunction -> f.construct(cx, scope, args, newTarget)
            f is BaseFunction -> f.construct(cx, scope, args, newTarget)
            newTarget === f -> f.construct(cx, scope, args)
            else -> {
                val result = f.construct(cx, scope, args)
                result.prototype = getPrototypeFromConstructor(cx, newTarget) { result.prototype }
                result
            }
        }

    /**
     * GetPrototypeFromConstructor: newTarget's `prototype` when it is an object, otherwise the
     * intrinsic [intrinsicDefault] picks from the realm [GetFunctionRealm][getFunctionRealm] finds.
     */
    internal fun getPrototypeFromConstructor(cx: Context, constructor: Scriptable, intrinsicDefault: (realm: Scriptable) -> Scriptable?): Scriptable? {
        val proto = get(cx, constructor, "prototype", constructor)
        if (ScriptRuntime.isObject(proto)) return proto as Scriptable
        return intrinsicDefault(getFunctionRealm(cx, constructor))
    }

    /** GetFunctionRealm: the global of the realm [obj] was made in, looking through bound functions and proxies. */
    internal fun getFunctionRealm(cx: Context, obj: Scriptable): Scriptable = when (obj) {
        is BoundFunction -> (obj.targetFunction as? Scriptable)?.let { getFunctionRealm(cx, it) } ?: ScriptableObject.getTopLevelScope(obj)
        is NativeProxy -> getFunctionRealm(cx, obj.getTargetThrowIfRevoked())
        else -> ScriptableObject.getTopLevelScope(obj)
    }

    /**
     * The prototype a built-in constructor [ctor] gives its objects, as found in [realm]: its own
     * `prototype` in its own realm, otherwise the same intrinsic's in the other realm. Which
     * intrinsic [ctor] is comes from comparing it with its own realm's built-ins, since a name is
     * not enough (GeneratorFunction is registered under an internal one); a constructor that is
     * none of them is looked up by name in [realm], and keeps its own prototype when [realm] has
     * no such global.
     */
    internal fun intrinsicPrototype(cx: Context, realm: Scriptable, ctor: BaseFunction): Scriptable? {
        val home = ScriptableObject.getTopLevelScope(ctor)
        if (home === realm) return ctor.prototypeProperty as? Scriptable
        TopLevel.Builtins.entries.firstOrNull { builtinCtor(home, it) === ctor }?.let { return TopLevel.getBuiltinPrototype(realm, it) }
        return ScriptableObject.getClassPrototype(realm, ctor.functionName) ?: ctor.prototypeProperty as? Scriptable
    }

    /** The built-in constructor [type] of the realm whose global is [realm], or whatever its global of that name holds. */
    private fun builtinCtor(realm: Scriptable, type: TopLevel.Builtins): Any? {
        TopLevel.cachedBuiltinCtor(realm, type)?.let { return it }
        if (type == TopLevel.Builtins.GeneratorFunction) return ScriptableObject.getTopScopeValue(realm, BaseFunction.GENERATOR_FUNCTION_CLASS)
        if (type == TopLevel.Builtins.AsyncFunction) return ScriptableObject.getTopScopeValue(realm, BaseFunction.ASYNC_FUNCTION_CLASS)
        return ScriptableObject.getProperty(realm, type.name)
    }

    internal fun isRegExp(cx: Context, scope: Scriptable, argument: Any?): Boolean {
        if (!ScriptRuntime.isObject(argument)) return false
        val matcher = ScriptRuntime.getObjectElem(argument, SymbolKey.MATCH, cx, scope)
        if (!Undefined.isUndefined(matcher)) {
            return ScriptRuntime.toBoolean(matcher)
        }
        val regExpProxy = ScriptRuntime.checkRegExpProxy(cx)
        if (argument is Scriptable && regExpProxy.isRegExp(argument)) return true
        return false
    }
}
