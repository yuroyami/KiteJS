/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An error thrown while a condition runs carries the line of the condition, whatever its shape:
 * a property read on the left of a comparison used to report the line of the statement before.
 */
class ConditionLineNumberTest {

    private fun eval(source: String): String = KiteJs(Rhino).use { js -> js.evaluate(source).asString() }

    @Test
    fun theIssuesCasesReportTheirOwnLines() {
        val source = """
            var item, r = [];
            function L(tag, f) { try { f() } catch (e) { r.push(tag + "=" + e.lineNumber) } }
            L("cmpFirstNoLoop", function () {
              var a = 1;
              if (item.p === "x") {}
            });
            L("truthyInLoop", function () {
              for (var n = 0; n < 1; n++) {
                var a = 1;
                if (item.p) {}
              }
            });
            L("cmpFirstInLoop", function () {
              for (var n = 0; n < 1; n++) {
                var a = 1;
                if (item.p === "x") {}
              }
            });
            L("cmpSecondInLoop", function () {
              for (var n = 0; n < 1; n++) {
                var a = 1;
                if ("x" === item.p) {}
              }
            });
            L("cmpFirstWhile", function () {
              var n = 0;
              while (n++ < 1) {
                if (item.p === "x") {}
              }
            });
            L("cmpFirstLtNoLoop", function () {
              var a = 1;
              if (item.p < 3) {}
            });
            L("callFirstNoLoop", function () {
              var a = 1;
              if (item.p() === 1) {}
            });
            L("assignCmp", function () {
              var a = 1;
              var b = item.p === "x";
            });
            r.join(" ")
        """.trimIndent()
        assertEquals(
            "cmpFirstNoLoop=5 truthyInLoop=10 cmpFirstInLoop=16 cmpSecondInLoop=22 cmpFirstWhile=28 " +
                "cmpFirstLtNoLoop=33 callFirstNoLoop=37 assignCmp=41",
            eval(source),
        )
    }

    /** The line [body], run inside a function after a statement on line 2, reports its error at. */
    private fun lineOf(body: String): String =
        eval("var item, o = {};\n(function () { try { var a = 1;\n$body\n} catch (e) { return String(e.lineNumber) } })()")

    @Test
    fun everyKindOfConditionReportsItsLine() {
        assertEquals("3", lineOf("if (o.x.y === 1) {}"))
        assertEquals("3", lineOf("if (o.x[0] == 2) {}"))
        assertEquals("3", lineOf("if (!(item.p > 1)) {}"))
        assertEquals("3", lineOf("if (item.p === 'x') {} else {}"))
        assertEquals("3", lineOf("while (item.p === 'x') {}"))
        assertEquals("3", lineOf("for (; item.p === 'x';) {}"))
        assertEquals("3", lineOf("do { var b = 1 } while (item.p === 'x')"))
        assertEquals("3", lineOf("var c = item.p === 'x' ? 1 : 2"))
    }

    @Test
    fun theStackNamesTheConditionsLine() {
        assertEquals(
            "true",
            eval("try {\n  var a = 1;\n  if (item.p === 'x') {}\n} catch (e) { String(/:3/.test(e.stack)) }"),
        )
    }
}
