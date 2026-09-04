/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.ast.AstRoot
import io.github.yuroyami.kitejs.ast.ExpressionStatement
import io.github.yuroyami.kitejs.ast.FunctionNode
import io.github.yuroyami.kitejs.ast.InfixExpression
import io.github.yuroyami.kitejs.ast.VariableDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Checks that the parser builds the expected tree shapes and renders them back to source. The
 * exhaustive check against upstream lives in the jvmTest oracle.
 */
class ParserSmokeTest {

    private fun parse(source: String, version: Int = Context.VERSION_ES6): AstRoot {
        val env = CompilerEnvirons()
        env.languageVersion = version
        return Parser(env).parse(source, "smoke.js", 1)
    }

    private fun roundTrip(source: String): String = parse(source).toSource()

    @Test
    fun parsesAnEmptyScript() {
        val root = parse("")
        assertEquals(Token.SCRIPT, root.type)
        assertEquals("", root.toSource())
    }

    @Test
    fun parsesVariableDeclarations() {
        val root = parse("var a = 1, b = 2;")
        val decl = root.firstChild as VariableDeclaration
        assertEquals(Token.VAR, decl.type)
        assertEquals(2, decl.variables.size)
        assertEquals("var a = 1, b = 2;\n", root.toSource())

        assertEquals("const a = 1;\n", roundTrip("const a = 1;"))
        assertEquals("let a = 1;\n", roundTrip("let a = 1;"))
    }

    @Test
    fun parsesOperatorPrecedence() {
        val root = parse("a + b * c;")
        val stmt = root.firstChild as ExpressionStatement
        val add = stmt.expression as InfixExpression
        assertEquals(Token.ADD, add.type)
        assertEquals(Token.MUL, add.right!!.type)
        assertEquals("a + b * c;\n", root.toSource())
    }

    @Test
    fun parsesFunctionDeclarations() {
        val root = parse("function f(a, b) { return a + b; }")
        val fn = root.firstChild as FunctionNode
        assertEquals("f", fn.name)
        assertEquals(2, fn.getParams().size)
        assertEquals("function f(a, b) {\n  return a + b;\n}\n", root.toSource())
    }

    @Test
    fun parsesControlFlow() {
        assertEquals("if (a) {\n  b();\n}\n", roundTrip("if (a) { b(); }"))
        assertEquals("while (a) {\n  b();\n}\n", roundTrip("while (a) { b(); }"))
        assertEquals("for (i = 0; i < 3; i++) {\n  b();\n}\n", roundTrip("for (i=0;i<3;i++){b();}"))
        assertEquals("do {\n  a();\n} while (b);\n", roundTrip("do { a(); } while (b);"))
    }

    @Test
    fun parsesObjectsAndArrays() {
        assertEquals("var a = [1, 2, 3];\n", roundTrip("var a = [1,2,3];"))
        assertEquals("var a = {\n  b: 1\n};\n", roundTrip("var a = {b: 1};"))
    }

    @Test
    fun parsesModernSyntax() {
        assertTrue(roundTrip("var f = (a) => a + 1;").contains("=>"))
        assertTrue(roundTrip("var [a, b] = c;").contains("["))
        assertTrue(roundTrip("var a = `x\${y}z`;").contains("`"))
        assertTrue(roundTrip("var a = b?.c;").isNotEmpty())
        assertTrue(roundTrip("var a = b ?? c;").contains("??"))
        assertTrue(roundTrip("function* g() { yield 1; }").contains("yield"))
        assertTrue(roundTrip("for (var x of y) { z(); }").contains(" of "))
    }

    @Test
    fun parsesTryCatchAndThrow() {
        val source = roundTrip("try { a(); } catch (e) { b(); } finally { c(); }")
        assertTrue(source.startsWith("try {"))
        assertTrue(source.contains("catch (e)"))
        assertTrue(source.contains("finally"))
        assertEquals("throw e;\n", roundTrip("throw e;"))
    }

    @Test
    fun parsesSwitchAndLabels() {
        val switch = roundTrip("switch (a) { case 1: b(); break; default: c(); }")
        assertTrue(switch.startsWith("switch (a) {"))
        assertTrue(switch.contains("case 1:"))
        assertTrue(switch.contains("default:"))

        val labeled = roundTrip("outer: for (;;) { break outer; }")
        assertTrue(labeled.startsWith("outer:"))
        assertTrue(labeled.contains("break outer;"))
    }

    @Test
    fun parsesRegexpAndDivisionApart() {
        assertEquals("var a = /b+/g;\n", roundTrip("var a = /b+/g;"))
        assertEquals("var a = b / c;\n", roundTrip("var a = b / c;"))
    }

    @Test
    fun recordsCommentsWhenAsked() {
        val env = CompilerEnvirons()
        env.recordingComments = true
        val root = Parser(env).parse("// note\nvar a = 1;", "smoke.js", 1)
        assertEquals(1, root.comments?.size)
        assertEquals("// note", root.comments!!.first().value)
    }

    @Test
    fun reportsSyntaxErrors() {
        assertFailsWith<EvaluatorException> { parse("var = ;") }
        assertFailsWith<EvaluatorException> { parse("function (") }
    }

    @Test
    fun collectsErrorsInIdeMode() {
        val env = CompilerEnvirons.ideEnvirons()
        val parser = Parser(env)
        parser.parse("var = ;", "smoke.js", 1)
        val collector = env.errorReporter as io.github.yuroyami.kitejs.ast.ErrorCollector
        assertTrue(collector.errors.isNotEmpty())
    }

    @Test
    fun refusesToBeReused() {
        val parser = Parser()
        parser.parse("var a = 1;", "smoke.js", 1)
        assertFailsWith<IllegalStateException> { parser.parse("var b = 2;", "smoke.js", 1) }
    }

    @Test
    fun buildsScopesAndSymbols() {
        val root = parse("var a; function f() { var b; }")
        assertEquals(1, root.symbols.count { it.name == "a" })
        // At parse time a function is an ordinary child node. The functions table is filled by
        // the IR factory in phase 2, so it is still empty here.
        val fn = root.statements.filterIsInstance<FunctionNode>().single()
        assertEquals("f", fn.name)
        assertEquals(1, fn.symbols.count { it.name == "b" })
    }

    @Test
    fun tracksSourceBounds() {
        val source = "var a = 1;"
        val root = parse(source)
        assertEquals(source.length, root.length)
        assertEquals("smoke.js", root.sourceName)
        assertEquals(1, root.baseLineno)
    }
}
