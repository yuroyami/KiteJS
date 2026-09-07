/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.Function
import io.github.yuroyami.kitejs.KBigInt
import io.github.yuroyami.kitejs.NativeArray
import io.github.yuroyami.kitejs.ScriptRuntime
import io.github.yuroyami.kitejs.Scriptable
import io.github.yuroyami.kitejs.Symbol
import io.github.yuroyami.kitejs.Undefined
import kotlin.jvm.JvmInline

/**
 * What a [JsValue] holds. A `when` over this covers every JavaScript value, so the compiler can
 * tell you when you have missed one.
 */
enum class JsType {
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
 * One value from the engine. It costs nothing at runtime: the wrapper disappears and only the
 * value itself is passed around.
 *
 * The `asX` readers coerce the way JavaScript does, so `asString()` on the number 5 gives `"5"`,
 * the same answer `String(5)` gives in a script. Use [type] first when you need the exact kind.
 */
@JvmInline
value class JsValue internal constructor(internal val raw: Any?) {

    /** Which kind of value this is. */
    val type: JsType
        get() = when {
            raw == null -> JsType.NULL
            Undefined.isUndefined(raw) -> JsType.UNDEFINED
            raw is Boolean -> JsType.BOOLEAN
            raw is KBigInt -> JsType.BIGINT
            raw is Number -> JsType.NUMBER
            raw is CharSequence -> JsType.STRING
            raw is Symbol -> JsType.SYMBOL
            raw is NativeArray -> JsType.ARRAY
            raw is Function -> JsType.FUNCTION
            else -> JsType.OBJECT
        }

    /** What the `typeof` operator answers for this value. */
    val typeOf: String get() = ScriptRuntime.typeOf(raw)

    val isUndefined: Boolean get() = Undefined.isUndefined(raw)

    val isNull: Boolean get() = raw == null

    /** True for `null` and `undefined`, the two values optional chaining stops at. */
    val isNullish: Boolean get() = raw == null || Undefined.isUndefined(raw)

    // ---- Coercing readers, each doing what the matching JavaScript conversion does ------------

    fun asBoolean(): Boolean = ScriptRuntime.toBoolean(raw)

    fun asDouble(): Double = ScriptRuntime.toNumber(raw)

    fun asInt(): Int = ScriptRuntime.toInt32(raw)

    fun asLong(): Long = ScriptRuntime.toInt32(raw).toLong()

    fun asString(): String = ScriptRuntime.toString(raw)

    /** Throws unless this really is a BigInt. There is no coercion from a number, as in a script. */
    fun asBigInt(): KBigInt = raw as? KBigInt ?: throw wrongType("a BigInt")

    // ---- Exact readers, which throw rather than guess -----------------------------------------

    fun asObject(): JsObject = asObjectOrNull() ?: throw wrongType("an object")

    fun asArray(): JsArray = asArrayOrNull() ?: throw wrongType("an array")

    fun asFunction(): JsFunction = asFunctionOrNull() ?: throw wrongType("a function")

    fun asObjectOrNull(): JsObject? = (raw as? Scriptable)?.let { JsObject(it) }

    fun asArrayOrNull(): JsArray? = (raw as? NativeArray)?.let { JsArray(it) }

    fun asFunctionOrNull(): JsFunction? = (raw as? Function)?.let { JsFunction(it) }

    /**
     * A plain Kotlin value, all the way down: `Map` for an object, `List` for an array, `Double`,
     * `String`, `Boolean`, or null. A function stays a [JsFunction], since it has no Kotlin twin.
     */
    fun toKotlin(): Any? = Converters.toKotlin(raw, HashSet())

    override fun toString(): String = if (isUndefined) "undefined" else ScriptRuntime.toString(raw)

    private fun wrongType(wanted: String): JsError =
        jsTypeError("expected $wanted, got ${typeOf}")

    companion object {
        val undefined: JsValue = JsValue(Undefined.instance)
        val nullValue: JsValue = JsValue(null)
        val `true`: JsValue = JsValue(true)
        val `false`: JsValue = JsValue(false)

        /** Wraps a Kotlin value the engine already understands. Use `KiteJs.valueOf` for the rest. */
        fun of(value: Any?): JsValue = JsValue(Converters.toEngine(value))
    }
}
