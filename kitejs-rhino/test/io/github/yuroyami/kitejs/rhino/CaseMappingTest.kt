/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

/** Case conversion comes from the engine's own UCD 16 tables, the same on every target (#136). */
class CaseMappingTest {

    private fun eval(source: String): String {
        val cx = Context.enter()
        try {
            val scope = cx.initStandardObjects()
            return ScriptRuntime.toString(cx.evaluateString(scope, source, "case.js", 1, null))
        } finally {
            Context.exit()
        }
    }

    @Test
    fun theFinalSigmaFollowsItsContext() {
        // Unicode, 3.13, Final_Sigma, as test262's special_casing_conditional.js checks it.
        assertEquals("σ", eval("'\\u03A3'.toLowerCase()"))
        assertEquals("aς", eval("'A\\u03A3'.toLowerCase()"))
        assertEquals("aσb", eval("'A\\u03A3B'.toLowerCase()"))
        assertEquals("a.ς", eval("'A.\\u03A3'.toLowerCase()"), "a case-ignorable character between")
        assertEquals("aς", eval("'A\\u03A3'.toLocaleLowerCase()"))
    }

    @Test
    fun fullMappingsAndUnicode16Letters() {
        assertEquals("SS", eval("'\\u00DF'.toUpperCase()"))
        assertEquals("FFI", eval("'\\uFB03'.toUpperCase()"))
        // Garay, Unicode 16: U+10D50 lowercases to U+10D70, which JDK 21 does not know.
        assertEquals("𐵰", eval("'\\uD803\\uDD50'.toLowerCase()"))
        assertEquals("𐵐", eval("'\\uD803\\uDD70'.toUpperCase()"))
        assertEquals("hello world", eval("'Hello World'.toLowerCase()"))
    }
}
