/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** The `Set` builtin, including the ES2025 set algebra methods. */
class NativeSet : ScriptableObject() {

    private val entries = Hashtable()

    /** Only an object the constructor built counts as a Set; the prototype itself does not. */
    private var instanceOfSet = false

    override val className: String
        get() = CLASS_NAME

    private fun js_add(k: Any?): Any {
        // The spec folds -0 into +0.
        if (k is Number && k.toDouble() == ScriptRuntime.negativeZero) {
            entries.put(ScriptRuntime.zeroObj, ScriptRuntime.zeroObj)
            return this
        }
        entries.put(k, k)
        return this
    }

    private fun js_delete(arg: Any?): Any = entries.deleteEntry(arg)

    private fun js_has(arg: Any?): Boolean {
        if (arg is Number && arg.toDouble() == ScriptRuntime.negativeZero) {
            return entries.has(ScriptRuntime.zeroObj)
        }
        return entries.has(arg)
    }

    private fun js_clear(): Any {
        entries.clear()
        return Undefined.instance
    }

    private fun js_getSize(): Any = entries.size

    private fun js_iterator(scope: Scriptable, type: NativeCollectionIterator.Type): Any =
        NativeCollectionIterator(scope, ITERATOR_TAG, type, entries.iterator())

    private fun js_forEach(cx: Context, scope: Scriptable, arg1: Any?, arg2: Any?): Any {
        if (arg1 !is Callable) throw ScriptRuntime.notFunctionError(arg1)
        val isStrict = cx.isStrictMode
        for (entry in entries) {
            // The spec re-converts on every step, so a primitive `this` is rebuilt each time.
            var thisObj = ScriptRuntime.toObjectOrNull(cx, arg2, scope)
            if (thisObj == null && !isStrict) thisObj = scope
            if (thisObj == null) thisObj = Undefined.SCRIPTABLE_UNDEFINED
            arg1.call(cx, scope, thisObj, arrayOf(entry.value(), entry.value(), this))
        }
        return Undefined.instance
    }

    // ---- The set algebra -----------------------------------------------------------------------
    //
    // Every one of these takes a "set-like" object, not only a real Set: anything with a `size`
    // property and callable `has` and `keys`. Which side gets walked depends on the two sizes, so
    // the number of calls into the other object stays proportional to the smaller one.

    private fun js_intersection(cx: Context, scope: Scriptable, args: Array<Any?>): Any {
        val otherObj = if (args.isNotEmpty()) args[0] else Undefined.instance
        val result = newResult(cx, scope)
        val other = readSetLike(otherObj).callablesThenSize()

        if (entries.size <= other.size) {
            for (entry in entries) {
                if (ScriptRuntime.toBoolean(other.callHas(cx, scope, otherObj, entry.key()))) {
                    result.js_add(entry.key())
                }
            }
        } else {
            IteratorLikeIterable(cx, scope, other.keyIterator(cx, scope)).use { it ->
                for (key in it) if (js_has(key)) result.js_add(key)
            }
        }
        return result
    }

    private fun js_union(cx: Context, scope: Scriptable, args: Array<Any?>): Any {
        val otherObj = if (args.isNotEmpty()) args[0] else Undefined.instance
        val result = newResult(cx, scope)
        for (entry in entries) result.js_add(entry.key())

        val other = readSetLike(otherObj).sizeThenCallables()
        IteratorLikeIterable(cx, scope, other.keyIterator(cx, scope)).use { it ->
            for (key in it) result.js_add(key)
        }
        return result
    }

    private fun js_difference(cx: Context, scope: Scriptable, args: Array<Any?>): Any {
        val otherObj = if (args.isNotEmpty()) args[0] else Undefined.instance
        val result = newResult(cx, scope)
        val other = readSetLike(otherObj).callablesThenSize()

        if (entries.size > other.size) {
            // Walking the other side is cheaper: copy everything, then take away what it holds.
            for (entry in entries) result.js_add(entry.key())
            IteratorLikeIterable(cx, scope, other.keyIterator(cx, scope)).use { it ->
                for (key in it) {
                    if (key is Number && key.toDouble() == ScriptRuntime.negativeZero) {
                        result.js_delete(ScriptRuntime.zeroObj)
                    } else {
                        result.js_delete(key)
                    }
                }
            }
        } else {
            for (entry in entries) {
                if (!ScriptRuntime.toBoolean(other.callHas(cx, scope, otherObj, entry.key()))) {
                    result.js_add(entry.key())
                }
            }
        }
        return result
    }

    private fun js_symmetricDifference(cx: Context, scope: Scriptable, args: Array<Any?>): Any {
        val otherObj = if (args.isNotEmpty()) args[0] else Undefined.instance
        val result = newResult(cx, scope)
        val other = readSetLike(otherObj).sizeThenCallables()

        for (entry in entries) {
            if (!ScriptRuntime.toBoolean(other.callHas(cx, scope, otherObj, entry.key()))) {
                result.js_add(entry.key())
            }
        }
        IteratorLikeIterable(cx, scope, other.keyIterator(cx, scope)).use { it ->
            for (key in it) if (!js_has(key)) result.js_add(key)
        }
        return result
    }

    private fun js_isSubsetOf(cx: Context, scope: Scriptable, args: Array<Any?>): Any {
        val otherObj = if (args.isNotEmpty()) args[0] else Undefined.instance
        val other = readSetLike(otherObj).callablesThenSize()

        // A bigger set can never be a subset.
        if (entries.size > other.size) return false
        for (entry in entries) {
            if (!ScriptRuntime.toBoolean(other.callHas(cx, scope, otherObj, entry.key()))) return false
        }
        return true
    }

    private fun js_isSupersetOf(cx: Context, scope: Scriptable, args: Array<Any?>): Any {
        val otherObj = if (args.isNotEmpty()) args[0] else Undefined.instance
        val other = readSetLike(otherObj).sizeThenCallables()

        IteratorLikeIterable(cx, scope, other.keyIterator(cx, scope)).use { it ->
            for (value in it) if (!js_has(value)) return false
        }
        return true
    }

    private fun js_isDisjointFrom(cx: Context, scope: Scriptable, args: Array<Any?>): Any {
        val otherObj = if (args.isNotEmpty()) args[0] else Undefined.instance
        val other = readSetLike(otherObj).callablesThenSize()

        if (entries.size <= other.size) {
            for (entry in entries) {
                if (ScriptRuntime.toBoolean(other.callHas(cx, scope, otherObj, entry.key()))) return false
            }
        } else {
            IteratorLikeIterable(cx, scope, other.keyIterator(cx, scope)).use { it ->
                for (key in it) if (js_has(key)) return false
            }
        }
        return true
    }

    private fun newResult(cx: Context, scope: Scriptable): NativeSet {
        val result = cx.newObject(scope, CLASS_NAME) as NativeSet
        result.instanceOfSet = true
        return result
    }

    companion object {
        private const val CLASS_NAME = "Set"
        internal const val ITERATOR_TAG = "Set Iterator"

        /** The private key `Map` and `Set` both hang their size getter on. */
        internal val GETSIZE: SymbolKey = SymbolKey("[Symbol.getSize]", Symbol.Kind.REGULAR)

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val constructor = LambdaConstructor(
                scope,
                CLASS_NAME,
                0,
                LambdaConstructor.CONSTRUCTOR_NEW,
                SerializableConstructable { icx, s, args -> jsConstructor(icx, s, args) },
            )
            constructor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)

            val propAttrs = DONTENUM or READONLY
            constructor.definePrototypeMethod(scope, "add", 1, SerializableCallable { _, _, thisObj, args -> realThis(thisObj, "add").js_add(NativeMap.key(args)) }, DONTENUM, propAttrs)
            constructor.definePrototypeMethod(scope, "delete", 1, SerializableCallable { _, _, thisObj, args -> realThis(thisObj, "add").js_delete(NativeMap.key(args)) }, DONTENUM, propAttrs)
            constructor.definePrototypeMethod(scope, "has", 1, SerializableCallable { _, _, thisObj, args -> realThis(thisObj, "add").js_has(NativeMap.key(args)) }, DONTENUM, propAttrs)
            constructor.definePrototypeMethod(scope, "clear", 0, SerializableCallable { _, _, thisObj, _ -> realThis(thisObj, "add").js_clear() }, DONTENUM, propAttrs)
            constructor.definePrototypeMethod(scope, "values", 0, SerializableCallable { _, s, thisObj, _ -> realThis(thisObj, "values").js_iterator(s, NativeCollectionIterator.Type.VALUES) }, DONTENUM, propAttrs)
            constructor.definePrototypeAlias("values", "keys", propAttrs)
            constructor.definePrototypeAlias("values", SymbolKey.ITERATOR, DONTENUM)

            constructor.definePrototypeMethod(scope, "forEach", 1, SerializableCallable { icx, s, thisObj, args ->
                realThis(thisObj, "forEach").js_forEach(icx, s, NativeMap.key(args), if (args.size > 1) args[1] else Undefined.instance)
            }, DONTENUM, propAttrs)

            constructor.definePrototypeMethod(scope, "entries", 0, SerializableCallable { _, s, thisObj, _ -> realThis(thisObj, "values").js_iterator(s, NativeCollectionIterator.Type.BOTH) }, DONTENUM, propAttrs)

            constructor.definePrototypeMethod(scope, "intersection", 1, SerializableCallable { icx, s, thisObj, args -> realThis(thisObj, "intersection").js_intersection(icx, s, args) }, DONTENUM, propAttrs)
            constructor.definePrototypeMethod(scope, "union", 1, SerializableCallable { icx, s, thisObj, args -> realThis(thisObj, "union").js_union(icx, s, args) }, DONTENUM, propAttrs)
            constructor.definePrototypeMethod(scope, "difference", 1, SerializableCallable { icx, s, thisObj, args -> realThis(thisObj, "difference").js_difference(icx, s, args) }, DONTENUM, propAttrs)
            constructor.definePrototypeMethod(scope, "symmetricDifference", 1, SerializableCallable { icx, s, thisObj, args -> realThis(thisObj, "symmetricDifference").js_symmetricDifference(icx, s, args) }, DONTENUM, propAttrs)
            constructor.definePrototypeMethod(scope, "isSubsetOf", 1, SerializableCallable { icx, s, thisObj, args -> realThis(thisObj, "isSubsetOf").js_isSubsetOf(icx, s, args) }, DONTENUM, propAttrs)
            constructor.definePrototypeMethod(scope, "isSupersetOf", 1, SerializableCallable { icx, s, thisObj, args -> realThis(thisObj, "isSupersetOf").js_isSupersetOf(icx, s, args) }, DONTENUM, propAttrs)
            constructor.definePrototypeMethod(scope, "isDisjointFrom", 1, SerializableCallable { icx, s, thisObj, args -> realThis(thisObj, "isDisjointFrom").js_isDisjointFrom(icx, s, args) }, DONTENUM, propAttrs)

            // "size" is an accessor with a configurable descriptor, which none of the other
            // helpers here produce, so it is built by hand.
            val desc = cx.newObject(scope) as ScriptableObject
            desc.put("enumerable", desc, false)
            desc.put("configurable", desc, true)
            val sizeFunc = LambdaFunction(scope, "get size", 0, SerializableCallable { _, _, thisObj, _ -> realThis(thisObj, "add").js_getSize() })
            sizeFunc.setPrototypeProperty(Undefined.instance)
            desc.put("get", desc, sizeFunc)
            constructor.definePrototypeProperty(cx, "size", desc)
            constructor.definePrototypeProperty(cx, GETSIZE, desc)

            constructor.definePrototypeProperty(SymbolKey.TO_STRING_TAG, CLASS_NAME, DONTENUM or READONLY)

            ScriptRuntimeES6.addSymbolSpecies(cx, scope, constructor)
            if (sealed) {
                constructor.sealObject()
                (constructor.prototypeProperty as ScriptableObject).sealObject()
            }
            return constructor
        }

        private fun jsConstructor(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
            val ns = NativeSet()
            ns.instanceOfSet = true
            if (args.isNotEmpty()) loadFromIterable(cx, scope, ns, NativeMap.key(args))
            return ns
        }

        /**
         * Fills a new set from an iterable. `add` is looked up on the prototype rather than called
         * directly, because a script may have replaced it.
         */
        internal fun loadFromIterable(cx: Context, scope: Scriptable, set: ScriptableObject, arg1: Any?) {
            if (arg1 == null || Undefined.instance == arg1) return
            val ito = ScriptRuntime.callIterator(arg1, cx, scope)
            if (Undefined.instance == ito) return

            // The set is not finished being built, so a throwaway instance provides the prototype.
            val dummy = ensureScriptableObject(cx.newObject(scope, set.className))
            val add = ScriptRuntime.getPropAndThis(dummy.prototype, "add", cx, scope)!!.callable

            IteratorLikeIterable(cx, scope, ito).use { it ->
                for (value in it) {
                    val finalVal = if (value === Scriptable.NOT_FOUND) Undefined.instance else value
                    add.call(cx, scope, set, arrayOf(finalVal))
                }
            }
        }

        private fun realThis(thisObj: Scriptable?, name: String): NativeSet {
            val ns = LambdaConstructor.convertThisObject<NativeSet>(thisObj)
            // Stands in for the spec's "Set internal data slot" check.
            if (!ns.instanceOfSet) throw ScriptRuntime.typeErrorById("msg.incompat.call", name)
            return ns
        }

        /** What the spec calls GetSetRecord, before the parts have been checked. */
        private fun readSetLike(otherObj: Any?): RawSetLike {
            val scriptable = ensureScriptable(otherObj)
            val sizeVal = getProperty(scriptable, "size")
            val hasVal = getProperty(scriptable, "has")
            val keysVal = getProperty(scriptable, "keys")
            if (sizeVal === Scriptable.NOT_FOUND) throw ScriptRuntime.typeError("Set-like object must have a 'size' property")
            if (hasVal === Scriptable.NOT_FOUND) throw ScriptRuntime.typeError("Set-like object must have a 'has' method")
            if (keysVal === Scriptable.NOT_FOUND) throw ScriptRuntime.typeError("Set-like object must have a 'keys' method")
            return RawSetLike(scriptable, sizeVal, hasVal, keysVal)
        }

        /**
         * The three parts read but not yet checked. Which check comes first is observable when the
         * other object is wrong in more than one way, so the two orders upstream uses both exist.
         */
        private class RawSetLike(
            val scriptable: Scriptable,
            val sizeVal: Any?,
            val hasVal: Any?,
            val keysVal: Any?,
        ) {
            fun callablesThenSize(): SetLike {
                requireCallable(hasVal, "has")
                requireCallable(keysVal, "keys")
                return SetLike(scriptable, hasVal as Callable, keysVal as Callable, requireSize(sizeVal))
            }

            fun sizeThenCallables(): SetLike {
                val size = requireSize(sizeVal)
                requireCallable(hasVal, "has")
                requireCallable(keysVal, "keys")
                return SetLike(scriptable, hasVal as Callable, keysVal as Callable, size)
            }
        }

        /** A checked set-like object: its `has`, its `keys` and how many entries it claims. */
        private class SetLike(
            private val scriptable: Scriptable,
            private val hasMethod: Callable,
            private val keysMethod: Callable,
            val size: Int,
        ) {
            fun callHas(cx: Context, scope: Scriptable, obj: Any?, key: Any?): Any? =
                hasMethod.call(cx, scope, ensureScriptable(obj), arrayOf(key))

            fun keyIterator(cx: Context, scope: Scriptable): Any? =
                ScriptRuntime.callIterator(keysMethod.call(cx, scope, scriptable, ScriptRuntime.emptyArgs), cx, scope)
        }

        private fun requireCallable(value: Any?, name: String) {
            if (value !is Callable) {
                throw ScriptRuntime.typeErrorById("msg.isnt.function", name, ScriptRuntime.typeOf(value))
            }
        }

        private fun requireSize(sizeVal: Any?): Int {
            val d = ScriptRuntime.toNumber(sizeVal)
            if (d.isNaN()) throw ScriptRuntime.typeError("size is not a number")
            // An infinite size means "bigger than anything here", so the walk goes the other way.
            return if (d.isInfinite()) Int.MAX_VALUE else floor(d)
        }

        private fun floor(d: Double): Int {
            val f = kotlin.math.floor(d)
            return when {
                f >= Int.MAX_VALUE.toDouble() -> Int.MAX_VALUE
                f <= Int.MIN_VALUE.toDouble() -> Int.MIN_VALUE
                else -> f.toInt()
            }
        }
    }
}
