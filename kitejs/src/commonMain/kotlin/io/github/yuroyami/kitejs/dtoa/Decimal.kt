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
 * Formats a decimal number into a string and keeps the result in a buffer. It is tuned for
 * formatting a double, so it only works for a number of at most 17 significant digits.
 *
 * Based on code by Giulietti.
 */
public class Decimal internal constructor(
    private val digits: Long,
    private val exponent: Int,
    private val negative: Boolean,
) {

    private var length = 0
    private val buf = CharArray(MAX_CHARS)

    internal enum class Mode {
        DEFAULT,
        TO_EXPONENTIAL,
    }

    /**
     * Formats the number by the rules of ECMAScript chapter 6, as defined for the
     * `Number::toString` operation at radix 10.
     */
    override fun toString(): String = toString(Mode.DEFAULT)

    internal fun toString(mode: Mode): String {
        length = 0

        // Find len such that 10^(len-1) <= f < 10^len. Section 10 of the Schubfach paper has the
        // details left out here.
        var len = MathUtils.flog10pow2(64 - digits.countLeadingZeroBits())
        if (digits >= MathUtils.pow10(len)) {
            len += 1
        }

        /*
         * Let fp and ep be the original f and e. Transform f and e so that
         *     10^(H-1) <= f < 10^H   and   fp 10^ep = f 10^(e-H) = 0.f 10^e
         */
        val f = digits * MathUtils.pow10(DoubleFormatter.H - len)
        val e = exponent + len

        /*
         * The digit extraction below works on ints, which needs arguments limited to 8 digits, so
         * the H = 17 digits of f split into the most significant digit h, the next 8 digits m and
         * the last 8 digits l.
         *
         * The adjustment above put the significant bits at the front with trailing zeroes, which
         * is what makes the three-way split work. Giulietti's original was adapted for JavaScript,
         * which stays in fixed format over a wider range before switching to exponential.
         */
        val hm = MathUtils.multiplyHigh(f, 193_428_131_138_340_668L) ushr 20
        val l = (f - 100_000_000L * hm).toInt()
        val h = ((hm * 1_441_151_881L) ushr 57).toInt()
        val m = (hm - 100_000_000L * h).toInt()

        if (negative) {
            append('-')
        }

        if (mode == Mode.DEFAULT) {
            if (e in 1..8) {
                return toFixed(h, m, l, e)
            }
            if (e in 9..16) {
                return toFixedBigger(h, m, l, e)
            }
            if (e in 17..21) {
                return toFixedBiggest(h, m, l, e)
            }
            if (e in -5..0) {
                return toFixedSmall(h, m, l, e)
            }
        }
        return toExponential(h, m, l, e)
    }

    /** 0 < e <= 8: plain format with no leading zeroes. */
    private fun toFixed(h: Int, m: Int, l: Int, e: Int): String {
        // Left-to-right digit extraction: algorithm 1 of the Bouvier and Zimmermann paper, with
        // b = 10, k = 8, n = 28.
        appendDigit(h)
        var y = y(m)
        var t: Int
        var i = 1
        while (i < e) {
            t = 10 * y
            appendDigit(t ushr 28)
            y = t and MASK_28
            ++i
        }
        append('.')
        while (i <= 8) {
            t = 10 * y
            appendDigit(t ushr 28)
            y = t and MASK_28
            ++i
        }
        lowDigits(l)
        return makeString()
    }

    /** 8 < e <= 16: plain format, with the first 9 characters before the decimal point. */
    private fun toFixedBigger(h: Int, m: Int, l: Int, e: Int): String {
        appendDigit(h)
        append8Digits(m)
        var y = y(l)
        var t: Int
        var i = 9
        while (i < e) {
            t = 10 * y
            appendDigit(t ushr 28)
            y = t and MASK_28
            ++i
        }
        append('.')
        while (i <= 16) {
            t = 10 * y
            appendDigit(t ushr 28)
            y = t and MASK_28
            ++i
        }
        trimZeroes()
        return makeString()
    }

    /** 16 < e: plain format with trailing zeroes. */
    private fun toFixedBiggest(h: Int, m: Int, l: Int, e: Int): String {
        appendDigit(h)
        append8Digits(m)
        append8Digits(l)
        for (i in 17 until e) {
            append('0')
        }
        return makeString()
    }

    /** -6 < e <= 0: plain format with leading zeroes. */
    private fun toFixedSmall(h: Int, m: Int, l: Int, e: Int): String {
        var exp = e
        appendDigit(0)
        append('.')
        while (exp < 0) {
            appendDigit(0)
            ++exp
        }
        appendDigit(h)
        append8Digits(m)
        lowDigits(l)
        return makeString()
    }

    /** Computerized scientific notation. */
    private fun toExponential(h: Int, m: Int, l: Int, e: Int): String {
        appendDigit(h)
        append('.')
        append8Digits(m)
        lowDigits(l)
        exponent(e - 1)
        return makeString()
    }

    private fun y(a: Int): Int {
        /*
         * Algorithm 1 of the Bouvier and Zimmermann paper needs floor((a + 1) 2^n / b^k) - 1 with
         * a < 10^8, b = 10, k = 8, n = 28. Since (a + 1) 2^n <= 10^8 2^28 < 10^17, the table in
         * section 10 of the Schubfach paper for n = 17, m = 8 gives this.
         */
        return (MathUtils.multiplyHigh((a + 1).toLong() shl 28, 193_428_131_138_340_668L) ushr 20)
            .toInt() - 1
    }

    private fun lowDigits(l: Int) {
        if (l != 0) {
            append8Digits(l)
        }
        trimZeroes()
    }

    private fun append8Digits(m: Int) {
        var y = y(m)
        for (i in 0 until 8) {
            val t = 10 * y
            appendDigit(t ushr 28)
            y = t and MASK_28
        }
    }

    private fun exponent(e: Int) {
        var exp = e
        append('e')
        if (exp < 0) {
            append('-')
            exp = -exp
        } else {
            append('+')
        }
        if (exp < 10) {
            appendDigit(exp)
            return
        }
        var d: Int
        if (exp >= 100) {
            // For n = 3, m = 2 the table in section 10 gives floor(e / 100) = floor(1_311 e / 2^17)
            d = (exp * 1_311) ushr 17
            appendDigit(d)
            exp -= 100 * d
        }
        // For n = 2, m = 1 the same table gives floor(e / 10) = floor(103 e / 2^10)
        d = (exp * 103) ushr 10
        appendDigit(d)
        appendDigit(exp - 10 * d)
    }

    private fun trimZeroes() {
        while (length > 0 && buf[length - 1] == '0') {
            length--
        }
        if (length > 0 && buf[length - 1] == '.') {
            length--
        }
    }

    private fun append(ch: Char) {
        buf[length++] = ch
    }

    private fun appendDigit(d: Int) {
        buf[length++] = ('0'.code + d).toChar()
    }

    private fun makeString(): String = buf.concatToString(0, length)

    public companion object {
        // Used for left-to-right digit extraction.
        private const val MASK_28 = (1 shl 28) - 1

        /*
         * Room for the longest form, always 17 significant digits:
         *     -ddddd.dddddddddddd          H + 2 characters
         *     -0.000000ddddddddddddddddd   H + 9 characters (JS stays fixed down to exponent -6)
         *     -d.ddddddddddddddddE-eee     H + 7 characters
         *     -ddddddddddddddddd0000       H + 5 characters (JS stays fixed up to exponent 21)
         * That is 26 characters at most; 32 is used because powers of two are convenient.
         */
        public const val MAX_CHARS: Int = 32
    }
}
