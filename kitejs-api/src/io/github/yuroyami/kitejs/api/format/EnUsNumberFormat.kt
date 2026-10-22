/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api.format

import io.github.yuroyami.kitejs.api.InternalKiteJsApi
import io.github.yuroyami.kitejs.api.KBigInt
import io.github.yuroyami.kitejs.api.dtoa.DoubleFormatter

/**
 * What `Number.prototype.toLocaleString` and `BigInt.prototype.toLocaleString` print on every
 * engine: the text `new Intl.NumberFormat("en-US", options).format(x)` gives, without an `Intl`.
 *
 * The options are ECMA-402's, read in its order with its coercions and errors (InitializeNumberFormat,
 * SetNumberFormatDigitOptions), and the formatting is its FormatNumericToString and
 * PartitionNumberPattern over en-US's patterns and strings. The locale argument is not read: en-US
 * is the only locale there is, as it is for dates. Like ICU, rounding works on the shortest decimal
 * digits of a double, so 1.0005 rounds to 1.001.
 */
@InternalKiteJsApi
public class EnUsNumberFormat private constructor(
    private val style: Style,
    private val currency: String,
    private val currencyDisplay: CurrencyDisplay,
    private val accounting: Boolean,
    private val unit: String,
    private val unitDisplay: Int,
    private val notation: Notation,
    private val compactLong: Boolean,
    private val minimumIntegerDigits: Int,
    private val minimumFractionDigits: Int,
    private val maximumFractionDigits: Int,
    private val minimumSignificantDigits: Int,
    private val maximumSignificantDigits: Int,
    private val roundingType: RoundingType,
    private val roundingIncrement: Int,
    private val roundingMode: RoundingMode,
    private val stripIfInteger: Boolean,
    private val grouping: Grouping,
    private val signDisplay: SignDisplay,
) {

    /** Formats a Number. */
    public fun format(x: Double): String = when {
        x.isNaN() -> compose(null, false, "NaN", 0, false)
        x.isInfinite() -> compose(null, x < 0, "\u221E", 0, false)
        else -> formatFinite(Dec.of(x), x < 0 || (x == 0.0 && 1.0 / x < 0), x == 0.0 && 1.0 / x < 0)
    }

    /** Formats a BigInt. */
    public fun format(x: KBigInt): String = formatFinite(Dec.of(x), x.signum() < 0, false)

    private fun formatFinite(abs: Dec, negative: Boolean, negativeZero: Boolean): String {
        var x = abs
        var exponent = 0
        if (!negativeZero) {
            if (style == Style.PERCENT) x = x.scale(2)
            exponent = computeExponent(x)
            x = x.scale(-exponent)
        }
        val result = formatNumericToString(x, negative)
        val roundedNegative = negative && !result.rounded.isZero
        return compose(result, roundedNegative, result.string, exponent, negative)
    }

    /**
     * ComputeExponent: the exponent the notation shows for [x], taken again one magnitude up when
     * rounding carries into it, as 999,999 does in compact notation.
     */
    private fun computeExponent(x: Dec): Int {
        if (x.isZero) return 0
        val magnitude = x.magnitude
        val exponent = exponentForMagnitude(magnitude)
        val rounded = formatNumericToString(x.scale(-exponent), false).rounded
        if (rounded.isZero) return exponent
        if (rounded.magnitude == magnitude - exponent) return exponent
        return exponentForMagnitude(magnitude + 1)
    }

    private fun exponentForMagnitude(magnitude: Int): Int = when (notation) {
        Notation.STANDARD -> 0
        Notation.SCIENTIFIC -> magnitude
        Notation.ENGINEERING -> floorDiv(magnitude, 3) * 3
        // en's compact patterns run from thousands to trillions.
        Notation.COMPACT -> if (magnitude < 3) 0 else minOf(floorDiv(magnitude, 3) * 3, 12)
    }

    private class Raw(val string: String, val rounded: Dec, val integerDigits: Int, val roundingMagnitude: Int)

    /** FormatNumericToString, for a non-negative [x] whose sign is [negative]. */
    private fun formatNumericToString(x: Dec, negative: Boolean): Raw {
        val mode = roundingMode.unsigned(negative)
        val result = when (roundingType) {
            RoundingType.SIGNIFICANT -> toRawPrecision(x, minimumSignificantDigits, maximumSignificantDigits, mode)
            RoundingType.FRACTION -> toRawFixed(x, minimumFractionDigits, maximumFractionDigits, roundingIncrement, mode)
            else -> {
                val s = toRawPrecision(x, minimumSignificantDigits, maximumSignificantDigits, mode)
                val f = toRawFixed(x, minimumFractionDigits, maximumFractionDigits, roundingIncrement, mode)
                val sMorePrecise = s.roundingMagnitude <= f.roundingMagnitude
                if (roundingType == RoundingType.MORE_PRECISION) {
                    if (sMorePrecise) s else f
                } else {
                    if (sMorePrecise) f else s
                }
            }
        }
        var string = result.string
        if (stripIfInteger && result.rounded.isInteger) {
            val dot = string.indexOf('.')
            if (dot >= 0) string = string.substring(0, dot)
        }
        if (result.integerDigits < minimumIntegerDigits) {
            string = "0".repeat(minimumIntegerDigits - result.integerDigits) + string
        }
        return Raw(string, result.rounded, result.integerDigits, result.roundingMagnitude)
    }

    /** Lays out the number, its exponent or compact suffix, its sign and its style. */
    private fun compose(raw: Raw?, negative: Boolean, number: String, exponent: Int, negativeInput: Boolean): String {
        val numeric = StringBuilder()
        if (raw == null) {
            numeric.append(number)
        } else {
            numeric.append(group(number))
            when (notation) {
                Notation.SCIENTIFIC, Notation.ENGINEERING -> numeric.append('E').append(exponent)
                Notation.COMPACT -> if (exponent != 0) {
                    val i = exponent / 3 - 1
                    numeric.append(if (compactLong) COMPACT_LONG[i] else COMPACT_SHORT[i])
                }
                Notation.STANDARD -> {}
            }
        }
        // English plurals: "one" for exactly 1 with no fraction digits shown, in plain magnitude.
        val one = raw != null && exponent == 0 && number.trimStart('0') == "1"
        val body = when (style) {
            Style.DECIMAL -> numeric.toString()
            Style.PERCENT -> "$numeric%"
            Style.CURRENCY -> when (currencyDisplay) {
                CurrencyDisplay.NAME -> {
                    val names = EnUsNumberData.currencyNames(currency)
                    "$numeric " + (if (one) names.first else names.second)
                }
                else -> {
                    val symbol = when (currencyDisplay) {
                        CurrencyDisplay.CODE -> "$currency\u00A0"
                        else -> EnUsNumberData.currencySymbol(currency, narrow = currencyDisplay == CurrencyDisplay.NARROW_SYMBOL)
                    }
                    // CLDR's currency spacing puts the space between a symbol ending in a letter and
                    // a digit only, so NaN and infinity follow such a symbol directly.
                    if (symbol.endsWith('\u00A0') && !numeric[0].isDigit()) symbol.dropLast(1) + numeric else symbol + numeric
                }
            }
            Style.UNIT -> EnUsNumberData.unitPattern(unit, unitDisplay, one).replace("{0}", numeric.toString())
        }
        val isZero = raw != null && raw.rounded.isZero
        // GetNumberFormatPattern. NaN has no sign, and counts as positive for "always".
        val isNaN = raw == null && number == "NaN"
        val sign = when (signDisplay) {
            SignDisplay.NEVER -> 0
            SignDisplay.AUTO -> if (negative || (isZero && negativeInput)) -1 else 0
            SignDisplay.ALWAYS -> if (negative || (isZero && negativeInput)) -1 else 1
            SignDisplay.EXCEPT_ZERO -> if (isNaN || isZero) 0 else if (negative) -1 else 1
            SignDisplay.NEGATIVE -> if (negative && !isZero) -1 else 0
        }
        val accountingStyle = accounting && style == Style.CURRENCY && currencyDisplay != CurrencyDisplay.NAME
        return when {
            sign < 0 && accountingStyle -> "($body)"
            sign < 0 -> "-$body"
            sign > 0 -> "+$body"
            else -> body
        }
    }

    private fun group(number: String): String {
        val dot = number.indexOf('.')
        val integer = if (dot < 0) number else number.substring(0, dot)
        val threshold = when (grouping) {
            Grouping.NONE -> return number
            Grouping.MIN2 -> 5
            Grouping.AUTO, Grouping.ALWAYS -> 4
        }
        if (integer.length < threshold) return number
        val b = StringBuilder()
        for (i in integer.indices) {
            if (i > 0 && (integer.length - i) % 3 == 0) b.append(',')
            b.append(integer[i])
        }
        if (dot >= 0) b.append(number, dot, number.length)
        return b.toString()
    }

    internal enum class Style { DECIMAL, PERCENT, CURRENCY, UNIT }
    internal enum class CurrencyDisplay { CODE, SYMBOL, NARROW_SYMBOL, NAME }
    internal enum class Notation { STANDARD, SCIENTIFIC, ENGINEERING, COMPACT }
    internal enum class RoundingType { FRACTION, SIGNIFICANT, MORE_PRECISION, LESS_PRECISION }
    internal enum class Grouping { NONE, MIN2, AUTO, ALWAYS }
    internal enum class SignDisplay { AUTO, NEVER, ALWAYS, EXCEPT_ZERO, NEGATIVE }

    /** The unsigned rounding modes of ECMA-402's GetUnsignedRoundingMode. */
    internal enum class Unsigned { ZERO, INFINITY, HALF_ZERO, HALF_INFINITY, HALF_EVEN }

    internal enum class RoundingMode(private val positive: Unsigned, private val negative: Unsigned) {
        CEIL(Unsigned.INFINITY, Unsigned.ZERO),
        FLOOR(Unsigned.ZERO, Unsigned.INFINITY),
        EXPAND(Unsigned.INFINITY, Unsigned.INFINITY),
        TRUNC(Unsigned.ZERO, Unsigned.ZERO),
        HALF_CEIL(Unsigned.HALF_INFINITY, Unsigned.HALF_ZERO),
        HALF_FLOOR(Unsigned.HALF_ZERO, Unsigned.HALF_INFINITY),
        HALF_EXPAND(Unsigned.HALF_INFINITY, Unsigned.HALF_INFINITY),
        HALF_TRUNC(Unsigned.HALF_ZERO, Unsigned.HALF_ZERO),
        HALF_EVEN(Unsigned.HALF_EVEN, Unsigned.HALF_EVEN),
        ;

        fun unsigned(isNegative: Boolean): Unsigned = if (isNegative) negative else positive
    }

    public companion object {
        private val COMPACT_SHORT = arrayOf("K", "M", "B", "T")
        private val COMPACT_LONG = arrayOf(" thousand", " million", " billion", " trillion")

        /** The format of `toLocaleString()` with no options, or with `undefined` for them. */
        public val DEFAULT: EnUsNumberFormat by lazy { resolve(null) }

        /**
         * Reads the options of `new Intl.NumberFormat("en-US", options)` through [options], or
         * takes every default when it is null (CoerceOptionsToObject of `undefined`). Throws a
         * [NumberFormatOptionError] where ECMA-402 throws, and lets what [options] throws through.
         */
        public fun resolve(options: NumberFormatOptionReader?): EnUsNumberFormat {
            val read = Options(options)
            read.string("localeMatcher", listOf("lookup", "best fit"), "best fit")
            val numberingSystem = read.string("numberingSystem", null, null)
            if (numberingSystem != null && !isUnicodeType(numberingSystem)) {
                throw NumberFormatOptionError(false, "Invalid numberingSystem : $numberingSystem")
            }

            // SetNumberFormatUnitOptions
            val styleName = read.string("style", listOf("decimal", "percent", "currency", "unit"), "decimal")!!
            val currency = read.string("currency", null, null)
            if (currency == null) {
                if (styleName == "currency") throw NumberFormatOptionError(true, "Currency code is required with currency style.")
            } else if (!isWellFormedCurrencyCode(currency)) {
                throw NumberFormatOptionError(false, "Invalid currency code : $currency")
            }
            val currencyDisplayName = read.string("currencyDisplay", listOf("code", "symbol", "narrowSymbol", "name"), "symbol")!!
            val currencySign = read.string("currencySign", listOf("standard", "accounting"), "standard")!!
            val unit = read.string("unit", null, null)
            if (unit == null) {
                if (styleName == "unit") throw NumberFormatOptionError(true, "Unit is required with unit style.")
            } else if (!EnUsNumberData.isWellFormedUnit(unit)) {
                throw NumberFormatOptionError(false, "Invalid unit argument for Intl.NumberFormat() '$unit'")
            }
            val unitDisplayName = read.string("unitDisplay", listOf("short", "narrow", "long"), "short")!!
            val style = Style.entries[listOf("decimal", "percent", "currency", "unit").indexOf(styleName)]

            val notationName = read.string("notation", listOf("standard", "scientific", "engineering", "compact"), "standard")!!
            val notation = Notation.entries[listOf("standard", "scientific", "engineering", "compact").indexOf(notationName)]
            val upperCurrency = currency?.uppercase() ?: ""
            val mnfdDefault: Int
            val mxfdDefault: Int
            if (style == Style.CURRENCY) {
                val digits = EnUsNumberData.currencyDigits(upperCurrency)
                mnfdDefault = digits
                mxfdDefault = digits
            } else {
                mnfdDefault = 0
                mxfdDefault = if (style == Style.PERCENT) 0 else 3
            }

            // SetNumberFormatDigitOptions
            val mnid = read.number("minimumIntegerDigits", 1, 21, 1)!!
            val mnfdRaw = read.raw("minimumFractionDigits")
            val mxfdRaw = read.raw("maximumFractionDigits")
            val mnsdRaw = read.raw("minimumSignificantDigits")
            val mxsdRaw = read.raw("maximumSignificantDigits")
            val roundingIncrement = read.number("roundingIncrement", 1, 5000, 1)!!
            if (roundingIncrement !in INCREMENTS) {
                throw NumberFormatOptionError(false, "roundingIncrement value is out of range.")
            }
            val roundingModeName = read.string("roundingMode", ROUNDING_MODES, "halfExpand")!!
            val roundingPriority = read.string("roundingPriority", listOf("auto", "morePrecision", "lessPrecision"), "auto")!!
            val trailingZeroDisplay = read.string("trailingZeroDisplay", listOf("auto", "stripIfInteger"), "auto")!!
            val mxfdFallback = if (roundingIncrement != 1) mnfdDefault else mxfdDefault
            val hasSd = mnsdRaw != null || mxsdRaw != null
            val hasFd = mnfdRaw != null || mxfdRaw != null
            var needSd = true
            var needFd = true
            if (roundingPriority == "auto") {
                needSd = hasSd
                if (needSd || (!hasFd && notation == Notation.COMPACT)) needFd = false
            }
            var mnsd = 1
            var mxsd = 21
            if (needSd && hasSd) {
                mnsd = read.default("minimumSignificantDigits", mnsdRaw, 1, 21, 1)!!
                mxsd = read.default("maximumSignificantDigits", mxsdRaw, mnsd, 21, 21)!!
            }
            var mnfd = mnfdDefault
            var mxfd = mxfdFallback
            if (needFd && hasFd) {
                val lo = read.default("minimumFractionDigits", mnfdRaw, 0, 100, null)
                val hi = read.default("maximumFractionDigits", mxfdRaw, 0, 100, null)
                when {
                    lo == null -> {
                        mnfd = minOf(mnfdDefault, hi!!)
                        mxfd = hi
                    }
                    hi == null -> {
                        mnfd = lo
                        mxfd = maxOf(mxfdFallback, lo)
                    }
                    lo > hi -> throw NumberFormatOptionError(false, "maximumFractionDigits value is out of range.")
                    else -> {
                        mnfd = lo
                        mxfd = hi
                    }
                }
            }
            val roundingType: RoundingType
            if (!needSd && !needFd) {
                mnfd = 0
                mxfd = 0
                mnsd = 1
                mxsd = 2
                roundingType = RoundingType.MORE_PRECISION
            } else if (roundingPriority == "auto") {
                roundingType = if (hasSd) RoundingType.SIGNIFICANT else RoundingType.FRACTION
            } else {
                roundingType = if (roundingPriority == "morePrecision") RoundingType.MORE_PRECISION else RoundingType.LESS_PRECISION
            }
            if (roundingIncrement != 1) {
                if (roundingType != RoundingType.FRACTION) {
                    throw NumberFormatOptionError(true, "roundingIncrement is only allowed with fraction digit rounding.")
                }
                if (mxfd != mnfd) {
                    throw NumberFormatOptionError(false, "maximumFractionDigits must equal minimumFractionDigits with a roundingIncrement.")
                }
            }

            val compactDisplay = read.string("compactDisplay", listOf("short", "long"), "short")!!
            val grouping = read.grouping(if (notation == Notation.COMPACT) Grouping.MIN2 else Grouping.AUTO)
            val signDisplayName = read.string("signDisplay", listOf("auto", "never", "always", "exceptZero", "negative"), "auto")!!

            return EnUsNumberFormat(
                style = style,
                currency = upperCurrency,
                currencyDisplay = CurrencyDisplay.entries[listOf("code", "symbol", "narrowSymbol", "name").indexOf(currencyDisplayName)],
                accounting = currencySign == "accounting",
                unit = unit ?: "",
                unitDisplay = listOf("short", "narrow", "long").indexOf(unitDisplayName),
                notation = notation,
                compactLong = compactDisplay == "long",
                minimumIntegerDigits = mnid,
                minimumFractionDigits = mnfd,
                maximumFractionDigits = mxfd,
                minimumSignificantDigits = mnsd,
                maximumSignificantDigits = mxsd,
                roundingType = roundingType,
                roundingIncrement = roundingIncrement,
                roundingMode = RoundingMode.entries[ROUNDING_MODES.indexOf(roundingModeName)],
                stripIfInteger = trailingZeroDisplay == "stripIfInteger",
                grouping = grouping,
                signDisplay = SignDisplay.entries[listOf("auto", "never", "always", "exceptZero", "negative").indexOf(signDisplayName)],
            )
        }

        private val ROUNDING_MODES =
            listOf("ceil", "floor", "expand", "trunc", "halfCeil", "halfFloor", "halfExpand", "halfTrunc", "halfEven")
        private val INCREMENTS = setOf(1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000, 2000, 2500, 5000)

        private fun isWellFormedCurrencyCode(code: String): Boolean =
            code.length == 3 && code.all { it in 'a'..'z' || it in 'A'..'Z' }

        /** The `type` production of Unicode locale identifiers: alphanum{3,8} ("-" alphanum{3,8})*. */
        private fun isUnicodeType(s: String): Boolean =
            s.split('-').all { part -> part.length in 3..8 && part.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }

        private fun floorDiv(a: Int, b: Int): Int {
            val q = a / b
            return if (a % b != 0 && (a < 0) != (b < 0)) q - 1 else q
        }

        /** ToRawPrecision. */
        private fun toRawPrecision(x: Dec, minPrecision: Int, maxPrecision: Int, mode: Unsigned): Raw {
            val p = maxPrecision
            val e: Int
            val n: String
            val rounded: Dec
            if (x.isZero) {
                n = "0".repeat(p)
                e = 0
                rounded = Dec.ZERO
            } else {
                val e1 = x.magnitude
                rounded = x.roundAt(e1 - p + 1, 1, mode)
                e = rounded.magnitude
                n = rounded.digits + "0".repeat(rounded.exp - (e - p + 1))
            }
            var m: String
            val integerDigits: Int
            if (e >= p - 1) {
                m = n + "0".repeat(e - p + 1)
                integerDigits = e + 1
            } else if (e >= 0) {
                m = n.substring(0, e + 1) + "." + n.substring(e + 1)
                integerDigits = e + 1
            } else {
                m = "0." + "0".repeat(-(e + 1)) + n
                integerDigits = 1
            }
            if (m.indexOf('.') >= 0 && maxPrecision > minPrecision) m = cutZeros(m, maxPrecision - minPrecision)
            return Raw(m, rounded, integerDigits, e - p + 1)
        }

        /** ToRawFixed. */
        private fun toRawFixed(x: Dec, minFraction: Int, maxFraction: Int, increment: Int, mode: Unsigned): Raw {
            val f = maxFraction
            val rounded = x.roundAt(-f, increment, mode)
            var m = if (rounded.isZero) "0" else rounded.digits + "0".repeat(rounded.exp + f)
            val integerDigits: Int
            if (f != 0) {
                var k = m.length
                if (k <= f) {
                    m = "0".repeat(f + 1 - k) + m
                    k = f + 1
                }
                m = m.substring(0, k - f) + "." + m.substring(k - f)
                integerDigits = k - f
            } else {
                integerDigits = m.length
            }
            if (maxFraction > minFraction) m = cutZeros(m, maxFraction - minFraction)
            if (m.endsWith('.')) m = m.dropLast(1)
            return Raw(m, rounded, integerDigits, -f)
        }

        /** Drops up to [cut] trailing zeros, and then a trailing point. */
        private fun cutZeros(s: String, cut: Int): String {
            var end = s.length
            var left = cut
            while (left > 0 && s[end - 1] == '0') {
                end--
                left--
            }
            if (s[end - 1] == '.') end--
            return s.substring(0, end)
        }
    }

    /** GetOption, GetNumberOption and DefaultNumberOption over an options object, or none. */
    private class Options(private val reader: NumberFormatOptionReader?) {

        fun raw(name: String): Any? {
            val r = reader ?: return null
            val v = r.get(name)
            return if (r.isUndefined(v)) null else (v ?: NULL)
        }

        private fun unwrap(v: Any): Any? = if (v === NULL) null else v

        fun string(name: String, values: List<String>?, fallback: String?): String? {
            val v = raw(name) ?: return fallback
            val s = reader!!.toJsString(unwrap(v))
            if (values != null && s !in values) {
                throw NumberFormatOptionError(false, "Value $s out of range for Intl.NumberFormat options property $name")
            }
            return s
        }

        fun number(name: String, min: Int, max: Int, fallback: Int?): Int? = default(name, raw(name), min, max, fallback)

        fun default(name: String, value: Any?, min: Int, max: Int, fallback: Int?): Int? {
            if (value == null) return fallback
            val d = reader!!.toNumber(unwrap(value))
            if (d.isNaN() || d < min || d > max) throw NumberFormatOptionError(false, "$name value is out of range.")
            return kotlin.math.floor(d).toInt()
        }

        /** GetBooleanOrStringNumberFormatOption for `useGrouping`, with ECMA-402's fallbacks. */
        fun grouping(fallback: Grouping): Grouping {
            val v = raw("useGrouping") ?: return fallback
            val value = unwrap(v)
            if (reader!!.isTrue(value)) return Grouping.ALWAYS
            if (!reader.toBoolean(value)) return Grouping.NONE
            return when (val s = reader.toJsString(value)) {
                "min2" -> Grouping.MIN2
                "auto" -> Grouping.AUTO
                "always" -> Grouping.ALWAYS
                "true", "false" -> fallback
                else -> throw NumberFormatOptionError(false, "Value $s out of range for Intl.NumberFormat options property useGrouping")
            }
        }

        private companion object {
            /** A JavaScript null read from the options, told apart from a missing value. */
            val NULL = Any()
        }
    }
}

/** Reads the options object of `toLocaleString` for [EnUsNumberFormat.resolve]. */
@InternalKiteJsApi
public interface NumberFormatOptionReader {
    /** `Get(options, name)`: whatever the engine holds, which may run a getter. */
    public fun get(name: String): Any?

    public fun isUndefined(value: Any?): Boolean

    /** ToNumber. */
    public fun toNumber(value: Any?): Double

    /** ToString. */
    public fun toJsString(value: Any?): String

    /** ToBoolean. */
    public fun toBoolean(value: Any?): Boolean

    /** Whether [value] is the boolean `true` itself, which `useGrouping` tells apart from `"true"`. */
    public fun isTrue(value: Any?): Boolean
}

/** A RangeError, or a TypeError when [isTypeError], that the options call for. */
@InternalKiteJsApi
public class NumberFormatOptionError(public val isTypeError: Boolean, message: String) : RuntimeException(message)

/**
 * An exact non-negative decimal, [digits] times ten to the [exp]. [digits] has no leading or
 * trailing zeros, and is empty for zero.
 */
internal class Dec private constructor(val digits: String, val exp: Int) {

    val isZero: Boolean get() = digits.isEmpty()

    /** floor(log10(x)), for a value that is not zero. */
    val magnitude: Int get() = digits.length - 1 + exp

    val isInteger: Boolean get() = isZero || exp >= 0

    fun scale(power: Int): Dec = if (isZero) this else Dec(digits, exp + power)

    /**
     * Rounds to a multiple of [increment] units of ten to the [m], as ToRawFixed and ToRawPrecision
     * choose between the two candidates around the value. Every increment ECMA-402 allows divides
     * 10000, so the last four digits decide the remainder and the parity half-even needs.
     */
    fun roundAt(m: Int, increment: Int, mode: EnUsNumberFormat.Unsigned): Dec {
        if (isZero) return this
        val shift = exp - m
        val n: String
        val firstDropped: Int
        val restNonZero: Boolean
        if (shift >= 0) {
            n = digits + "0".repeat(shift)
            firstDropped = 0
            restNonZero = false
        } else {
            val k = -shift
            when {
                k > digits.length -> {
                    n = "0"
                    firstDropped = 0
                    restNonZero = true
                }
                k == digits.length -> {
                    n = "0"
                    firstDropped = digits[0] - '0'
                    restNonZero = digits.length > 1
                }
                else -> {
                    n = digits.substring(0, digits.length - k)
                    firstDropped = digits[digits.length - k] - '0'
                    restNonZero = k > 1
                }
            }
        }
        val fractionZero = firstDropped == 0 && !restNonZero
        val last4 = n.takeLast(4).toInt()
        val d = last4 % increment
        if (d == 0 && fractionZero) return of(n, m)
        val low = subtractSmall(n, d)
        val up = when (mode) {
            EnUsNumberFormat.Unsigned.ZERO -> false
            EnUsNumberFormat.Unsigned.INFINITY -> true
            else -> {
                val t = 2 * d
                val cmp = when {
                    t + 1 < increment -> -1
                    t + 1 == increment -> if (firstDropped < 5) -1 else if (firstDropped == 5 && !restNonZero) 0 else 1
                    t == increment -> if (fractionZero) 0 else 1
                    else -> 1
                }
                when {
                    cmp < 0 -> false
                    cmp > 0 -> true
                    mode == EnUsNumberFormat.Unsigned.HALF_ZERO -> false
                    mode == EnUsNumberFormat.Unsigned.HALF_INFINITY -> true
                    else -> (last4 / increment) % 2 != 0
                }
            }
        }
        return of(if (up) addSmall(low, increment) else low, m)
    }

    companion object {
        val ZERO = Dec("", 0)

        /** [n], a string of decimal digits, times ten to the [m]. */
        fun of(n: String, m: Int): Dec {
            var start = 0
            while (start < n.length && n[start] == '0') start++
            if (start == n.length) return ZERO
            var end = n.length
            while (n[end - 1] == '0') end--
            return Dec(n.substring(start, end), m + (n.length - end))
        }

        /** The absolute value of a finite [x], from the digits `Number::toString` gives it. */
        fun of(x: Double): Dec {
            if (x == 0.0) return ZERO
            val s = DoubleFormatter.toString(kotlin.math.abs(x))
            val e = s.indexOf('e')
            val mantissa = if (e < 0) s else s.substring(0, e)
            var exponent = if (e < 0) 0 else s.substring(e + 1).removePrefix("+").toInt()
            val dot = mantissa.indexOf('.')
            if (dot >= 0) exponent -= mantissa.length - dot - 1
            return of(mantissa.replace(".", ""), exponent)
        }

        fun of(x: KBigInt): Dec = of(x.abs().toString(), 0)

        private fun subtractSmall(n: String, d: Int): String {
            if (d == 0) return n
            val c = n.toCharArray()
            var borrow = d
            var i = c.size - 1
            while (borrow > 0) {
                val v = (c[i] - '0') - borrow % 10
                borrow /= 10
                if (v < 0) {
                    c[i] = '0' + (v + 10)
                    borrow += 1
                } else {
                    c[i] = '0' + v
                }
                i--
            }
            return c.concatToString()
        }

        private fun addSmall(n: String, a: Int): String {
            val c = n.toCharArray()
            var carry = a
            var i = c.size - 1
            while (carry > 0 && i >= 0) {
                val v = (c[i] - '0') + carry % 10
                carry /= 10
                if (v >= 10) {
                    c[i] = '0' + (v - 10)
                    carry += 1
                } else {
                    c[i] = '0' + v
                }
                i--
            }
            val body = c.concatToString()
            return if (carry > 0) carry.toString() + body else body
        }
    }
}
