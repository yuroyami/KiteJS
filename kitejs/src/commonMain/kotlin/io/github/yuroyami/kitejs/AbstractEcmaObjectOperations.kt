/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.ScriptableObject.DescriptorInfo
import io.github.yuroyami.kitejs.typedarrays.NativeTypedArrayView

/** The spec's abstract operations on objects: integrity levels, species, grouping and the rest. */
public object AbstractEcmaObjectOperations {

    /** How locked down an object is: `Object.freeze` or `Object.seal`. */
    public enum class INTEGRITY_LEVEL { FROZEN, SEALED }

    /** Whether a key is coerced the way a property name is, or the way a Map key is. */
    public enum class KEY_COERCION { PROPERTY, COLLECTION }

    internal fun hasOwnProperty(cx: Context, o: Any?, property: Any?): Boolean {
        val obj = ScriptableObject.ensureScriptable(o)
        if (property is Symbol) {
            return ScriptableObject.ensureSymbolScriptable(o).has(property, obj)
        }
        val s = ScriptRuntime.toStringIdOrIndex(property)
        val stringId = s.stringId ?: return obj.has(s.index, obj)
        return obj.has(stringId, obj)
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
        if (species !is Constructable) {
            throw ScriptRuntime.typeErrorById("msg.not.ctor", ScriptRuntime.typeOf(species))
        }
        return species
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

    /** IsConstructor: does [argument] have a [[Construct]] method. */
    public fun isConstructor(cx: Context, argument: Any?): Boolean {
        if (argument is LambdaConstructor) return true
        if (argument is LambdaFunction) return false
        if (argument is NativeProxy.NativeProxyFunction) {
            return isConstructor(cx, argument.getTargetThrowIfRevoked())
        }
        return argument is Constructable
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
