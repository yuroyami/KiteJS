/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import io.github.yuroyami.kitejs.rhino.asmReports
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Negative zero is a floating value until an explicit integer conversion erases its sign. */
class AsmNegativeZeroTest {
    private fun parity(body: String, exports: String, call: String, expected: String, compiled: Boolean = true) {
        for (budget in listOf(0, 1_000_000)) for (enabled in listOf(false, true)) {
            KiteJs(Rhino) { asmJs = enabled; instructionBudget = budget }.use { js ->
                js.evaluate("""
                    var m = (function (s) {
                      "use asm";
                      var round = s.Math.fround;
                      $body
                      return { $exports };
                    })({ Math: Math });
                """.trimIndent(), "asm-negative-zero.js")
                if (enabled) {
                    val report = js.asmReports.single()
                    assertEquals(compiled, report.compiled, "$report")
                    if (compiled) assertTrue(report.linked, "$report")
                    else assertTrue(report.reason.isNotEmpty(), "$report")
                }
                assertEquals(expected, js.evaluate(call).asString(), "asmJs=$enabled budget=$budget")
                assertEquals(2, js.evaluate("1 + 1").asInt())
            }
        }
    }

    @Test
    fun everyNegativeZeroLiteralSpellingKeepsTheSign() {
        for (literal in listOf("-0", "-0.0", "-0e0", "-(0)", "-0x0", "-0b0", "-0o0")) {
            parity("function f() { return $literal; }", "f: f",
                "Object.is(m.f(), -0) + '|' + 1 / m.f()", "true|-Infinity")
        }
    }

    @Test
    fun globalsAndLocalsKeepNegativeZeroInFloatingSlots() = parity(
        """
            var g = -0, gf = round(-0);
            function global() { return +g; }
            function globalFloat() { return round(gf); }
            function local() { var x = -0; return +x; }
            function localFloat() { var x = round(-0); return round(x); }
            function division() { var x = -0; return +(x / 1.0); }
        """.trimIndent(),
        "g: global, gf: globalFloat, l: local, lf: localFloat, d: division",
        "[m.g(), m.gf(), m.l(), m.lf(), m.d()].map(function (x) { return Object.is(x, -0) + ':' + 1 / x; }).join('|')",
        "true:-Infinity|true:-Infinity|true:-Infinity|true:-Infinity|true:-Infinity",
    )

    @Test
    fun froundOfANegativeZeroLiteralKeepsTheSign() = parity(
        "function f() { return round(-0); }", "f: f",
        "Object.is(m.f(), -0) + '|' + 1 / m.f()", "true|-Infinity",
    )

    @Test
    fun directTableAndNestedReturnsUseFloatingSignatures() = parity(
        """
            function negative(n) { n = n | 0; return -0; }
            function positive(n) { n = n | 0; return 0.0; }
            function direct(n) { n = n | 0; return +negative(n | 0); }
            function indirect(n) { n = n | 0; return +T[n & 1](n | 0); }
            function branch(n) { n = n | 0; if (n | 0) return -0; else return 0.0; }
            var T = [negative, positive];
        """.trimIndent(),
        "d: direct, t: indirect, b: branch",
        "[m.d(0), m.t(0), m.t(1), m.b(1), m.b(0)].map(function (x) { return Object.is(x, -0) + ':' + 1 / x; }).join('|')",
        "true:-Infinity|true:-Infinity|false:Infinity|true:-Infinity|false:Infinity",
    )

    @Test
    fun incompatibleIntegerAndNegativeZeroReturnsFallBack() = parity(
        "function f(n) { n = n | 0; if (n | 0) return -0; else return 0; }", "f: f",
        "Object.is(m.f(1), -0) + '|' + Object.is(m.f(0), 0)", "true|true", compiled = false,
    )

    @Test
    fun explicitIntegerConversionsStillEraseTheSign() {
        for (expression in listOf("-0 | 0", "-0 >>> 0")) {
            parity("function f() { return ($expression) | 0; }", "f: f",
                "Object.is(m.f(), 0) + '|' + 1 / m.f()", "true|Infinity", compiled = false)
        }
        parity("function f() { return ~~(-0) | 0; }", "f: f",
            "Object.is(m.f(), 0) + '|' + 1 / m.f()", "true|Infinity")
        parity("function f(n) { n = n | 0; return n | 0; }", "f: f",
            "Object.is(m.f(-0), 0) + '|' + 1 / m.f(-0)", "true|Infinity")
    }

    @Test
    fun positiveZeroAndOrdinaryIntegersKeepTheirExistingTypes() = parity(
        """
            var g = 0;
            function zero() { var x = 0; return (g + x) | 0; }
            function positive() { return 17; }
            function negative() { return -17; }
        """.trimIndent(),
        "z: zero, p: positive, n: negative",
        "Object.is(m.z(), 0) + '|' + 1 / m.z() + '|' + m.p() + '|' + m.n()",
        "true|Infinity|17|-17",
    )

    @Test
    fun singleExportsKeepTheirSignAtTheHostBoundary() {
        for (budget in listOf(0, 1_000_000)) for (enabled in listOf(false, true)) {
            KiteJs(Rhino) { asmJs = enabled; instructionBudget = budget }.use { js ->
                val f = js.evaluate("""
                    (function () { "use asm"; function f() { return -0; } return f; })();
                """.trimIndent()).asFunction()
                if (enabled) {
                    val report = js.asmReports.single()
                    assertTrue(report.compiled && report.linked, "$report")
                }
                val answer = f().asDouble()
                assertEquals(Long.MIN_VALUE, answer.toRawBits())
                assertEquals(Double.NEGATIVE_INFINITY, 1.0 / answer)
            }
        }
    }
}
