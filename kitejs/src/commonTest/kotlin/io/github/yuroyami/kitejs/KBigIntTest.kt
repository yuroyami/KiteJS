/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [KBigInt] on every target, against answers `java.math.BigInteger` gave. `KBigIntOracleTest` is
 * what checks the whole operand corpus on the JVM; this is the slice that proves the same code
 * gives the same answers off it.
 *
 * That distinction matters more here than elsewhere: Kotlin/JS has no 64-bit integer of its own, so
 * every `Long` and `ULong` in the division loops is emulated with a pair of Ints. A carry bug that
 * the JVM never shows would surface here.
 */
class KBigIntTest {

    private val a = KBigInt.parse("123456789012345678901234567890123456789012345678901234567890")
    private val b = KBigInt.parse("-98765432109876543210987654321")
    private val c = KBigInt.parse("1606938044258990275541962092341162602522202993782792835289031")
    private val d = KBigInt.parse("4294967297")
    private val e = KBigInt.parse("-18446744073709551617")

    private fun eq(expected: String, actual: KBigInt) = assertEquals(expected, actual.toString())

    @Test
    fun addAndSubtract() {
        eq("123456789012345678901234567890024691356902469135690246913569", a.add(b))
        eq("123456789012345678901234567890222222221122222222112222222211", a.subtract(b))
        eq("0", a.subtract(a))
        eq("0", KBigInt.ZERO.add(KBigInt.ZERO))
        eq("123456789012345678901234567890123456789012345678901234567890", a.add(KBigInt.ZERO))
    }

    @Test
    fun multiply() {
        eq("-12193263113702179522618503273374485596337448559633744855963362292333223746380111126352690", a.multiply(b))
        eq(
            "2582249878086908589655919172003011874329705792829223512790984056227893152113710150569742048623864639519406016869308918961",
            c.multiply(c),
        )
        eq("0", a.multiply(KBigInt.ZERO))
    }

    @Test
    fun divideAndRemainderTruncateTowardZero() {
        eq("-1249999988609375000142382812499", a.divide(b))
        eq("46440971104644097110464409711", a.remainder(b))
        eq("28744523642491818233100406280025618734999263413227", a.divide(d))
        eq("2672330471", a.remainder(d))
        eq("-87112285931760246641901533019663016919295", c.divide(e))
        eq("18446744073709539016", c.remainder(e))
        val (q, r) = a.divideAndRemainder(b)
        eq("-1249999988609375000142382812499", q)
        eq("46440971104644097110464409711", r)
    }

    @Test
    fun modIsNeverNegative() {
        eq("46440971104644097110464409711", a.mod(b.abs()))
        eq("3742414535", b.mod(d.abs()))
    }

    @Test
    fun bitwiseUsesTwosComplement() {
        eq("123456789012345678901234567890040475438361896240023890035266", a.and(b))
        eq("-15784081459427104333643121697", a.or(b))
        eq("-123456789012345678901234567890056259519821323344357533156963", a.xor(b))
        eq("-123456789012345678901234567890123456789012345678901234567891", a.not())
        eq("98765432109876543210987654320", b.not())
        eq("4294967297", e.and(d))
        eq("-18446744073709551617", e.or(d))
        // The four cases that a sign-magnitude implementation gets wrong.
        eq("3", KBigInt.fromLong(-1).and(KBigInt.fromLong(3)))
        eq("-1", KBigInt.fromLong(-2).or(KBigInt.fromLong(1)))
        eq("-6", KBigInt.fromLong(5).not())
        eq("-3", KBigInt.fromLong(-5).shiftRight(1))
    }

    @Test
    fun shiftsFloorOnNegatives() {
        eq("-125200059295885441386334822942923965204609031085004156829696", b.shiftLeft(100))
        eq("-11497809564447559325", b.shiftRight(33))
        eq("61728394506172839450617283945061728394506172839450617283945", a.shiftRight(1))
        eq("-576460752303423489", e.shiftRight(5))
        eq("123456789012345678901234567890123456789012345678901234567890", a.shiftLeft(0))
    }

    @Test
    fun radixConversionRoundTrips() {
        assertEquals(
            "10011101010101111010100000100111001001011110000011110011000100001011100111111100001111010010000110111100011000011011110110100100111001000110011001111111100011001011011001110001111110000101011010010",
            a.toString(2),
        )
        assertEquals("13aaf504e4bc1e62173f87a4378c37b49c8ccff196ce3f0ad2", a.toString(16))
        assertEquals("w8g22aadxdzqcj994778lrfivxob1p0k7954gi", a.toString(36))
        assertEquals("-15526625134532262546160101010623450", b.toString(7))
        assertEquals("ffffffffffffffffffffffffffffffffffffffffffffffcfc7", c.toString(16))
        for (radix in 2..36) {
            assertEquals(a, KBigInt.parse(a.toString(radix), radix), "round trip at radix $radix")
            assertEquals(b, KBigInt.parse(b.toString(radix), radix), "round trip at radix $radix")
        }
    }

    @Test
    fun bitLengthMatchesTheTwosComplementRule() {
        assertEquals(197, a.bitLength())
        assertEquals(97, b.bitLength())
        assertEquals(65, e.bitLength())
        assertEquals(0, KBigInt.ZERO.bitLength())
        // A negative power of two needs one bit fewer than its magnitude.
        assertEquals(3, KBigInt.fromLong(-8).bitLength())
        assertEquals(4, KBigInt.fromLong(-9).bitLength())
    }

    @Test
    fun doubleConversionRoundsToEven() {
        assertEquals(1.2345678901234567E59, a.toDouble())
        assertEquals(-9.876543210987655E28, b.toDouble())
        assertEquals(1.6069380442589903E60, c.toDouble())
        assertEquals(9.007199254740992E15, KBigInt.ONE.shiftLeft(53).add(KBigInt.ONE).toDouble())
        assertEquals(1.8014398509481988E16, KBigInt.ONE.shiftLeft(54).add(KBigInt.fromLong(3)).toDouble())
        assertEquals(Double.POSITIVE_INFINITY, KBigInt.ONE.shiftLeft(1024).toDouble())
        assertEquals(Double.NEGATIVE_INFINITY, KBigInt.ONE.shiftLeft(1024).negate().toDouble())
        assertEquals(0.0, KBigInt.ZERO.toDouble())
    }

    @Test
    fun integerConversionsWrap() {
        assertEquals(-8300149958212908334L, a.toLong())
        assertEquals(-834729262, a.toInt())
        assertEquals(-1L, e.toLong())
        assertEquals(42, KBigInt.fromLong(42).intValueExact())
        assertFailsWith<ArithmeticException> { a.intValueExact() }
    }

    @Test
    fun powers() {
        eq("12157665459056928801", KBigInt.fromLong(3).pow(40))
        eq(
            "-963418328982521107774371943460909113147943609921004627042326879183201949040439108518161",
            b.pow(3),
        )
        eq("1", a.pow(0))
        eq("123456789012345678901234567890123456789012345678901234567890", a.pow(1))
    }

    @Test
    fun asIntNAndAsUintN() {
        eq("10146594115496643282", a.asUintN(64))
        eq("-8300149958212908334", a.asIntN(64))
        eq("507048783", b.asUintN(32))
        eq("507048783", b.asIntN(32))
        eq("0", a.asUintN(0))
    }

    @Test
    fun signAndComparison() {
        assertTrue(a.compareTo(b) > 0)
        assertTrue(b.compareTo(a) < 0)
        assertEquals(0, a.compareTo(a))
        assertEquals(1, a.signum())
        assertEquals(-1, b.signum())
        assertEquals(0, KBigInt.ZERO.signum())
        assertTrue(KBigInt.ZERO.isZero())
        eq("98765432109876543210987654321", b.abs())
        assertEquals(a, KBigInt.parse("123456789012345678901234567890123456789012345678901234567890"))
        assertEquals(a.hashCode(), KBigInt.parse(a.toString()).hashCode())
    }

    @Test
    fun comparisonAgainstDoublesIsExact() {
        // 2^53 + 1 is not representable as a double, so the two are not equal.
        val big = KBigInt.ONE.shiftLeft(53).add(KBigInt.ONE)
        assertTrue(big.compareToDouble(9.007199254740992E15) > 0)
        assertEquals(0, KBigInt.fromLong(5).compareToDouble(5.0))
        assertTrue(KBigInt.fromLong(5).compareToDouble(5.5) < 0)
        assertTrue(KBigInt.fromLong(6).compareToDouble(5.5) > 0)
        assertTrue(KBigInt.fromLong(-5).compareToDouble(-4.5) < 0)
    }

    @Test
    fun divisionByZeroFails() {
        assertFailsWith<ArithmeticException> { a.divide(KBigInt.ZERO) }
        assertFailsWith<ArithmeticException> { a.remainder(KBigInt.ZERO) }
        assertFailsWith<ArithmeticException> { a.pow(-1) }
    }

    @Test
    fun theLexerConstructorStillWorks() {
        assertEquals(KBigInt.parse("255"), KBigInt("FF", 16))
        assertEquals(KBigInt.parse("123"), KBigInt("123", 10))
        assertEquals(KBigInt.parse("-7"), KBigInt("-111", 2))
    }
}
