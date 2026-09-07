/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The port's float rounding against the JVM's, which is a real IEEE 754 conversion. Kotlin/JS has
 * none, which is why the port does the rounding itself (D-59); this is what says it does it right.
 */
class FroundOracleTest {

    private fun check(x: Double) {
        val expected = x.toFloat().toDouble()
        val actual = NativeMath.froundToDouble(x)
        // A NaN payload is not observable from JavaScript, and the JVM rewrites it on the way
        // through a float, so any NaN answers for any other.
        if (expected.isNaN() && actual.isNaN()) return
        // Bits otherwise, so that -0.0 and 0.0 are told apart.
        assertEquals(expected.toRawBits(), actual.toRawBits(), "fround($x)")
    }

    @Test
    fun theSpecialValuesMatch() {
        for (x in doubleArrayOf(
            0.0, -0.0, 1.0, -1.0, 0.5, 1.5, 2.5, 1.1, 0.1, -0.1,
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.MIN_VALUE, -Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
            1.0e40, -1.0e40, 1.0e-46, 5.0e-324, 2147483647.0, 1.0000000000000002,
        )) {
            check(x)
        }
    }

    @Test
    fun theFloatBoundariesMatch() {
        // Either side of the largest float, the smallest normal one and the smallest subnormal.
        for (exponent in -155..130) {
            check(twoTo(exponent))
            check(-twoTo(exponent))
            check(twoTo(exponent) * 1.0000001)
            check(twoTo(exponent) * 0.9999999)
        }
    }

    @Test
    fun tiesRoundToEven() {
        // Values exactly between two floats, where round-half-even is the only right answer.
        for (i in 0 until 2000) {
            val stepUp = (1L shl 29).toDouble()
            val mantissa = (1L shl 52) or (i.toLong() * stepUp.toLong() + (stepUp / 2).toLong())
            check(Double.fromBits(mantissa or (1023L shl 52)))
        }
    }

    @Test
    fun aSeededSpreadMatches() {
        val rnd = Random(20260907)
        repeat(200_000) {
            check(Double.fromBits(rnd.nextLong()))
        }
    }

    private fun twoTo(n: Int): Double = Double.fromBits(((n + 1023).toLong() and 0x7FFL) shl 52)
}
