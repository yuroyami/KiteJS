/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.dtoa

/**
 * `Number.prototype.toFixed`, `toExponential` and `toPrecision`.
 *
 * Upstream leans on `java.math.BigDecimal` for the exact decimal expansion of a double and its
 * HALF_UP rounding. Common Kotlin has no BigDecimal, so this file expands the double by hand
 * (every double is a finite decimal) and rounds the digit string itself. Same results, same
 * shapes.
 */
object DecimalFormatter {
    private const val MAX_FIXED = 1E21

    fun toExponential(v: Double, fractionDigits: Int): String {
        if (fractionDigits < 0) {
            return DoubleFormatter.toDecimal(v).toString(Decimal.Mode.TO_EXPONENTIAL)
        }
        val negative = v < 0
        val bd = Exact.of(if (negative) -v else v).roundToPrecision(fractionDigits + 1)
        val exponent = bd.precision - bd.scale - 1
        return toExponentialString(bd, exponent, fractionDigits, negative)
    }

    fun toFixed(v: Double, fractionDigits: Int): String {
        val negative = v < 0
        val value = if (negative) -v else v
        if (value >= MAX_FIXED) {
            return DoubleFormatter.toString(v)
        }
        var bd = Exact.of(value)
        if (bd.scale > fractionDigits) {
            bd = bd.setScale(fractionDigits)
        }
        return toFixedString(bd, fractionDigits, negative)
    }

    fun toPrecision(v: Double, precision: Int): String {
        val negative = v < 0
        val bd = Exact.of(if (negative) -v else v).roundToPrecision(precision)
        val scale = bd.scale
        val numDigits = bd.precision
        val exponent: Int
        val fractionDigits: Int
        if (scale >= 0) {
            fractionDigits = if (scale >= numDigits) precision else precision - (numDigits - scale)
            exponent = numDigits - scale - 1
        } else {
            fractionDigits = 0
            exponent = numDigits + -scale - 1
        }
        if (exponent < -6 || exponent >= precision) {
            return toExponentialString(bd, exponent, precision - 1, negative)
        }
        return toFixedString(bd, fractionDigits, negative)
    }

    /**
     * Plain positional notation with [fractionDigits] digits, never switching to an exponent.
     * With [halfEven] the rounding is BigDecimal's HALF_EVEN, which is what `MessageFormat` uses.
     */
    internal fun toPlainString(v: Double, fractionDigits: Int, halfEven: Boolean): String {
        val negative = v < 0
        var bd = Exact.of(if (negative) -v else v)
        if (bd.scale > fractionDigits) {
            bd = bd.setScale(fractionDigits, halfEven)
        }
        return toFixedString(bd, fractionDigits, negative)
    }

    private fun toFixedString(d: Exact, fractionDigits: Int, negative: Boolean): String {
        val scale = d.scale
        val digits = d.digits
        val numDigits = digits.length
        if (scale == 0 && fractionDigits == 0) {
            return if (negative) "-$digits" else digits
        }
        val b = StringBuilder(numDigits * 2 + 3)
        if (negative) b.append('-')
        if (scale >= numDigits) {
            b.append("0.")
            fillZeroes(b, scale - numDigits)
            b.append(digits)
        } else {
            b.append(digits, 0, numDigits - scale)
            b.append('.')
            b.append(digits, numDigits - scale, numDigits)
        }
        fillZeroes(b, fractionDigits - scale)
        return b.toString()
    }

    private fun toExponentialString(d: Exact, exponent: Int, fractionDigits: Int, negative: Boolean): String {
        val digits = d.digits
        val numDigits = digits.length
        val b = StringBuilder(numDigits + fractionDigits + 7)
        if (negative) b.append('-')
        b.append(digits[0])
        if (numDigits > 1 || fractionDigits >= 1) {
            b.append('.')
            b.append(digits, 1, numDigits)
            fillZeroes(b, fractionDigits - (numDigits - 1))
        }
        b.append('e')
        if (exponent >= 0) b.append('+')
        b.append(exponent)
        return b.toString()
    }

    private fun fillZeroes(b: StringBuilder, count: Int) {
        repeat(count) { b.append('0') }
    }

    /**
     * A non-negative decimal: [digits] times ten to the minus [scale]. [digits] has no leading
     * zeros, and is "0" for zero. This is the part of BigDecimal the formatter needs.
     */
    private class Exact(val digits: String, val scale: Int) {
        val precision: Int get() = digits.length

        /** BigDecimal's MathContext rounding: at most [precision] digits, HALF_UP. */
        fun roundToPrecision(precision: Int): Exact {
            var d = digits
            var s = scale
            while (d.length > precision) {
                val drop = d.length - precision
                d = dropDigits(d, drop)
                s -= drop
            }
            return Exact(d, s)
        }

        /** BigDecimal.setScale with HALF_UP (or HALF_EVEN), for a smaller scale only. */
        fun setScale(newScale: Int, halfEven: Boolean = false): Exact {
            val drop = scale - newScale
            return if (drop <= 0) this else Exact(dropDigits(digits, drop, halfEven), newScale)
        }

        companion object {
            fun of(v: Double): Exact {
                val bits = v.toRawBits()
                val biasedExponent = ((bits ushr 52) and 0x7FF).toInt()
                var significand = bits and 0xFFFFFFFFFFFFFL
                var exponent: Int
                if (biasedExponent == 0) {
                    exponent = -1074
                } else {
                    significand = significand or (1L shl 52)
                    exponent = biasedExponent - 1075
                }
                if (significand == 0L) return Exact("0", 0)
                while (significand and 1L == 0L && exponent < 0) {
                    significand = significand ushr 1
                    exponent++
                }
                val limbs = Limbs(significand)
                return if (exponent >= 0) {
                    var left = exponent
                    while (left > 0) {
                        val chunk = minOf(left, 30)
                        limbs.multiply(1 shl chunk)
                        left -= chunk
                    }
                    Exact(limbs.toDigits(), 0)
                } else {
                    // value = significand / 2^k = significand * 5^k / 10^k
                    var left = -exponent
                    while (left > 0) {
                        val chunk = minOf(left, 13)
                        limbs.multiply(POWERS_OF_FIVE[chunk])
                        left -= chunk
                    }
                    Exact(limbs.toDigits(), -exponent)
                }
            }

            private val POWERS_OF_FIVE = IntArray(14).also {
                it[0] = 1
                for (i in 1 until it.size) it[i] = it[i - 1] * 5
            }

            /** Drops the last [drop] digits with HALF_UP (or HALF_EVEN) rounding. "0" when nothing is left. */
            fun dropDigits(digits: String, drop: Int, halfEven: Boolean = false): String {
                val keep = digits.length - drop
                val roundUp = when {
                    keep < 0 -> false
                    digits[keep] > '5' -> true
                    digits[keep] < '5' -> false
                    !halfEven -> true
                    // A tie: up when anything follows the 5, or when the kept digit is odd.
                    else -> digits.substring(keep + 1).any { it != '0' } || (keep > 0 && (digits[keep - 1] - '0') % 2 == 1)
                }
                var kept = if (keep > 0) digits.substring(0, keep) else "0"
                if (roundUp) kept = increment(kept)
                return kept
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
        }
    }

    /** A little-endian base 10^9 integer that only ever grows by small multiplications. */
    private class Limbs(initial: Long) {
        private var limbs = LongArray(4)
        private var size = 0

        init {
            var v = initial
            while (v != 0L) {
                push(v % BASE)
                v /= BASE
            }
        }

        private fun push(limb: Long) {
            if (size == limbs.size) limbs = limbs.copyOf(limbs.size * 2)
            limbs[size++] = limb
        }

        fun multiply(m: Int) {
            var carry = 0L
            for (i in 0 until size) {
                val product = limbs[i] * m + carry
                limbs[i] = product % BASE
                carry = product / BASE
            }
            while (carry != 0L) {
                push(carry % BASE)
                carry /= BASE
            }
        }

        fun toDigits(): String {
            if (size == 0) return "0"
            val b = StringBuilder(size * 9)
            b.append(limbs[size - 1])
            for (i in size - 2 downTo 0) {
                val s = limbs[i].toString()
                repeat(9 - s.length) { b.append('0') }
                b.append(s)
            }
            return b.toString()
        }

        private companion object {
            const val BASE = 1_000_000_000L
        }
    }
}
