/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.test.Test
import kotlin.test.assertEquals
import io.github.yuroyami.kitejs.Characters
import kotlin.test.assertTrue

/**
 * Walks every code point and compares the generated tables with the `java.lang.Character` they
 * were read from. Rhino asks Character these exact questions, so this is what makes the regexp
 * engine's answers on JS, iOS and Wasm the same as upstream's on the JVM.
 *
 * Regenerate with `tools/unicode/UnicodeTablesGenerator.java` if this ever fails.
 */
class UnicodeTablesOracleTest {

    private val max = 0x10FFFF

    private fun sweep(name: String, expected: (Int) -> Boolean, actual: (Int) -> Boolean) {
        var mismatches = 0
        var firstBad = -1
        for (cp in 0..max) {
            if (expected(cp) != actual(cp)) {
                if (firstBad < 0) firstBad = cp
                mismatches++
            }
        }
        assertEquals(0, mismatches, "$name differs from java.lang.Character, first at U+${firstBad.toString(16)}")
    }

    @Test
    fun booleanTablesMatchTheJdk() {
        sweep("Alphabetic", { Character.isAlphabetic(it) }, { UnicodeTables.inRanges(UnicodeTables.ALPHABETIC, it) })
        sweep("Lowercase", { Character.isLowerCase(it) }, { UnicodeTables.inRanges(UnicodeTables.LOWERCASE, it) })
        sweep("Uppercase", { Character.isUpperCase(it) }, { UnicodeTables.inRanges(UnicodeTables.UPPERCASE, it) })
        sweep("White_Space", { Character.isSpaceChar(it) || Character.isWhitespace(it) }, { UnicodeTables.inRanges(UnicodeTables.WHITE_SPACE, it) })
        sweep("ID_Start", { Character.isUnicodeIdentifierStart(it) }, { UnicodeTables.inRanges(UnicodeTables.ID_START, it) })
        sweep("ID_Continue", { Character.isUnicodeIdentifierPart(it) }, { UnicodeTables.inRanges(UnicodeTables.ID_CONTINUE, it) })
        sweep("Hex_Digit", { Character.digit(it, 16) != -1 }, { UnicodeTables.inRanges(UnicodeTables.HEX_DIGIT, it) })
        sweep("JavaIdentifierStart", { Character.isJavaIdentifierStart(it) }, { UnicodeTables.inRanges(UnicodeTables.JAVA_IDENTIFIER_START, it) })
        sweep("JavaIdentifierPart", { Character.isJavaIdentifierPart(it) }, { UnicodeTables.inRanges(UnicodeTables.JAVA_IDENTIFIER_PART, it) })
    }

    @Test
    fun generalCategoryMatchesTheJdk() {
        var mismatches = 0
        var firstBad = -1
        for (cp in 0..max) {
            val actual = UnicodeTables.runValue(UnicodeTables.CATEGORY_STARTS, UnicodeTables.CATEGORY_VALUES, cp)
            if (actual != Character.getType(cp)) {
                if (firstBad < 0) firstBad = cp
                mismatches++
            }
        }
        assertEquals(0, mismatches, "general category differs, first at U+${firstBad.toString(16)}")
    }

    @Test
    fun scriptMatchesTheJdk() {
        val scripts = Character.UnicodeScript.entries.toTypedArray()
        var mismatches = 0
        var firstBad = -1
        for (cp in 0..max) {
            val actual = UnicodeTables.runValue(UnicodeTables.SCRIPT_STARTS, UnicodeTables.SCRIPT_VALUES, cp)
            if (scripts[actual] != Character.UnicodeScript.of(cp)) {
                if (firstBad < 0) firstBad = cp
                mismatches++
            }
        }
        assertEquals(0, mismatches, "script differs, first at U+${firstBad.toString(16)}")
    }

    @Test
    fun caseMappingsMatchTheJdk() {
        var upperBad = -1
        var lowerBad = -1
        for (cp in 0..max) {
            if (UnicodeTables.mapCase(UnicodeTables.UPPER_KEYS, UnicodeTables.UPPER_DELTAS, cp) != Character.toUpperCase(cp) && upperBad < 0) {
                upperBad = cp
            }
            if (UnicodeTables.mapCase(UnicodeTables.LOWER_KEYS, UnicodeTables.LOWER_DELTAS, cp) != Character.toLowerCase(cp) && lowerBad < 0) {
                lowerBad = cp
            }
        }
        assertEquals(-1, upperBad, "toUpperCase differs, first at U+${upperBad.toString(16)}")
        assertEquals(-1, lowerBad, "toLowerCase differs, first at U+${lowerBad.toString(16)}")
    }

    @Test
    fun scriptNamesMatchTheJdk() {
        val scripts = Character.UnicodeScript.entries.toTypedArray()
        // Every name the table knows has to resolve to the same script the JDK resolves it to.
        for ((name, ordinal) in UnicodeTables.SCRIPT_NAMES) {
            assertEquals(Character.UnicodeScript.forName(name), scripts[ordinal], "script name $name")
        }
        // And every script the JDK has needs a name in the table.
        for (script in scripts) {
            assertTrue(UnicodeTables.SCRIPT_NAMES.containsValue(script.ordinal), "no name for ${script.name}")
        }
    }

    @Test
    fun scriptLookupMatchesForName() {
        val scripts = Character.UnicodeScript.entries.toTypedArray()
        // Names the JDK accepts, in every casing, plus names it rejects.
        val candidates = buildList {
            for (script in scripts) {
                add(script.name)
                add(script.name.lowercase())
                add(script.name.replaceFirstChar { it.uppercase() }.lowercase().replaceFirstChar { it.uppercase() })
            }
            addAll(UnicodeTables.SCRIPT_NAMES.keys)
            addAll(listOf("Latn", "latn", "LATN", "NotAScript", "", "Old_North_Arabian", "OldNorthArabian", "Latin_", "_Latin"))
        }
        for (name in candidates) {
            val expected = try { scripts.indexOf(Character.UnicodeScript.forName(name)) } catch (e: IllegalArgumentException) { -1 }
            assertEquals(expected, Characters.scriptForName(name), "scriptForName(\"$name\")")
        }
    }
}
