/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** `WeakSet`: the same weak keys as [NativeWeakMap], with nothing stored against them. */
class NativeWeakSet : ScriptableObject() {

    private var instanceOfWeakSet = false

    private val entries = WeakKeyMap<Boolean>()

    override val className: String
        get() = CLASS_NAME

    private fun js_add(key: Any?): Any {
        if (!isValidValue(key)) {
            throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeOf(key))
        }
        entries.put(key!!, true)
        return this
    }

    private fun js_delete(key: Any?): Any {
        if (!isValidValue(key)) return false
        return entries.remove(key!!)
    }

    private fun js_has(key: Any?): Any {
        if (!isValidValue(key)) return false
        return entries.containsKey(key!!)
    }

    companion object {
        private const val CLASS_NAME = "WeakSet"

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val constructor = LambdaConstructor(
                scope,
                CLASS_NAME,
                0,
                LambdaConstructor.CONSTRUCTOR_NEW,
                SerializableConstructable { icx, s, args -> jsConstructor(icx, s, args) },
            )
            constructor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)

            constructor.definePrototypeMethod(scope, "add", 1, SerializableCallable { _, _, thisObj, args ->
                realThis(thisObj, "add").js_add(NativeMap.key(args))
            })
            // Upstream names all three of these "add" in the incompatible-call error; kept as is.
            constructor.definePrototypeMethod(scope, "delete", 1, SerializableCallable { _, _, thisObj, args ->
                realThis(thisObj, "add").js_delete(NativeMap.key(args))
            })
            constructor.definePrototypeMethod(scope, "has", 1, SerializableCallable { _, _, thisObj, args ->
                realThis(thisObj, "add").js_has(NativeMap.key(args))
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
            val ns = NativeWeakSet()
            ns.instanceOfWeakSet = true
            if (args.isNotEmpty()) NativeSet.loadFromIterable(cx, scope, ns, NativeMap.key(args))
            return ns
        }

        private fun isValidValue(v: Any?): Boolean =
            ScriptRuntime.isUnregisteredSymbol(v) || ScriptRuntime.isObject(v)

        private fun realThis(thisObj: Scriptable?, name: String): NativeWeakSet {
            val ns = LambdaConstructor.convertThisObject<NativeWeakSet>(thisObj)
            if (!ns.instanceOfWeakSet) throw ScriptRuntime.typeErrorById("msg.incompat.call", name)
            return ns
        }
    }
}
