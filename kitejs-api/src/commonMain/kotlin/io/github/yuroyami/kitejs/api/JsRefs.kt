/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/*
 * A handle belongs to the engine that made it, as a GraalJS `Value` belongs to its context: it
 * works only while that engine is open and only on that engine's thread, and it cannot be handed
 * to another engine. Touched anywhere else it throws JsEngineError before any script can run.
 * Scalars are copies and work anywhere.
 */

/**
 * A JavaScript object seen from Kotlin. Reading and writing goes straight through to the engine,
 * so a change here is a change the script sees.
 *
 * Reading and writing a property is not a call from the host: it runs whatever getter, setter or
 * proxy trap the object has, but leaves queued Promise reactions where they are. Calling a method
 * is a call, and runs them before it returns, as `evaluate` does.
 */
public abstract class JsObject @InternalKiteJsApi constructor() {

    /** The engine this object belongs to. */
    @InternalKiteJsApi
    public abstract val engine: KiteJs

    /** The value this wrapper holds, for handing back to the engine. */
    public val value: JsValue get() = JsValue(this)

    public abstract operator fun get(name: String): JsValue

    public abstract operator fun get(index: Int): JsValue

    public abstract operator fun set(name: String, value: Any?)

    public abstract operator fun set(index: Int, value: Any?)

    /** Whether the object has [name], its own or inherited, as the `in` operator answers. */
    public abstract fun has(name: String): Boolean

    /** Deletes [name] from the object, as the `delete` operator does. */
    public abstract fun delete(name: String)

    /** The object's own enumerable string keys, in the order `Object.keys` gives them. */
    public abstract val keys: List<String>

    /** Calls a method on this object, the way `obj.name(...)` does in a script. */
    public abstract fun call(name: String, vararg args: Any?): JsValue

    /**
     * A copy of the bytes this object views when it is an `ArrayBuffer`, a typed array or a
     * `DataView`, read without running any script, or null for any other object. A detached
     * buffer, or a view past the end of its buffer, gives no bytes.
     */
    public open fun toByteArrayOrNull(): ByteArray? = null

    /** A plain Kotlin map, all the way down. Cycles come back as the [JsObject] that closed them. */
    @Suppress("UNCHECKED_CAST")
    public fun toMap(): Map<String, Any?> = Converters.toKotlinMap(this, HashSet()) as Map<String, Any?>

    /**
     * What `String(obj)` gives, without ever throwing. Where the engine cannot be used, or the
     * object's own conversion fails, it prints its class instead, without running any script.
     */
    override fun toString(): String {
        if (!isUsableHere()) return inertText()
        return try {
            toPrimitive(PrimitiveHint.STRING).asString()
        } catch (e: JsException) {
            inertText()
        }
    }

    /** Two handles are equal when they hold the same object. */
    abstract override fun equals(other: Any?): Boolean

    abstract override fun hashCode(): Int

    // ---- What the engine provides ---------------------------------------------------------------

    /** ToPrimitive with [hint], which can run the object's script. */
    @InternalKiteJsApi
    public abstract fun toPrimitive(hint: PrimitiveHint): JsValue

    /** True when the engine is open and this is its thread, so the object can be touched here. */
    @InternalKiteJsApi
    public abstract fun isUsableHere(): Boolean

    /** What the object prints as where it cannot be touched: `[object Class]`, running nothing. */
    @InternalKiteJsApi
    public abstract fun inertText(): String

    /** Defines a data property, as `Object.defineProperty` would. */
    @InternalKiteJsApi
    public abstract fun defineValue(name: String, value: Any?, flags: PropertyFlags)

    /** Defines an accessor property whose halves call back into the host. Either can be null. */
    @InternalKiteJsApi
    public abstract fun defineAccessor(
        name: String,
        read: (() -> Any?)?,
        write: ((JsValue) -> Unit)?,
        flags: PropertyFlags,
    )
}

/** A JavaScript array seen from Kotlin. */
public abstract class JsArray @InternalKiteJsApi constructor() : JsObject() {

    /** The array's `length`. */
    public abstract val size: Int

    public fun add(value: Any?) {
        this[size] = value
    }

    /** A plain Kotlin list, all the way down. */
    @Suppress("UNCHECKED_CAST")
    public fun toList(): List<Any?> = Converters.toKotlin(value, HashSet()) as List<Any?>

    /** Every element as a [JsValue], without converting anything. */
    public fun values(): List<JsValue> = (0 until size).map { this[it] }
}

/** A JavaScript function seen from Kotlin. */
public abstract class JsFunction @InternalKiteJsApi constructor() : JsObject() {

    /** The declared argument count, which is what `f.length` answers. */
    public open val arity: Int get() = this["length"].asInt()

    /** Calls the function as `f(...)` in a script would, with no particular `this`. */
    public operator fun invoke(vararg args: Any?): JsValue = callOn(null, *args)

    /** Calls the function with an explicit `this`. */
    public abstract fun callOn(thisArg: Any?, vararg args: Any?): JsValue

    /** Calls it with `new`. */
    public abstract fun construct(vararg args: Any?): JsObject

    /** A new function with `this` and the leading arguments fixed, as `Function.prototype.bind` does. */
    public abstract fun bind(thisArg: Any?, vararg args: Any?): JsFunction
}

/**
 * A JavaScript symbol. It is a primitive, but its identity belongs to the engine that made it, so
 * it cannot be handed to another engine.
 */
public abstract class JsSymbol @InternalKiteJsApi constructor() {

    /** What `Symbol('d')` was given, or null when it was given nothing. */
    public abstract val description: String?

    /** The engine this symbol belongs to. */
    @InternalKiteJsApi
    public abstract val engine: KiteJs

    override fun toString(): String = "Symbol(${description ?: ""})"

    abstract override fun equals(other: Any?): Boolean

    abstract override fun hashCode(): Int
}

/** A parsed script, ready to run more than once. */
public abstract class JsScript @InternalKiteJsApi constructor() {

    /** Runs it in the engine's global scope. The answer is its last expression's value. */
    public abstract fun run(): JsValue

    /** Runs it as [KiteJs.evaluatePausing] runs source: pausing about every [slice] where the engine can. */
    public open suspend fun runPausing(slice: Duration = 16.milliseconds): JsValue = run()

    /**
     * The compiled form, which [KiteJs.loadBytecode] reads back in another engine of the same
     * kind without parsing the source again. Null on an engine that has no bytecode, such as Rhino.
     * Bytecode fits only the build of the engine that wrote it, so keep it for this process, or
     * key a saved copy on [KiteJs.version].
     */
    public open fun bytecode(): ByteArray? = null
}
