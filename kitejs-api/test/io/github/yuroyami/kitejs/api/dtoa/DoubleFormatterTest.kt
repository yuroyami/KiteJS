/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api.dtoa

import kotlin.test.Test
import kotlin.test.assertEquals

/** [DoubleFormatter] prints the shortest digits, as V8 does, down to the smallest subnormals. */
class DoubleFormatterTest {

    @Test
    fun theSmallestSubnormalsPrintWithOneDigitWhereOneIsEnough() {
        val printed = (1..20).joinToString(" ") { DoubleFormatter.toString(Double.fromBits(it.toLong())) }
        assertEquals(
            "5e-324 1e-323 1.5e-323 2e-323 2.5e-323 3e-323 3.5e-323 4e-323 4.4e-323 5e-323 " +
                "5.4e-323 6e-323 6.4e-323 7e-323 7.4e-323 8e-323 8.4e-323 9e-323 9.4e-323 1e-322",
            printed,
        )
        assertEquals("-5e-324", DoubleFormatter.toString(-Double.MIN_VALUE))
    }

    @Test
    fun everyValueMatchesV8() {
        val failures = mutableListOf<String>()
        for (line in V8_NUMBER_STRINGS.joinToString("").lineSequence()) {
            if (line.isEmpty()) continue
            val (bits, expected) = line.split(' ')
            val actual = DoubleFormatter.toString(Double.fromBits(bits.toULong(16).toLong()))
            if (actual != expected) failures += "$bits: expected $expected, was $actual"
        }
        assertEquals(emptyList(), failures)
    }
}
