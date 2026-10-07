/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import io.github.yuroyami.kitejs.rhino.asmReports
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A discarded import result must never be coerced; an explicit conversion must still run. */
class AsmImportResultTest {
    private fun parity(
        expected: String, functions: String, call: String, returned: String,
        setup: String = "", callbackPrelude: String = "",
    ) {
        for (budget in listOf(0, 1_000_000)) for (enabled in listOf(false, true)) {
            KiteJs(Rhino) { asmJs = enabled; instructionBudget = budget }.use { js ->
                js.evaluate("""
                    var calls = 0, conversions = 0, seen = [];
                    var heap = new ArrayBuffer(64);
                    $setup
                    var m = (function (s, imports, heap) {
                      "use asm";
                      var cb = imports.cb;
                      var round = s.Math.fround;
                      var H32 = new s.Int32Array(heap);
                      $functions
                      return { f: f };
                    })({ Math: Math, Int32Array: Int32Array }, {
                      cb: function (x) { calls++; seen.push(x); $callbackPrelude return $returned; }
                    }, heap);
                """.trimIndent(), "asm-import-result.js")
                assertEquals(expected, js.evaluate(call).asString(), "asmJs=$enabled budget=$budget")
                if (enabled) {
                    val report = js.asmReports.single()
                    assertTrue(report.compiled && report.linked, "$report")
                }
                assertEquals(2, js.evaluate("1 + 1").asInt())
            }
        }
    }

    @Test
    fun discardedObjectsSymbolsAndBigIntsNeverReadConversionHooks() {
        for (returned in listOf(
            "{ valueOf: function () { conversions++; return 9; } }",
            "{ valueOf: function () { conversions++; throw 'conversion'; } }",
            "{ get valueOf() { conversions++; throw 'get'; } }",
            "{ [Symbol.toPrimitive]: function () { conversions++; throw 'primitive'; } }",
            "Symbol('result')", "9n",
        )) {
            parity("4|1|0", "function f() { cb(); return 4; }",
                "m.f() + '|' + calls + '|' + conversions", returned)
        }
    }

    @Test
    fun commaOperandsAndBothLoopPositionsDiscardWithoutConversion() {
        val returned = "{ valueOf: function () { conversions++; throw 'conversion'; } }"
        parity("4|3|0|1,2,3", "function f() { (cb(1), (cb(2), cb(3))); return 4; }",
            "m.f() + '|' + calls + '|' + conversions + '|' + seen.join()", returned)
        parity("4|1|0", "function f() { return (cb(), 4) | 0; }",
            "m.f() + '|' + calls + '|' + conversions", returned)
        parity("2|3|0|0,1,1", """
            function f() {
              var i = 0;
              for (cb(0); (i | 0) < 2; cb(1)) { i = (i + 1) | 0; }
              return i | 0;
            }
        """.trimIndent(), "m.f() + '|' + calls + '|' + conversions + '|' + seen.join()", returned)
    }

    @Test
    fun explicitNumericResultsConvertExactlyOnce() {
        for (result in listOf("+cb()", "cb() | 0", "round(cb())")) {
            parity("9|1|1", "function f() { return $result; }",
                "m.f() + '|' + calls + '|' + conversions",
                "{ valueOf: function () { conversions++; return 9; } }")
        }
    }

    @Test
    fun discardedExplicitCoercionsRetainTheirEffectsAndErrors() {
        for (statement in listOf("+cb();", "cb() | 0;", "round(cb());", "(+cb(), cb());")) {
            val count = if (statement.startsWith("(")) 2 else 1
            parity("4|$count|1", "function f() { $statement return 4; }",
                "m.f() + '|' + calls + '|' + conversions",
                "{ valueOf: function () { conversions++; return 9; } }")
        }
        parity("2|3|3", """
            function f() {
              var i = 0;
              for (+cb(); (i | 0) < 2; +cb()) { i = (i + 1) | 0; }
              return i | 0;
            }
        """.trimIndent(), "m.f() + '|' + calls + '|' + conversions",
            "{ valueOf: function () { conversions++; return 9; } }")
        for (statement in listOf("+cb();", "cb() | 0;", "round(cb());")) {
            for (returned in listOf("Symbol('result')", "9n")) {
                parity("TypeError|1|0", "function f() { $statement return 4; }",
                    "var result = ''; try { m.f(); } catch (e) { result = e.name; } result + '|' + calls + '|' + conversions",
                    returned)
            }
            parity("conversion|1|1", "function f() { $statement return 4; }",
                "var result = ''; try { m.f(); } catch (e) { result = e; } result + '|' + calls + '|' + conversions",
                "{ valueOf: function () { conversions++; throw 'conversion'; } }")
        }
    }

    @Test
    fun aDiscardedCallbackMayReenterWithoutConvertingItsResult() = parity(
        "7|99|2|0",
        "function f(x) { x = x | 0; var saved = 0; saved = x; cb(x | 0); return saved | 0; }",
        "m.f(7) + '|' + answers.join() + '|' + calls + '|' + conversions",
        "{ valueOf: function () { conversions++; throw 'conversion'; } }",
        setup = "var inside = false, answers = [];",
        callbackPrelude = "if (!inside) { inside = true; try { answers.push(m.f(99)); } finally { inside = false; } }",
    )

    @Test
    fun heapReadsObserveChangesMadeDuringNumericResultConversion() = parity(
        "10|1|1|9",
        "function f() { var result = 0; result = cb() | 0; return (result + H32[0]) | 0; }",
        "m.f() + '|' + calls + '|' + conversions + '|' + new Int32Array(heap)[0]",
        "{ valueOf: function () { conversions++; new Int32Array(heap)[0] = 9; return 1; } }",
    )

    @Test
    fun detachingTheHeapDuringNumericConversionCannotLeaveStaleBytes() = parity(
        "1|1|1|true|9",
        "function f() { var result = 0; result = cb() | 0; return (result + (H32[0] | 0)) | 0; }",
        "m.f() + '|' + calls + '|' + conversions + '|' + heap.detached + '|' + new Int32Array(moved)[0]",
        "{ valueOf: function () { conversions++; moved = heap.transfer(); return 1; } }",
        setup = "var moved = null; new Int32Array(heap)[0] = 9;",
    )
}
