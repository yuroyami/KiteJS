/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import io.github.yuroyami.kitejs.rhino.asmReports
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Signature inference and reachable return paths must agree, including field-held AST nodes. */
class AsmReturnTest {
    private fun parity(expected: String, functions: String, exports: String, call: String, compiled: Boolean = true) {
        for (budget in listOf(0, 1_000_000)) for (enabled in listOf(false, true)) {
            KiteJs(Rhino) { asmJs = enabled; instructionBudget = budget }.use { js ->
                js.evaluate("""
                    var m = (function (s) {
                      "use asm";
                      var fround = s.Math.fround;
                      var events = 0;
                      $functions
                      function count() { return events | 0; }
                      return { $exports, count: count };
                    })({ Math: Math });
                """.trimIndent(), "asm-return.js")
                if (enabled) {
                    val report = js.asmReports.single()
                    assertEquals(compiled, report.compiled, "$report")
                    if (compiled) assertTrue(report.linked, "module must link: $report")
                    else assertTrue(report.reason.isNotEmpty(), "rejection must explain the return contract")
                }
                assertEquals(expected, js.evaluate(call).asString(), "asmJs=$enabled budget=$budget")
                assertEquals(2, js.evaluate("1 + 1").asInt())
            }
        }
    }

    @Test
    fun ifElseReturnsKeepIntegerDoubleAndFloatSignatures() = parity(
        "3,4|5.25,10.5|1.25,1.75|-Infinity,-Infinity",
        """
            function i(n) { n = n | 0; if (n | 0) { return 3 | 0; } else { return 4 | 0; } }
            function d(n, x, y) {
              n = n | 0; x = +x; y = +y;
              if (n | 0) { return +x; } else { return +(y * 1.5); }
            }
            function f(n, x) {
              n = n | 0; x = fround(x);
              if (n | 0) { return fround(x); } else { return fround(x + fround(0.5)); }
            }
        """.trimIndent(),
        "i: i, d: d, f: f",
        "m.i(1) + ',' + m.i(0) + '|' + m.d(1, 5.25, 7) + ',' + m.d(0, 5.25, 7) + " +
            "'|' + m.f(1, 1.25) + ',' + m.f(0, 1.25) + '|' + 1 / m.d(1, -0, 7) + ',' + 1 / m.f(1, -0)",
    )

    @Test
    fun switchLoopsAndLabelsContributeTheirNestedReturns() = parity(
        "7,8,9,11,11|12,13|14,15|16,17|19,18|20,21",
        """
            function choose(n) {
              n = n | 0;
              switch (n | 0) {
                case 0: return 7 | 0;
                case 1: { return 8 | 0; }
                case 3:
                case 4: return 11 | 0;
                default: return 9 | 0;
              }
            }
            function once(n) {
              n = n | 0;
              do { if (n | 0) return 12 | 0; else return 13 | 0; } while (n | 0);
            }
            function forever(n) {
              n = n | 0;
              for (;;) { if (n | 0) return 14 | 0; else return 15 | 0; }
            }
            function labeled(n) {
              n = n | 0;
              outer: { if (n | 0) return 16 | 0; else return 17 | 0; }
            }
            function breaking(n) {
              n = n | 0;
              outer: { if (n | 0) break outer; return 18 | 0; }
              return 19 | 0;
            }
            function whileReturn(n) {
              n = n | 0;
              while (n | 0) { return 20 | 0; }
              return 21 | 0;
            }
        """.trimIndent(),
        "choose: choose, once: once, forever: forever, labeled: labeled, breaking: breaking, w: whileReturn",
        "[m.choose(0), m.choose(1), m.choose(2), m.choose(3), m.choose(4)].join() + '|' + " +
            "m.once(1) + ',' + m.once(0) + '|' + m.forever(1) + ',' + m.forever(0) + '|' + " +
            "m.labeled(1) + ',' + m.labeled(0) + '|' + m.breaking(1) + ',' + m.breaking(0) + '|' + m.w(1) + ',' + m.w(0)",
    )

    @Test
    fun directAndTableCallsUseTheNestedReturnSignature() = parity(
        "5.75,6.5|5.75,6.5",
        """
            function integer(n) {
              n = n | 0;
              if (n | 0) return (n + 3) | 0; else return 4 | 0;
            }
            function double(n, x) {
              n = n | 0; x = +x;
              if (n | 0) return +(x + 0.5); else return +(x * 2.0);
            }
            function direct(n, x) {
              n = n | 0; x = +x;
              var saved = 0, held = 0.0;
              saved = integer(n | 0) | 0;
              held = +double(n | 0, +x);
              return +(+(saved | 0) + held);
            }
            function table(n, x) {
              n = n | 0; x = +x;
              var saved = 0, held = 0.0;
              saved = I[n & 1](n | 0) | 0;
              held = +D[n & 1](n | 0, +x);
              return +(+(saved | 0) + held);
            }
            var I = [integer, integer], D = [double, double];
        """.trimIndent(),
        "direct: direct, table: table",
        "m.direct(1, 1.25) + ',' + m.direct(0, 1.25) + '|' + m.table(1, 1.25) + ',' + m.table(0, 1.25)",
    )

    @Test
    fun aSingleFunctionExportKeepsItsNestedReturn() {
        for (budget in listOf(0, 1_000_000)) for (enabled in listOf(false, true)) {
            KiteJs(Rhino) { asmJs = enabled; instructionBudget = budget }.use { js ->
                val function = js.evaluate("""
                    (function () {
                      "use asm";
                      function f(n, x) {
                        n = n | 0; x = +x;
                        if (n | 0) return +x; else return +(x + 1.0);
                      }
                      return f;
                    })();
                """.trimIndent()).asFunction()
                if (enabled) {
                    val report = js.asmReports.single()
                    assertTrue(report.compiled && report.linked, "single export must compile: $report")
                }
                assertEquals(2.5, function(1, 2.5).asDouble())
                assertEquals(3.5, function(0, 2.5).asDouble())
                assertEquals(Double.NEGATIVE_INFINITY, 1.0 / function(1, -0.0).asDouble())
                assertTrue(function(1).asDouble().isNaN())
                assertEquals(2, js.evaluate("1 + 1").asInt())
            }
        }
    }

    @Test
    fun mixedNestedReturnTypesRejectCompilation() {
        for ((body, expected) in listOf(
            "if (n | 0) return 3 | 0; else return +4.5;" to "3,4.5",
            "if (n | 0) return 3 | 0; return +4.5;" to "3,4.5",
            "switch (n | 0) { case 1: return 3 | 0; default: return +4.5; }" to "3,4.5",
            "do { if (n | 0) return 3 | 0; return +4.5; } while (0);" to "3,4.5",
            "if (n | 0) return; else return 3 | 0;" to "undefined,3",
            "if (n | 0) return fround(x); else return +x;" to "1.25,1.25",
        )) {
            parity(expected, "function f(n, x) { n = n | 0; x = +x; $body }", "f: f",
                "String(m.f(1, 1.25)) + ',' + String(m.f(0, 1.25))", compiled = false)
        }
    }

    @Test
    fun anUncoercedUnsignedLiteralCannotBecomeASignedReturn() = parity(
        "4294967295,2147483648",
        "function f(n) { n = n | 0; if (n | 0) return 4294967295; return 2147483648; }",
        "f: f", "String(m.f(1)) + ',' + String(m.f(0))", compiled = false,
    )

    @Test
    fun aReachableTypedFallthroughRunsAsOrdinaryJavaScript() {
        for ((body, expected) in listOf(
            "if (n | 0) return 3 | 0;" to "3,undefined|0",
            "if (n | 0) return +4.5;" to "4.5,undefined|0",
            "switch (n | 0) { case 1: return 3 | 0; }" to "3,undefined|0",
            "while (n | 0) { return 3 | 0; }" to "3,undefined|0",
            "do { if (n | 0) return 3 | 0; } while (0);" to "3,undefined|0",
            "outer: { if (n | 0) break outer; return 3 | 0; }" to "undefined,3|0",
            "if (n | 0) return 3 | 0; events = (events + 1) | 0;" to "3,undefined|1",
        )) {
            parity(expected, "function f(n) { n = n | 0; $body }", "f: f",
                "String(m.f(1)) + ',' + String(m.f(0)) + '|' + m.count()", compiled = false)
        }
    }

    @Test
    fun unreachableTailCodeAndVoidFallthroughKeepTheirContracts() = parity(
        "3,4|undefined,undefined,undefined|7",
        """
            function returning(n) {
              n = n | 0;
              if (n | 0) return 3 | 0; else return 4 | 0;
              events = (events + 1000) | 0;
            }
            function bare(n) {
              n = n | 0;
              if (n | 0) { events = (events + 1) | 0; return; }
              else { events = (events + 2) | 0; return; }
            }
            function noReturn(n) {
              n = n | 0;
              var i = 0;
              for (i = 0; (i | 0) < (n | 0); i = (i + 1) | 0) {}
              events = (events + i) | 0;
            }
        """.trimIndent(),
        "returning: returning, bare: bare, noReturn: noReturn",
        "m.returning(1) + ',' + m.returning(0) + '|' + String(m.bare(1)) + ',' + " +
            "String(m.bare(0)) + ',' + String(m.noReturn(4)) + '|' + m.count()",
    )
}
