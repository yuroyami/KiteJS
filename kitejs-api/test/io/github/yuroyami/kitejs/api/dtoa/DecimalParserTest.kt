/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api.dtoa

import io.github.yuroyami.kitejs.api.KBigInt
import kotlin.math.nextUp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/** [DecimalParser] rounds long inputs correctly and in time that grows with the length, not its square. */
class DecimalParserTest {

    /** The exact decimal digits of 2 to the power -[n]: 5^n shifted n places right. */
    private fun twoToTheMinus(n: Int, times: Long = 1L): String {
        val digits = KBigInt.fromLong(5L).pow(n).multiply(KBigInt.fromLong(times)).toString()
        return "0." + "0".repeat(n - digits.length) + digits
    }

    @Test
    fun anExactHalfwayRoundsToEven() {
        // 2^-1075 lies halfway between 0 and the smallest subnormal; 3 * 2^-1075 between the first and second.
        assertEquals(0.0, DecimalParser.parse(twoToTheMinus(1075)))
        assertEquals(Double.fromBits(2L), DecimalParser.parse(twoToTheMinus(1075, 3L)))
        // 1 + 2^-53 lies halfway between 1 and the next double.
        assertEquals(1.0, DecimalParser.parse("1" + twoToTheMinus(53).substring(1)))
    }

    @Test
    fun aDigitFarPastTheHalfwayPointStillRoundsUp() {
        val far = "0".repeat(5000) + "1"
        assertEquals(Double.MIN_VALUE, DecimalParser.parse(twoToTheMinus(1075) + far))
        assertEquals(Double.fromBits(3L), DecimalParser.parse(twoToTheMinus(1075, 5L) + far))
        assertEquals(1.0.nextUp(), DecimalParser.parse("1" + twoToTheMinus(53).substring(1) + far))
    }

    @Test
    fun aLongInputTakesLinearTime() {
        val text = "0." + "1".repeat(800_000)
        val took = measureTime { assertEquals(0.1111111111111111, DecimalParser.parse(text)) }
        // The old parser took 18 s on the JVM; the new one reads the digits once.
        assertTrue(took.inWholeSeconds < 3, "took $took")
        assertEquals(1.1111111111111111e299, DecimalParser.parse("1".repeat(300_000) + "e-299700"))
    }
}
