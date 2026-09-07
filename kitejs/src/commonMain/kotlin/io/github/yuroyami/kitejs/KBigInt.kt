/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.math.absoluteValue

/**
 * An arbitrary-precision integer: what `BigInt` values are made of.
 *
 * Upstream uses `java.math.BigInteger`, which common Kotlin has no equal of, so this is the port's
 * own (D-5). Sign and magnitude, with the magnitude a little-endian [IntArray] of base 2^32 limbs
 * and no trailing zero limbs, so a value has exactly one representation and equality is a plain
 * array comparison.
 *
 * The bitwise operations follow the JavaScript rule: two's complement over an infinitely long bit
 * string, so `-1n and 3n` is `3n` and `not(5n)` is `-6n`. `shiftRight` floors, matching `>>`.
 *
 * It deliberately does NOT extend [Number], even though upstream's `BigInteger` does: on
 * Kotlin/JS `Number` is the JS primitive number type, so a class extending it fails `is Number`
 * and throws on `as Number`. The runtime tells a bigint apart with `is KBigInt` instead (D-54).
 */
class KBigInt private constructor(
    private val sign: Int,
    private val mag: IntArray,
) : Comparable<KBigInt> {

    /** Parses [digits] in [radix]. This is the shape the lexer builds a `123n` literal with. */
    constructor(digits: String, radix: Int) : this(parse(digits, radix))

    private constructor(other: KBigInt) : this(other.sign, other.mag)

    // ---- Sign and size -------------------------------------------------------------------------

    /** -1, 0 or 1. */
    fun signum(): Int = sign

    val isZero: Boolean get() = sign == 0

    fun negate(): KBigInt = if (sign == 0) this else KBigInt(-sign, mag)

    fun abs(): KBigInt = if (sign < 0) negate() else this

    /**
     * The bits in the shortest two's complement spelling, not counting the sign bit, the same
     * count `java.math.BigInteger.bitLength` gives.
     */
    fun bitLength(): Int {
        if (sign == 0) return 0
        val len = magBitLength(mag)
        // A negative power of two needs one bit fewer than its magnitude does.
        if (sign < 0 && isPowerOfTwo(mag)) return len - 1
        return len
    }

    // ---- Arithmetic ----------------------------------------------------------------------------

    fun add(other: KBigInt): KBigInt {
        if (sign == 0) return other
        if (other.sign == 0) return this
        if (sign == other.sign) return make(sign, magAdd(mag, other.mag))
        val c = magCompare(mag, other.mag)
        if (c == 0) return ZERO
        return if (c > 0) make(sign, magSub(mag, other.mag)) else make(other.sign, magSub(other.mag, mag))
    }

    fun subtract(other: KBigInt): KBigInt = add(other.negate())

    fun multiply(other: KBigInt): KBigInt {
        if (sign == 0 || other.sign == 0) return ZERO
        return make(sign * other.sign, magMultiply(mag, other.mag))
    }

    /** Truncates toward zero, which is what `/` does on BigInt. */
    fun divide(other: KBigInt): KBigInt {
        if (other.sign == 0) throw ArithmeticException("BigInt division by zero")
        if (sign == 0) return ZERO
        if (magCompare(mag, other.mag) < 0) return ZERO
        return make(sign * other.sign, magDivRem(mag, other.mag).first)
    }

    /** Takes the sign of the dividend, which is what `%` does on BigInt. */
    fun remainder(other: KBigInt): KBigInt {
        if (other.sign == 0) throw ArithmeticException("BigInt division by zero")
        if (sign == 0) return ZERO
        if (magCompare(mag, other.mag) < 0) return this
        return make(sign, magDivRem(mag, other.mag).second)
    }

    fun divideAndRemainder(other: KBigInt): Pair<KBigInt, KBigInt> {
        if (other.sign == 0) throw ArithmeticException("BigInt division by zero")
        if (sign == 0) return ZERO to ZERO
        if (magCompare(mag, other.mag) < 0) return ZERO to this
        val (q, r) = magDivRem(mag, other.mag)
        return make(sign * other.sign, q) to make(sign, r)
    }

    /** The remainder that is never negative, so `(-7).mod(3)` is 2. */
    fun mod(modulus: KBigInt): KBigInt {
        if (modulus.sign <= 0) throw ArithmeticException("BigInt modulus must be positive")
        val r = remainder(modulus)
        return if (r.sign < 0) r.add(modulus) else r
    }

    fun pow(exponent: Int): KBigInt {
        if (exponent < 0) throw ArithmeticException("BigInt negative exponent")
        if (exponent == 0) return ONE
        if (sign == 0) return ZERO
        var result = ONE
        var base = this
        var e = exponent
        while (e > 0) {
            if ((e and 1) == 1) result = result.multiply(base)
            e = e ushr 1
            if (e > 0) base = base.multiply(base)
        }
        return result
    }

    // ---- Bit operations ------------------------------------------------------------------------

    /** `-x - 1`, which is what `~` means on an infinitely long two's complement number. */
    fun not(): KBigInt = negate().subtract(ONE)

    fun and(other: KBigInt): KBigInt = bitwise(other) { a, b -> a and b }

    fun or(other: KBigInt): KBigInt = bitwise(other) { a, b -> a or b }

    fun xor(other: KBigInt): KBigInt = bitwise(other) { a, b -> a xor b }

    fun shiftLeft(n: Int): KBigInt {
        if (n < 0) return shiftRight(-n)
        if (sign == 0 || n == 0) return this
        return make(sign, magShiftLeft(mag, n))
    }

    /** Arithmetic, so it floors: `-5 shr 1` is -3, not -2. */
    fun shiftRight(n: Int): KBigInt {
        if (n < 0) return shiftLeft(-n)
        if (sign == 0 || n == 0) return this
        val shifted = magShiftRight(mag, n)
        if (sign > 0) return make(1, shifted)
        // Flooring a negative number means rounding away from zero when anything was dropped.
        return if (magAnyBitsBelow(mag, n)) make(-1, magAdd(shifted, ONE_MAG)) else make(-1, shifted)
    }

    fun testBit(n: Int): Boolean {
        if (n < 0) throw ArithmeticException("negative bit index")
        val limb = n ushr 5
        val bit = n and 31
        return ((twosLimb(limb) ushr bit) and 1) == 1
    }

    /** `BigInt.asUintN`: the low [bits] bits, read as a number that is never negative. */
    fun asUintN(bits: Int): KBigInt {
        if (bits < 0) throw ArithmeticException("negative bit count")
        if (bits == 0) return ZERO
        if (sign >= 0 && magBitLength(mag) <= bits) return this
        return mod(ONE.shiftLeft(bits))
    }

    /** `BigInt.asIntN`: the low [bits] bits, read as a two's complement signed number. */
    fun asIntN(bits: Int): KBigInt {
        if (bits < 0) throw ArithmeticException("negative bit count")
        if (bits == 0) return ZERO
        val unsigned = asUintN(bits)
        return if (unsigned.testBit(bits - 1)) unsigned.subtract(ONE.shiftLeft(bits)) else unsigned
    }

    // ---- Comparison ----------------------------------------------------------------------------

    override fun compareTo(other: KBigInt): Int {
        if (sign != other.sign) return if (sign > other.sign) 1 else -1
        if (sign == 0) return 0
        val c = magCompare(mag, other.mag)
        return if (sign > 0) c else -c
    }

    /** Compares against a finite [d] without losing anything to rounding. */
    fun compareToDouble(d: Double): Int {
        if (d.isNaN()) return 1
        if (d == Double.POSITIVE_INFINITY) return -1
        if (d == Double.NEGATIVE_INFINITY) return 1
        val floor = kotlin.math.floor(d)
        val c = compareTo(fromDouble(floor))
        if (c != 0) return c
        // Equal against the floor, so anything after the point makes the double the larger one.
        return if (d > floor) -1 else 0
    }

    override fun equals(other: Any?): Boolean =
        other is KBigInt && sign == other.sign && mag.contentEquals(other.mag)

    override fun hashCode(): Int {
        var h = sign
        for (limb in mag) h = h * 31 + limb
        return h
    }

    // ---- Conversion ----------------------------------------------------------------------------

    override fun toString(): String = toString(10)

    fun toString(radix: Int): String {
        if (radix < 2 || radix > 36) throw ArithmeticException("radix $radix out of range")
        if (sign == 0) return "0"
        val digits = magToString(mag, radix)
        return if (sign < 0) "-$digits" else digits
    }

    /** The low 32 bits, wrapping the way a cast does. */
    fun toInt(): Int = toLong().toInt()

    /** The low 64 bits, wrapping the way a cast does. */
    fun toLong(): Long {
        val low = twosLimb(0).toLong() and 0xFFFFFFFFL
        val high = twosLimb(1).toLong() and 0xFFFFFFFFL
        return (high shl 32) or low
    }

    /** The value as an [Int], or an error when it does not fit. */
    fun intValueExact(): Int {
        if (bitLength() > 31) throw ArithmeticException("BigInt out of int range")
        return toInt()
    }

    /** The value as a [Long], or an error when it does not fit. */
    fun longValueExact(): Long {
        if (bitLength() > 63) throw ArithmeticException("BigInt out of long range")
        return toLong()
    }

    /** The nearest double, rounded to even, and infinite once past the double range. */
    fun toDouble(): Double {
        if (sign == 0) return 0.0
        val bits = magBitLength(mag)
        if (bits > 1024) return if (sign > 0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY

        var exponent = bits - 1
        val drop = bits - 54
        // 54 bits: the implicit one, the 52 stored, and one more to round on.
        val top = magTopBits(mag, 54)
        val sticky = drop > 0 && magAnyBitsBelow(mag, drop)

        val roundBit = (top and 1L) == 1L
        var mantissa = top ushr 1
        if (roundBit && (sticky || (mantissa and 1L) == 1L)) {
            mantissa++
            if (mantissa == (1L shl 53)) {
                mantissa = mantissa ushr 1
                exponent++
            }
        }
        if (exponent > 1023) return if (sign > 0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY

        val signBit = if (sign < 0) 1L shl 63 else 0L
        val raw = signBit or ((exponent + 1023).toLong() shl 52) or (mantissa and 0x000FFFFFFFFFFFFFL)
        return Double.fromBits(raw)
    }

    fun toFloat(): Float = toDouble().toFloat()
    fun toShort(): Short = toInt().toShort()
    fun toByte(): Byte = toInt().toByte()

    // ---- Two's complement view -----------------------------------------------------------------

    /** Limb [i] of the infinitely long two's complement spelling. */
    private fun twosLimb(i: Int): Int {
        if (sign >= 0) return if (i < mag.size) mag[i] else 0
        // A negative number's bits are those of |x| - 1, inverted, with ones going on forever.
        if (negatedCache == null) negatedCache = magSub(mag, ONE_MAG)
        val m = negatedCache!!
        return if (i < m.size) m[i].inv() else -1
    }

    private var negatedCache: IntArray? = null

    private inline fun bitwise(other: KBigInt, op: (Int, Int) -> Int): KBigInt {
        val n = maxOf(mag.size, other.mag.size) + 1
        val out = IntArray(n)
        for (i in 0 until n) out[i] = op(twosLimb(i), other.twosLimb(i))
        // The extra limb is all zeroes or all ones, which is exactly the sign of the answer.
        if (out[n - 1] >= 0) return make(1, normalize(out))
        // Negative: undo the two's complement to recover the magnitude.
        for (i in 0 until n) out[i] = out[i].inv()
        return make(-1, magAdd(normalize(out), ONE_MAG))
    }

    companion object {
        val ZERO: KBigInt = KBigInt(0, IntArray(0))
        val ONE: KBigInt = KBigInt(1, intArrayOf(1))
        private val ONE_MAG = intArrayOf(1)

        /** Karatsuba pays off above this many limbs; below it schoolbook wins. */
        private const val KARATSUBA_LIMBS = 80

        fun fromLong(value: Long): KBigInt {
            if (value == 0L) return ZERO
            val sign = if (value < 0) -1 else 1
            // Negating Long.MIN_VALUE overflows, so the magnitude is taken from the raw bits.
            val m = if (value == Long.MIN_VALUE) value.toULong() else value.absoluteValue.toULong()
            val low = (m and 0xFFFFFFFFuL).toInt()
            val high = (m shr 32).toInt()
            return make(sign, if (high == 0) intArrayOf(low) else intArrayOf(low, high))
        }

        fun fromInt(value: Int): KBigInt = fromLong(value.toLong())

        /** Truncates toward zero. [value] must be finite. */
        fun fromDouble(value: Double): KBigInt {
            if (value.isNaN() || value.isInfinite()) throw ArithmeticException("not a finite number")
            val truncated = if (value < 0) kotlin.math.ceil(value) else kotlin.math.floor(value)
            if (truncated == 0.0) return ZERO
            val raw = truncated.toRawBits()
            val sign = if (raw < 0) -1 else 1
            val exponent = ((raw ushr 52) and 0x7FF).toInt()
            val fraction = raw and 0x000FFFFFFFFFFFFFL
            // |value| >= 1 here, so the number is normal and carries its implicit leading bit.
            val mantissa = fraction or (1L shl 52)
            val shift = exponent - 1075
            val m = fromLong(mantissa)
            val magnitude = if (shift >= 0) magShiftLeft(m.mag, shift) else magShiftRight(m.mag, -shift)
            return make(sign, magnitude)
        }

        /** Reads [text] in [radix], with an optional leading sign. */
        fun parse(text: String, radix: Int = 10): KBigInt {
            if (radix < 2 || radix > 36) throw ArithmeticException("radix $radix out of range")
            if (text.isEmpty()) throw ArithmeticException("empty BigInt literal")

            var i = 0
            var sign = 1
            if (text[0] == '+' || text[0] == '-') {
                if (text[0] == '-') sign = -1
                i = 1
            }
            if (i == text.length) throw ArithmeticException("no digits in BigInt literal")

            // Digits go in a chunk at a time: as many as fit in a limb without overflowing.
            val perChunk = digitsPerLimb(radix)
            val chunkScale = powInt(radix, perChunk)
            var mag = IntArray(0)
            while (i < text.length) {
                val take = minOf(perChunk, text.length - i)
                var chunk = 0L
                for (k in 0 until take) {
                    val d = digitOf(text[i + k], radix)
                    chunk = chunk * radix + d
                }
                i += take
                val scale = if (take == perChunk) chunkScale else powInt(radix, take)
                mag = magMulAddSmall(mag, scale, chunk.toInt())
            }
            mag = normalize(mag)
            return if (mag.isEmpty()) ZERO else make(sign, mag)
        }

        private fun digitOf(c: Char, radix: Int): Int {
            val d = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'z' -> c - 'a' + 10
                in 'A'..'Z' -> c - 'A' + 10
                else -> -1
            }
            if (d < 0 || d >= radix) throw ArithmeticException("bad digit '$c' for radix $radix")
            return d
        }

        private fun make(sign: Int, magnitude: IntArray): KBigInt {
            val m = normalize(magnitude)
            return if (m.isEmpty()) ZERO else KBigInt(sign, m)
        }

        // ---- Magnitude helpers, all little-endian and normalized --------------------------------

        private fun normalize(a: IntArray): IntArray {
            var n = a.size
            while (n > 0 && a[n - 1] == 0) n--
            return if (n == a.size) a else a.copyOf(n)
        }

        private fun magBitLength(a: IntArray): Int =
            if (a.isEmpty()) 0 else a.size * 32 - a[a.size - 1].countLeadingZeroBits()

        private fun isPowerOfTwo(a: IntArray): Boolean {
            val top = a[a.size - 1]
            if (top and (top - 1) != 0) return false
            for (i in 0 until a.size - 1) if (a[i] != 0) return false
            return true
        }

        private fun magCompare(a: IntArray, b: IntArray): Int {
            if (a.size != b.size) return if (a.size > b.size) 1 else -1
            for (i in a.indices.reversed()) {
                val x = a[i].toLong() and 0xFFFFFFFFL
                val y = b[i].toLong() and 0xFFFFFFFFL
                if (x != y) return if (x > y) 1 else -1
            }
            return 0
        }

        private fun magAdd(a: IntArray, b: IntArray): IntArray {
            val big = if (a.size >= b.size) a else b
            val small = if (a.size >= b.size) b else a
            val out = IntArray(big.size + 1)
            var carry = 0L
            for (i in big.indices) {
                var sum = (big[i].toLong() and 0xFFFFFFFFL) + carry
                if (i < small.size) sum += small[i].toLong() and 0xFFFFFFFFL
                out[i] = sum.toInt()
                carry = sum ushr 32
            }
            out[big.size] = carry.toInt()
            return normalize(out)
        }

        /** [a] must not be smaller than [b]. */
        private fun magSub(a: IntArray, b: IntArray): IntArray {
            val out = IntArray(a.size)
            var borrow = 0L
            for (i in a.indices) {
                var diff = (a[i].toLong() and 0xFFFFFFFFL) - borrow
                if (i < b.size) diff -= b[i].toLong() and 0xFFFFFFFFL
                out[i] = diff.toInt()
                borrow = if (diff < 0) 1L else 0L
            }
            return normalize(out)
        }

        private fun magMultiply(a: IntArray, b: IntArray): IntArray =
            if (a.size >= KARATSUBA_LIMBS && b.size >= KARATSUBA_LIMBS) magMulKaratsuba(a, b)
            else magMulSchoolbook(a, b)

        private fun magMulSchoolbook(a: IntArray, b: IntArray): IntArray {
            if (a.isEmpty() || b.isEmpty()) return IntArray(0)
            val out = IntArray(a.size + b.size)
            for (i in a.indices) {
                val x = a[i].toLong() and 0xFFFFFFFFL
                if (x == 0L) continue
                var carry = 0L
                for (j in b.indices) {
                    val product = x * (b[j].toLong() and 0xFFFFFFFFL) +
                        (out[i + j].toLong() and 0xFFFFFFFFL) + carry
                    out[i + j] = product.toInt()
                    carry = product ushr 32
                }
                out[i + b.size] = carry.toInt()
            }
            return normalize(out)
        }

        /** Splits both operands in half and trades one multiplication for three additions. */
        private fun magMulKaratsuba(a: IntArray, b: IntArray): IntArray {
            val half = (maxOf(a.size, b.size) + 1) / 2
            val aLow = normalize(a.copyOfRange(0, minOf(half, a.size)))
            val aHigh = if (a.size > half) normalize(a.copyOfRange(half, a.size)) else IntArray(0)
            val bLow = normalize(b.copyOfRange(0, minOf(half, b.size)))
            val bHigh = if (b.size > half) normalize(b.copyOfRange(half, b.size)) else IntArray(0)

            val low = magMultiply(aLow, bLow)
            val high = magMultiply(aHigh, bHigh)
            val middle = magSub(
                magSub(magMultiply(magAdd(aLow, aHigh), magAdd(bLow, bHigh)), low),
                high,
            )
            return magAdd(
                magAdd(low, magShiftLeft(middle, half * 32)),
                magShiftLeft(high, half * 64),
            )
        }

        /** Knuth's algorithm D, in the shape Hacker's Delight gives it. */
        private fun magDivRem(u: IntArray, v: IntArray): Pair<IntArray, IntArray> {
            if (v.isEmpty()) throw ArithmeticException("BigInt division by zero")
            if (magCompare(u, v) < 0) return IntArray(0) to u

            val n = v.size
            val m = u.size

            if (n == 1) {
                // ULong, not Long: the running remainder times 2^32 runs past the signed range.
                val divisor = ul(v[0])
                val q = IntArray(m)
                var rem = 0uL
                for (j in m - 1 downTo 0) {
                    val cur = (rem shl 32) or ul(u[j])
                    q[j] = (cur / divisor).toInt()
                    rem = cur % divisor
                }
                return normalize(q) to normalize(intArrayOf(rem.toInt()))
            }

            // D1: shift both so the divisor's top limb has its high bit set.
            val s = v[n - 1].countLeadingZeroBits()
            val vn = IntArray(n)
            for (i in n - 1 downTo 1) {
                vn[i] = (v[i] shl s) or ((v[i - 1].toLong() and 0xFFFFFFFFL) ushr (32 - s)).toInt()
            }
            vn[0] = v[0] shl s

            val un = IntArray(m + 1)
            un[m] = ((u[m - 1].toLong() and 0xFFFFFFFFL) ushr (32 - s)).toInt()
            for (i in m - 1 downTo 1) {
                un[i] = (u[i] shl s) or ((u[i - 1].toLong() and 0xFFFFFFFFL) ushr (32 - s)).toInt()
            }
            un[0] = u[0] shl s

            val q = IntArray(m - n + 1)
            val base = 1uL shl 32
            val vTop = ul(vn[n - 1])
            val vNext = ul(vn[n - 2])

            for (j in m - n downTo 0) {
                // D3: guess the digit from the top two limbs, then walk it back at most twice.
                // The intermediates here run to a full 64 unsigned bits, so they are ULong.
                val head = (ul(un[j + n]) shl 32) or ul(un[j + n - 1])
                var qhat = head / vTop
                var rhat = head % vTop
                // The left side of the `||` keeps qhat under 2^32, so the product cannot overflow.
                while (qhat >= base || qhat * vNext > ((rhat shl 32) or ul(un[j + n - 2]))) {
                    qhat--
                    rhat += vTop
                    if (rhat >= base) break
                }

                // D4: multiply the divisor by the guess and take it away. The borrow is signed,
                // which is how the "guess was one too big" case shows up as a negative top limb.
                var carry = 0L
                for (i in 0 until n) {
                    val product = qhat * ul(vn[i])
                    val t = ul(un[i + j]).toLong() - carry - (product and 0xFFFFFFFFuL).toLong()
                    un[i + j] = t.toInt()
                    carry = (product shr 32).toLong() - (t shr 32)
                }
                val t = ul(un[j + n]).toLong() - carry
                un[j + n] = t.toInt()

                q[j] = qhat.toInt()
                if (t < 0) {
                    // D6: the guess was one too big, so put one divisor back.
                    q[j] = q[j] - 1
                    var addCarry = 0L
                    for (i in 0 until n) {
                        val sum = (un[i + j].toLong() and 0xFFFFFFFFL) +
                            (vn[i].toLong() and 0xFFFFFFFFL) + addCarry
                        un[i + j] = sum.toInt()
                        addCarry = sum ushr 32
                    }
                    un[j + n] = ((un[j + n].toLong() and 0xFFFFFFFFL) + addCarry).toInt()
                }
            }

            // D8: undo the D1 shift to get the remainder back.
            val r = IntArray(n)
            for (i in 0 until n - 1) {
                r[i] = (((un[i].toLong() and 0xFFFFFFFFL) ushr s) or
                    ((un[i + 1].toLong() and 0xFFFFFFFFL) shl (32 - s))).toInt()
            }
            r[n - 1] = ((un[n - 1].toLong() and 0xFFFFFFFFL) ushr s).toInt()

            return normalize(q) to normalize(r)
        }

        /** One limb read as the unsigned value it stands for. */
        private fun ul(limb: Int): ULong = (limb.toLong() and 0xFFFFFFFFL).toULong()

        private fun magShiftLeft(a: IntArray, n: Int): IntArray {
            if (a.isEmpty() || n == 0) return a
            val limbShift = n ushr 5
            val bitShift = n and 31
            val out = IntArray(a.size + limbShift + 1)
            if (bitShift == 0) {
                a.copyInto(out, limbShift)
            } else {
                var carry = 0L
                for (i in a.indices) {
                    val cur = (a[i].toLong() and 0xFFFFFFFFL) shl bitShift
                    out[i + limbShift] = (cur or carry).toInt()
                    carry = cur ushr 32
                }
                out[a.size + limbShift] = carry.toInt()
            }
            return normalize(out)
        }

        private fun magShiftRight(a: IntArray, n: Int): IntArray {
            val limbShift = n ushr 5
            if (limbShift >= a.size) return IntArray(0)
            val bitShift = n and 31
            val size = a.size - limbShift
            val out = IntArray(size)
            if (bitShift == 0) {
                a.copyInto(out, 0, limbShift, a.size)
            } else {
                for (i in 0 until size) {
                    var v = (a[i + limbShift].toLong() and 0xFFFFFFFFL) ushr bitShift
                    if (i + limbShift + 1 < a.size) {
                        v = v or ((a[i + limbShift + 1].toLong() and 0xFFFFFFFFL) shl (32 - bitShift))
                    }
                    out[i] = v.toInt()
                }
            }
            return normalize(out)
        }

        /** Whether any of the low [n] bits is set, which is the sticky bit for rounding. */
        private fun magAnyBitsBelow(a: IntArray, n: Int): Boolean {
            val limbs = n ushr 5
            for (i in 0 until minOf(limbs, a.size)) if (a[i] != 0) return true
            val bits = n and 31
            if (bits != 0 && limbs < a.size) {
                if ((a[limbs] and ((1 shl bits) - 1)) != 0) return true
            }
            return false
        }

        /**
         * The top [count] bits of [a], right-aligned in a Long. Shorter magnitudes are padded on
         * the right with zeroes, so the answer always holds exactly [count] bits.
         */
        private fun magTopBits(a: IntArray, count: Int): Long {
            val shift = magBitLength(a) - count
            var out = 0L
            for (i in 0 until count) {
                val index = shift + i
                if (index >= 0 && magBit(a, index)) out = out or (1L shl i)
            }
            return out
        }

        private fun magBit(a: IntArray, index: Int): Boolean {
            val limb = index ushr 5
            if (limb >= a.size) return false
            return ((a[limb] ushr (index and 31)) and 1) == 1
        }

        private fun magMulAddSmall(a: IntArray, multiplier: Int, addend: Int): IntArray {
            val mul = multiplier.toLong() and 0xFFFFFFFFL
            val out = IntArray(a.size + 2)
            var carry = addend.toLong() and 0xFFFFFFFFL
            for (i in a.indices) {
                val product = (a[i].toLong() and 0xFFFFFFFFL) * mul + carry
                out[i] = product.toInt()
                carry = product ushr 32
            }
            out[a.size] = carry.toInt()
            out[a.size + 1] = (carry ushr 32).toInt()
            return normalize(out)
        }

        private fun magToString(a: IntArray, radix: Int): String {
            val perChunk = digitsPerLimb(radix)
            val chunkScale = ul(powInt(radix, perChunk))
            var cur = a
            val parts = ArrayList<String>()
            while (cur.isNotEmpty()) {
                // Short division again, peeling off `perChunk` digits at a time, ULong for the
                // same reason: the running remainder shifted up does not fit a signed Long.
                val q = IntArray(cur.size)
                var rem = 0uL
                for (j in cur.size - 1 downTo 0) {
                    val v = (rem shl 32) or ul(cur[j])
                    q[j] = (v / chunkScale).toInt()
                    rem = v % chunkScale
                }
                cur = normalize(q)
                parts.add(rem.toLong().toString(radix))
            }
            val sb = StringBuilder()
            sb.append(parts[parts.size - 1])
            for (i in parts.size - 2 downTo 0) {
                val part = parts[i]
                repeat(perChunk - part.length) { sb.append('0') }
                sb.append(part)
            }
            return sb.toString()
        }

        /** The most digits of [radix] that always fit in one limb. */
        private fun digitsPerLimb(radix: Int): Int {
            var count = 0
            var limit = 1L
            while (limit * radix < 0x100000000L) {
                limit *= radix
                count++
            }
            return count
        }

        private fun powInt(base: Int, exponent: Int): Int {
            var result = 1L
            repeat(exponent) { result *= base }
            return result.toInt()
        }
    }
}
