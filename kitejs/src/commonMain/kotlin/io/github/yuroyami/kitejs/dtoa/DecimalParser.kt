/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.dtoa

import io.github.yuroyami.kitejs.KBigInt

/**
 * Turns a decimal string into the nearest double, rounding halves to even, on every target.
 *
 * The platform's own `String.toDouble` is not good enough. Kotlin/Wasm answers a neighbouring
 * double for about one string in a hundred, mostly but not only in the subnormal range, and a
 * JavaScript engine that reads `5.65865417e-315` as a different number than the JVM does is not
 * the same engine. This is the last conversion the port asked a platform for.
 *
 * Two paths. Almost every literal a script contains takes the first one.
 */
internal object DecimalParser {

    /** The largest power of ten a double holds exactly. Beyond this a multiply would round twice. */
    private const val MAX_EXACT_POWER = 22

    /** 10^0 through 10^22, each exact. */
    private val POW10 = DoubleArray(MAX_EXACT_POWER + 1).also {
        var v = 1.0
        for (i in it.indices) {
            it[i] = v
            v *= 10.0
        }
    }

    /** How many significant digits still fit in a Long, and so in a double, without loss. */
    private const val MAX_EXACT_DIGITS = 15

    private val TEN = KBigInt.fromLong(10L)

    /** Where the significand of a normal double starts: it always has 53 bits. */
    private const val SIGNIFICAND_BITS = 53

    /** The smallest binary exponent a double can hold, which is where subnormals live. */
    private const val MIN_EXPONENT = -1074

    /**
     * Parses [text], which the caller has already checked contains only digits, a dot, an
     * exponent marker and signs. Returns null when it is not a decimal number after all.
     */
    fun parse(text: String): Double? {
        val n = text.length
        if (n == 0) return null
        var i = 0
        var negative = false
        when (text[0]) {
            '+' -> i = 1
            '-' -> { negative = true; i = 1 }
        }

        // The significant digits, with leading zeros dropped, and how many of them sat after
        // the dot. The value is those digits read as an integer, times ten to the power of the rest.
        val digits = StringBuilder()
        var digitsAfterDot = 0
        var sawDigit = false
        var sawDot = false
        while (i < n) {
            val c = text[i]
            when {
                c in '0'..'9' -> {
                    sawDigit = true
                    if (digits.isEmpty() && c == '0') {
                        // A leading zero adds nothing, but one after the dot still shifts the
                        // exponent, so it has to be counted.
                        if (sawDot) digitsAfterDot++
                    } else {
                        digits.append(c)
                        if (sawDot) digitsAfterDot++
                    }
                    i++
                }
                c == '.' && !sawDot -> { sawDot = true; i++ }
                else -> break
            }
        }
        if (!sawDigit) return null

        var exponent = 0L
        if (i < n && (text[i] == 'e' || text[i] == 'E')) {
            i++
            var expNegative = false
            if (i < n && (text[i] == '+' || text[i] == '-')) {
                expNegative = text[i] == '-'
                i++
            }
            if (i >= n || text[i] !in '0'..'9') return null
            while (i < n && text[i] in '0'..'9') {
                // Anything past a million is far outside the range of a double either way.
                if (exponent < 10_000_000L) exponent = exponent * 10 + (text[i] - '0')
                i++
            }
            if (expNegative) exponent = -exponent
        }
        if (i != n) return null

        if (digits.isEmpty()) return if (negative) -0.0 else 0.0

        // Trailing zeros are exponent, not precision, and dropping them keeps the big integer
        // small for something like 1000000000000000000000000000000.
        var end = digits.length
        var droppedZeros = 0
        while (end > 1 && digits[end - 1] == '0') {
            end--
            droppedZeros++
        }
        val significant = digits.substring(0, end)
        val exp10 = exponent - digitsAfterDot + droppedZeros

        // Where the leading digit sits. Outside these bounds the answer cannot be anything but
        // infinity or zero, and saying so here keeps a silly exponent from building a silly
        // big integer.
        val leadingExponent = exp10 + significant.length - 1
        if (leadingExponent > 309) return if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        if (leadingExponent < -400) return if (negative) -0.0 else 0.0

        val fast = fastPath(significant, exp10.toInt())
        val value = fast ?: exactPath(significant, exp10.toInt())
        return if (negative) -value else value
    }

    /**
     * One rounding, so one correct answer. It applies when the digits land in a double exactly
     * and the power of ten does too, which is true of nearly every number written by hand.
     */
    private fun fastPath(significant: String, exp10: Int): Double? {
        if (significant.length > MAX_EXACT_DIGITS) return null
        if (exp10 > MAX_EXACT_POWER || exp10 < -MAX_EXACT_POWER) return null
        val mantissa = significant.toLong().toDouble()
        return if (exp10 >= 0) mantissa * POW10[exp10] else mantissa / POW10[-exp10]
    }

    /**
     * The value written as one exact fraction, then divided down to the 53 bits a double holds,
     * rounding what is left over to nearest and ties to even. Nothing here approximates.
     */
    private fun exactPath(significant: String, exp10: Int): Double {
        var numerator = KBigInt.parse(significant, 10)
        var denominator = KBigInt.ONE
        if (exp10 >= 0) numerator = numerator.multiply(TEN.pow(exp10)) else denominator = TEN.pow(-exp10)

        // Scale so the quotient is exactly 53 bits wide, or narrower when the exponent has
        // already hit the floor and the answer is subnormal.
        var exponent2 = numerator.bitLength() - denominator.bitLength() - SIGNIFICAND_BITS
        if (exponent2 < MIN_EXPONENT) exponent2 = MIN_EXPONENT

        var scaledNum = numerator
        var scaledDen = denominator
        if (exponent2 >= 0) scaledDen = scaledDen.shiftLeft(exponent2) else scaledNum = scaledNum.shiftLeft(-exponent2)

        var (quotient, remainder) = scaledNum.divideAndRemainder(scaledDen)

        // The bit-length estimate can be one out in either direction. Correcting it moves the
        // exponent by one and redoes the division; it never needs more than one step each way.
        while (quotient.bitLength() > SIGNIFICAND_BITS) {
            exponent2++
            scaledDen = denominator
            scaledNum = numerator
            if (exponent2 >= 0) scaledDen = scaledDen.shiftLeft(exponent2) else scaledNum = scaledNum.shiftLeft(-exponent2)
            val pair = scaledNum.divideAndRemainder(scaledDen)
            quotient = pair.first
            remainder = pair.second
        }
        while (quotient.bitLength() < SIGNIFICAND_BITS && exponent2 > MIN_EXPONENT) {
            exponent2--
            scaledDen = denominator
            scaledNum = numerator
            if (exponent2 >= 0) scaledDen = scaledDen.shiftLeft(exponent2) else scaledNum = scaledNum.shiftLeft(-exponent2)
            val pair = scaledNum.divideAndRemainder(scaledDen)
            quotient = pair.first
            remainder = pair.second
        }

        var mantissa = quotient.toLong()
        // Twice the remainder against the divisor says which side of halfway the exact value is.
        val twiceRemainder = remainder.shiftLeft(1)
        val comparison = twiceRemainder.compareTo(scaledDen)
        if (comparison > 0 || (comparison == 0 && (mantissa and 1L) == 1L)) {
            mantissa++
            // Rounding up can carry into a 54th bit, which costs one bit of exponent.
            if (mantissa == (1L shl SIGNIFICAND_BITS)) {
                mantissa = mantissa shr 1
                exponent2++
            }
        }
        if (mantissa == 0L) return 0.0
        return ldexp(mantissa, exponent2)
    }

    /** [mantissa] times two to the [exponent], built from the bits so nothing rounds twice. */
    private fun ldexp(mantissa: Long, exponent: Int): Double {
        // A normal double keeps its leading bit implicit; a subnormal one does not have it.
        if (exponent == MIN_EXPONENT && mantissa < (1L shl (SIGNIFICAND_BITS - 1))) {
            return Double.fromBits(mantissa)
        }
        val biased = exponent + 1075L
        if (biased >= 2047L) return Double.POSITIVE_INFINITY
        if (biased <= 0L) return 0.0
        val fraction = mantissa and ((1L shl (SIGNIFICAND_BITS - 1)) - 1)
        return Double.fromBits((biased shl 52) or fraction)
    }
}
