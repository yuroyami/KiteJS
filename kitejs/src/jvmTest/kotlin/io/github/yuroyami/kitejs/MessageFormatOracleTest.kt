/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import java.text.MessageFormat
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** Upstream formats messages with `java.text.MessageFormat`; the port's formatter has to print the same. */
class MessageFormatOracleTest {

    @Test
    fun argumentsPrintLikeMessageFormat() {
        val pattern = "illegal character: {0}"
        val args = listOf<Any?>(
            5, 5.0, 2.5, -2.5, 1234567, 1234567.0, 1234567.891, 0.0625, 0.0005, 0.0015, 1e21, -0.0, 0.0, Double.NaN,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Long.MAX_VALUE, 0.1 + 0.2, 100.0, 1e15, 123456789012345680000.0,
            -1234.5, 999.9995, 1.0005, 4.35, 0.000001, -1000, 2147483648.0, "text", null, true, 'c',
        )
        val failures = mutableListOf<String>()
        for (arg in args) {
            val expected = MessageFormat(pattern, Locale.US).format(arrayOf(arg))
            val actual = Messages.getMessageById("msg.illegal.character", arg)
            if (expected != actual) failures.add("$arg: expected \"$expected\", got \"$actual\"")
        }
        assertEquals(emptyList(), failures)
    }
}
