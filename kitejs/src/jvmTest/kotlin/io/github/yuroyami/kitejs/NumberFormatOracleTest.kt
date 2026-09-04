/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mozilla.javascript.ScriptRuntime as UpstreamScriptRuntime

/**
 * Number-to-string is where a port most easily drifts, because the result has to be the shortest
 * decimal that round-trips. Every value here goes through the upstream formatter and through the
 * ported one, and the strings must match exactly.
 */
class NumberFormatOracleTest {

    private fun check(values: Sequence<Double>, label: String) {
        var checked = 0
        val failures = mutableListOf<String>()
        for (d in values) {
            val expected = UpstreamScriptRuntime.numberToString(d, 10)
            val actual = ScriptRuntime.numberToString(d, 10)
            if (expected != actual) {
                failures.add("bits=${d.toRawBits()} expected=$expected actual=$actual")
                if (failures.size > 20) break
            }
            checked++
        }
        assertTrue(checked > 0, "$label produced no values")
        assertEquals(emptyList(), failures, "$label: formatting differs from upstream")
    }

    @Test
    fun boundaryValuesMatchUpstream() {
        val values = listOf(
            0.0, -0.0, 1.0, -1.0, 0.1, -0.1, 0.5, 2.0, 10.0, 100.0,
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.MIN_VALUE, -Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
            java.lang.Double.MIN_NORMAL, -java.lang.Double.MIN_NORMAL,
            // The points where ECMAScript switches between fixed and exponential notation.
            1e20, 1e21, 1e-6, 1e-7, 1.2345e20, 1.2345e21, 1.2345e-6, 1.2345e-7,
            // Values with well known shortest-representation traps.
            9007199254740992.0, 9007199254740993.0, 5e-324, 2.2250738585072014e-308,
            1.7976931348623157e308, 4.9e-324, 0.3, 0.7, 1.0 / 3.0, 2.0 / 3.0,
            123456789012345680000.0, 1e-323, 1.5e300, 1.5e-300,
        )
        check(values.asSequence(), "boundary values")
    }

    @Test
    fun everyIntegerInASmallRangeMatchesUpstream() {
        check((-10000..10000).asSequence().map { it.toDouble() }, "small integers")
    }

    @Test
    fun powersOfTenMatchUpstream() {
        check((-330..330).asSequence().map { Math.pow(10.0, it.toDouble()) }, "powers of ten")
    }

    @Test
    fun powersOfTwoMatchUpstream() {
        check(
            (-1080..1023).asSequence().map { Math.pow(2.0, it.toDouble()) },
            "powers of two",
        )
    }

    @Test
    fun randomBitPatternsMatchUpstream() {
        // Deterministic seed so a failure is reproducible.
        val random = Random(20260904)
        check(
            generateSequence { Double.fromBits(random.nextLong()) }.take(200_000),
            "random bit patterns",
        )
    }

    @Test
    fun randomDecimalsMatchUpstream() {
        val random = Random(1234567)
        check(
            generateSequence {
                val mantissa = random.nextLong(-1_000_000_000_000_000L, 1_000_000_000_000_000L)
                val exponent = random.nextInt(-30, 30)
                mantissa.toDouble() * Math.pow(10.0, exponent.toDouble())
            }.take(100_000),
            "random decimals",
        )
    }

    @Test
    fun toStringMatchesUpstream() {
        for (d in listOf(0.0, -0.0, 1.5, 1e21, 1e-7, Double.NaN)) {
            assertEquals(UpstreamScriptRuntime.toString(d), ScriptRuntime.toString(d), "d=$d")
        }
    }
}
