/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** The `Map` builtin, over the insertion-ordered [Hashtable]. */
class NativeMap : ScriptableObject() {

    private val entries = Hashtable()

    /** Only an object the constructor built counts as a Map; the prototype itself does not. */
    private var instanceOfMap = false

    override val className: String
        get() = CLASS_NAME

    private fun js_set(k: Any?, v: Any?): Any {
        // The spec folds -0 into +0 for the key.
        var key = k
        if (key is Number && key.toDouble() == ScriptRuntime.negativeZero) key = ScriptRuntime.zeroObj
        entries.put(key, v)
        return this
    }

    private fun js_delete(arg: Any?): Any = entries.deleteEntry(arg)

    private fun js_get(arg: Any?): Any? = entries.getEntry(arg)?.value() ?: Undefined.instance

    private fun js_has(arg: Any?): Any = entries.has(arg)

    private fun js_getSize(): Any = entries.size

    private fun js_clear(): Any {
        entries.clear()
        return Undefined.instance
    }

    private fun js_iterator(scope: Scriptable, type: NativeCollectionIterator.Type): Any =
        NativeCollectionIterator(scope, ITERATOR_TAG, type, entries.iterator())

    private fun js_forEach(cx: Context, scope: Scriptable, arg1: Any?, arg2: Any?): Any {
        if (arg1 !is Callable) {
            throw ScriptRuntime.typeErrorById("msg.isnt.function", arg1, ScriptRuntime.typeOf(arg1))
        }
        val isStrict = cx.isStrictMode
        for (entry in entries) {
            // The spec re-converts on every step, so a primitive `this` is rebuilt each time.
            var thisObj = ScriptRuntime.toObjectOrNull(cx, arg2, scope)
            if (thisObj == null && !isStrict) thisObj = scope
            if (thisObj == null) thisObj = Undefined.SCRIPTABLE_UNDEFINED
            arg1.call(cx, scope, thisObj, arrayOf(entry.value(), entry.key(), this))
        }
        return Undefined.instance
    }

    companion object {
        private const val CLASS_NAME = "Map"
        internal const val ITERATOR_TAG = "Map Iterator"

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val constructor = LambdaConstructor(
                scope,
                CLASS_NAME,
                0,
                LambdaConstructor.CONSTRUCTOR_NEW,
                SerializableConstructable { icx, s, args -> jsConstructor(icx, s, args) },
            )
            constructor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)

            constructor.defineConstructorMethod(scope, "groupBy", 2, SerializableCallable { icx, s, thisObj, args -> jsGroupBy(icx, s, thisObj, args) })

            constructor.definePrototypeMethod(scope, "set", 2, SerializableCallable { _, _, thisObj, args -> realThis(thisObj, "set").js_set(key(args), if (args.size > 1) args[1] else Undefined.instance) })
            constructor.definePrototypeMethod(scope, "delete", 1, SerializableCallable { _, _, thisObj, args -> realThis(thisObj, "delete").js_delete(key(args)) })
            constructor.definePrototypeMethod(scope, "get", 1, SerializableCallable { _, _, thisObj, args -> realThis(thisObj, "get").js_get(key(args)) })
            constructor.definePrototypeMethod(scope, "has", 1, SerializableCallable { _, _, thisObj, args -> realThis(thisObj, "has").js_has(key(args)) })
            constructor.definePrototypeMethod(scope, "clear", 0, SerializableCallable { _, _, thisObj, _ -> realThis(thisObj, "clear").js_clear() })
            constructor.definePrototypeMethod(scope, "keys", 0, SerializableCallable { _, s, thisObj, _ -> realThis(thisObj, "keys").js_iterator(s, NativeCollectionIterator.Type.KEYS) })
            constructor.definePrototypeMethod(scope, "values", 0, SerializableCallable { _, s, thisObj, _ -> realThis(thisObj, "values").js_iterator(s, NativeCollectionIterator.Type.VALUES) })
            constructor.definePrototypeMethod(scope, "forEach", 1, SerializableCallable { icx, s, thisObj, args ->
                realThis(thisObj, "forEach").js_forEach(icx, s, if (args.isNotEmpty()) args[0] else Undefined.instance, if (args.size > 1) args[1] else Undefined.instance)
            })

            constructor.definePrototypeMethod(scope, "entries", 0, SerializableCallable { _, s, thisObj, _ -> realThis(thisObj, "entries").js_iterator(s, NativeCollectionIterator.Type.BOTH) })
            constructor.definePrototypeAlias("entries", SymbolKey.ITERATOR, DONTENUM)

            // "size" is an accessor with a configurable descriptor, which none of the other
            // helpers here produce, so it is built by hand.
            val desc = cx.newObject(scope) as ScriptableObject
            desc.put("enumerable", desc, false)
            desc.put("configurable", desc, true)
            val sizeFunc = LambdaFunction(scope, "get size", 0, SerializableCallable { _, _, thisObj, _ -> realThis(thisObj, "size").js_getSize() }, false)
            desc.put("get", desc, sizeFunc)
            constructor.definePrototypeProperty(cx, "size", desc)
            constructor.definePrototypeProperty(cx, NativeSet.GETSIZE, desc)

            constructor.definePrototypeProperty(SymbolKey.TO_STRING_TAG, CLASS_NAME, DONTENUM or READONLY)

            ScriptRuntimeES6.addSymbolSpecies(cx, scope, constructor)
            if (sealed) {
                constructor.sealObject()
                (constructor.prototypeProperty as ScriptableObject).sealObject()
            }
            return constructor
        }

        private fun jsConstructor(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
            val nm = NativeMap()
            nm.instanceOfMap = true
            if (args.isNotEmpty()) loadFromIterable(cx, scope, nm, key(args))
            return nm
        }

        private fun jsGroupBy(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val items = if (args.isEmpty()) Undefined.instance else args[0]
            val callback = if (args.size < 2) Undefined.instance else args[1]

            val groups = AbstractEcmaObjectOperations.groupBy(
                cx, scope, CLASS_NAME, "groupBy", items, callback,
                AbstractEcmaObjectOperations.KEY_COERCION.COLLECTION,
            )

            val map = cx.newObject(scope, "Map") as NativeMap
            for ((k, v) in groups) {
                map.entries.put(k, cx.newArray(scope, v.toTypedArray()))
            }
            return map
        }

        /**
         * Fills a new map from an iterable of `[key, value]` pairs. `set` is looked up on the
         * prototype rather than called directly, because a script may have replaced it.
         */
        internal fun loadFromIterable(cx: Context, scope: Scriptable, map: ScriptableObject, arg1: Any?) {
            if (arg1 == null || Undefined.instance == arg1) return
            val ito = ScriptRuntime.callIterator(arg1, cx, scope)
            if (Undefined.instance == ito) return

            val proto = getClassPrototype(scope, map.className)
            val set = ScriptRuntime.getPropAndThis(proto, "set", cx, scope)!!.callable
            ScriptRuntime.loadFromIterable(cx, scope, arg1) { k, v ->
                set.call(cx, scope, map, arrayOf(k, v))
            }
        }

        private fun realThis(thisObj: Scriptable?, name: String): NativeMap {
            val nm = LambdaConstructor.convertThisObject<NativeMap>(thisObj)
            // Stands in for the spec's "Map internal data slot" check.
            if (!nm.instanceOfMap) throw ScriptRuntime.typeErrorById("msg.incompat.call", name)
            return nm
        }

        /** The first argument, or undefined. Shared with Set and, later, the weak collections. */
        internal fun key(args: Array<Any?>): Any? = if (args.isNotEmpty()) args[0] else Undefined.instance
    }
}
