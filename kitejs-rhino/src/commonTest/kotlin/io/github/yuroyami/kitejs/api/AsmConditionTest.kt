/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import io.github.yuroyami.kitejs.rhino.asmReports
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Integer arithmetic cannot expose its wrap through an uncoerced condition. */
class AsmConditionTest {
    private val inputs = listOf(
        Int.MIN_VALUE to 1, Int.MAX_VALUE to -1, -1 to 1, 0 to 0, 1 to 1,
    )
    private val flows = listOf("if", "while", "do", "for", "ternary")

    private fun flow(kind: String, condition: String): String = when (kind) {
        "if" -> "if ($condition) result = 1; else result = 2;"
        "while" -> "while ($condition) { result = 1; break; }"
        "do" -> "do { result = (result + 1) | 0; if ((result | 0) == 2) break; } while ($condition);"
        "for" -> "for (; $condition; ) { result = 1; break; }"
        else -> "result = ($condition) ? 1 : 2;"
    }

    private fun answer(kind: String, truthy: Boolean): Int = when (kind) {
        "while", "for" -> if (truthy) 1 else 0
        "do" -> if (truthy) 2 else 1
        else -> if (truthy) 1 else 2
    }

    private fun check(
        expression: String, value: (Int, Int) -> Long, compiled: Boolean,
        coerced: Boolean = false,
    ) {
        for (kind in flows) for (budget in listOf(0, 1_000_000)) for (enabled in listOf(false, true)) {
            KiteJs(Rhino) { asmJs = enabled; instructionBudget = budget }.use { js ->
                js.evaluate("""
                    var m = (function () {
                      "use asm";
                      function f(x, y) {
                        x = x | 0; y = y | 0;
                        var result = 0;
                        ${flow(kind, expression)}
                        return result | 0;
                      }
                      return { f: f };
                    })();
                """.trimIndent(), "asm-condition.js")
                for ((x, y) in inputs) {
                    val wide = value(x, y)
                    val truthy = if (coerced) wide.toInt() != 0 else wide != 0L
                    assertEquals(answer(kind, truthy), js.evaluate("m.f($x, $y)").asInt(),
                        "$kind ($expression) at $x,$y: asmJs=$enabled budget=$budget")
                }
                if (enabled) {
                    val report = js.asmReports.single()
                    assertEquals(compiled, report.compiled, "$kind ($expression): $report")
                    if (compiled) assertTrue(report.linked, "$report")
                    else assertTrue(report.reason.isNotEmpty(), "$report")
                }
                assertEquals(2, js.evaluate("1 + 1").asInt())
            }
        }
    }

    @Test
    fun uncoercedArithmeticConditionsFallBackInEveryControlFlow() {
        check("x + x", { x, _ -> x.toLong() + x }, compiled = false)
        check("x - y", { x, y -> x.toLong() - y }, compiled = false)
        check("x * 2", { x, _ -> x.toLong() * 2 }, compiled = false)
    }

    @Test
    fun explicitSignedAndUnsignedCoercionsKeepCompiling() {
        for (coercion in listOf("| 0", ">>> 0")) {
            check("(x + x) $coercion", { x, _ -> x.toLong() + x }, compiled = true, coerced = true)
            check("(x - y) $coercion", { x, y -> x.toLong() - y }, compiled = true, coerced = true)
            check("(x * 2) $coercion", { x, _ -> x.toLong() * 2 }, compiled = true, coerced = true)
        }
    }

    @Test
    fun existingIntegerConditionsRetainTheirTruthiness() {
        check("x | 0", { x, _ -> x.toLong() }, compiled = true, coerced = true)
        check("x >>> 0", { x, _ -> x.toLong() }, compiled = true, coerced = true)
        check("(x | 0) < (y | 0)", { x, y -> if (x < y) 1L else 0L }, compiled = true)
    }
}
