/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino.contract

import io.github.yuroyami.kitejs.api.JsEngineError
import io.github.yuroyami.kitejs.api.JsSyntaxError
import io.github.yuroyami.kitejs.api.KiteJs
import io.github.yuroyami.kitejs.rhino.BaseFunction
import io.github.yuroyami.kitejs.rhino.CompilerEnvirons
import io.github.yuroyami.kitejs.rhino.Context
import io.github.yuroyami.kitejs.rhino.EvaluatorException
import io.github.yuroyami.kitejs.rhino.LanguageVersion
import io.github.yuroyami.kitejs.rhino.Parser
import io.github.yuroyami.kitejs.rhino.Rhino
import io.github.yuroyami.kitejs.rhino.ScriptRuntime
import io.github.yuroyami.kitejs.rhino.Undefined
import io.github.yuroyami.kitejs.rhino.asmReports
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** What only Rhino does, on top of the contract every engine keeps. */
class RhinoOnlyTest {

    @Test
    fun twoEnginesOnOneThreadAreRefusedClearly() {
        KiteJs(Rhino).use {
            val e = assertFailsWith<JsEngineError> { KiteJs(Rhino) }
            assertContains(e.message ?: "", "this thread already has an open engine")
        }
    }

    @Test
    fun theEngineReportsItsVersion() {
        KiteJs(Rhino).use { js ->
            assertContains(js.version, "KiteJS")
            assertContains(js.version, "Rhino")
            assertEquals("Rhino", js.engine.name)
        }
    }

    @Test
    fun anOlderLanguageVersionLeavesOutTheNewSyntax() {
        KiteJs(Rhino) { languageVersion = LanguageVersion.ES5 }.use { js ->
            assertFailsWith<JsSyntaxError> { js.evaluate("class A {}") }
        }
    }

    @Test
    fun safeBuiltinsLeaveOutThePackagesHooks() {
        KiteJs(Rhino) { safeBuiltins = true }.use { js ->
            assertEquals("undefined", js.evaluate("typeof Packages").asString())
        }
    }

    @Test
    fun compilingAStandaloneAnonymousFunctionRemainsValid() {
        KiteJs(Rhino).use { js ->
            val cx = Context.getContext()
            val scope = cx.initStandardObjects()
            for (source in listOf(
                "function (a) { return a + 1 }", "function* (a) { yield a }", "async function (a) { return a }",
            )) {
                val compiled = cx.compileFunction(scope, source, "standalone-function.js", 1)
                assertEquals("", (compiled as BaseFunction).functionName, source)
            }
            val compiled = cx.compileFunction(scope, "function (a) { return a + 1 }", "standalone-function.js", 1)
            assertEquals(5.0, ScriptRuntime.toNumber(compiled.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, arrayOf(4))))
            assertEquals(2, js.evaluate("1 + 1").asInt())
        }
    }

    @Test
    fun aDeclarationStillNeedsANameWhenMemberNamesAreEnabled() {
        val env = CompilerEnvirons().apply {
            languageVersion = Context.VERSION_ES6
            allowMemberExprAsFunctionName = true
        }
        val error = assertFailsWith<EvaluatorException> { Parser(env).parse("function () {}", "member-name.js", 1) }
        assertContains(error.message, "Function statements require a function name")
        assertTrue(Parser(env).parse("(function () {})", "member-name.js", 1).firstChild != null)
    }

    @Test
    fun asmReportsListEachModule() {
        KiteJs(Rhino).use { js ->
            js.evaluate("function M() { 'use asm'; function f() { return 1 } return { f: f } } M().f()")
            assertEquals(1, js.asmReports.size)
            assertTrue(js.asmReports.single().compiled, js.asmReports.single().toString())
        }
    }
}
