/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.Context
import io.github.yuroyami.kitejs.Function
import io.github.yuroyami.kitejs.KBigInt
import io.github.yuroyami.kitejs.NativeArray
import io.github.yuroyami.kitejs.NativeObject
import io.github.yuroyami.kitejs.ScriptRuntime
import io.github.yuroyami.kitejs.Scriptable
import io.github.yuroyami.kitejs.ScriptableObject
import io.github.yuroyami.kitejs.Symbol
import io.github.yuroyami.kitejs.Undefined

/**
 * The fixed table between Kotlin values and engine values. Nothing here uses reflection, so it
 * works the same on every target.
 *
 * Kotlin to JavaScript: `null` and `Unit` become `undefined`, `Boolean` stays, every number widens
 * to `Double`, a `Long` outside the range JavaScript can hold exactly becomes a BigInt, any
 * `CharSequence` becomes a `String`, and `List`, `Array`, `Map` and `Set` become the matching
 * JavaScript objects. Anything already from the engine passes straight through.
 */
object Converters {

    /** The largest integer a JavaScript number holds exactly. */
    private const val MAX_SAFE = 9007199254740991L

    /** Extra types an embedder registers, tried in order after the built-in table. */
    private val custom = mutableListOf<(Any) -> Any?>()

    /**
     * Teaches the table one more Kotlin type. [convert] answers the engine value, or null to let
     * the next rule try. Registering the same type twice keeps both; the first match wins.
     */
    fun register(convert: (Any) -> Any?) {
        custom.add(convert)
    }

    internal fun clearRegistrations() {
        custom.clear()
    }

    /** For a value that needs no scope: scalars only. Collections need [toEngine] with a context. */
    fun toEngine(value: Any?): Any? = when (value) {
        null, Unit -> Undefined.instance
        is JsValue -> value.raw
        is JsObject -> value.target
        is Boolean, is String, is KBigInt, is Symbol, is Scriptable -> value
        is Double -> value
        is Float -> value.toDouble()
        is Int -> value.toDouble()
        is Short -> value.toDouble()
        is Byte -> value.toDouble()
        is Long -> if (value in -MAX_SAFE..MAX_SAFE) value.toDouble() else KBigInt.fromLong(value)
        is Char -> value.toString()
        is CharSequence -> value.toString()
        else -> firstCustom(value) ?: value
    }

    /** The full table, including the collections, which need a scope to build their objects in. */
    fun toEngine(value: Any?, cx: Context, scope: Scriptable): Any? = when (value) {
        is List<*> -> cx.newArray(scope, value.map { toEngine(it, cx, scope) }.toTypedArray())
        is Array<*> -> cx.newArray(scope, value.map { toEngine(it, cx, scope) }.toTypedArray())
        is IntArray -> cx.newArray(scope, value.map { it.toDouble() as Any? }.toTypedArray())
        is LongArray -> cx.newArray(scope, value.map { toEngine(it) }.toTypedArray())
        is DoubleArray -> cx.newArray(scope, value.map { it as Any? }.toTypedArray())
        is BooleanArray -> cx.newArray(scope, value.map { it as Any? }.toTypedArray())
        is Map<*, *> -> {
            val obj = cx.newObject(scope)
            for ((k, v) in value) ScriptableObject.putProperty(obj, k.toString(), toEngine(v, cx, scope))
            obj
        }
        is Set<*> -> cx.newArray(scope, value.map { toEngine(it, cx, scope) }.toTypedArray())
        else -> toEngine(value)
    }

    internal fun toEngineAll(args: Array<out Any?>, cx: Context, scope: Scriptable): Array<Any?> =
        Array(args.size) { toEngine(args[it], cx, scope) }

    private fun firstCustom(value: Any): Any? {
        for (rule in custom) rule(value)?.let { return it }
        return null
    }

    /**
     * JavaScript to Kotlin, all the way down. [seen] stops a cycle: an object already on the way
     * down comes back as its [JsObject] wrapper instead of looping forever.
     */
    internal fun toKotlin(value: Any?, seen: MutableSet<Scriptable>): Any? = when {
        value == null -> null
        Undefined.isUndefined(value) -> null
        value is Boolean || value is String || value is KBigInt -> value
        value is CharSequence -> value.toString()
        value is Number -> value.toDouble()
        value is Function -> JsFunction(value)
        value is NativeArray -> {
            if (!seen.add(value)) JsObject(value)
            else (0 until value.length.toInt()).map {
                toKotlin(unwrapNotFound(ScriptableObject.getProperty(value, it)), seen)
            }.also { seen.remove(value) }
        }
        value is Scriptable -> {
            if (!seen.add(value)) JsObject(value)
            else buildMap {
                for (id in value.getIds()) {
                    if (id is String) put(id, toKotlin(unwrapNotFound(ScriptableObject.getProperty(value, id)), seen))
                }
            }.also { seen.remove(value) }
        }
        else -> value
    }

    /** A `NativeObject` in [scope], which is what a plain `{}` gives a script. */
    internal fun newObject(cx: Context, scope: Scriptable): NativeObject = cx.newObject(scope) as NativeObject

    /** The string a value would print as in a script. */
    internal fun render(value: Any?): String = ScriptRuntime.toString(value)
}
