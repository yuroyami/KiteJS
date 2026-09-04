/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.ScriptableObject.DescriptorInfo

/** The spec's abstract operations on objects: integrity levels, species, grouping and the rest. */
object AbstractEcmaObjectOperations {

    enum class INTEGRITY_LEVEL { FROZEN, SEALED }

    enum class KEY_COERCION { PROPERTY, COLLECTION }

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
            val desc = obj.getOwnPropertyDescriptor(cx, name)!!
            if (desc.isConfigurable()) return false
            if (level == INTEGRITY_LEVEL.FROZEN && desc.isDataDescriptor() && desc.isWritable()) return false
        }
        return true
    }

    /** SetIntegrityLevel: seals or freezes [o]. Returns false when it cannot be made non-extensible. */
    internal fun setIntegrityLevel(cx: Context, o: Any?, level: INTEGRITY_LEVEL): Boolean {
        val obj = ScriptableObject.ensureScriptableObject(o)
        if (!obj.preventExtensions()) return false
        val ids = obj.startCompoundOp(false).use { obj.getIds(it, true, true) }
        for (key in ids) {
            val desc = obj.getOwnPropertyDescriptor(cx, key)!!
            if (level == INTEGRITY_LEVEL.SEALED) {
                if (desc.isConfigurable()) {
                    desc.configurable = false
                    obj.defineOwnProperty(cx, key, desc, false)
                }
            } else {
                if (desc.isDataDescriptor() && desc.isWritable()) desc.writable = false
                if (desc.isConfigurable()) desc.configurable = false
                obj.defineOwnProperty(cx, key, desc, false)
            }
        }
        return true
    }

    /** SpeciesConstructor: the constructor to derive new objects from [s], or [defaultConstructor]. */
    fun speciesConstructor(cx: Context, s: Scriptable, defaultConstructor: Constructable): Constructable {
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
    ): Map<Any?, MutableList<Any?>> = groupBy(cx, scope, f.tag, f.getFunctionName(), items, callback, keyCoercion)

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

    fun lengthOfArrayLike(cx: Context, o: Scriptable): Long {
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
            if (desc.isConfigurable()) return false
            if (desc.hasEnumerable() && desc.enumerable != current.enumerable) return false
        }
        if (desc.isGenericDescriptor()) return true
        if (current.isDataDescriptor() != desc.isDataDescriptor()) {
            if (current.isConfigurable(false)) return false
        } else if (current.isDataDescriptor() && desc.isDataDescriptor()) {
            if (current.isConfigurable(false) && current.isWritable(false)) {
                if (desc.isWritable()) return false
                if (desc.hasValue() && desc.value != current.value) return false
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

    /** IsConstructor: does [argument] have a [[Construct]] method. */
    fun isConstructor(cx: Context, argument: Any?): Boolean {
        if (argument is LambdaConstructor) return true
        if (argument is LambdaFunction) return false
        // TODO(P4): a revocable NativeProxy function asks its target.
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
