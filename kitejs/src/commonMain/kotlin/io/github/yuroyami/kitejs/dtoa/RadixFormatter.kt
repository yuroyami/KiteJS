/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.dtoa

import io.github.yuroyami.kitejs.KBigInt
import kotlin.math.floor

/**
 * `Number.prototype.toString(radix)` for every radix but 10, which [DoubleFormatter] handles.
 *
 * This is upstream's `DToA.JS_dtobasestr`. It prints the shortest digit string that still reads
 * back as the same double, which is why the fraction loop compares against the gap to the
 * neighbouring doubles rather than just running out of digits. Radix 10 went its own way in the
 * port (D-33); this one keeps upstream's algorithm because there is nothing simpler that gives the
 * same answers.
 */
internal object RadixFormatter {

    private const val EXP_SHIFT1 = 20
    private const val EXP_MASK = 0x7ff00000
    private const val BNDRY_MASK = 0xfffff
    private const val LOG2P = 1
    private const val BIAS = 1023
    private const val P = 53

    private fun baseDigit(digit: Int): Char =
        if (digit >= 10) ('a' - 10 + digit) else ('0' + digit)

    fun toBaseString(base: Int, value: Double): String {
        require(base in 2..36) { "Bad base: $base" }

        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0.0) "Infinity" else "-Infinity"
        if (value == 0.0) return "0"

        var d = value
        val negative = d < 0.0
        if (negative) d = -d

        val dfloor = floor(d)
        val intPart = KBigInt.fromDouble(dfloor)
        val intDigits = (if (negative) intPart.negate() else intPart).toString(base)
        if (d == dfloor) return intDigits

        val buffer = StringBuilder()
        buffer.append(intDigits).append('.')
        val df = d - dfloor

        val dBits = d.toRawBits()
        val word0 = (dBits shr 32).toInt()
        val word1 = dBits.toInt()

        // df = b * 2^e, with e negative because 0 < df < 1.
        val (bStart, e) = d2b(df)
        var b = bStart

        var s2 = -((word0 ushr EXP_SHIFT1) and (EXP_MASK shr EXP_SHIFT1))
        if (s2 == 0) s2 = -1
        s2 += BIAS + P

        // 1/2^s2 is half the distance from d to the next double along.
        var mlo = KBigInt.ONE
        var mhi = mlo
        if (word1 == 0 && (word0 and BNDRY_MASK) == 0 && (word0 and (EXP_MASK and (EXP_MASK shl 1))) != 0) {
            // Right at a binary exponent boundary the gap below d is half the gap above, so the
            // test has to be tightened on that side.
            s2 += LOG2P
            mhi = KBigInt.fromLong((1 shl LOG2P).toLong())
        }

        b = b.shiftLeft(e + s2)
        val s = KBigInt.ONE.shiftLeft(s2)
        val bigBase = KBigInt.fromLong(base.toLong())

        var done = false
        do {
            b = b.multiply(bigBase)
            val (quotient, rest) = b.divideAndRemainder(s)
            b = rest
            var digit = quotient.toInt()
            if (mlo == mhi) {
                mlo = mlo.multiply(bigBase)
                mhi = mlo
            } else {
                mlo = mlo.multiply(bigBase)
                mhi = mhi.multiply(bigBase)
            }

            // Is what has been printed already close enough to round back to d?
            val j = b.compareTo(mlo)
            val delta = s.subtract(mhi)
            val j1 = if (delta.signum() <= 0) 1 else b.compareTo(delta)
            if (j1 == 0 && (word1 and 1) == 0) {
                if (j > 0) digit++
                done = true
            } else if (j < 0 || (j == 0 && (word1 and 1) == 0)) {
                if (j1 > 0) {
                    // Either digit would read back correctly, so take the closer one. There is no
                    // even-digit tiebreak here on purpose: it goes wrong for odd bases.
                    b = b.shiftLeft(1)
                    if (b.compareTo(s) > 0) digit++
                }
                done = true
            } else if (j1 > 0) {
                digit++
                done = true
            }
            buffer.append(baseDigit(digit))
        } while (!done)

        return buffer.toString()
    }

    /** Splits [df] into an odd `b` and an exponent `e` with `df == b * 2^e`. */
    private fun d2b(df: Double): Pair<KBigInt, Int> {
        val bits = df.toRawBits()
        val biasedExponent = ((bits ushr 52) and 0x7FF).toInt()
        val fraction = bits and 0x000FFFFFFFFFFFFFL
        var mantissa: Long
        var e: Int
        if (biasedExponent != 0) {
            mantissa = fraction or (1L shl 52)
            e = biasedExponent - 1075
        } else {
            mantissa = fraction
            e = -1074
        }
        val trailing = mantissa.countTrailingZeroBits()
        mantissa = mantissa ushr trailing
        e += trailing
        return KBigInt.fromLong(mantissa) to e
    }
}
