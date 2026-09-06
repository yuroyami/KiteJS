/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Case-insensitive matching over the code points where the platforms disagree with each other:
 * the dotted and dotless I, long s, sharp S, the three Greek sigmas, the Cherokee block, the
 * title-case digraphs and the Kelvin and Angstrom signs.
 *
 * The answers were recorded from upstream Rhino on the JVM. Running this on JS, iOS and Wasm is
 * what proves the generated case tables travel, instead of each host folding case its own way.
 */
class CaseFoldingSliceTest {

    private val points = intArrayOf(
        0x0130, 0x0131, 0x017F, 0x1E9E, 0x00DF, 0x03A3, 0x03C2, 0x03C3, 0x03B8, 0x03D1,
        0x0345, 0x1FBE, 0x2126, 0x212A, 0x212B, 0x13A0, 0xAB70, 0x0049, 0x0069, 0x00C5,
        0x00E5, 0x01C4, 0x01C5, 0x01C6, 0xFB00, 0x1E96, 0x0390, 0x1F88, 0x2170, 0x2160,
    )

    private fun hex(cp: Int): String = "\\u" + cp.toString(16).uppercase().padStart(4, '0')

    private fun eval(source: String): String {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            return ScriptRuntime.toString(cx.evaluateString(scope, source, "fold.js", 1, null))
        } finally {
            Context.exit()
        }
    }

    @Test
    fun everyPairFoldsTheWayUpstreamDoes() {
        val actual = StringBuilder()
        for (a in points) {
            for (b in points) {
                val r = eval("/" + hex(a) + "/i.test('" + hex(b) + "')")
                actual.append(if (r == "true") '1' else '0')
            }
        }
        assertEquals(EXPECTED_FOLD, actual.toString(), "case-insensitive matching differs from upstream")
    }

    @Test
    fun classesAndCaseMappingMatchUpstream() {
        val actual = StringBuilder()
        for (a in points) {
            val p = hex(a)
            for (source in listOf(
                "/[" + p + "]/i.test('\\u0130')",
                "/[" + p + "-" + p + "]/i.test('" + p + "')",
                "'" + p + "'.toUpperCase().length",
                "'" + p + "'.toLowerCase().length",
            )) {
                actual.append(eval(source)).append(';')
            }
        }
        assertEquals(EXPECTED_MAPS, actual.toString(), "class folding or case mapping differs from upstream")
    }

    private companion object {
        const val EXPECTED_FOLD = "100000000000000000000000000000010000000000000000000000000000001000000000000000000000000000000100000000000000000000000000000010000000000000000000000000000001110000000000000000000000000001110000000000000000000000000001110000000000000000000000000000001100000000000000000000000000001100000000000000000000000000000011000000000000000000000000000011000000000000000000000000000000100000000000000000000000000000010000000000000000000000000000001000000000000000000000000000000110000000000000000000000000000110000000000000000000000000000001100000000000000000000000000001100000000000000000000000000000011000000000000000000000000000011000000000000000000000000000000111000000000000000000000000000111000000000000000000000000000111000000000000000000000000000000100000000000000000000000000000010000000000000000000000000000001000000000000000000000000000000100000000000000000000000000000011000000000000000000000000000011"
        const val EXPECTED_MAPS = "true;true;1;2;false;true;1;1;false;true;1;1;false;true;1;1;false;true;2;1;false;true;1;1;false;true;1;1;false;true;1;1;false;true;1;1;false;true;1;1;false;true;1;1;false;true;1;1;false;true;1;1;false;true;1;1;false;true;1;1;false;true;1;1;false;true;1;1;false;true;1;1;false;true;1;1;false;true;1;1;false;true;1;1;false;true;1;1;false;true;1;1;false;true;1;1;false;true;2;1;false;true;2;1;false;true;3;1;false;true;2;1;false;true;1;1;false;true;1;1;"
    }
}
