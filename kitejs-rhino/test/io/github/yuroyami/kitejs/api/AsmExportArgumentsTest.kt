/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import io.github.yuroyami.kitejs.rhino.asmReports
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Omitting an export parameter supplies undefined, while an explicit null remains null. */
class AsmExportArgumentsTest {

    private fun bothWays(body: (KiteJs) -> Unit) {
        for (enabled in listOf(false, true)) {
            KiteJs(Rhino) { asmJs = enabled }.use { js ->
                js.evaluate(
                    """
                    var m = (function (s) {
                      "use asm";
                      var fround = s.Math.fround;
                      function d(x) { x = +x; return +x; }
                      function f(x) { x = fround(x); return fround(x); }
                      function i(x) { x = x | 0; return x | 0; }
                      function mixed(x, y) { x = x | 0; y = +y; return +(+(x | 0) + y); }
                      return { d: d, f: f, i: i, mixed: mixed };
                    })({ Math: Math });
                    function describe(value) {
                      if (value === 0 && 1 / value === -Infinity) return '-0';
                      return String(value);
                    }
                    """.trimIndent(),
                    "asm-arguments.js",
                )
                if (enabled) {
                    val report = js.asmReports.single()
                    assertTrue(report.compiled, "module must compile: $report")
                    assertTrue(report.linked, "module must link: $report")
                }
                body(js)
                assertEquals(2, js.evaluate("1 + 1").asInt())
            }
        }
    }

    @Test
    fun missingAndNullishArgumentsKeepTheirNumericConversions() = bothWays { js ->
        for ((arguments, floating, integer) in listOf(
            Triple("", "NaN", "0"), Triple("undefined", "NaN", "0"), Triple("null", "0", "0"),
            Triple("-0", "-0", "0"), Triple("1.25", "1.25", "1"), Triple("-1.25", "-1.25", "-1"),
            Triple("Infinity", "Infinity", "0"), Triple("NaN", "NaN", "0"),
        )) {
            for (name in listOf("d", "f", "i")) {
                val expected = if (name == "i") integer else floating
                assertEquals(expected, js.evaluate("describe(m.$name($arguments))").asString(), "$name($arguments)")
            }
        }
    }

    @Test
    fun laterMissingArgumentsAndExtraArgumentsUseTheSameBoundary() = bothWays { js ->
        for (arguments in listOf("", "7", "7, undefined", "undefined, undefined")) {
            assertTrue(js.evaluate("Number.isNaN(m.mixed($arguments))").asBoolean(), arguments)
        }
        for ((arguments, expected) in listOf(
            "7, null" to 7.0, "null, null" to 0.0, "undefined, 1.5" to 1.5, "7, 1.5" to 8.5,
        )) {
            assertEquals(expected, js.evaluate("m.mixed($arguments)").asDouble(), arguments)
        }
        assertEquals(1.25, js.evaluate(
            "var extraEffects = 0; m.d(1.25, { valueOf: function () { extraEffects++; throw new Error('unused'); } })",
        ).asDouble())
        assertEquals(0, js.global["extraEffects"].asInt())
        assertTrue(js.evaluate("Number.isNaN(Reflect.apply(m.d, null, []))").asBoolean())
        assertTrue(js.evaluate("Number.isNaN(m.f.call(null))").asBoolean())
    }

    @Test
    fun hostHandlesAndBoundExportsPreserveAnOmittedArgument() = bothWays { js ->
        val exports = js.global["m"].asObject()
        for (name in listOf("d", "f")) {
            val function = exports[name].asFunction()
            assertTrue(function().asDouble().isNaN(), name)
            assertTrue(function(JsUndefined).asDouble().isNaN(), name)
            assertTrue(function(null).asDouble().isNaN(), name)
            assertEquals(0.0, function(JsValue.nullValue).asDouble(), name)
            assertEquals(Double.NEGATIVE_INFINITY, 1.0 / function(-0.0).asDouble(), name)
            assertEquals(1.25, function(1.25).asDouble(), name)
            assertTrue(function.callOn(js.global).asDouble().isNaN(), name)
            assertTrue(function.bind(null)().asDouble().isNaN(), name)
        }
        val mixed = exports["mixed"].asFunction()
        assertTrue(mixed(7).asDouble().isNaN())
        assertTrue(mixed(7, null).asDouble().isNaN())
        assertEquals(7.0, mixed(7, JsValue.nullValue).asDouble())
        assertEquals(8.5, mixed(7, 1.5).asDouble())
        assertEquals(0, exports["i"].asFunction()().asInt())
    }
}
