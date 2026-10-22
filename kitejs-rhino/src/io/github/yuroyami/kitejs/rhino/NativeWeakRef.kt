/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

/**
 * `WeakRef` (ECMAScript 2021, 26.1). It holds its target through the engine's [WeakRef], and a
 * target that `deref` or the constructor answers stays alive until the job queue drains
 * (KeepDuringJob), which [Context.processMicrotasks] ends.
 */
internal class NativeWeakRef private constructor(private val target: WeakRef<Any>) : ScriptableObject() {

    override val className: String
        get() = CLASS_NAME

    private fun deref(cx: Context): Any? {
        val value = target.get() ?: return Undefined.instance
        cx.keepDuringJob(value)
        return value
    }

    companion object {
        private const val CLASS_NAME = "WeakRef"

        fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val constructor = LambdaConstructor(
                scope,
                CLASS_NAME,
                1,
                LambdaConstructor.CONSTRUCTOR_NEW,
                SerializableConstructable { icx, _, args -> construct(icx, args) },
            )
            constructor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)
            constructor.definePrototypeMethod(scope, "deref", 0, SerializableCallable { icx, _, thisObj, _ ->
                (thisObj as? NativeWeakRef ?: throw ScriptRuntime.typeErrorById("msg.incompat.call", "WeakRef.prototype.deref")).deref(icx)
            })
            constructor.definePrototypeProperty(SymbolKey.TO_STRING_TAG, CLASS_NAME, DONTENUM or READONLY)
            if (sealed) {
                constructor.sealObject()
                (constructor.prototypeProperty as ScriptableObject).sealObject()
            }
            return constructor
        }

        private fun construct(cx: Context, args: Array<Any?>): Scriptable {
            val target = if (args.isNotEmpty()) args[0] else Undefined.instance
            if (!canBeHeldWeakly(target)) throw ScriptRuntime.typeErrorById("msg.weakref.target")
            cx.keepDuringJob(target!!)
            return NativeWeakRef(WeakRef(target))
        }

        /** CanBeHeldWeakly (ECMAScript 2023, 9.13): an object or a symbol that `Symbol.for` did not make. */
        fun canBeHeldWeakly(value: Any?): Boolean =
            ScriptRuntime.isObject(value) || ScriptRuntime.isUnregisteredSymbol(value)
    }
}
