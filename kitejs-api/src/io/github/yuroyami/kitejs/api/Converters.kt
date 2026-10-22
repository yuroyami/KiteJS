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
 * BigInt, a `Char` or any `CharSequence` becomes a string, a `ByteArray` becomes a `Uint8Array`
 * over a copy of its bytes, and `List`, `Array`, the other primitive arrays and `Set` become arrays
 * and `Map` a plain object, all the way down. Cycles and repeated collection references keep
 * their identity within one conversion; another conversion makes a separate copy. A [JsValue]
 * or a handle passes straight through. Anything else goes to the converters [register] added,
 * and is refused when none of them takes it.
 *
 * JavaScript to Kotlin, as [JsValue.toKotlin] does it: undefined and null become `null`, numbers
 * `Double`, strings `String`, booleans `Boolean`, BigInts [KBigInt], arrays `List`, an
 * `ArrayBuffer`, a typed array or a `DataView` a `ByteArray` of the bytes it views, and other
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
     * answers wins. Rules apply to every engine. A chain that cycles or takes more than 256
     * transformations before reaching the built-in table throws [JsEngineError].
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
    public fun canonical(value: Any?): Any? {
        var current = value
        var conversions = 0
        var seen: MutableSet<IdentityKey>? = null
        while (true) {
            when (val v = current) {
                null -> return JsUndefined
                is Unit -> return JsUndefined
                is JsValue -> return v.raw
                is JsObject, is JsSymbol, is JsUndefined -> return v
                is Boolean, is String, is KBigInt, is Double -> return v
                is Float -> return v.toDouble()
                is Int -> return v.toDouble()
                is Short -> return v.toDouble()
                is Byte -> return v.toDouble()
                is Long -> return if (v in -MAX_SAFE..MAX_SAFE) v.toDouble() else KBigInt.fromLong(v)
                is Char -> return v.toString()
                is CharSequence -> return v.toString()
                is List<*>, is Array<*>, is Set<*>, is Map<*, *>, is ByteArray, is ShortArray, is CharArray,
                is IntArray, is LongArray, is FloatArray, is DoubleArray, is BooleanArray -> return v
                else -> {
                    val visited = seen ?: HashSet<IdentityKey>().also { seen = it }
                    if (conversions++ >= 256 || !visited.add(IdentityKey(v))) {
                        throw JsEngineError("a custom conversion is cyclic or exceeds 256 conversion steps")
                    }
                    current = firstCustom(v) ?: refuse(v)
                }
            }
        }
    }

    /** Whether [canonical] left [value] as a collection for [toEngine] to walk. */
    @InternalKiteJsApi
    public fun isCollection(value: Any?): Boolean = when (value) {
        is List<*>, is Array<*>, is Set<*>, is Map<*, *>, is ByteArray, is ShortArray, is CharArray,
        is IntArray, is LongArray, is FloatArray, is DoubleArray, is BooleanArray -> true
        else -> false
    }

    /**
     * Builds [value] in an engine: [scalar] takes a canonical scalar or a handle and answers the
     * engine's own value, [array] builds an array from elements already built, [obj] a plain
     * object from keys and values already built, and [bytes] a `Uint8Array` over a copy of bytes.
     * These callbacks cannot build cycles; cyclic input or a depth of 128 throws [JsEngineError].
     * Engines use [toEngineGraph] to preserve graphs without a traversal depth limit.
     */
    @InternalKiteJsApi
    public fun <V> toEngine(
        value: Any?,
        scalar: (Any?) -> V,
        array: (List<V>) -> V,
        obj: (List<Pair<String, V>>) -> V,
        bytes: (ByteArray) -> V,
    ): V {
        val visiting = HashSet<IdentityKey>()
        fun build(v: Any?, depth: Int): V {
            if (depth >= 128) throw JsEngineError("collection conversion exceeds 128 levels; use toEngineGraph")
            val c = canonical(v)
            val key = if (isCollection(c)) IdentityKey(c!!) else null
            if (key != null && !visiting.add(key)) throw JsEngineError("cyclic collection conversion requires toEngineGraph")
            try {
                return when (c) {
                    is List<*> -> array(c.map { build(it, depth + 1) })
                    is Array<*> -> array(c.map { build(it, depth + 1) })
                    is Set<*> -> array(c.map { build(it, depth + 1) })
                    is ByteArray -> bytes(c)
                    is ShortArray -> array(c.map { scalar(it.toDouble()) })
                    is CharArray -> array(c.map { scalar(it.toString()) })
                    is IntArray -> array(c.map { scalar(it.toDouble()) })
                    is LongArray -> array(c.map { build(it, depth + 1) })
                    is FloatArray -> array(c.map { scalar(it.toDouble()) })
                    is DoubleArray -> array(c.map { scalar(it) })
                    is BooleanArray -> array(c.map { scalar(it) })
                    is Map<*, *> -> obj(c.entries.map { (k, e) -> k.toString() to build(e, depth + 1) })
                    else -> scalar(c)
                }
            } finally {
                if (key != null) visiting.remove(key)
            }
        }
        return build(value, 0)
    }

    /**
     * Builds a graph without recursive host calls. [array] and [obj] allocate empty destinations;
     * [append] and [put] fill them without consuming the child values. Collections and custom
     * objects converted to collections retain their identity within this call. The caller owns
     * the engine values the callbacks allocate and must clean up temporaries, including on error.
     */
    @InternalKiteJsApi
    public fun <V> toEngineGraph(
        value: Any?,
        scalar: (Any?) -> V,
        array: () -> V,
        obj: () -> V,
        append: (V, V) -> Unit,
        put: (V, String, V) -> Unit,
        bytes: (ByteArray) -> V,
    ): V {
        val initial = canonical(value)
        if (!isCollection(initial)) return scalar(initial)
        val seen = HashMap<IdentityKey, Built<V>>()
        val pending = ArrayDeque<Pending<V>>()
        fun build(v: Any?): V {
            // Scalars can be JavaScript primitives on the web, where a WeakMap has object keys.
            val source = when (v) {
                null -> null
                is Unit, is JsUndefined, is JsValue, is JsObject, is JsSymbol, is Boolean,
                is Double, is Float, is Int, is Short, is Byte, is Long,
                is Char, is CharSequence, is KBigInt -> null
                else -> v
            }
            if (source != null) seen[IdentityKey(source)]?.let { return it.value }
            val c = if (v === value) initial else canonical(v)
            if (!isCollection(c)) return scalar(c)
            seen[IdentityKey(c!!)]?.let { found ->
                if (source != null) seen[IdentityKey(source)] = found
                return found.value
            }
            val target = when (c) {
                is ByteArray -> bytes(c)
                is Map<*, *> -> obj().also { pending.addLast(Pending(it, c.entries.iterator(), isMap = true)) }
                else -> array().also { pending.addLast(Pending(it, elements(c))) }
            }
            val built = Built(target)
            seen[IdentityKey(c)] = built
            if (source != null) seen[IdentityKey(source)] = built
            return target
        }
        val root = build(value)
        while (pending.isNotEmpty()) {
            val work = pending.last()
            if (!work.elements.hasNext()) {
                pending.removeLast()
            } else if (work.isMap) {
                val entry = work.elements.next() as Map.Entry<*, *>
                val key = entry.key.toString()
                put(work.value, key, build(entry.value))
            } else {
                append(work.value, build(work.elements.next()))
            }
        }
        return root
    }

    private class Built<V>(val value: V)
    private class Pending<V>(val value: V, val elements: Iterator<*>, val isMap: Boolean = false)

    private fun elements(value: Any): Iterator<*> = when (value) {
        is List<*> -> value.iterator()
        is Array<*> -> value.iterator()
        is Set<*> -> value.iterator()
        is ShortArray -> value.iterator()
        is CharArray -> value.iterator()
        is IntArray -> value.iterator()
        is LongArray -> value.iterator()
        is FloatArray -> value.iterator()
        is DoubleArray -> value.iterator()
        is BooleanArray -> value.iterator()
        else -> error("not an array collection")
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
        is JsObject -> r.toByteArrayOrNull() ?: toKotlinMap(r, seen)
        else -> r
    }

    /** [obj] as a map of its own keys, even when it views bytes, or the handle when it closes a cycle. */
    internal fun toKotlinMap(obj: JsObject, seen: MutableSet<JsObject>): Any =
        if (!seen.add(obj)) obj
        else buildMap { for (key in obj.keys) put(key, toKotlin(obj[key], seen)) }.also { seen.remove(obj) }
}
