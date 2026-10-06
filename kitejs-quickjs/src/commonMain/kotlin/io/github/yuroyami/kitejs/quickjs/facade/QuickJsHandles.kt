/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.quickjs.facade

import io.github.yuroyami.kitejs.api.JsArray
import io.github.yuroyami.kitejs.api.JsFunction
import io.github.yuroyami.kitejs.api.JsObject
import io.github.yuroyami.kitejs.api.JsScript
import io.github.yuroyami.kitejs.api.JsSymbol
import io.github.yuroyami.kitejs.api.JsValue
import io.github.yuroyami.kitejs.api.PrimitiveHint
import io.github.yuroyami.kitejs.api.PropertyFlags

/*
 * The handles a QuickJS engine gives out. JsObject, JsArray and JsFunction are classes, so the
 * three cannot share a base here; each forwards to the functions on QuickJsHandle instead.
 * Property access goes through the prelude's helpers, which do what `o[k]` does in a script.
 */

/** What every QuickJS object handle holds: its engine, its slot in the engine's table, and who it is. */
internal interface QuickJsHandle {
    val engine: QuickJsKiteJs
    val handle: Int

    /** The object's address, the same for every handle to it while any of them lives. */
    val identity: Double

    fun getAt(key: Any): JsValue = engine.call(drain = false) {
        if (key is String) engine.read(this, key) else engine.helper(Helper.GET, this, key)
    }

    fun setAt(key: Any, value: Any?) {
        engine.call(drain = false) { engine.helper(Helper.SET, this, key, value) }
    }

    fun hasKey(name: String): Boolean = engine.call(drain = false) { engine.helper(Helper.HAS, this, name).asBoolean() }

    fun deleteKey(name: String) {
        engine.call(drain = false) { engine.helper(Helper.DELETE, this, name) }
    }

    fun ownKeys(): List<String> = engine.keysOf(this)

    fun callMethod(name: String, args: Array<out Any?>): JsValue = engine.call {
        engine.helper(Helper.CALL_METHOD, this, name, *args)
    }

    fun primitive(hint: PrimitiveHint): JsValue = engine.call(drain = false) {
        engine.helper(if (hint == PrimitiveHint.STRING) Helper.TO_STRING_PRIMITIVE else Helper.TO_NUMBER_PRIMITIVE, this)
    }

    fun usable(): Boolean = engine.isUsableHere

    fun defineData(name: String, value: Any?, flags: PropertyFlags) {
        engine.call(drain = false) {
            engine.helper(Helper.DEFINE_VALUE, this, name, value, flags.writable, flags.enumerable, flags.configurable)
        }
    }

    fun defineAccessorProperty(name: String, read: (() -> Any?)?, write: ((JsValue) -> Unit)?, flags: PropertyFlags) {
        engine.call(drain = false) {
            val getter = read?.let { r -> engine.newFunction("get $name", 0) { _, _ -> r() } }
            val setter = write?.let { w ->
                engine.newFunction("set $name", 1) { _, args -> w(args.firstOrNull() ?: JsValue.undefined) }
            }
            engine.helper(Helper.DEFINE_ACCESSOR, this, name, getter, setter, flags.enumerable, flags.configurable)
        }
    }

    fun sameAs(other: Any?): Boolean =
        other is QuickJsHandle && other.engine === engine && other.identity == identity
}

internal class QuickJsObject(override val engine: QuickJsKiteJs, adopted: QuickJsKiteJs.Adopted) : JsObject(), QuickJsHandle {
    override val handle: Int = adopted.handle
    override val identity: Double = adopted.identity

    @Suppress("unused")
    private val cleanup = engine.register(this, handle)

    override fun get(name: String): JsValue = getAt(name)
    override fun get(index: Int): JsValue = getAt(index)
    override fun set(name: String, value: Any?) = setAt(name, value)
    override fun set(index: Int, value: Any?) = setAt(index, value)
    override fun has(name: String): Boolean = hasKey(name)
    override fun delete(name: String) = deleteKey(name)
    override val keys: List<String> get() = ownKeys()
    override fun call(name: String, vararg args: Any?): JsValue = callMethod(name, args)
    override fun toPrimitive(hint: PrimitiveHint): JsValue = primitive(hint)
    override fun isUsableHere(): Boolean = usable()
    override fun inertText(): String = "[object Object]"
    override fun defineValue(name: String, value: Any?, flags: PropertyFlags) = defineData(name, value, flags)
    override fun defineAccessor(name: String, read: (() -> Any?)?, write: ((JsValue) -> Unit)?, flags: PropertyFlags) =
        defineAccessorProperty(name, read, write, flags)
    override fun equals(other: Any?): Boolean = sameAs(other)
    override fun hashCode(): Int = identity.hashCode()
}

internal class QuickJsArray(override val engine: QuickJsKiteJs, adopted: QuickJsKiteJs.Adopted) : JsArray(), QuickJsHandle {
    override val handle: Int = adopted.handle
    override val identity: Double = adopted.identity

    @Suppress("unused")
    private val cleanup = engine.register(this, handle)

    override val size: Int get() = getAt("length").asInt()
    override fun get(name: String): JsValue = getAt(name)
    override fun get(index: Int): JsValue = getAt(index)
    override fun set(name: String, value: Any?) = setAt(name, value)
    override fun set(index: Int, value: Any?) = setAt(index, value)
    override fun has(name: String): Boolean = hasKey(name)
    override fun delete(name: String) = deleteKey(name)
    override val keys: List<String> get() = ownKeys()
    override fun call(name: String, vararg args: Any?): JsValue = callMethod(name, args)
    override fun toPrimitive(hint: PrimitiveHint): JsValue = primitive(hint)
    override fun isUsableHere(): Boolean = usable()
    override fun inertText(): String = "[object Array]"
    override fun defineValue(name: String, value: Any?, flags: PropertyFlags) = defineData(name, value, flags)
    override fun defineAccessor(name: String, read: (() -> Any?)?, write: ((JsValue) -> Unit)?, flags: PropertyFlags) =
        defineAccessorProperty(name, read, write, flags)
    override fun equals(other: Any?): Boolean = sameAs(other)
    override fun hashCode(): Int = identity.hashCode()
}

internal class QuickJsFunction(override val engine: QuickJsKiteJs, adopted: QuickJsKiteJs.Adopted) : JsFunction(), QuickJsHandle {
    override val handle: Int = adopted.handle
    override val identity: Double = adopted.identity

    @Suppress("unused")
    private val cleanup = engine.register(this, handle)

    /**
     * Calls it with [thisArg] as given, as `f.call(thisArg, ...args)` would: a sloppy function
     * given nothing sees the global object, and a strict one sees undefined.
     */
    override fun callOn(thisArg: Any?, vararg args: Any?): JsValue = engine.call {
        val self = engine.toHandle(thisArg)
        try {
            engine.toJs(engine.callWith(handle, self, args))
        } finally {
            engine.releaseHandle(self)
        }
    }

    override fun construct(vararg args: Any?): JsObject = engine.call {
        engine.toJs(engine.constructWith(handle, args)).asObject()
    }

    override fun bind(thisArg: Any?, vararg args: Any?): JsFunction = engine.call(drain = false) {
        engine.helper(Helper.BIND, this, thisArg, *args).asFunction()
    }

    override fun get(name: String): JsValue = getAt(name)
    override fun get(index: Int): JsValue = getAt(index)
    override fun set(name: String, value: Any?) = setAt(name, value)
    override fun set(index: Int, value: Any?) = setAt(index, value)
    override fun has(name: String): Boolean = hasKey(name)
    override fun delete(name: String) = deleteKey(name)
    override val keys: List<String> get() = ownKeys()
    override fun call(name: String, vararg args: Any?): JsValue = callMethod(name, args)
    override fun toPrimitive(hint: PrimitiveHint): JsValue = primitive(hint)
    override fun isUsableHere(): Boolean = usable()
    override fun inertText(): String = "[object Function]"
    override fun defineValue(name: String, value: Any?, flags: PropertyFlags) = defineData(name, value, flags)
    override fun defineAccessor(name: String, read: (() -> Any?)?, write: ((JsValue) -> Unit)?, flags: PropertyFlags) =
        defineAccessorProperty(name, read, write, flags)
    override fun equals(other: Any?): Boolean = sameAs(other)
    override fun hashCode(): Int = identity.hashCode()
}

/** A symbol. Its description is read once, so it prints even after its engine is gone. */
internal class QuickJsSymbol(
    override val engine: QuickJsKiteJs,
    adopted: QuickJsKiteJs.Adopted,
    override val description: String?,
) : JsSymbol() {
    val handle: Int = adopted.handle
    private val identity: Double = adopted.identity

    @Suppress("unused")
    private val cleanup = engine.register(this, handle)

    override fun equals(other: Any?): Boolean =
        other is QuickJsSymbol && other.engine === engine && other.identity == identity

    override fun hashCode(): Int = identity.hashCode()
}

internal class QuickJsScript(private val engine: QuickJsKiteJs, val handle: Int) : JsScript() {
    @Suppress("unused")
    private val cleanup = engine.register(this, handle)

    override fun run(): JsValue = engine.run(this)
}
