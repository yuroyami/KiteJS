/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The port's Java-style double printing against the real `java.lang.Double.toString`. */
class JavaNumbersOracleTest {

    private val corpus = listOf(
        0.0, -0.0, 1.0, -1.0, 5.0, 2.5, 100.0, 1000.0, 1e6, 9999999.0, 1e7, 12345678.0, 1e21, 1e-3, 0.001, 1e-4, 0.0001, 1.5e-7,
        123.456, 0.1, 0.2, 0.1 + 0.2, 1.0 / 3, 2.0 / 3, 1e15, 1e16, 1e17, 123456789012345680000.0, Double.MAX_VALUE,
        Double.MIN_VALUE, 2.2250738585072014e-308, 4.9e-324, 1e100, 1e-100, 3.14159265358979, 2.718281828459045,
        9007199254740992.0, 9007199254740993.0, 0.000001234, 1e300, 1.7976931348623157e308, 4.35, 1.005, 0.5, 1.5,
        Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e23, 8.41e21, 5e-324, 1e-7, 123e-20, 1234.5678e10,
    )

    private fun randomDoubles(): List<Double> {
        val random = Random(7)
        val out = ArrayList<Double>()
        repeat(300) { val d = Double.fromBits(random.nextLong()); if (d.isFinite()) out.add(d) }
        repeat(300) { out.add((random.nextDouble() - 0.5) * 1e6) }
        repeat(100) { out.add(random.nextInt(-100000, 100000).toDouble()) }
        repeat(100) { out.add(random.nextDouble() * 1e-5) }
        return out
    }

    @Test
    fun doubleToStringMatchesJava() {
        val failures = mutableListOf<String>()
        for (d in corpus + randomDoubles()) {
            val expected = java.lang.Double.toString(d)
            val actual = JavaNumbers.doubleToString(d)
            if (expected != actual) failures.add("$d: expected $expected, got $actual")
        }
        assertEquals(emptyList(), failures)
    }
}
