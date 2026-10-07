/* This Source Code Form is subject to the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import io.github.yuroyami.kitejs.rhino.asmReports
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An export must refer to the final binding, not an earlier function with the same name. */
class AsmBindingTest {
    private fun parity(expected: String, body: String, call: String, compiled: Boolean = false) {
        for (budget in listOf(0, 1_000_000)) for (enabled in listOf(false, true)) {
            KiteJs(Rhino) { asmJs = enabled; instructionBudget = budget }.use { js ->
                js.evaluate("""
                    var calls = 0;
                    var m = (function (stdlib, imports, heap) {
                      "use asm";
                      $body
                    })({ Math: Math, Int32Array: Int32Array },
                       { cb: function () { calls++; return 7; } }, new ArrayBuffer(64));
                """.trimIndent(), "asm-binding.js")
                assertEquals(expected, js.evaluate(call).asString(), "asmJs=$enabled budget=$budget")
                if (enabled) {
                    val report = js.asmReports.single()
                    assertEquals(compiled, report.compiled, "$report")
                    if (compiled) assertTrue(report.linked, "$report")
                    else assertTrue(report.reason.isNotEmpty(), "$report")
                }
                assertEquals(2, js.evaluate("1 + 1").asInt())
            }
        }
    }

    @Test
    fun numericReplacementsInEitherOrderKeepTheirExportedValues() {
        for (declarations in listOf(
            "function f() { return 3; } var f = 7;",
            "var f = 7; function f() { return 3; }",
        )) {
            parity("number|7", "$declarations return { value: f };", "typeof m.value + '|' + m.value")
            parity("number|7", "$declarations return f;", "typeof m + '|' + m")
        }
        parity("number|-Infinity", "function f() { return 3; } var f = -0; return { value: f };",
            "typeof m.value + '|' + 1 / m.value")
    }

    @Test
    fun importedFunctionsReplaceTheBindingWithoutCallingTheOldBody() = parity(
        "function|7|1",
        "function f() { return 3; } var f = imports.cb; return { value: f };",
        "typeof m.value + '|' + m.value() + '|' + calls",
    )

    @Test
    fun aMathImportKeepsItsActualFunction() = parity(
        "function|1.25",
        "function f() { return 3; } var f = stdlib.Math.fround; return f;",
        "typeof m + '|' + m(1.25)",
    )

    @Test
    fun viewsAndTablesCannotBeExportedAsAnEarlierFunction() {
        parity("object|16",
            "function H() { return 3; } var H = new stdlib.Int32Array(heap); return { value: H };",
            "typeof m.value + '|' + m.value.length")
        parity("object|true|2",
            "function T() { return 3; } function g() { return 7; } var T = [g, g]; return { value: T };",
            "typeof m.value + '|' + Array.isArray(m.value) + '|' + m.value.length")
    }

    @Test
    fun uniquelyNamedFunctionsAndRenamedExportsStillCompile() = parity(
        "function|10,11",
        """
            var data = 7;
            function f(x) { x = x | 0; return (data + x) | 0; }
            function g(x) { x = x | 0; return f(x | 0) | 0; }
            var T = [f, g];
            function table(x) { x = x | 0; return T[x & 1](x | 0) | 0; }
            return { renamed: table };
        """.trimIndent(),
        "typeof m.renamed + '|' + m.renamed(3) + ',' + m.renamed(4)", compiled = true,
    )
}
