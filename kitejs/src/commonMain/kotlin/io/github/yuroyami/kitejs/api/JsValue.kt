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
 * One value from the engine. It costs nothing at runtime: the wrapper disappears and only the
 * value itself is passed around.
 *
 * The `asX` readers coerce the way JavaScript does, so `asString()` on the number 5 gives `"5"`,
 * the same answer `String(5)` gives in a script. Use [type] first when you need the exact kind.
 */
@JvmInline
public value class JsValue internal constructor(internal val raw: Any?) {

    /** Which kind of value this is. */
    public val type: JsType
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
    public val typeOf: String get() = ScriptRuntime.typeOf(raw)

    public val isUndefined: Boolean get() = Undefined.isUndefined(raw)

    public val isNull: Boolean get() = raw == null

    /** True for `null` and `undefined`, the two values optional chaining stops at. */
    public val isNullish: Boolean get() = raw == null || Undefined.isUndefined(raw)

    // ---- Coercing readers, each doing what the matching JavaScript conversion does ------------

    public fun asBoolean(): Boolean = ScriptRuntime.toBoolean(raw)

    public fun asDouble(): Double = ScriptRuntime.toNumber(raw)

    public fun asInt(): Int = ScriptRuntime.toInt32(raw)

    public fun asLong(): Long = ScriptRuntime.toInt32(raw).toLong()

    public fun asString(): String = ScriptRuntime.toString(raw)

    /** Throws unless this really is a BigInt. There is no coercion from a number, as in a script. */
    public fun asBigInt(): KBigInt = raw as? KBigInt ?: throw wrongType("a BigInt")

    // ---- Exact readers, which throw rather than guess -----------------------------------------

    public fun asObject(): JsObject = asObjectOrNull() ?: throw wrongType("an object")

    public fun asArray(): JsArray = asArrayOrNull() ?: throw wrongType("an array")

    public fun asFunction(): JsFunction = asFunctionOrNull() ?: throw wrongType("a function")

    public fun asObjectOrNull(): JsObject? = (raw as? Scriptable)?.let { JsObject(it) }

    public fun asArrayOrNull(): JsArray? = (raw as? NativeArray)?.let { JsArray(it) }

    public fun asFunctionOrNull(): JsFunction? = (raw as? Function)?.let { JsFunction(it) }

    /**
     * A plain Kotlin value, all the way down: `Map` for an object, `List` for an array, `Double`,
     * `String`, `Boolean`, or null. A function stays a [JsFunction], since it has no Kotlin twin.
     */
    public fun toKotlin(): Any? = Converters.toKotlin(raw, HashSet())

    override fun toString(): String = if (isUndefined) "undefined" else ScriptRuntime.toString(raw)

    private fun wrongType(wanted: String): JsError =
        jsTypeError("expected $wanted, got ${typeOf}")

    public companion object {
        public val undefined: JsValue = JsValue(Undefined.instance)
        public val nullValue: JsValue = JsValue(null)
        public val `true`: JsValue = JsValue(true)
        public val `false`: JsValue = JsValue(false)

        /** Wraps a Kotlin value the engine already understands. Use `KiteJs.valueOf` for the rest. */
        public fun of(value: Any?): JsValue = JsValue(Converters.toEngine(value))
    }
}
