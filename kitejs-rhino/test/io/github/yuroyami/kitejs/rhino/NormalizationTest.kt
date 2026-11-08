/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `String.prototype.normalize` and `localeCompare` from the engine's own UCD 16 tables, the same on
 * every target (#135). The rows are from the Unicode conformance file NormalizationTest.txt, which
 * the whole engine passed when the tables were made.
 */
class NormalizationTest {

    private fun eval(source: String): String {
        val cx = Context.enter()
        try {
            val scope = cx.initStandardObjects()
            return ScriptRuntime.toString(cx.evaluateString(scope, source, "normalize.js", 1, null))
        } finally {
            Context.exit()
        }
    }

    private fun str(vararg cps: Int): String {
        val sb = StringBuilder()
        for (cp in cps) {
            if (cp >= 0x10000) {
                sb.append((0xD800 + ((cp - 0x10000) ushr 10)).toChar()).append((0xDC00 + ((cp - 0x10000) and 0x3FF)).toChar())
            } else {
                sb.append(cp.toChar())
            }
        }
        return sb.toString()
    }

    private fun check(source: IntArray, nfc: IntArray, nfd: IntArray, nfkc: IntArray, nfkd: IntArray) {
        val s = str(*source)
        assertEquals(str(*nfc), Normalizer.normalize(s, Normalizer.Form.NFC), "NFC")
        assertEquals(str(*nfd), Normalizer.normalize(s, Normalizer.Form.NFD), "NFD")
        assertEquals(str(*nfkc), Normalizer.normalize(s, Normalizer.Form.NFKC), "NFKC")
        assertEquals(str(*nfkd), Normalizer.normalize(s, Normalizer.Form.NFKD), "NFKD")
    }

    @Test
    fun theFormsOfAnAccentedLetter() {
        assertEquals("1", eval("'e\\u0301'.normalize().length"), "NFC is the default")
        assertEquals("1", eval("'e\\u0301'.normalize('NFC').length"))
        assertEquals("2", eval("'\\u00E9'.normalize('NFD').length"))
        assertEquals("true", eval("'\\u00E9'.normalize(undefined) === '\\u00E9'"))
        assertEquals("RangeError", eval("try { 'a'.normalize('nfc') } catch (e) { e.name }"))
    }

    @Test
    fun conformanceRows() {
        // 1E0A 0323: canonical ordering moves the dot below before the dot above.
        check(intArrayOf(0x1E0A, 0x0323), intArrayOf(0x1E0C, 0x0307), intArrayOf(0x0044, 0x0323, 0x0307),
            intArrayOf(0x1E0C, 0x0307), intArrayOf(0x0044, 0x0323, 0x0307))
        // A Hangul syllable with a trailing consonant, by the algorithm of Unicode 3.12.
        check(intArrayOf(0xAC01), intArrayOf(0xAC01), intArrayOf(0x1100, 0x1161, 0x11A8),
            intArrayOf(0xAC01), intArrayOf(0x1100, 0x1161, 0x11A8))
        // The ligature ffi differs only in the compatibility forms.
        check(intArrayOf(0xFB03), intArrayOf(0xFB03), intArrayOf(0xFB03), intArrayOf(0x66, 0x66, 0x69), intArrayOf(0x66, 0x66, 0x69))
        // 1E9B 0323: long s with dot above, whose NFKC composes past the compatibility mapping.
        check(intArrayOf(0x1E9B, 0x0323), intArrayOf(0x1E9B, 0x0323), intArrayOf(0x017F, 0x0323, 0x0307),
            intArrayOf(0x1E69), intArrayOf(0x0073, 0x0323, 0x0307))
        // U+0958 is a composition exclusion: NFC leaves it decomposed.
        check(intArrayOf(0x0958), intArrayOf(0x0915, 0x093C), intArrayOf(0x0915, 0x093C),
            intArrayOf(0x0915, 0x093C), intArrayOf(0x0915, 0x093C))
        // A mark blocked by one of the same class does not compose with the starter.
        check(intArrayOf(0x0061, 0x0305, 0x0300), intArrayOf(0x0061, 0x0305, 0x0300), intArrayOf(0x0061, 0x0305, 0x0300),
            intArrayOf(0x0061, 0x0305, 0x0300), intArrayOf(0x0061, 0x0305, 0x0300))
        // Outside the BMP: U+1D15E, a musical half note, decomposes to two code points.
        check(intArrayOf(0x1D15E), intArrayOf(0x1D157, 0x1D165), intArrayOf(0x1D157, 0x1D165),
            intArrayOf(0x1D157, 0x1D165), intArrayOf(0x1D157, 0x1D165))
    }

    @Test
    fun canonicallyEquivalentStringsCompareEqual() {
        // ECMAScript 2015, 21.1.3.10, as test262's 15.5.4.9_CE.js checks it.
        assertEquals("0", eval("'\\u00E9'.localeCompare('e\\u0301')"))
        assertEquals("0", eval("'\\u1E0B\\u0323'.localeCompare('\\u1E0D\\u0307')"))
        assertEquals("-1", eval("'a'.localeCompare('b')"))
    }
}
