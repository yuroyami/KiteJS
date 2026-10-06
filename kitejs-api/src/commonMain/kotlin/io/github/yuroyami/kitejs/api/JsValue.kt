/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.jvm.JvmInline

/**
 * What a [JsValue] holds. A `when` over this covers every JavaScript value, so the compiler can
 * tell you when you have missed one.
 */
public enum class JsType {
    UNDEFINED,
    NULL,
    BOOLEAN,
    NUMBER,
    BIGINT,
    STRING,
    SYMBOL,
    ARRAY,
    FUNCTION,
    OBJECT,
}

/**
 * The `undefined` a [JsValue] holds. Engines compare against it when they turn a value into their
 * own; nothing else needs it, since [JsValue.isUndefined] answers the question.
 */
@InternalKiteJsApi
public object JsUndefined {
    override fun toString(): String = "undefined"
}

/**
 * One value from an engine. It costs nothing at runtime: the wrapper disappears and only the value
 * itself is passed around.
 *
 * A number, a string, a boolean, a BigInt, null or undefined is a copy, the same whichever engine
 * it came from, and works anywhere, even after its engine is closed. A symbol, an object, an array
 * or a function is a handle into the engine that made it, and works only while that engine is
 * open and only on its thread.
 *
 * The `asX` readers coerce the way JavaScript does, so `asString()` on the number 5 gives `"5"`,
 * the same answer `String(5)` gives in a script. Use [type] first when you need the exact kind.
 */
@JvmInline
public value class JsValue @InternalKiteJsApi constructor(
    /**
     * The value itself: [JsUndefined], null, a [Boolean], a [Double], a [String], a [KBigInt], a
     * [JsSymbol] or a [JsObject]. Engines build and read these; nothing else should.
     */
    @property:InternalKiteJsApi public val raw: Any?,
) {

    /** Which kind of value this is. */
    public val type: JsType
        get() = when (raw) {
            null -> JsType.NULL
            JsUndefined -> JsType.UNDEFINED
            is Boolean -> JsType.BOOLEAN
            is Double -> JsType.NUMBER
            is String -> JsType.STRING
            is KBigInt -> JsType.BIGINT
            is JsSymbol -> JsType.SYMBOL
            is JsArray -> JsType.ARRAY
            is JsFunction -> JsType.FUNCTION
            else -> JsType.OBJECT
        }

    /** What the `typeof` operator answers for this value. */
    public val typeOf: String
        get() = when (type) {
            JsType.UNDEFINED -> "undefined"
            JsType.NULL, JsType.ARRAY, JsType.OBJECT -> "object"
            JsType.BOOLEAN -> "boolean"
            JsType.NUMBER -> "number"
            JsType.BIGINT -> "bigint"
            JsType.STRING -> "string"
            JsType.SYMBOL -> "symbol"
            JsType.FUNCTION -> "function"
        }

    public val isUndefined: Boolean get() = raw === JsUndefined

    public val isNull: Boolean get() = raw == null

    /** True for `null` and `undefined`, the two values optional chaining stops at. */
    public val isNullish: Boolean get() = raw == null || raw === JsUndefined

    // ---- Coercing readers, each doing what the matching JavaScript conversion does ------------

    /** ToBoolean: false for undefined, null, false, zero, NaN, `""` and `0n`, true for the rest. */
    public fun asBoolean(): Boolean = when (val r = raw) {
        null, JsUndefined -> false
        is Boolean -> r
        is Double -> !(r == 0.0 || r.isNaN())
        is String -> r.isNotEmpty()
        is KBigInt -> r.signum() != 0
        else -> true
    }

    /** ToNumber. An object is turned into a primitive first, which can run its script. */
    public fun asDouble(): Double = when (val r = raw) {
        null -> 0.0
        JsUndefined -> Double.NaN
        is Boolean -> if (r) 1.0 else 0.0
        is Double -> r
        is String -> JsNumbers.stringToNumber(r)
        is KBigInt -> throw typeError("Cannot convert a BigInt value to a number")
        is JsSymbol -> throw typeError("Cannot convert a Symbol value to a number")
        is JsObject -> r.toPrimitive(PrimitiveHint.NUMBER).asDouble()
        else -> throw notCanonical(r)
    }

    /** ToInt32: the number wrapped into a 32-bit integer, as the bitwise operators see it. */
    public fun asInt(): Int = JsNumbers.toInt32(asDouble())

    /** The same 32-bit integer [asInt] gives, widened. */
    public fun asLong(): Long = asInt().toLong()

    /** ToString. An object is turned into a primitive first, which can run its script. */
    public fun asString(): String = when (val r = raw) {
        null -> "null"
        JsUndefined -> "undefined"
        is Boolean -> r.toString()
        is Double -> JsNumbers.numberToString(r)
        is String -> r
        is KBigInt -> r.toString()
        is JsSymbol -> throw typeError("Cannot convert a Symbol value to a string")
        is JsObject -> r.toPrimitive(PrimitiveHint.STRING).asString()
        else -> throw notCanonical(r)
    }

    /** Throws unless this really is a BigInt. There is no coercion from a number, as in a script. */
    public fun asBigInt(): KBigInt = raw as? KBigInt ?: throw wrongType("a BigInt")

    // ---- Exact readers, which throw rather than guess -----------------------------------------

    public fun asObject(): JsObject = asObjectOrNull() ?: throw wrongType("an object")

    public fun asArray(): JsArray = asArrayOrNull() ?: throw wrongType("an array")

    public fun asFunction(): JsFunction = asFunctionOrNull() ?: throw wrongType("a function")

    public fun asSymbol(): JsSymbol = raw as? JsSymbol ?: throw wrongType("a symbol")

    public fun asObjectOrNull(): JsObject? = raw as? JsObject

    public fun asArrayOrNull(): JsArray? = raw as? JsArray

    public fun asFunctionOrNull(): JsFunction? = raw as? JsFunction

    /**
     * A plain Kotlin value, all the way down: `Map` for an object, `List` for an array, `ByteArray`
     * for an `ArrayBuffer`, a typed array or a `DataView`, `Double`, `String`, `Boolean`, [KBigInt],
     * or null. A function stays a [JsFunction] and a symbol a
     * [JsSymbol], since they have no Kotlin twin, and an object that closes a cycle stays the
     * [JsObject] it is.
     */
    public fun toKotlin(): Any? = Converters.toKotlin(this, HashSet())

    /**
     * What `String(value)` gives, without ever throwing: a symbol prints as `Symbol(description)`,
     * and an object its engine cannot run here, or whose own `toString` fails, prints its class.
     */
    override fun toString(): String = when (val r = raw) {
        is JsSymbol -> r.toString()
        is JsObject -> r.toString()
        else -> asString()
    }

    private fun wrongType(wanted: String): JsError = typeError("expected $wanted, got $typeOf")

    public companion object {
        public val undefined: JsValue = JsValue(JsUndefined)
        public val nullValue: JsValue = JsValue(null)
        public val `true`: JsValue = JsValue(true)
        public val `false`: JsValue = JsValue(false)

        /**
         * A Kotlin scalar as a JavaScript value: see [Converters] for the table. A collection
         * needs an engine to build its object in, so it goes through `KiteJs.valueOf` instead.
         */
        public fun of(value: Any?): JsValue {
            val canonical = Converters.canonical(value)
            if (Converters.isCollection(canonical)) {
                throw IllegalArgumentException(
                    "a ${value!!::class.simpleName} needs an engine to become a JavaScript value; use KiteJs.valueOf",
                )
            }
            return JsValue(canonical)
        }
    }
}

/** Which primitive an object is asked for, as the ECMAScript ToPrimitive hint. */
@InternalKiteJsApi
public enum class PrimitiveHint { NUMBER, STRING }

/** A TypeError the facade raises itself, which reaches the caller as a [JsError]. */
internal fun typeError(message: String): JsError =
    JsError(JsValue.undefined, "TypeError", message, emptyList(), null)

private fun notCanonical(raw: Any?): IllegalStateException =
    IllegalStateException("a JsValue cannot hold a ${raw?.let { it::class.simpleName }}")
