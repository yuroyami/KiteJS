/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.dtoa.DecimalParser
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The port's own decimal parser against the JVM's, which is correctly rounded. Every string has
 * to give bit-for-bit the same double. This is what lets the engine stop asking the platform,
 * which matters because Kotlin/Wasm answers a neighbouring double often enough to break test262.
 */
class DecimalParserOracleTest {

    private fun check(text: String) {
        val expected = text.toDouble()
        val actual = DecimalParser.parse(text)
        assertTrue(actual != null, "the parser rejected \"$text\"")
        assertEquals(
            expected.toRawBits(),
            actual.toRawBits(),
            "\"$text\": expected $expected (${expected.toRawBits().toString(16)}), " +
                "got $actual (${actual.toRawBits().toString(16)})",
        )
    }

    @Test
    fun theBoundariesAreExact() {
        val cases = listOf(
            "0", "0.0", "-0.0", "1", "-1", "10", "0.1", "0.2", "0.3", "1.5", "2.5", "0.5",
            "1e0", "1e1", "1e-1", "1E10", "1e+10", "1e308", "1e-308", "1e309", "1e-400",
            // The edges of the double's range, and the first value past each of them.
            "1.7976931348623157e308", "1.7976931348623159e308",
            "2.2250738585072014e-308", "2.2250738585072009e-308",
            "4.9406564584124654e-324", "4.9406564584124654e-325", "2.4703282292062327e-324",
            // Halfway cases, where rounding to even is the only correct answer.
            "9007199254740993", "9007199254740992", "9007199254740994",
            "1.0000000000000001", "1.0000000000000002",
            // Long digit strings that no fast path can take.
            "1.2345678901234567890123456789012345678901234567890e-300",
            "123456789012345678901234567890123456789012345678901234567890",
            "0.000000000000000000000000000000000000000000000000000000000001",
            // The ones test262 caught on Wasm.
            "5.65865417e-315", "1.44861546824e-312", "3.7084555987028e-310",
            "2.254739805726094e-307", "747563348316297500000", "10846169068898440",
            // Leading and trailing zeros in every position.
            "000123", "123000", "0.000123000", "00.00", "1e00010", "-000.5",
        )
        for (text in cases) check(text)
    }

    @Test
    fun everySubnormalPowerOfTwoIsExact() {
        // The whole subnormal range, one power of two at a time, printed and read back.
        for (bit in 0 until 52) {
            val d = Double.fromBits(1L shl bit)
            check(d.toString())
            check(ScriptRuntime.numberToString(d, 10))
        }
    }

    @Test
    fun aQuarterMillionRandomStringsMatchTheJvm() {
        val random = Random(20260907)
        var checked = 0
        repeat(250_000) {
            val digitCount = 1 + random.nextInt(25)
            val digits = buildString {
                repeat(digitCount) { append('0' + random.nextInt(10)) }
            }
            val exponent = random.nextInt(-360, 320)
            val text = buildString {
                if (random.nextBoolean()) append('-')
                append(digits)
                if (random.nextBoolean()) {
                    val dot = random.nextInt(digits.length + 1)
                    setLength(length - digits.length)
                    append(digits, 0, dot).append('.').append(digits, dot, digits.length)
                }
                append('e').append(exponent)
            }
            check(text)
            checked++
        }
        assertEquals(250_000, checked)
    }

    @Test
    fun everyDoubleRoundTripsThroughItsOwnPrinter() {
        // The printer promises the shortest string that reads back as the same double. That
        // promise is only true if the reader agrees with it, so check the pair together.
        val random = Random(99)
        repeat(100_000) {
            val d = Double.fromBits(random.nextLong())
            if (!d.isFinite()) return@repeat
            val printed = ScriptRuntime.numberToString(d, 10)
            val back = DecimalParser.parse(printed)
            assertEquals(d.toRawBits(), back?.toRawBits(), "round trip failed for $printed")
        }
    }

    @Test
    fun whatIsNotADecimalIsRejected() {
        for (text in listOf("", "+", "-", ".", "e5", "1e", "1e+", "1.2.3", "1e5e5", "--1", "1..2")) {
            assertEquals(null, DecimalParser.parse(text), "\"$text\" should not parse")
        }
    }
}
