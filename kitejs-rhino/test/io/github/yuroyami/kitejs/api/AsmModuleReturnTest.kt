/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import io.github.yuroyami.kitejs.rhino.asmReports
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Module returns end initialization, while ordinary declaration hoisting remains observable. */
class AsmModuleReturnTest {
    private fun parity(
        expected: String, body: String, call: String,
        setup: String = "var stdlib = { Math: Math }, foreign = {}, heap = new ArrayBuffer(64);",
        compiled: Boolean = false,
        linked: Boolean = compiled,
    ) {
        for (budget in listOf(0, 1_000_000)) for (enabled in listOf(false, true)) {
            KiteJs(Rhino) { asmJs = enabled; instructionBudget = budget }.use { js ->
                js.evaluate("""
                    $setup
                    var m = (function (s, f, h) {
                      "use asm";
                      var round = s.Math.fround;
                      $body
                    })(stdlib, foreign, heap);
                """.trimIndent(), "asm-module-return.js")
                assertEquals(expected, js.evaluate(call).asString(), "asmJs=$enabled budget=$budget")
                if (enabled) {
                    val report = js.asmReports.single()
                    assertEquals(compiled, report.compiled, "$report")
                    assertEquals(linked, report.linked, "$report")
                    if (!compiled || !linked) assertTrue(report.reason.isNotEmpty(), "$report")
                }
                assertEquals(2, js.evaluate("1 + 1").asInt())
            }
        }
    }

    @Test
    fun numericInitializersAfterReturnKeepOrdinaryHoistedValues() {
        for ((initializer, read, expected) in listOf(
            Triple("7", "g | 0", "0"), Triple("1.25", "+g", "NaN"),
            Triple("-0", "+g", "NaN"), Triple("round(3)", "round(g)", "NaN"),
        )) {
            parity(expected,
                "function read() { return $read; } return { read: read }; var g = $initializer;",
                "String(m.read())")
        }
        parity("0", "function read() { return g | 0; } return read; var g = 7;", "String(m())")
    }

    @Test
    fun lateImportGettersAndConversionsNeverRun() {
        for (declaration in listOf("var", "let", "const")) for (initializer in listOf("f.value | 0", "+f.value", "round(f.value)")) {
            parity("0|0|0",
                "function read() { return 0; } return { read: read }; $declaration g = $initializer;",
                "m.read() + '|' + gets + '|' + conversions",
                setup = """
                    var gets = 0, conversions = 0;
                    var stdlib = { Math: Math }, foreign = {}, heap = new ArrayBuffer(64);
                    Object.defineProperty(foreign, 'value', { get: function () {
                      gets++;
                      return { valueOf: function () { conversions++; throw new RangeError('unreachable'); } };
                    } });
                """.trimIndent())
        }
    }

    @Test
    fun aLateHeapViewDoesNotReadItsConstructor() = parity(
        "TypeError|0",
        """
            function read(i) { i = i | 0; return H[i >> 2] | 0; }
            return { read: read };
            var H = new s.Int32Array(h);
        """.trimIndent(),
        "var result = 'no error'; try { m.read(0); } catch (e) { result = e.name; } result + '|' + gets",
        setup = """
            var gets = 0;
            var stdlib = { Math: Math }, foreign = {}, heap = new ArrayBuffer(64);
            Object.defineProperty(stdlib, 'Int32Array', { get: function () { gets++; return Int32Array; } });
        """.trimIndent(),
    )

    @Test
    fun aLateCallbackStaysUndefinedAndIsNeverImported() = parity(
        "TypeError|0|0",
        "function read() { return cb() | 0; } return { read: read }; var cb = f.cb;",
        "var result = 'no error'; try { m.read(); } catch (e) { result = e.name; } result + '|' + gets + '|' + calls",
        setup = """
            var gets = 0, calls = 0;
            var stdlib = { Math: Math }, foreign = {}, heap = new ArrayBuffer(64);
            Object.defineProperty(foreign, 'cb', { get: function () {
              gets++; return function () { calls++; return 17; };
            } });
        """.trimIndent(),
    )

    @Test
    fun initializersBeforeReturnStillCompileAndRunOnce() = parity(
        "7,1.25,3|1|1",
        """
            var g = f.value | 0, d = 1.25, fl = round(3);
            function i() { return g | 0; }
            function double() { return +d; }
            function float() { return round(fl); }
            return { i: i, d: double, f: float };
        """.trimIndent(),
        "[m.i(), m.d(), m.f()].join() + '|' + gets + '|' + conversions",
        setup = """
            var gets = 0, conversions = 0;
            var stdlib = { Math: Math }, foreign = {}, heap = new ArrayBuffer(64);
            Object.defineProperty(foreign, 'value', { get: function () {
              gets++; return { valueOf: function () { conversions++; return 7; } };
            } });
        """.trimIndent(),
        compiled = true,
        linked = false,
    )

    @Test
    fun functionDeclarationsAfterReturnRemainSafelyHoisted() {
        parity("7", "return read; function read() { return 7; }", "String(m())", compiled = true)
        parity("7",
            "return { read: read }; function read() { return next() | 0; } function next() { return 7; }",
            "String(m.read())", compiled = true)
    }
}
