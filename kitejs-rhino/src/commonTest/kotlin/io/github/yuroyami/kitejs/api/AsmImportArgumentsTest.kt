/* This Source Code Form is subject to the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import io.github.yuroyami.kitejs.rhino.asmReports
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Foreign calls turn integer slots into the Number their signedness describes. */
class AsmImportArgumentsTest {
    private fun source(functions: String, imports: String): String = """
        var seen = [];
        var m = (function (s, f) {
          "use asm";
          var cb = f.cb;
          var round = s.Math.fround;
          var abs = s.Math.abs;
          $functions
          return { main: main };
        })({ Math: Math }, $imports);
    """.trimIndent()

    private fun parity(
        expected: String, functions: String, call: String,
        callback: String = "function () { seen.push(Array.prototype.join.call(arguments, ',')); return 0; }",
        compiled: Boolean = true,
    ) {
        for (budget in listOf(0, 1_000_000)) for (enabled in listOf(false, true)) {
            KiteJs(Rhino) { asmJs = enabled; instructionBudget = budget }.use { js ->
                js.evaluate(source(functions, "{ cb: $callback }"), "asm-import-arguments.js")
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
    fun signedAndUnsignedBoundaryValuesReachJavaScriptExactly() = parity(
        "0|0|2147483647|2147483647|-2147483648|2147483648|-1|4294967295",
        "function main(x) { x = x | 0; cb(x | 0); cb(x >>> 0); return 0; }",
        "[0, 2147483647, 2147483648, 4294967295].forEach(function (x) { m.main(x); }); seen.join('|')",
    )

    @Test
    fun unsignedLiteralsAndAbsoluteValuesKeepTheirReading() = parity(
        "2147483648,4294967295,2147483648",
        "function main(x) { x = x | 0; cb(2147483648, 4294967295, abs(x | 0)); return 0; }",
        "m.main(-2147483648); seen.join('|')",
    )

    @Test
    fun mixedArgumentsKeepTheirOrderAtEveryUnsignedFlagPosition() {
        for (unsignedAt in 0 until 12) {
            val expressions = List(12) { i ->
                when {
                    i == unsignedAt -> "x >>> 0"
                    i % 3 == 0 -> "$i | 0"
                    i % 3 == 1 -> "+(d + $i.0)"
                    else -> "round(f + round($i.0))"
                }
            }
            val expected = List(12) { i ->
                when {
                    i == unsignedAt -> "4294967295"
                    i % 3 == 0 -> "$i"
                    i % 3 == 1 -> "${i + 1.25}"
                    else -> "${i + 2.5}"
                }
            }.joinToString(",")
            parity(expected,
                "function main(x, d, f) { x = x | 0; d = +d; f = round(f); cb(${expressions.joinToString(",")}); return 0; }",
                "m.main(-1, 1.25, 2.5); seen.join('|')")
        }
        parity(List(12) { "4294967295" }.joinToString(","),
            "function main(x) { x = x | 0; cb(${List(12) { "x >>> 0" }.joinToString(",")}); return 0; }",
            "m.main(-1); seen.join('|')")
    }

    @Test
    fun hostCallbacksSeeExactUnsignedNumbersAndFloatingZeroSign() {
        for (budget in listOf(0, 1_000_000)) for (enabled in listOf(false, true)) {
            val observed = mutableListOf<List<Double>>()
            KiteJs(Rhino) { asmJs = enabled; instructionBudget = budget }.use { js ->
                js.global.function("record") { args -> observed.add(args.map { it.asDouble() }); 0 }
                js.evaluate(source("""
                    function main(x, d, f) {
                      x = x | 0; d = +d; f = round(f);
                      cb(x | 0, x >>> 0, +d, round(f), 2147483648, 4294967295);
                      return 0;
                    }
                """.trimIndent(), "{ cb: record }"))
                if (enabled) {
                    val report = js.asmReports.single()
                    assertTrue(report.compiled && report.linked, "$report")
                }
                for (x in listOf(0L, 2147483647L, 2147483648L, 4294967295L)) {
                    js.evaluate("m.main($x, 1.25, -0)")
                    val args = observed.last()
                    assertEquals(x.toInt().toDouble(), args[0])
                    assertEquals(x.toDouble(), args[1])
                    assertEquals(1.25, args[2])
                    assertEquals(Long.MIN_VALUE, args[3].toRawBits())
                    assertEquals(2147483648.0, args[4])
                    assertEquals(4294967295.0, args[5])
                }
                assertEquals(4, observed.size)
            }
        }
    }

    @Test
    fun aCallbackMayReenterAfterReceivingAnUnsignedArgument() = parity(
        "-1|4294967295|2147483648",
        "function main(x) { x = x | 0; var saved = 0; saved = x; cb(x >>> 0); return saved | 0; }",
        "var inside = false; var result = m.main(-1); result + '|' + seen.join('|')",
        callback = """
            function (x) {
              seen.push(x);
              if (!inside) { inside = true; try { m.main(2147483648); } finally { inside = false; } }
              return 0;
            }
        """.trimIndent(),
    )

    @Test
    fun tooManyArgumentsStillFallBackWithoutTruncatingValues() = parity(
        List(13) { "4294967295" }.joinToString(","),
        "function main(x) { x = x | 0; cb(${List(13) { "x >>> 0" }.joinToString(",")}); return 0; }",
        "m.main(-1); seen.join('|')", compiled = false,
    )
}
