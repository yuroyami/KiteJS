/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * `WeakMap`: a map that does not keep its keys alive. Only objects and unregistered symbols can be
 * keys, and there is no way to ask it what it holds, which is what makes the weakness unobservable
 * from script.
 */
class NativeWeakMap : ScriptableObject() {

    private var instanceOfWeakMap = false

    private val entries = WeakKeyMap<Any>()

    override val className: String
        get() = CLASS_NAME

    private fun js_delete(key: Any?): Any {
        if (!isValidKey(key)) return false
        return entries.remove(key!!)
    }

    private fun js_get(key: Any?): Any? {
        if (!isValidKey(key)) return Undefined.instance
        val result = entries.get(key!!) ?: return Undefined.instance
        // A stored null comes back out as null, not as the marker standing in for it.
        return if (result === NULL_VALUE) null else result
    }

    private fun js_has(key: Any?): Any {
        if (!isValidKey(key)) return false
        return entries.containsKey(key!!)
    }

    private fun js_set(key: Any?, v: Any?): Any {
        if (!isValidKey(key)) {
            throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeOf(key))
        }
        entries.put(key!!, v ?: NULL_VALUE)
        return this
    }

    companion object {
        private const val CLASS_NAME = "WeakMap"

        /** Stands in for a stored null, which the map itself cannot hold. */
        private val NULL_VALUE = Any()

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val constructor = LambdaConstructor(
                scope,
                CLASS_NAME,
                0,
                LambdaConstructor.CONSTRUCTOR_NEW,
                SerializableConstructable { icx, s, args -> jsConstructor(icx, s, args) },
            )
            constructor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)

            constructor.definePrototypeMethod(scope, "set", 2, SerializableCallable { _, _, thisObj, args ->
                realThis(thisObj, "set").js_set(NativeMap.key(args), if (args.size > 1) args[1] else Undefined.instance)
            })
            constructor.definePrototypeMethod(scope, "delete", 1, SerializableCallable { _, _, thisObj, args ->
                realThis(thisObj, "delete").js_delete(NativeMap.key(args))
            })
            constructor.definePrototypeMethod(scope, "get", 1, SerializableCallable { _, _, thisObj, args ->
                realThis(thisObj, "get").js_get(NativeMap.key(args))
            })
            constructor.definePrototypeMethod(scope, "has", 1, SerializableCallable { _, _, thisObj, args ->
                realThis(thisObj, "has").js_has(NativeMap.key(args))
            })

            constructor.definePrototypeProperty(SymbolKey.TO_STRING_TAG, CLASS_NAME, DONTENUM or READONLY)

            ScriptRuntimeES6.addSymbolSpecies(cx, scope, constructor)
            if (sealed) {
                constructor.sealObject()
                (constructor.prototypeProperty as ScriptableObject).sealObject()
            }
            return constructor
        }

        private fun jsConstructor(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
            val nm = NativeWeakMap()
            nm.instanceOfWeakMap = true
            if (args.isNotEmpty()) NativeMap.loadFromIterable(cx, scope, nm, NativeMap.key(args))
            return nm
        }

        private fun isValidKey(key: Any?): Boolean =
            ScriptRuntime.isUnregisteredSymbol(key) || ScriptRuntime.isObject(key)

        /** The prototype methods only work on something actually built by the constructor. */
        private fun realThis(thisObj: Scriptable?, name: String): NativeWeakMap {
            val nm = LambdaConstructor.convertThisObject<NativeWeakMap>(thisObj)
            if (!nm.instanceOfWeakMap) throw ScriptRuntime.typeErrorById("msg.incompat.call", name)
            return nm
        }
    }
}
