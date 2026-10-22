/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import io.github.yuroyami.kitejs.rhino.typedarrays.Float16
import kotlin.test.Test
import kotlin.test.assertEquals

/** [Float16] rounds a double to binary16, ties to even, and reads every half back exactly. */
class Float16Test {

    @Test
    fun everyHalfReadsBackToItsOwnBits() {
        for (h in 0 until 0x10000) {
            val d = Float16.fromBits(h)
            if (d.isNaN()) {
                assertEquals(0x7C00, h and 0x7C00, "NaN from $h")
                continue
            }
            assertEquals(h, Float16.toBits(d), "half ${h.toString(16)}")
        }
    }

    @Test
    fun doublesRoundAsV8Rounds() {
        val failures = mutableListOf<String>()
        for (line in FLOAT16_CASES.joinToString("").lineSequence()) {
            if (line.isEmpty()) continue
            val (double, half) = line.split(' ')
            val d = Double.fromBits(double.toULong(16).toLong())
            val actual = Float16.toBits(d)
            if (actual != half.toInt(16)) failures += "$d: expected $half, was ${actual.toString(16)}"
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun tiesGoToTheEvenHalf() {
        // 1 + 2^-11 lies halfway between 1 and the next half, 1 + 2^-10; 1 is even.
        assertEquals(0x3C00, Float16.toBits(1.00048828125))
        // 1 + 3 * 2^-11 lies halfway between two halves whose upper one is even.
        assertEquals(0x3C02, Float16.toBits(1.00146484375))
        // 2^-25 is halfway between zero and the smallest subnormal.
        assertEquals(0x0000, Float16.toBits(2.9802322387695312e-8))
        assertEquals(0x7C00, Float16.toBits(65520.0))
        assertEquals(0x7BFF, Float16.toBits(65519.99999999999))
    }
}
