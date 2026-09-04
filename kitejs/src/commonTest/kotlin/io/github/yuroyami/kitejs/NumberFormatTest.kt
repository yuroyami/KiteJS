/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The same number formatting the JVM oracle checks against upstream, run on every target. This is
 * what makes the result platform independent: before the Schubfach port the value came from
 * `Double.toString`, which differs between the JVM, JS and native.
 */
class NumberFormatTest {

    private fun str(d: Double) = ScriptRuntime.numberToString(d, 10)

    @Test
    fun formatsPlainValues() {
        assertEquals("0", str(0.0))
        assertEquals("0", str(-0.0))
        assertEquals("1", str(1.0))
        assertEquals("-1", str(-1.0))
        assertEquals("-42", str(-42.0))
        assertEquals("100", str(100.0))
        assertEquals("0.1", str(0.1))
        assertEquals("0.5", str(0.5))
        assertEquals("0.3", str(0.3))
    }

    @Test
    fun formatsNonFiniteValues() {
        assertEquals("NaN", str(Double.NaN))
        assertEquals("Infinity", str(Double.POSITIVE_INFINITY))
        assertEquals("-Infinity", str(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun switchesToExponentialAtTheSpecPoints() {
        // ECMAScript stays in fixed notation up to 10^21 and down to 10^-6.
        assertEquals("100000000000000000000", str(1e20))
        assertEquals("1e+21", str(1e21))
        assertEquals("0.000001", str(1e-6))
        assertEquals("1e-7", str(1e-7))
    }

    @Test
    fun formatsExtremeValues() {
        assertEquals("4.9e-324", str(Double.MIN_VALUE))
        assertEquals("1.7976931348623157e+308", str(Double.MAX_VALUE))
        assertEquals("1.5e+300", str(1.5e300))
        assertEquals("1.5e-300", str(1.5e-300))
    }

    @Test
    fun picksTheShortestRoundTrippingForm() {
        assertEquals("9007199254740992", str(9007199254740992.0))
        assertEquals("0.3333333333333333", str(1.0 / 3.0))
        assertEquals("123456789.12345679", str(123456789.123456789))
    }

    @Test
    fun everyValueRoundTripsThroughTheLexer() {
        // The formatter has to produce something the scanner reads back as the same double.
        val values = listOf(
            0.1, 0.5, 1.0 / 3.0, 1e-7, 1e21, 1.5e300, 1.5e-300,
            Double.MIN_VALUE, Double.MAX_VALUE, 9007199254740992.0, 123456789.123456789,
        )
        for (d in values) {
            val text = str(d)
            assertEquals(d, text.toDouble(), "round trip of $text")
        }
    }

    @Test
    fun rejectsAnImpossibleRadix() {
        assertFailsWith<EcmaError> { ScriptRuntime.numberToString(1.0, 1) }
        assertFailsWith<EcmaError> { ScriptRuntime.numberToString(1.0, 37) }
    }

    @Test
    fun nonDecimalRadixesWaitForBigInt() {
        // Non-finite and zero short-circuit before the unsupported path.
        assertEquals("NaN", ScriptRuntime.numberToString(Double.NaN, 16))
        assertEquals("0", ScriptRuntime.numberToString(0.0, 16))
        assertFailsWith<UnsupportedOperationException> {
            ScriptRuntime.numberToString(255.0, 16)
        }
    }
}
