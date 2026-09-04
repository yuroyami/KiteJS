/*
 * Copyright 2018-2020 Raffaello Giulietti
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.github.yuroyami.kitejs.dtoa

/**
 * Turns a double into a set of digits and an exponent, the same code Jackson and OpenJDK use.
 *
 * References:
 * 1. Giulietti, "The Schubfach way to render doubles"
 * 2. IEEE Computer Society, "IEEE Standard for Floating-Point Arithmetic"
 * 3. Bouvier and Zimmermann, "Division-Free Binary-to-Decimal Conversion"
 *
 * Divisions are avoided throughout, which helps architectures where they are slow. Section 10 of
 * reference 1 covers that.
 */
object DoubleFormatter {

    /** The precision in bits. */
    internal const val P = 53

    /** Exponent width in bits. */
    private const val W = (64 - 1) - (P - 1)

    /** Minimum value of the exponent: -(2^(W-1)) - P + 3. */
    internal const val Q_MIN = (-1 shl (W - 1)) - P + 3

    /** Maximum value of the exponent: 2^(W-1) - P. */
    internal const val Q_MAX = (1 shl (W - 1)) - P

    /** 10^(E_MIN - 1) <= MIN_VALUE < 10^E_MIN */
    internal const val E_MIN = -323

    /** 10^(E_MAX - 1) <= MAX_VALUE < 10^E_MAX */
    internal const val E_MAX = 309

    /** Threshold that detects tiny values, as in section 8.1.1 of reference 1. */
    internal const val C_TINY = 3L

    /** H is as in section 8 of reference 1. */
    internal const val H = 17

    /** Minimum significand of a normal value: 2^(P-1). */
    private const val C_MIN = 1L shl (P - 1)

    /** Mask that extracts the biased exponent. */
    private const val BQ_MASK = (1 shl W) - 1

    /** Mask that extracts the fraction bits. */
    private const val T_MASK = (1L shl (P - 1)) - 1

    /** Used in [rop]. */
    private const val MASK_63 = (1L shl 63) - 1

    /**
     * Converts a double to a String the way ECMAScript's `Number::toString` defines it. Handles
     * every double, including the non-finite ones.
     */
    fun toString(v: Double): String {
        val bits = v.toRawBits()
        val t = bits and T_MASK
        val bq = (bits ushr (P - 1)).toInt() and BQ_MASK
        if (bq < BQ_MASK) {
            if (bq == 0 && t == 0L) {
                return "0"
            }
            return toDecimalImpl(bits, t, bq).toString()
        }
        if (t != 0L) {
            return "NaN"
        }
        return if (bits > 0) "Infinity" else "-Infinity"
    }

    /**
     * Converts a double to a [Decimal] that can then be rendered in several ways. Unlike
     * [toString] this always returns a Decimal, so it is only defined for finite numbers.
     */
    fun toDecimal(v: Double): Decimal {
        val bits = v.toRawBits()
        val t = bits and T_MASK
        val bq = (bits ushr (P - 1)).toInt() and BQ_MASK
        return toDecimalImpl(bits, t, bq)
    }

    private fun toDecimalImpl(bits: Long, t: Long, bq: Int): Decimal {
        /*
         * For the full details see references 2 and 1.
         *
         * For a finite v != 0, find integers c and q with
         *     |v| = c 2^q   and   Q_MIN <= q <= Q_MAX
         * and either 2^(P-1) <= c < 2^P (normal), or 0 < c < 2^(P-1) and q = Q_MIN (subnormal).
         */
        val negative = bits < 0
        if (bq != 0) {
            // Normal value. Here mq = -q.
            val mq = -Q_MIN + 1 - bq
            val c = C_MIN or t
            // The fast path from section 8.2 of reference 1.
            if (mq in 1 until P) {
                val f = c shr mq
                if (f shl mq == c) {
                    return Decimal(f, 0, negative)
                }
            }
            return toDecimalFull(-mq, c, 0, negative)
        }
        if (t != 0L) {
            // Subnormal value.
            return if (t < C_TINY) {
                toDecimalFull(Q_MIN, 10 * t, -1, negative)
            } else {
                toDecimalFull(Q_MIN, t, 0, negative)
            }
        }
        return Decimal(0, 1, false)
    }

    private fun toDecimalFull(q: Int, c: Long, dk: Int, negative: Boolean): Decimal {
        /*
         * The skeleton is figure 4 of reference 1, the efficient computations are figure 7.
         * Names map to reference 1 like this: cb is c-bar, cbr is c-bar-r, cbl is c-bar-l,
         * vb is v-bar, vbl is v-bar-l, vbr is v-bar-r, rop is r-o-prime.
         */
        val out = c.toInt() and 0x1
        val cb = c shl 2
        val cbr = cb + 2
        val cbl: Long
        val k: Int
        if (c != C_MIN || q == Q_MIN) {
            // Regular spacing.
            cbl = cb - 2
            k = MathUtils.flog10pow2(q)
        } else {
            // Irregular spacing.
            cbl = cb - 1
            k = MathUtils.flog10threeQuartersPow2(q)
        }
        val h = q + MathUtils.flog2pow10(-k) + 2

        // g1 and g0 are as in section 9.9.3 of reference 1, so g = g1 2^63 + g0.
        val g1 = MathUtils.g1(k)
        val g0 = MathUtils.g0(k)

        val vb = rop(g1, g0, cb shl h)
        val vbl = rop(g1, g0, cbl shl h)
        val vbr = rop(g1, g0, cbr shl h)

        val s = vb shr 2
        if (s >= 100) {
            /*
             * For n = 17, m = 1 the table in section 10 of reference 1 gives
             *     s' = floor(s / 10) = floor(s 115_292_150_460_684_698 / 2^60)
             * sp10 = 10 s', tp10 = 10 t'; upin means u' = sp10 10^k is in Rv and wpin means
             * w' = tp10 10^k is in Rv. See section 9.4 of reference 1.
             */
            val sp10 = 10 * MathUtils.multiplyHigh(s, 115_292_150_460_684_698L shl 4)
            val tp10 = sp10 + 10
            val upin = vbl + out <= sp10 shl 2
            val wpin = (tp10 shl 2) + out <= vbr
            if (upin != wpin) {
                return Decimal(if (upin) sp10 else tp10, k, negative)
            }
        }

        /*
         * Either 10 <= s < 100, or s >= 100 with u' and w' both outside Rv.
         * uin means u = s 10^k is in Rv, win means w = t 10^k is in Rv.
         */
        val t = s + 1
        val uin = vbl + out <= s shl 2
        val win = (t shl 2) + out <= vbr
        if (uin != win) {
            // Exactly one of u or w lies in Rv.
            return Decimal(if (uin) s else t, k + dk, negative)
        }
        // Both lie in Rv, so pick the one closest to v. See section 9.4 of reference 1.
        val cmp = vb - ((s + t) shl 1)
        return Decimal(
            if (cmp < 0 || (cmp == 0L && (s and 0x1L) == 0L)) s else t,
            k + dk,
            negative,
        )
    }

    /**
     * Computes rop(cp g 2^-127), where g = g1 2^63 + g0. See section 9.10 and figure 5 of
     * reference 1.
     */
    private fun rop(g1: Long, g0: Long, cp: Long): Long {
        val x1 = MathUtils.multiplyHigh(g0, cp)
        val y0 = g1 * cp
        val y1 = MathUtils.multiplyHigh(g1, cp)
        val z = (y0 ushr 1) + x1
        val vbp = y1 + (z ushr 63)
        return vbp or (((z and MASK_63) + MASK_63) ushr 63)
    }
}
