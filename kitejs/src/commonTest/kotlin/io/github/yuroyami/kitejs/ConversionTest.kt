/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The conversions on every target. The JVM oracle proves these answers match upstream; this proves
 * the same code gives the same answers off the JVM, where the platform's own number parsing and
 * formatting differ.
 */
class ConversionTest {

    private fun num(s: String) = ScriptRuntime.toNumber(s)

    @Test
    fun emptyAndWhitespaceBecomeZero() {
        assertEquals(0.0, num(""))
        assertEquals(0.0, num(" "))
        assertEquals(0.0, num("\t\n\r "))
        assertEquals(0.0, num(" ﻿"))
        assertEquals(0.0, num("  "))
    }

    @Test
    fun plainDecimalsParse() {
        assertEquals(0.0, num("0"))
        assertEquals(1.0, num("1"))
        assertEquals(-1.0, num("-1"))
        assertEquals(1.0, num("+1"))
        assertEquals(1.5, num("1.5"))
        assertEquals(0.5, num(".5"))
        assertEquals(5.0, num("5."))
        assertEquals(42.0, num("  42  "))
    }

    @Test
    fun exponentsParse() {
        assertEquals(1000.0, num("1e3"))
        assertEquals(1000.0, num("1E3"))
        assertEquals(0.001, num("1e-3"))
        assertEquals(Double.POSITIVE_INFINITY, num("1e309"))
        assertEquals(0.0, num("1e-400"))
        assertTrue(num("1e").isNaN())
        assertTrue(num("e5").isNaN())
    }

    @Test
    fun hexadecimalParsesWithTheLegacyRules() {
        // With no context entered the engine uses the pre-ES6 rules: a hex literal parses only its
        // valid prefix, and it may carry a sign.
        assertEquals(16.0, num("0x10"))
        assertEquals(16.0, num("0X10"))
        assertEquals(-16.0, num("-0x10"))
        assertEquals(16.0, num("+0x10"))
        assertEquals(16.0, num("0x10 something"))
        assertEquals(4294967295.0, num("0xFFFFFFFF"))
    }

    @Test
    fun binaryAndOctalAreRejectedByTheLegacyRules() {
        assertTrue(num("0b1").isNaN())
        assertTrue(num("0o5").isNaN())
    }

    @Test
    fun infinityIsRecognisedExactly() {
        assertEquals(Double.POSITIVE_INFINITY, num("Infinity"))
        assertEquals(Double.POSITIVE_INFINITY, num("+Infinity"))
        assertEquals(Double.NEGATIVE_INFINITY, num("-Infinity"))
        assertEquals(Double.POSITIVE_INFINITY, num(" Infinity "))
        assertTrue(num("infinity").isNaN())
        assertTrue(num("Infinityy").isNaN())
    }

    @Test
    fun junkBecomesNaN() {
        for (s in listOf("abc", "1a", "a1", "1 2", "--1", "1-", "+", "-", ".", "1.2.3", "1,000")) {
            assertTrue(num(s).isNaN(), "\"$s\" should be NaN")
        }
    }

    @Test
    fun longDigitStringsRoundCorrectly() {
        // The platform parsers differ here, so this is the one that would catch a drift.
        assertEquals(9007199254740992.0, num("9007199254740993"))
        assertEquals(1.2345678901234568e24, num("1234567890123456789012345"))
        assertEquals(0.12345678901234568, num("0.1234567890123456789012345"))
        assertEquals(1.8446744073709552e19, num("18446744073709551616"))
    }

    @Test
    fun toIntegerTruncatesTowardZero() {
        assertEquals(0.0, ScriptRuntime.toInteger(Double.NaN))
        assertEquals(1.0, ScriptRuntime.toInteger(1.9))
        assertEquals(-1.0, ScriptRuntime.toInteger(-1.9))
        assertEquals(0.0, ScriptRuntime.toInteger(0.0))
        assertEquals(Double.POSITIVE_INFINITY, ScriptRuntime.toInteger(Double.POSITIVE_INFINITY))
    }

    @Test
    fun toInt32AndToUint32Wrap() {
        assertEquals(0, ScriptRuntime.toInt32(Double.NaN))
        assertEquals(1, ScriptRuntime.toInt32(1.9))
        assertEquals(-1, ScriptRuntime.toInt32(4294967295.0))
        assertEquals(0, ScriptRuntime.toInt32(4294967296.0))
        assertEquals(4294967295L, ScriptRuntime.toUint32(-1.0))
        assertEquals(0L, ScriptRuntime.toUint32(4294967296.0))
    }

    @Test
    fun formatThenParseRoundTripsOnEveryTarget() {
        val values = listOf(
            0.1, 0.5, 1.0 / 3.0, 1e-7, 1e21, 1.5e300, 1.5e-300,
            Double.MIN_VALUE, Double.MAX_VALUE, 9007199254740992.0, 123456789.123456789,
            2.2250738585072014e-308,
        )
        for (d in values) {
            val text = ScriptRuntime.numberToString(d, 10)
            assertEquals(d, ScriptRuntime.toNumber(text), "round trip of $text")
        }

        // Negative zero is the one value that cannot round trip: the spec says it formats as "0".
        assertEquals("0", ScriptRuntime.numberToString(-0.0, 10))
        assertEquals(0.0, ScriptRuntime.toNumber("0"))
    }

    @Test
    fun consStringFlattensLazily() {
        val a = ConsString("foo", "bar")
        assertEquals(6, a.length)
        assertEquals("foobar", a.toString())

        // A deep left-leaning rope, which is what repeated concatenation builds.
        var rope: CharSequence = ""
        repeat(500) { rope = ConsString(rope, "x") }
        assertEquals(500, rope.length)
        assertEquals("x".repeat(500), rope.toString())

        val nested = ConsString(ConsString("a", "b"), ConsString("c", "d"))
        assertEquals("abcd", nested.toString())
        assertEquals('c', nested[2])
        assertEquals("bc", nested.subSequence(1, 3).toString())
    }

    @Test
    fun undefinedHasTwoRepresentations() {
        assertTrue(Undefined.isUndefined(Undefined.instance))
        assertTrue(Undefined.isUndefined(Undefined.SCRIPTABLE_UNDEFINED))
        assertTrue(!Undefined.isUndefined(null))
        assertTrue(!Undefined.isUndefined("undefined"))
        assertEquals("undefined", Undefined.SCRIPTABLE_UNDEFINED.toString())
        assertEquals("undefined", Undefined.SCRIPTABLE_UNDEFINED.className)
    }

    @Test
    fun uniqueTagsAreDistinct() {
        assertTrue(UniqueTag.NOT_FOUND !== UniqueTag.NULL_VALUE)
        assertTrue(UniqueTag.NULL_VALUE !== UniqueTag.DOUBLE_MARK)
        assertEquals(UniqueTag.NOT_FOUND, Scriptable.NOT_FOUND)
        assertTrue(UniqueTag.NOT_FOUND.toString().endsWith(": NOT_FOUND"))
    }
}
