/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Context
import io.github.yuroyami.kitejs.rhino.NativeObject
import io.github.yuroyami.kitejs.rhino.Rhino
import io.github.yuroyami.kitejs.rhino.ScriptRuntime
import io.github.yuroyami.kitejs.rhino.ScriptableObject
import io.github.yuroyami.kitejs.rhino.asmReports
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal fun checkAsmLink(expected: String, source: String, linked: Boolean) {
    for (budget in listOf(0, 1_000_000)) for (enabled in listOf(false, true)) {
        KiteJs(Rhino) { asmJs = enabled; instructionBudget = budget }.use { js ->
            assertEquals(expected, js.evaluate(source, "asm-link.js").asString(),
                "asmJs=$enabled budget=$budget: $source")
            if (enabled) {
                val report = js.asmReports.single()
                assertTrue(report.compiled, "$report")
                assertEquals(linked, report.linked, "$report")
                if (!linked) assertTrue(report.reason.isNotEmpty(), "$report")
            }
            assertEquals(2, js.evaluate("1 + 1").asInt())
        }
    }
}

/** Native operations must never stand in for an import that replaced a global built-in. */
class AsmLinkIdentityTest {
    private val maths = listOf(
        listOf("imul", "x = x | 0; y = y | 0; return op(x | 0, y | 0) | 0;", "3, 4", "12"),
        listOf("clz32", "x = x | 0; return op(x | 0) | 0;", "1", "31"),
        listOf("abs", "x = x | 0; return op(x | 0) | 0;", "-7", "7"),
        listOf("fround", "x = +x; return +op(x);", "1.25", "1.25"),
        listOf("sin", "x = +x; return +op(x);", "0.0", "0"),
        listOf("max", "x = +x; y = +y; return +op(x, y);", "1.5, 2.5", "2.5"),
    )

    private fun mathModule(field: String, body: String, arguments: String, stdlib: String): String = """
        var m = (function (s) {
          "use asm";
          var op = s.Math.$field;
          function f(x${if (body.contains("y =")) ", y" else ""}) { $body }
          return { f: f };
        })($stdlib);
        m.f($arguments) + '|' + calls;
    """.trimIndent()

    @Test
    fun replacingMathMembersRunsTheirActualFunctions() {
        for ((field, body, arguments) in maths) {
            checkAsmLink("99|1", "var calls = 0; Math.$field = function () { calls++; return 99; };\n" +
                mathModule(field, body, arguments, "{ Math: Math }"), linked = false)
        }
    }

    @Test
    fun replacingTheWholeMathObjectOrOnlyTheSuppliedLibraryAlsoFallsBack() {
        for (setup in listOf(
            "Math = { imul: function () { calls++; return 99; } }; var supplied = Math;",
            "var supplied = { imul: function () { calls++; return 99; } };",
        )) {
            checkAsmLink("99|1", "var calls = 0; $setup\n" +
                mathModule(maths[0][0], maths[0][1], maths[0][2], "{ Math: supplied }"), linked = false)
        }
    }

    @Test
    fun originalAndSavedMathFunctionsKeepLinkingAfterGlobalReplacement() {
        for ((field, body, arguments, expected) in maths) {
            checkAsmLink("$expected|0", "var calls = 0;\n" +
                mathModule(field, body, arguments, "{ Math: Math }"), linked = true)
            checkAsmLink("$expected|0", """
                var calls = 0, original = Math.$field;
                Math.$field = function () { calls++; return 99; };
            """.trimIndent() + "\n" + mathModule(field, body, arguments, "{ Math: { $field: original } }"), linked = true)
        }
    }

    @Test
    fun replacingAnyGlobalViewConstructorPreservesItsConstructionEffects() {
        for (name in listOf("Int8Array", "Uint8Array", "Int16Array", "Uint16Array", "Int32Array",
            "Uint32Array", "Float32Array", "Float64Array")) {
            val result = if (name.startsWith("Float")) "+H[0]" else "H[0] | 0"
            checkAsmLink("77|1", """
                var calls = 0, Original = $name;
                this['$name'] = function (heap) { calls++; var view = new Original(heap); view[0] = 77; return view; };
                var m = (function (s, imports, heap) {
                  "use asm";
                  var H = new s.$name(heap);
                  function f() { return $result; }
                  return { f: f };
                })({ $name: this['$name'] }, {}, new ArrayBuffer(16));
                m.f() + '|' + calls;
            """.trimIndent(), linked = false)
        }
    }

    @Test
    fun savedViewConstructorsAndInheritedDataPropertiesKeepLinking() {
        checkAsmLink("12|0", """
            var calls = 0, original = Math.imul;
            var supplied = Object.create({ Math: Object.create({ imul: original }) });
        """.trimIndent() + "\n" + mathModule(maths[0][0], maths[0][1], maths[0][2], "supplied"), linked = true)
        checkAsmLink("0|0", """
            var calls = 0, Original = Int32Array;
            Int32Array = function () { calls++; throw 'replacement'; };
            var m = (function (s, imports, heap) {
              "use asm";
              var H = new s.Int32Array(heap);
              function f() { return H[0] | 0; }
              return { f: f };
            })(Object.create({ Int32Array: Original }), {}, new ArrayBuffer(16));
            m.f() + '|' + calls;
        """.trimIndent(), linked = true)
    }

    @Test
    fun ordinaryGlobalScopesAndRebuiltRealmsRetainTheirOriginalIdentities() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val plain: ScriptableObject = cx.initStandardObjects(NativeObject(), false)
            val source = "var calls = 0; Math.imul = function () { calls++; return 99; };\n" +
                mathModule(maths[0][0], maths[0][1], maths[0][2], "{ Math: Math }")
            assertEquals("99|1", ScriptRuntime.toString(cx.evaluateString(plain, source, "plain-asm.js", 1, null)))
            cx.initStandardObjects(plain, false)
            val control = "var calls = 0;\n" + mathModule(maths[0][0], maths[0][1], maths[0][2], "{ Math: Math }")
            assertEquals("12|0", ScriptRuntime.toString(cx.evaluateString(plain, control, "rebuilt-asm.js", 1, null)))
        } finally {
            Context.exit()
        }
    }
}
