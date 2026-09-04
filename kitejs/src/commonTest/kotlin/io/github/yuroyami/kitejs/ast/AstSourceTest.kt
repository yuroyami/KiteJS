/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.KBigInt
import io.github.yuroyami.kitejs.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Renders every leaf node type on every target. The JVM oracle proves these strings match
 * upstream; this test proves the same code runs identically on iOS, JS and native.
 */
class AstSourceTest {

    private fun name(pos: Int, text: String) = Name(pos, text.length, text)

    private fun stmt(pos: Int, text: String) = ExpressionStatement(name(pos, text))

    private fun block(pos: Int, vararg statements: AstNode) =
        Block(pos).apply { statements.forEach { addStatement(it) } }

    @Test
    fun assignmentRendersOperator() {
        assertEquals("a = b", Assignment(Token.ASSIGN, name(0, "a"), name(4, "b"), 2).toSource())
        assertEquals(
            "a += b",
            Assignment(Token.ASSIGN_ADD, name(0, "a"), name(5, "b"), 2).toSource(),
        )
    }

    @Test
    fun bigIntLiteralRendersDigitsWithSuffix() {
        assertEquals("123n", BigIntLiteral(0, "123n", KBigInt("123", 10)).toSource())
        assertEquals("<null>", BigIntLiteral(0, "1n").toSource())
    }

    @Test
    fun breakAndContinueRenderLabels() {
        assertEquals("break;\n", BreakStatement(0, 6).toSource())
        assertEquals(
            "break L;\n",
            BreakStatement(0, 8).apply { breakLabel = name(6, "L") }.toSource(),
        )
        assertEquals("continue;\n", ContinueStatement(0, 9).toSource())
        assertEquals("continue L;\n", ContinueStatement(0, 12, name(9, "L")).toSource())

        // The one-argument forms leave the length at -1, matching upstream.
        assertEquals(-1, ContinueStatement(3).length)
        assertEquals(-1, Label(3).length)
    }

    @Test
    fun computedPropertyKeyRendersBrackets() {
        val key = ComputedPropertyKey(0, 5)
        key.expression = name(1, "k")
        assertEquals("[k]", key.toSource())
        assertEquals(Token.COMPUTED_PROPERTY, key.type)
    }

    @Test
    fun conditionalExpressionRendersTernary() {
        val cond = ConditionalExpression(0, 9)
        cond.testExpression = name(0, "a")
        cond.trueExpression = name(4, "b")
        cond.falseExpression = name(8, "c")
        assertEquals("a ? b : c", cond.toSource())
        assertEquals(-1, cond.questionMarkPosition)
    }

    @Test
    fun doAndWhileLoopsRenderBodies() {
        val doLoop = DoLoop(0)
        doLoop.condition = name(20, "c")
        doLoop.body = block(3, stmt(5, "a"))
        assertEquals("do {\n  a;\n} while (c);\n", doLoop.toSource())

        val whileLoop = WhileLoop(0)
        whileLoop.condition = name(7, "c")
        whileLoop.body = block(10, stmt(12, "a"))
        assertEquals("while (c) {\n  a;\n}\n", whileLoop.toSource())

        val whileNoBlock = WhileLoop(0)
        whileNoBlock.condition = name(7, "c")
        whileNoBlock.body = stmt(10, "a")
        assertEquals("while (c) \n  a;\n", whileNoBlock.toSource())
    }

    @Test
    fun elementGetAndCallsRenderArguments() {
        assertEquals("a[b]", ElementGet(name(0, "a"), name(2, "b")).toSource())

        val call = FunctionCall(0, 6)
        call.target = name(0, "f")
        call.addArgument(name(2, "a"))
        call.addArgument(name(5, "b"))
        assertEquals("f(a, b)", call.toSource())

        call.markIsOptionalCall()
        assertTrue(call.isOptionalCall)
        assertEquals("f?.(a, b)", call.toSource())

        val noArgs = FunctionCall(0, 3)
        noArgs.target = name(0, "f")
        assertEquals("f()", noArgs.toSource())
        assertEquals(emptyList(), noArgs.getArguments())
    }

    @Test
    fun emptyAndErrorNodesRender() {
        assertEquals(";\n", EmptyStatement(0, 1).toSource())
        assertEquals("      ;\n", EmptyStatement(0, 1).toSource(3))
        assertEquals("", ErrorNode(0, 4).toSource())
        assertNull(ErrorNode(0, 4).message)
    }

    @Test
    fun generatorLoopRefusesForEach() {
        val loop = GeneratorExpressionLoop(0)
        loop.iterator = name(5, "k")
        loop.iteratedObject = name(10, "o")
        assertEquals(" for (k in o)", loop.toSource())
        assertFalse(loop.isForEach)
        assertFailsWith<UnsupportedOperationException> { loop.isForEach = true }
    }

    @Test
    fun generatorMethodDefinitionRendersStar() {
        val def = GeneratorMethodDefinition(0, 5, name(1, "next"))
        assertEquals("*next", def.toSource())
        assertEquals(Token.MUL, def.type)
    }

    @Test
    fun ifStatementRendersBothBranches() {
        val plain = IfStatement(0)
        plain.condition = name(4, "c")
        plain.thenPart = block(7, stmt(9, "a"))
        assertEquals("if (c) {\n  a;\n}\n", plain.toSource())

        val withElse = IfStatement(0)
        withElse.condition = name(4, "c")
        withElse.thenPart = block(7, stmt(9, "a"))
        withElse.elsePart = block(20, stmt(22, "b"))
        assertEquals("if (c) {\n  a;\n} else {\n  b;\n}\n", withElse.toSource())

        val noBlocks = IfStatement(0)
        noBlocks.condition = name(4, "c")
        noBlocks.thenPart = stmt(7, "a")
        noBlocks.elsePart = stmt(20, "b")
        assertEquals("if (c) \n  a;\nelse \n  b;\n", noBlocks.toSource())
    }

    @Test
    fun keywordLiteralsRenderTheirKeyword() {
        val cases = listOf(
            Token.THIS to "this",
            Token.SUPER to "super",
            Token.NULL to "null",
            Token.UNDEFINED to "undefined",
            Token.TRUE to "true",
            Token.FALSE to "false",
            Token.DEBUGGER to "debugger;\n",
        )
        for ((token, text) in cases) {
            assertEquals(text, KeywordLiteral(0, 4, token).toSource(), "token $token")
        }
        assertTrue(KeywordLiteral(0, 4, Token.TRUE).isBooleanLiteral)
        assertFalse(KeywordLiteral(0, 4, Token.NULL).isBooleanLiteral)
        assertFailsWith<IllegalArgumentException> { KeywordLiteral(0, 1, Token.NAME) }
    }

    @Test
    fun labelRendersNameAndRejectsBlank() {
        assertEquals("outer:\n", Label(0, 6, "outer").toSource())
        assertFailsWith<IllegalArgumentException> { Label(0, 1, "  ") }
        assertFailsWith<IllegalArgumentException> { Label(0, 1, null) }
    }

    @Test
    fun parenthesizedAndSpreadRender() {
        assertEquals("(a)", ParenthesizedExpression(name(1, "a")).toSource())

        val spread = Spread(0, 4)
        spread.expression = name(3, "a")
        assertEquals("...a", spread.toSource())
        assertEquals(Token.DOTDOTDOT, spread.type)
    }

    @Test
    fun parseProblemRendersLocationAndSeverity() {
        val error = ParseProblem(ParseProblem.Type.Error, "boom", "a.js", 4, 2)
        assertEquals("a.js:offset=4,length=2,error: boom", error.toString())

        val warning = ParseProblem(ParseProblem.Type.Warning, "hmm", "a.js", 1, 1)
        assertEquals("a.js:offset=1,length=1,warning: hmm", warning.toString())
    }

    @Test
    fun switchCaseRendersCaseAndDefault() {
        val case = SwitchCase(0)
        case.expression = name(5, "a")
        case.addStatement(stmt(8, "b"))
        assertEquals("case a:\n  b;\n", case.toSource())
        assertFalse(case.isDefault)

        val default = SwitchCase(0)
        default.addStatement(stmt(9, "b"))
        assertEquals("default:\n  b;\n", default.toSource())
        assertTrue(default.isDefault)
    }

    @Test
    fun taggedTemplateRendersTagThenLiteral() {
        val literal = TemplateLiteral(0)
        literal.addElement(TemplateCharacters(0).apply { value = "a"; rawValue = "a" })

        val tagged = TaggedTemplateLiteral(0, 6)
        tagged.target = name(0, "tag")
        tagged.templateLiteral = literal
        assertEquals("tag`a`", tagged.toSource())
    }

    @Test
    fun throwStatementRenders() {
        assertEquals("throw e;\n", ThrowStatement(0, 9, name(6, "e")).toSource())
        assertEquals("  throw e;\n", ThrowStatement(0, 9, name(6, "e")).toSource(1))
        // The (pos, expr) form takes its length from the expression.
        assertEquals(1, ThrowStatement(0, name(6, "e")).length)
    }

    @Test
    fun unaryExpressionsRenderSpacingPerOperator() {
        assertEquals("!a", UnaryExpression(Token.NOT, 0, name(1, "a")).toSource())
        assertEquals("~a", UnaryExpression(Token.BITNOT, 0, name(1, "a")).toSource())
        assertEquals("-a", UnaryExpression(Token.NEG, 0, name(1, "a")).toSource())
        // typeof, delete and void are word operators, so they get a trailing space.
        assertEquals("typeof a", UnaryExpression(Token.TYPEOF, 0, name(1, "a")).toSource())
        assertEquals("void a", UnaryExpression(Token.VOID, 0, name(1, "a")).toSource())
        assertEquals("delete a", UnaryExpression(Token.DELPROP, 0, name(1, "a")).toSource())
        assertFailsWith<IllegalArgumentException> {
            UnaryExpression(Token.LAST_TOKEN + 1, 0, name(1, "a"))
        }
    }

    @Test
    fun updateExpressionsRenderPrefixAndPostfix() {
        val prefix = UpdateExpression(Token.INC, 3, name(1, "a"), false)
        assertEquals("++a", prefix.toSource())
        assertTrue(prefix.isPrefix)

        val postfix = UpdateExpression(Token.DEC, 3, name(1, "a"), true)
        assertEquals("a--", postfix.toSource())
        assertTrue(postfix.isPostfix)
        // Postfix bounds run from the operand to two characters past the operator.
        assertEquals(1, postfix.position)
        assertEquals(4, postfix.length)
    }

    @Test
    fun withStatementRendersBody() {
        val withBlock = WithStatement(0)
        withBlock.expression = name(6, "o")
        withBlock.statement = block(9, stmt(11, "a"))
        assertEquals("with (o) {\n  a;\n}\n", withBlock.toSource())

        val withStmt = WithStatement(0)
        withStmt.expression = name(6, "o")
        withStmt.statement = stmt(9, "a")
        assertEquals("with (o) \n  a;\n", withStmt.toSource())
    }

    @Test
    fun yieldRendersStarAndValue() {
        assertEquals("yield", Yield(0, 5).toSource())
        assertEquals("yield a", Yield(0, 7, name(6, "a"), false).toSource())
        assertEquals(Token.YIELD, Yield(0, 7, name(6, "a"), false).type)
        assertEquals(Token.YIELD_STAR, Yield(0, 7, name(6, "a"), true).type)
    }
}
