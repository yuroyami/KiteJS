/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every [KBigInt] operation against `java.math.BigInteger`, which is the same differential idea the
 * rest of the port uses: one implementation is known good, so the other has to agree with it.
 *
 * The operand corpus is seeded, so a failure is reproducible. It covers the places carry and borrow
 * bugs hide: zero, one, limb boundaries, powers of two, both signs, and magnitudes from one bit to
 * a few thousand.
 */
class KBigIntOracleTest {

    private fun k(b: BigInteger): KBigInt = KBigInt.parse(b.toString())

    private fun j(k: KBigInt): BigInteger = BigInteger(k.toString())

    /** Operands small enough that every pair of them can be tried. */
    private val small: List<BigInteger> = buildList {
        val seeds = listOf(
            0L, 1L, -1L, 2L, -2L, 3L, 7L, 10L, 255L, 256L, 1000L,
            65535L, 65536L, 65537L,
            Int.MAX_VALUE.toLong(), Int.MIN_VALUE.toLong(),
            0xFFFFFFFFL, 0x100000000L, 0x100000001L,
            Long.MAX_VALUE, Long.MIN_VALUE,
        )
        for (s in seeds) {
            add(BigInteger.valueOf(s))
            add(BigInteger.valueOf(s).negate())
        }
        // Powers of two either side of a limb boundary, where carries go wrong.
        for (bit in intArrayOf(1, 15, 31, 32, 33, 63, 64, 65, 96, 127, 128, 129)) {
            val p = BigInteger.ONE.shiftLeft(bit)
            add(p)
            add(p.negate())
            add(p.subtract(BigInteger.ONE))
            add(p.subtract(BigInteger.ONE).negate())
            add(p.add(BigInteger.ONE))
        }
        val rnd = Random(20260906)
        for (bits in intArrayOf(1, 5, 31, 33, 64, 100, 200)) {
            repeat(3) {
                val v = BigInteger(bits, java.util.Random(rnd.nextLong()))
                add(v)
                add(v.negate())
            }
        }
    }.distinct()

    /** Operands big enough to exercise Karatsuba and the long division loop. */
    private val large: List<BigInteger> = buildList {
        val rnd = java.util.Random(20260906L)
        for (bits in intArrayOf(300, 600, 1200, 2600, 4000)) {
            val v = BigInteger(bits, rnd)
            add(v)
            add(v.negate())
        }
        // A magnitude that is all ones, the worst case for borrow propagation.
        add(BigInteger.ONE.shiftLeft(2048).subtract(BigInteger.ONE))
        add(BigInteger.ONE.shiftLeft(2048).subtract(BigInteger.ONE).negate())
    }

    private val all: List<BigInteger> get() = small + large

    private fun check(label: String, expected: BigInteger, actual: KBigInt) {
        assertEquals(expected.toString(), actual.toString(), label)
    }

    // ---- Conversion comes first: everything below is compared through it ------------------------

    @Test
    fun decimalRoundTripsBothWays() {
        assertTrue(all.size > 80, "corpus too small: ${all.size}")
        for (b in all) {
            assertEquals(b.toString(), k(b).toString(), "round trip of $b")
        }
    }

    @Test
    fun everyRadixMatches() {
        for (radix in 2..36) {
            for (b in all) {
                assertEquals(b.toString(radix), k(b).toString(radix), "toString($radix) of $b")
                check("parse(radix $radix)", b, KBigInt.parse(b.toString(radix), radix))
            }
        }
    }

    // ---- Arithmetic ----------------------------------------------------------------------------

    @Test
    fun addSubtractAndCompareMatch() {
        for (x in all) for (y in all) {
            val a = k(x)
            val b = k(y)
            check("$x + $y", x.add(y), a.add(b))
            check("$x - $y", x.subtract(y), a.subtract(b))
            assertEquals(x.compareTo(y), a.compareTo(b).coerceIn(-1, 1), "$x <=> $y")
        }
    }

    @Test
    fun multiplyMatches() {
        for (x in all) for (y in all) {
            check("$x * $y", x.multiply(y), k(x).multiply(k(y)))
        }
    }

    @Test
    fun divideAndRemainderMatch() {
        for (x in all) for (y in all) {
            if (y.signum() == 0) continue
            val a = k(x)
            val b = k(y)
            check("$x / $y", x.divide(y), a.divide(b))
            check("$x % $y", x.remainder(y), a.remainder(b))
            val (q, r) = a.divideAndRemainder(b)
            check("divideAndRemainder quotient", x.divide(y), q)
            check("divideAndRemainder remainder", x.remainder(y), r)
        }
    }

    @Test
    fun modIsNeverNegative() {
        for (x in all) for (y in all) {
            if (y.signum() <= 0) continue
            check("$x mod $y", x.mod(y), k(x).mod(k(y)))
        }
    }

    @Test
    fun powMatches() {
        for (x in small) {
            for (e in intArrayOf(0, 1, 2, 3, 7, 16, 33)) {
                check("$x ** $e", x.pow(e), k(x).pow(e))
            }
        }
        for (x in large.take(4)) {
            for (e in intArrayOf(0, 1, 2, 3)) {
                check("$x ** $e", x.pow(e), k(x).pow(e))
            }
        }
    }

    // ---- Bit operations, where two's complement is what matters --------------------------------

    @Test
    fun bitwiseOperationsUseTwosComplement() {
        for (x in all) for (y in all) {
            val a = k(x)
            val b = k(y)
            check("$x and $y", x.and(y), a.and(b))
            check("$x or $y", x.or(y), a.or(b))
            check("$x xor $y", x.xor(y), a.xor(b))
        }
        for (x in all) {
            check("not $x", x.not(), k(x).not())
        }
    }

    @Test
    fun shiftsMatch() {
        val amounts = intArrayOf(0, 1, 7, 31, 32, 33, 63, 64, 65, 100, 256, 1000)
        for (x in all) for (n in amounts) {
            check("$x shl $n", x.shiftLeft(n), k(x).shiftLeft(n))
            check("$x shr $n", x.shiftRight(n), k(x).shiftRight(n))
        }
    }

    @Test
    fun signBitLengthAndAbsMatch() {
        for (x in all) {
            val a = k(x)
            assertEquals(x.signum(), a.signum(), "signum of $x")
            assertEquals(x.bitLength(), a.bitLength(), "bitLength of $x")
            check("abs of $x", x.abs(), a.abs())
            check("negate of $x", x.negate(), a.negate())
        }
    }

    @Test
    fun testBitMatches() {
        for (x in all.take(60)) {
            val a = k(x)
            for (bit in intArrayOf(0, 1, 5, 31, 32, 33, 63, 64, 127, 200)) {
                assertEquals(x.testBit(bit), a.testBit(bit), "testBit($bit) of $x")
            }
        }
    }

    // ---- Number conversions --------------------------------------------------------------------

    @Test
    fun doubleConversionIsCorrectlyRounded() {
        for (x in all) {
            assertEquals(x.toDouble(), k(x).toDouble(), "toDouble of $x")
        }
        // Values that sit exactly on a rounding boundary, where round-half-even decides.
        for (extra in 0..64) {
            val v = BigInteger.ONE.shiftLeft(53).add(BigInteger.valueOf(extra.toLong()))
            assertEquals(v.toDouble(), k(v).toDouble(), "toDouble of $v")
            val w = BigInteger.ONE.shiftLeft(54).add(BigInteger.valueOf(extra.toLong()))
            assertEquals(w.toDouble(), k(w).toDouble(), "toDouble of $w")
        }
    }

    @Test
    fun longAndIntConversionsWrapLikeJava() {
        for (x in all) {
            val a = k(x)
            assertEquals(x.toLong(), a.toLong(), "toLong of $x")
            assertEquals(x.toInt(), a.toInt(), "toInt of $x")
        }
    }

    @Test
    fun fromDoubleTruncatesTowardZero() {
        val values = doubleArrayOf(
            0.0, -0.0, 1.0, -1.0, 1.5, -1.5, 2.5, -2.5, 1e15, -1e15,
            9.007199254740992E15, 1e300, -1e300, 12345.678, -12345.678,
        )
        for (d in values) {
            val expected = java.math.BigDecimal(d).toBigInteger()
            check("fromDouble($d)", expected, KBigInt.fromDouble(d))
        }
    }

    @Test
    fun compareToDoubleIsExact() {
        val doubles = doubleArrayOf(0.0, 0.5, -0.5, 1.0, -1.0, 2.5, 1e18, -1e18, 1e300, -1e300)
        for (x in small) for (d in doubles) {
            val expected = java.math.BigDecimal(x).compareTo(java.math.BigDecimal(d))
            assertEquals(expected, k(x).compareToDouble(d).coerceIn(-1, 1), "$x <=> $d")
        }
    }

    // ---- asIntN and asUintN, the two BigInt-only operations ------------------------------------

    @Test
    fun asUintNAndAsIntNMatchTheSpecFormula() {
        for (x in small) for (bits in intArrayOf(1, 3, 8, 31, 32, 33, 64, 65, 100)) {
            val modulus = BigInteger.ONE.shiftLeft(bits)
            val unsigned = x.mod(modulus)
            check("asUintN($bits, $x)", unsigned, k(x).asUintN(bits))
            val signed =
                if (unsigned.testBit(bits - 1)) unsigned.subtract(modulus) else unsigned
            check("asIntN($bits, $x)", signed, k(x).asIntN(bits))
        }
    }

    // ---- Algebraic identities, which catch what a wrong oracle would not -----------------------

    @Test
    fun algebraicIdentitiesHold() {
        for (x in all) {
            val a = k(x)
            assertTrue(a.subtract(a).isZero(), "$x - $x")
            check("negate twice", x, a.negate().negate())
            check("double negate through not", x, a.not().not())
        }
        for (x in all) for (y in all) {
            if (y.signum() == 0) continue
            val a = k(x)
            val b = k(y)
            // (a * b) / b == a, and a == (a / b) * b + (a % b).
            check("(x*y)/y", x, a.multiply(b).divide(b))
            check("q*b + r", x, a.divide(b).multiply(b).add(a.remainder(b)))
        }
        for (x in all) for (n in intArrayOf(1, 7, 32, 65)) {
            // Shifting left is multiplying by a power of two.
            check("shl as multiply", x.shiftLeft(n), k(x).multiply(KBigInt.fromLong(1L shl 0).shiftLeft(n)))
        }
    }
}
