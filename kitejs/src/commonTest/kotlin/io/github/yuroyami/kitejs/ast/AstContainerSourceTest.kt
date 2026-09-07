/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Renders the container node types on every target. The JVM oracle proves these strings match
 * upstream; this test proves the same code runs identically on iOS, JS and native.
 */
class AstContainerSourceTest {

    private fun name(pos: Int, text: String) = Name(pos, text.length, text)

    private fun stmt(pos: Int, text: String) = ExpressionStatement(name(pos, text))

    private fun block(pos: Int, vararg statements: AstNode) =
        Block(pos).apply { statements.forEach { addStatement(it) } }

    @Test
    fun arrayLiteralRendersElements() {
        val literal = ArrayLiteral(0, 8)
        literal.addElement(name(1, "a"))
        literal.addElement(NumberLiteral(4, "2"))
        assertEquals("[a, 2]", literal.toSource())
        assertEquals(2, literal.size)
        assertEquals("[]", ArrayLiteral(0, 2).toSource())

        assertFalse(literal.isDestructuring)
        literal.isDestructuring = true
        assertTrue(literal.isDestructuring)
        assertFailsWith<IndexOutOfBoundsException> { ArrayLiteral(0, 2).getElement(0) }
    }

    @Test
    fun objectLiteralRendersPropertiesOnePerLine() {
        val first = ObjectProperty()
        first.setKeyAndValue(name(2, "a"), NumberLiteral(5, "1"))
        val second = ObjectProperty()
        second.setKeyAndValue(name(8, "b"), NumberLiteral(11, "2"))

        val literal = ObjectLiteral(0, 14)
        literal.addElement(first)
        literal.addElement(second)

        assertEquals("{\n  a: 1,\n  b: 2\n}", literal.toSource())
        assertEquals("{}", ObjectLiteral(0, 2).toSource())
    }

    @Test
    fun objectPropertyTracksItsForm() {
        val prop = ObjectProperty()
        prop.setKeyAndValue(name(6, "a"), name(8, "f"))
        assertEquals(Token.COLON, prop.type)
        assertFalse(prop.isMethod)

        prop.setIsGetterMethod()
        assertTrue(prop.isGetterMethod)
        assertTrue(prop.isMethod)

        prop.setIsSetterMethod()
        assertTrue(prop.isSetterMethod)

        prop.setIsNormalMethod()
        assertTrue(prop.isNormalMethod)

        assertFailsWith<IllegalArgumentException> { ObjectProperty().setNodeType(Token.NAME) }
    }

    @Test
    fun spreadObjectPropertyDelegatesToItsSpread() {
        val spread = Spread(2, 4)
        spread.expression = name(5, "a")
        val prop = SpreadObjectProperty(spread)

        assertEquals("...a", prop.toSource())
        assertEquals(2, prop.position)
        assertEquals(4, prop.length)
        assertEquals(Token.DOTDOTDOT, prop.type)
    }

    @Test
    fun switchStatementRendersCases() {
        val case = SwitchCase(11)
        case.expression = name(16, "a")
        case.addStatement(stmt(19, "b"))
        val default = SwitchCase(22)
        default.addStatement(stmt(31, "c"))

        val switch = SwitchStatement(0)
        switch.expression = name(8, "x")
        switch.addCase(case)
        switch.addCase(default)

        assertEquals("switch (x) {\n  case a:\n    b;\n  default:\n    c;\n}\n", switch.toSource())
        assertTrue(SwitchStatement(0).cases.isEmpty())
    }

    @Test
    fun labeledStatementRendersEveryLabel() {
        val labeled = LabeledStatement(0)
        labeled.addLabel(Label(0, 6, "outer"))
        labeled.addLabel(Label(7, 6, "inner"))
        labeled.statement = stmt(14, "a")

        assertEquals("outer:\ninner:\n  a;\n", labeled.toSource())
        assertEquals("outer", labeled.firstLabel.name)
        assertEquals("inner", labeled.getLabelByName("inner")?.name)
        assertNull(labeled.getLabelByName("nope"))
        assertTrue(labeled.hasSideEffects())
    }

    @Test
    fun tryStatementRendersCatchAndFinally() {
        val catchBody = Scope(20)
        catchBody.addChild(stmt(22, "b"))
        val catchClause = CatchClause(10)
        catchClause.varName = name(17, "e")
        catchClause.body = catchBody

        val tryStatement = TryStatement(0)
        tryStatement.tryBlock = block(4, stmt(6, "a"))
        tryStatement.addCatchClause(catchClause)
        assertEquals("try {\n  a;\n}catch (e) {\n  b;\n}\n", tryStatement.toSource())

        tryStatement.finallyBlock = block(30, stmt(32, "c"))
        assertEquals(
            "try {\n  a;\n}catch (e) {\n  b;\n}\n finally {\n  c;\n}\n",
            tryStatement.toSource(),
        )
    }

    @Test
    fun catchClauseRendersGuard() {
        val clause = CatchClause(0)
        clause.varName = name(7, "e")
        clause.catchCondition = name(12, "c")
        clause.body = Scope(20)
        assertEquals("catch (e if c) {\n}\n", clause.toSource())
    }

    @Test
    fun forLoopRendersThreeClauses() {
        val loop = ForLoop(0)
        loop.initializer = name(5, "i")
        loop.condition = name(8, "c")
        loop.increment = name(11, "n")
        loop.body = block(14, stmt(16, "a"))
        assertEquals("for (i; c; n) {\n  a;\n}\n", loop.toSource())

        val noBlock = ForLoop(0)
        noBlock.initializer = name(5, "i")
        noBlock.condition = name(8, "c")
        noBlock.increment = name(11, "n")
        noBlock.body = stmt(14, "a")
        assertEquals("for (i; c; n) \n  a;\n", noBlock.toSource())
    }

    @Test
    fun variableDeclarationRendersItsKeyword() {
        val cases = listOf(Token.VAR to "var", Token.CONST to "const", Token.LET to "let")
        for ((token, keyword) in cases) {
            val init = VariableInitializer(4)
            init.target = name(4, "a")
            init.initializer = NumberLiteral(8, "1")

            val decl = VariableDeclaration(0)
            decl.type = token
            decl.addVariable(init)
            decl.isStatement = true

            assertEquals("$keyword a = 1;\n", decl.toSource(), "keyword $keyword")
        }
        assertFailsWith<IllegalArgumentException> { VariableDeclaration(0).type = Token.NAME }
    }

    @Test
    fun variableInitializerDetectsDestructuring() {
        val simple = VariableInitializer(0)
        simple.target = name(0, "a")
        assertEquals("a", simple.toSource())
        assertFalse(simple.isDestructuring)

        val destructuring = VariableInitializer(0)
        destructuring.target = ArrayLiteral(0, 6)
        assertTrue(destructuring.isDestructuring)

        assertFailsWith<IllegalArgumentException> { VariableInitializer(0).target = null }
        assertFailsWith<IllegalArgumentException> {
            VariableInitializer(0).setNodeType(Token.NAME)
        }
    }

    @Test
    fun letNodeRendersBindingsAndBody() {
        val init = VariableInitializer(5)
        init.target = name(5, "a")
        init.initializer = NumberLiteral(9, "1")
        val decl = VariableDeclaration(5)
        decl.addVariable(init)

        val let = LetNode(0)
        let.variables = decl
        let.body = block(12, stmt(14, "b"))
        assertEquals("let (a = 1) {\n  b;\n}\n", let.toSource())
    }

    @Test
    fun newExpressionRendersConstructorCall() {
        val expr = NewExpression(0, 9)
        expr.target = name(4, "Foo")
        expr.addArgument(NumberLiteral(8, "1"))
        assertEquals("new Foo(1)", expr.toSource())

        expr.initializer = ObjectLiteral(11, 2)
        assertEquals("new Foo(1) {}", expr.toSource())

        val noArgs = NewExpression(0, 9)
        noArgs.target = name(4, "Foo")
        assertEquals("new Foo()", noArgs.toSource())
    }

    @Test
    fun generatorExpressionRendersLoopsAndFilter() {
        val loop = GeneratorExpressionLoop(5)
        loop.iterator = name(10, "k")
        loop.iteratedObject = name(15, "o")

        val expr = GeneratorExpression(0)
        expr.result = name(1, "k")
        expr.addLoop(loop)
        assertEquals("(k for (k in o))", expr.toSource())

        expr.filter = name(20, "c")
        assertEquals("(k for (k in o) if (c))", expr.toSource())
        assertEquals(1, expr.loops.size)
    }

    @Test
    fun errorCollectorRecordsProblemsInOrder() {
        val collector = ErrorCollector()
        collector.error("bad", "a.js", 4, 2)
        collector.warning("meh", "a.js", 9, 1)

        assertEquals(2, collector.errors.size)
        assertEquals(
            "a.js:offset=4,length=2,error: bad\na.js:offset=9,length=1,warning: meh\n",
            collector.toString(),
        )
        assertFailsWith<UnsupportedOperationException> {
            collector.error("x", "a.js", 1, "src", 0)
        }
    }
}
