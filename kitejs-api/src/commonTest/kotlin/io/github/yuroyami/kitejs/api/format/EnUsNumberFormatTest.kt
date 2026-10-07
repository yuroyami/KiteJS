/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api.format

import io.github.yuroyami.kitejs.api.KBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

/**
 * [EnUsNumberFormat] against what ICU's `Intl.NumberFormat("en-US")` printed for the same values
 * and options, in [EN_US_NUMBER_ORACLE], and the order it reads its options in.
 */
class EnUsNumberFormatTest {

    /** Options as plain Kotlin values, with the conversions ECMA-402 applies to them. */
    private class MapReader(val map: Map<String, Any>, val reads: MutableList<String> = mutableListOf()) :
        NumberFormatOptionReader {
        override fun get(name: String): Any? {
            reads += name
            return map[name] ?: UNDEFINED
        }
        override fun isUndefined(value: Any?): Boolean = value === UNDEFINED
        override fun toNumber(value: Any?): Double = when (value) {
            is Double -> value
            is Boolean -> if (value) 1.0 else 0.0
            is String -> value.toDoubleOrNull() ?: Double.NaN
            else -> Double.NaN
        }
        override fun toJsString(value: Any?): String = when (value) {
            is Double -> if (value == kotlin.math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()
            else -> value.toString()
        }
        override fun toBoolean(value: Any?): Boolean = when (value) {
            is Boolean -> value
            is String -> value.isNotEmpty()
            is Double -> !(value == 0.0 || value.isNaN())
            else -> true
        }
        override fun isTrue(value: Any?): Boolean = value == true

        companion object {
            val UNDEFINED = Any()
        }
    }

    private fun parseOptions(text: String): Map<String, Any> {
        if (text.isEmpty()) return emptyMap()
        return text.split(',').associate { pair ->
            val eq = pair.indexOf('=')
            val raw = pair.substring(eq + 2)
            pair.substring(0, eq) to when (pair[eq + 1]) {
                's' -> raw
                'b' -> raw == "true"
                else -> raw.toDouble()
            }
        }
    }

    private fun format(value: String, options: Map<String, Any>): String {
        val nf = try {
            EnUsNumberFormat.resolve(MapReader(options))
        } catch (e: NumberFormatOptionError) {
            return if (e.isTypeError) "!TypeError" else "!RangeError"
        }
        val text = value.substring(1)
        return if (value[0] == 'b') {
            nf.format(KBigInt.parse(text))
        } else {
            nf.format(
                when (text) {
                    "NaN" -> Double.NaN
                    "Infinity" -> Double.POSITIVE_INFINITY
                    "-Infinity" -> Double.NEGATIVE_INFINITY
                    else -> text.toDouble()
                },
            )
        }
    }

    @Test
    fun everyCaseFormatsAsIcuDoes() {
        val failures = mutableListOf<String>()
        var count = 0
        for (line in EN_US_NUMBER_ORACLE.joinToString("").lineSequence()) {
            if (line.isEmpty()) continue
            count++
            val (value, options, expected) = line.split('|')
            val actual = try {
                format(value, parseOptions(options))
            } catch (e: Throwable) {
                "threw $e"
            }
            if (actual != expected) failures += "$value {$options}: expected <$expected> but was <$actual>"
        }
        if (failures.isNotEmpty()) {
            fail("${failures.size} of $count cases differ from ICU:\n" + failures.take(60).joinToString("\n"))
        }
    }

    @Test
    fun theDefaultsMatchAnEmptyOptionsObject() {
        assertEquals("2,046,430", EnUsNumberFormat.DEFAULT.format(2046430.0))
        assertEquals("1,234.568", EnUsNumberFormat.DEFAULT.format(1234.56789))
        assertEquals("-0", EnUsNumberFormat.DEFAULT.format(-0.0))
        assertEquals("12,345,678,901,234,567,890", EnUsNumberFormat.DEFAULT.format(KBigInt.parse("12345678901234567890")))
    }

    @Test
    fun optionsAreReadInEcma402Order() {
        val reader = MapReader(emptyMap())
        EnUsNumberFormat.resolve(reader)
        assertEquals(
            listOf(
                "localeMatcher", "numberingSystem", "style", "currency", "currencyDisplay", "currencySign",
                "unit", "unitDisplay", "notation", "minimumIntegerDigits", "minimumFractionDigits",
                "maximumFractionDigits", "minimumSignificantDigits", "maximumSignificantDigits",
                "roundingIncrement", "roundingMode", "roundingPriority", "trailingZeroDisplay",
                "compactDisplay", "useGrouping", "signDisplay",
            ),
            reader.reads,
        )
    }

    @Test
    fun aMissingCurrencyIsATypeErrorAndABadOneARangeError() {
        assertEquals(true, assertFailsWith<NumberFormatOptionError> { EnUsNumberFormat.resolve(MapReader(mapOf("style" to "currency"))) }.isTypeError)
        assertEquals(false, assertFailsWith<NumberFormatOptionError> { EnUsNumberFormat.resolve(MapReader(mapOf("currency" to "US"))) }.isTypeError)
    }
}
