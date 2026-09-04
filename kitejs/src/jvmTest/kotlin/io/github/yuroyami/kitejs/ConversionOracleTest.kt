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
 * The numeric conversions are the foundation everything else in the runtime stands on, and they
 * are full of specified corners. Each one is compared against upstream over a wide sample.
 *
 * No context is entered on either side, so both take the same "old parsing mode" branch that
 * upstream falls back to when nothing entered a context.
 */
class ConversionOracleTest {

    private fun sameDouble(a: Double, b: Double): Boolean =
        a.toRawBits() == b.toRawBits() || (a.isNaN() && b.isNaN())

    @Test
    fun toNumberOfAStringMatchesUpstream() {
        val strings = buildList {
            addAll(
                listOf(
                    // Empty and whitespace, including the exotic spaces the spec counts.
                    "", " ", "\t", "\n", "\r", "", "", " ", "﻿",
                    "  \t\n  ", " ", " ", " ", "　", " ",
                    // Plain decimals.
                    "0", "1", "-1", "+1", "1.5", "-1.5", ".5", "5.", "0.0", "-0", "-0.0",
                    "  42  ", "\t7\n",
                    // Exponents.
                    "1e3", "1E3", "1e+3", "1e-3", "1.5e300", "1e309", "1e-400", "1e", "e5",
                    // Hexadecimal, including the old signed form.
                    "0x10", "0X10", "-0x10", "+0x10", "0x", "0xg", "0x10 something",
                    "0xFFFFFFFF", "0x1FFFFFFFFFFFFF",
                    // Binary and octal, which the old mode does not accept.
                    "0b1", "0B1", "0o5", "0O5", "0b", "0o", "0b2", "0o9",
                    // Legacy octal-looking input.
                    "010", "089",
                    // Infinity and its near misses.
                    "Infinity", "+Infinity", "-Infinity", " Infinity ", "infinity",
                    "Infinit", "Infinityy", "Infinity1",
                    // Junk.
                    "abc", "1a", "a1", "1 2", "--1", "++1", "1-", "+", "-", ".", "..",
                    "1.2.3", "1,000", "null", "undefined", "true", "NaN",
                    // Long digit strings, where the rounding has to be right.
                    "1234567890123456789012345", "0.1234567890123456789012345",
                    "9007199254740993", "18446744073709551616",
                ),
            )
            // Every integer in a small range, plus random decimal spellings.
            for (i in -500..500) add(i.toString())
            val random = Random(31337)
            repeat(20_000) {
                val mantissa = random.nextLong(-1_000_000_000L, 1_000_000_000L)
                val exp = random.nextInt(-320, 320)
                add("${mantissa}e$exp")
            }
            repeat(5_000) {
                add("0x" + random.nextLong(0, 1L shl 48).toString(16))
            }
        }
        val failures = mutableListOf<String>()
        for (s in strings) {
            val expected = UpstreamScriptRuntime.toNumber(s)
            val actual = ScriptRuntime.toNumber(s)
            if (!sameDouble(expected, actual)) {
                failures.add("chars=${s.map { it.code }} expected=$expected actual=$actual")
                if (failures.size > 20) break
            }
        }
        assertTrue(strings.size > 25_000)
        assertEquals(emptyList(), failures, "toNumber(String) differs from upstream")
    }

    private fun doubleSample(): List<Double> = buildList {
        addAll(
            listOf(
                0.0, -0.0, 1.0, -1.0, 0.5, -0.5, 1.5, -1.5, 2.5, -2.5,
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
                java.lang.Double.MIN_NORMAL,
                2147483647.0, 2147483648.0, -2147483648.0, -2147483649.0,
                4294967295.0, 4294967296.0, 4294967297.0, -4294967296.0,
                1e9, 1e10, 1e20, 1e21, 1e300, 1e-300, 9007199254740992.0,
            ),
        )
        for (i in -70000..70000 step 13) add(i.toDouble())
        val random = Random(2718281)
        repeat(120_000) { add(Double.fromBits(random.nextLong())) }
        repeat(40_000) { add(random.nextLong(-1L shl 42, 1L shl 42).toDouble() / 3.0) }
    }

    @Test
    fun toIntegerMatchesUpstream() {
        val failures = mutableListOf<String>()
        for (d in doubleSample()) {
            val expected = UpstreamScriptRuntime.toInteger(d)
            val actual = ScriptRuntime.toInteger(d)
            if (!sameDouble(expected, actual)) {
                failures.add("bits=${d.toRawBits()} expected=$expected actual=$actual")
                if (failures.size > 20) break
            }
        }
        assertEquals(emptyList(), failures, "toInteger differs from upstream")
    }

    @Test
    fun toUint32MatchesUpstream() {
        val failures = mutableListOf<String>()
        for (d in doubleSample()) {
            val expected = UpstreamScriptRuntime.toUint32(d)
            val actual = ScriptRuntime.toUint32(d)
            if (expected != actual) {
                failures.add("bits=${d.toRawBits()} expected=$expected actual=$actual")
                if (failures.size > 20) break
            }
        }
        assertEquals(emptyList(), failures, "toUint32 differs from upstream")
    }

    @Test
    fun formatThenParseRoundTrips() {
        // Anything the formatter produces has to read back as the same double.
        val failures = mutableListOf<String>()
        val random = Random(11235)
        repeat(50_000) {
            val d = Double.fromBits(random.nextLong())
            if (d.isNaN() || d.isInfinite()) return@repeat
            val text = ScriptRuntime.numberToString(d, 10)
            val back = ScriptRuntime.toNumber(text)
            if (!sameDouble(d, back)) {
                failures.add("bits=${d.toRawBits()} text=$text back=$back")
            }
        }
        assertEquals(emptyList(), failures.take(20), "format then parse did not round trip")
    }

    @Test
    fun escapeStringStillMatchesUpstream() {
        val random = Random(4649)
        val failures = mutableListOf<String>()
        repeat(20_000) {
            val s = buildString {
                repeat(random.nextInt(0, 12)) { append(random.nextInt(0, 0x2000).toChar()) }
            }
            for (q in listOf('"', '\'')) {
                val expected = UpstreamScriptRuntime.escapeString(s, q)
                val actual = ScriptRuntime.escapeString(s, q)
                if (expected != actual) {
                    failures.add("chars=${s.map { it.code }} quote=$q")
                }
            }
        }
        assertEquals(emptyList(), failures.take(10), "escapeString differs from upstream")
    }
}
