/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.dtoa.DoubleFormatter
import kotlin.math.abs

/**
 * Numbers printed the way Java prints them. Upstream leaks `Double.toString()` and
 * `MessageFormat` into a few error messages, and Kotlin/JS prints doubles differently, so the
 * port formats them itself from the shortest round-trip digits (D-39).
 */
internal object JavaNumbers {

    /**
     * `Object.toString()` for a script value: Java's double format for doubles, else `toString`.
     * On Kotlin/JS an Int and a whole Double are the same thing (D-32), so a whole number prints
     * as an Int there; on the JVM a Double 5.0 still prints as `5.0`, as upstream does.
     */
    fun toString(value: Any?): String = when {
        value == null -> "null"
        ScriptRuntime.isInt(value) -> (value as Int).toString()
        value is Double -> doubleToString(value)
        value is Float -> doubleToString(value.toDouble())
        else -> value.toString()
    }

    /** `java.lang.Double.toString`. */
    fun doubleToString(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d == Double.POSITIVE_INFINITY) return "Infinity"
        if (d == Double.NEGATIVE_INFINITY) return "-Infinity"
        if (d == 0.0) return if (1 / d < 0) "-0.0" else "0.0"
        val sign = if (d < 0) "-" else ""
        val (digits, sciExp) = shortestDigits(d)
        if (sciExp in -3..6) {
            if (sciExp >= 0) {
                val intLen = sciExp + 1
                val intPart = digits.take(intLen).padEnd(intLen, '0')
                val frac = digits.drop(intLen).ifEmpty { "0" }
                return "$sign$intPart.$frac"
            }
            return sign + "0." + "0".repeat(-sciExp - 1) + digits
        }
        return sign + digits[0] + "." + digits.substring(1).ifEmpty { "0" } + "E" + sciExp
    }

    /**
     * The shortest digits that read back as [d], with the exponent of `d.ddd x 10^exp`. The
     * digits carry no leading or trailing zeros; zero is `"0"` with exponent 0.
     */
    fun shortestDigits(d: Double): Pair<String, Int> {
        val s = DoubleFormatter.toString(abs(d))
        val e = s.indexOf('e')
        val mant = if (e >= 0) s.substring(0, e) else s
        val exp = if (e >= 0) s.substring(e + 1).toInt() else 0
        val dot = mant.indexOf('.')
        val intPart = if (dot < 0) mant else mant.substring(0, dot)
        val fracPart = if (dot < 0) "" else mant.substring(dot + 1)
        var digits = intPart + fracPart
        var pointPos = intPart.length + exp
        val lead = digits.indexOfFirst { it != '0' }
        if (lead < 0) return "0" to 0
        digits = digits.substring(lead)
        pointPos -= lead
        digits = digits.trimEnd('0')
        return digits to (pointPos - 1)
    }

    /**
     * `MessageFormat`'s default number format: thousands separators, at most three fraction
     * digits rounded half-even on the shortest digits, no `.0` on a whole number.
     */
    fun messageFormat(n: Any): String {
        if (n is KBigInt) return n.toString()
        if (n is Long) return group(n.toString())
        if (ScriptRuntime.isInt(n)) return group((n as Int).toString())
        val d = ScriptRuntime.numericToDouble(n)
        if (d.isNaN()) return "NaN"
        if (d.isInfinite()) return if (d > 0) "∞" else "-∞"
        if (d == 0.0) return if (1 / d < 0) "-0" else "0"
        val sign = if (d < 0) "-" else ""
        val (digits, sciExp) = shortestDigits(d)
        // Lay the digits out as a plain decimal, then round the fraction to three places.
        var intPart: String
        var frac: String
        if (sciExp >= 0) {
            val intLen = sciExp + 1
            intPart = digits.take(intLen).padEnd(intLen, '0')
            frac = digits.drop(intLen)
        } else {
            intPart = "0"
            frac = "0".repeat(-sciExp - 1) + digits
        }
        if (frac.length > 3) {
            val kept = intPart + frac.substring(0, 3)
            val dropped = frac.substring(3)
            val roundUp = when {
                dropped[0] > '5' -> true
                dropped[0] < '5' -> false
                dropped.substring(1).any { it != '0' } -> true
                else -> (kept.last() - '0') % 2 == 1
            }
            val rounded = if (roundUp) increment(kept) else kept
            intPart = rounded.substring(0, rounded.length - 3)
            frac = rounded.substring(rounded.length - 3)
        }
        frac = frac.trimEnd('0')
        return sign + group(intPart) + (if (frac.isEmpty()) "" else ".$frac")
    }

    private fun increment(digits: String): String {
        val chars = digits.toCharArray()
        var i = chars.size - 1
        while (i >= 0) {
            if (chars[i] == '9') {
                chars[i] = '0'
                i--
            } else {
                chars[i]++
                return chars.concatToString()
            }
        }
        return "1" + chars.concatToString()
    }

    private fun group(digits: String): String {
        val negative = digits.startsWith("-")
        val body = if (negative) digits.substring(1) else digits
        if (body.length <= 3) return digits
        val sb = StringBuilder()
        val head = body.length % 3
        if (head > 0) sb.append(body, 0, head)
        var i = head
        while (i < body.length) {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(body, i, i + 3)
            i += 3
        }
        return (if (negative) "-" else "") + sb
    }
}
