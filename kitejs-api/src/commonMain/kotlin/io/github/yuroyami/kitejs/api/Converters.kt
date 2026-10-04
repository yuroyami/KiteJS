/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The fixed table between Kotlin values and JavaScript values. Nothing here uses reflection, so it
 * works the same on every target and for every engine.
 *
 * Kotlin to JavaScript: `null` and `Unit` become `undefined`, `Boolean` stays, every number widens
 * to a JavaScript number, a `Long` outside the range a JavaScript number holds exactly becomes a
 * BigInt, a `Char` or any `CharSequence` becomes a string, and `List`, `Array`, the primitive
 * arrays and `Set` become arrays and `Map` a plain object, all the way down. A [JsValue] or a
 * handle passes straight through. Anything else goes to the converters [register] added, and is
 * refused when none of them takes it.
 *
 * JavaScript to Kotlin, as [JsValue.toKotlin] does it: undefined and null become `null`, numbers
 * `Double`, strings `String`, booleans `Boolean`, BigInts [KBigInt], arrays `List` and other
 * objects `Map`, all the way down. A function stays a [JsFunction] and a symbol a [JsSymbol].
 */
@OptIn(ExperimentalAtomicApi::class)
public object Converters {

    /** The largest integer a JavaScript number holds exactly. */
    private const val MAX_SAFE = 9007199254740991L

    /** Extra rules an embedder registers, tried in order after the built-in table. */
    private val custom = AtomicReference<List<(Any) -> Any?>>(emptyList())

    /**
     * Teaches the table one more Kotlin type. [convert] answers anything the table already
     * understands, a `Map` for instance, or null to let the next rule try. The first rule that
     * answers wins. Rules apply to every engine.
     */
    public fun register(convert: (Any) -> Any?) {
        while (true) {
            val now = custom.load()
            if (custom.compareAndSet(now, now + convert)) return
        }
    }

    /** Forgets every rule [register] added. */
    public fun clearRegistrations() {
        custom.store(emptyList())
    }

    /**
     * [value] with every scalar in its canonical form, the form a [JsValue] holds. A collection
     * comes back as it was, for [toEngine] to walk. A value no rule takes is refused.
     */
    @InternalKiteJsApi
    public fun canonical(value: Any?): Any? = when (value) {
        null, Unit -> JsUndefined
        is JsValue -> value.raw
        is JsObject, is JsSymbol, JsUndefined -> value
        is Boolean, is String, is KBigInt -> value
        is Double -> value
        is Float -> value.toDouble()
        is Int -> value.toDouble()
        is Short -> value.toDouble()
        is Byte -> value.toDouble()
        is Long -> if (value in -MAX_SAFE..MAX_SAFE) value.toDouble() else KBigInt.fromLong(value)
        is Char -> value.toString()
        is CharSequence -> value.toString()
        is List<*>, is Array<*>, is Set<*>, is Map<*, *>,
        is IntArray, is LongArray, is DoubleArray, is BooleanArray -> value
        else -> canonical(firstCustom(value) ?: refuse(value))
    }

    /** Whether [canonical] left [value] as a collection for [toEngine] to walk. */
    @InternalKiteJsApi
    public fun isCollection(value: Any?): Boolean = when (value) {
        is List<*>, is Array<*>, is Set<*>, is Map<*, *>,
        is IntArray, is LongArray, is DoubleArray, is BooleanArray -> true
        else -> false
    }

    /**
     * Builds [value] in an engine: [scalar] takes a canonical scalar or a handle and answers the
     * engine's own value, [array] builds an array from elements already built, and [obj] a plain
     * object from keys and values already built.
     */
    @InternalKiteJsApi
    public fun <V> toEngine(
        value: Any?,
        scalar: (Any?) -> V,
        array: (List<V>) -> V,
        obj: (List<Pair<String, V>>) -> V,
    ): V {
        fun build(v: Any?): V = when (val c = canonical(v)) {
            is List<*> -> array(c.map(::build))
            is Array<*> -> array(c.map(::build))
            is Set<*> -> array(c.map(::build))
            is IntArray -> array(c.map { scalar(it.toDouble()) })
            is LongArray -> array(c.map { build(it) })
            is DoubleArray -> array(c.map { scalar(it) })
            is BooleanArray -> array(c.map { scalar(it) })
            is Map<*, *> -> obj(c.entries.map { (k, e) -> k.toString() to build(e) })
            else -> scalar(c)
        }
        return build(value)
    }

    private fun firstCustom(value: Any): Any? {
        for (rule in custom.load()) rule(value)?.let { return it }
        return null
    }

    private fun refuse(value: Any): Nothing = throw JsEngineError(
        "a ${value::class.simpleName} has no JavaScript form; teach it one with Converters.register",
    )

    /**
     * JavaScript to Kotlin, all the way down. [seen] stops a cycle: an object already on the way
     * down comes back as its [JsObject] handle instead of looping forever.
     */
    internal fun toKotlin(value: JsValue, seen: MutableSet<JsObject>): Any? = when (val r = value.raw) {
        null, JsUndefined -> null
        is JsFunction -> r
        is JsArray -> {
            if (!seen.add(r)) r
            else r.values().map { toKotlin(it, seen) }.also { seen.remove(r) }
        }
        is JsObject -> {
            if (!seen.add(r)) r
            else buildMap { for (key in r.keys) put(key, toKotlin(r[key], seen)) }.also { seen.remove(r) }
        }
        else -> r
    }
}
