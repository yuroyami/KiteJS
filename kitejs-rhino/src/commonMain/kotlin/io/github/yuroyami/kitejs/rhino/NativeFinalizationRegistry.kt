/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

/**
 * `FinalizationRegistry` (ECMAScript 2021, 26.2). Each cell holds its target and unregister token
 * weakly and its held value strongly. The engine has no reference queue, so when the job queue
 * drains, [Context.processMicrotasks] looks for cells whose target is gone and queues one cleanup
 * job for the registry. The spec leaves that timing to the host.
 */
internal class NativeFinalizationRegistry private constructor(
    private val callback: Callable,
    private val realm: Scriptable,
) : ScriptableObject() {

    private class Cell(val target: WeakRef<Any>, val heldValue: Any?, val token: WeakRef<Any>?)

    private val cells = ArrayList<Cell>()

    /** True while the [Context] that the first `register` ran on watches this registry. */
    internal var watched = false

    override val className: String
        get() = CLASS_NAME

    internal val hasCells: Boolean
        get() = cells.isNotEmpty()

    internal fun hasEmptyCells(): Boolean = cells.any { it.target.get() == null }

    private fun register(cx: Context, args: Array<Any?>): Any? {
        val target = args.getOrElse(0) { Undefined.instance }
        val heldValue = args.getOrElse(1) { Undefined.instance }
        val token = args.getOrElse(2) { Undefined.instance }
        if (!NativeWeakRef.canBeHeldWeakly(target)) throw ScriptRuntime.typeErrorById("msg.finalization.target")
        if (ScriptRuntime.same(target, heldValue)) throw ScriptRuntime.typeErrorById("msg.finalization.same")
        val tokenRef = when {
            NativeWeakRef.canBeHeldWeakly(token) -> WeakRef(token!!)
            Undefined.isUndefined(token) -> null
            else -> throw ScriptRuntime.typeErrorById("msg.finalization.token", ScriptRuntime.toString(token))
        }
        cells.add(Cell(WeakRef(target!!), heldValue, tokenRef))
        if (!watched) {
            watched = true
            cx.watchFinalizationRegistry(this)
        }
        return Undefined.instance
    }

    private fun unregister(args: Array<Any?>): Any? {
        val token = args.getOrElse(0) { Undefined.instance }
        if (!NativeWeakRef.canBeHeldWeakly(token)) throw ScriptRuntime.typeErrorById("msg.finalization.token", ScriptRuntime.toString(token))
        val before = cells.size
        cells.removeAll { cell -> cell.token?.get()?.let { ScriptRuntime.same(it, token) } == true }
        return cells.size != before
    }

    /** The cleanup job: CleanupFinalizationRegistry, where each empty cell leaves before its callback runs. */
    internal fun cleanupJob(): Context.Runnable {
        val reaction = NativeAsyncGenerator.HostReaction(realm, false) { cx, _ ->
            while (true) {
                val i = cells.indexOfFirst { it.target.get() == null }
                if (i < 0) break
                val cell = cells.removeAt(i)
                callback.call(cx, realm, Undefined.SCRIPTABLE_UNDEFINED, arrayOf(cell.heldValue))
            }
        }
        return Context.Runnable { reaction.call(Context.getContext(), realm, null, ScriptRuntime.emptyArgs) }
    }

    companion object {
        private const val CLASS_NAME = "FinalizationRegistry"

        fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val constructor = LambdaConstructor(
                scope,
                CLASS_NAME,
                1,
                LambdaConstructor.CONSTRUCTOR_NEW,
                SerializableConstructable { _, s, args ->
                    val callback = args.getOrElse(0) { Undefined.instance } as? Callable
                        ?: throw ScriptRuntime.typeErrorById("msg.finalization.callback")
                    NativeFinalizationRegistry(callback, getTopLevelScope(s))
                },
            )
            constructor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)
            constructor.definePrototypeMethod(scope, "register", 2, SerializableCallable { icx, _, thisObj, args ->
                realThis(thisObj, "register").register(icx, args)
            })
            constructor.definePrototypeMethod(scope, "unregister", 1, SerializableCallable { _, _, thisObj, args ->
                realThis(thisObj, "unregister").unregister(args)
            })
            constructor.definePrototypeProperty(SymbolKey.TO_STRING_TAG, CLASS_NAME, DONTENUM or READONLY)
            if (sealed) {
                constructor.sealObject()
                (constructor.prototypeProperty as ScriptableObject).sealObject()
            }
            return constructor
        }

        private fun realThis(thisObj: Scriptable?, name: String): NativeFinalizationRegistry =
            thisObj as? NativeFinalizationRegistry
                ?: throw ScriptRuntime.typeErrorById("msg.incompat.call", "FinalizationRegistry.prototype.$name")
    }
}
