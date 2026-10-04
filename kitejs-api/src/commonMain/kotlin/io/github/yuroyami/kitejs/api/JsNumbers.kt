/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.api.dtoa.DecimalParser
import io.github.yuroyami.kitejs.api.dtoa.DoubleFormatter
import kotlin.math.truncate

/**
 * The number conversions a [JsValue] does on its own, as ECMAScript defines them, so a number or a
 * string read out of any engine coerces the same way without going back to it.
 */
internal object JsNumbers {

    /** Number::toString: the shortest decimal that reads back as the same double. */
    fun numberToString(d: Double): String = DoubleFormatter.toString(d)

    /** ToInt32: truncated, then wrapped into a signed 32-bit integer. NaN and the infinities give 0. */
    fun toInt32(d: Double): Int {
        if (d.isNaN() || d.isInfinite()) return 0
        val i = d.toInt()
        if (i.toDouble() == d) return i
        var r = truncate(d) % TWO_32
        if (r >= TWO_31) r -= TWO_32 else if (r < -TWO_31) r += TWO_32
        return r.toInt()
    }

    /**
     * StringToNumber (ECMAScript 2015, 7.1.3.1): whitespace trimmed, empty is zero, `0x`, `0o` and
     * `0b` read their digits in that radix, `Infinity` may carry a sign, and anything else has to
     * be a whole decimal literal or the answer is NaN.
     */
    fun stringToNumber(s: String): Double {
        var start = 0
        var end = s.length - 1
        while (start <= end && isStrWhiteSpace(s[start])) start++
        if (start > end) return 0.0
        while (isStrWhiteSpace(s[end])) end--
        val t = s.substring(start, end + 1)

        if (t.length > 2 && t[0] == '0') {
            val radix = when (t[1]) {
                'x', 'X' -> 16
                'o', 'O' -> 8
                'b', 'B' -> 2
                else -> 0
            }
            if (radix != 0) return radixToNumber(t, 2, radix)
        }
        when (t) {
            "Infinity", "+Infinity" -> return Double.POSITIVE_INFINITY
            "-Infinity" -> return Double.NEGATIVE_INFINITY
        }
        if (!isDecimalLiteral(t)) return Double.NaN
        return DecimalParser.parse(t) ?: Double.NaN
    }

    /** The digits of [t] from [from] on, in [radix], or NaN when any of them is not one. */
    private fun radixToNumber(t: String, from: Int, radix: Int): Double {
        var sum = 0.0
        for (i in from until t.length) {
            val digit = t[i].digitToIntOrNull(radix) ?: return Double.NaN
            sum = sum * radix + digit
        }
        // Past 2^53 the running sum can round twice, so read the digits exactly instead.
        return if (sum < TWO_53) sum else KBigInt.parse(t.substring(from), radix).toDouble()
    }

    /**
     * StrDecimalLiteral without the Infinity case: an optional sign, digits with an optional
     * fraction or a fraction alone, and an optional exponent. No numeric separators.
     */
    private fun isDecimalLiteral(t: String): Boolean {
        var i = 0
        val n = t.length
        if (i < n && (t[i] == '+' || t[i] == '-')) i++
        var digits = 0
        while (i < n && t[i] in '0'..'9') { i++; digits++ }
        if (i < n && t[i] == '.') {
            i++
            while (i < n && t[i] in '0'..'9') { i++; digits++ }
        }
        if (digits == 0) return false
        if (i < n && (t[i] == 'e' || t[i] == 'E')) {
            i++
            if (i < n && (t[i] == '+' || t[i] == '-')) i++
            var exponentDigits = 0
            while (i < n && t[i] in '0'..'9') { i++; exponentDigits++ }
            if (exponentDigits == 0) return false
        }
        return i == n
    }

    /** StrWhiteSpaceChar: WhiteSpace and LineTerminator, Unicode's space separators included. */
    private fun isStrWhiteSpace(c: Char): Boolean = when (c.code) {
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true
        in 0x2000..0x200A -> true
        else -> false
    }

    private const val TWO_31 = 2147483648.0
    private const val TWO_32 = 4294967296.0
    private const val TWO_53 = 9007199254740992.0
}
