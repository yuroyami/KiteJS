/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Covers the typed AST facade: positions, parent links, visiting and toSource. */
class AstNodeTest {

    @Test
    fun positionsBecomeRelativeWhenParented() {
        val parent = Block(10, 20)
        val child = Name(15, 1, "x")

        child.parent = parent
        assertEquals(5, child.position)
        assertEquals(15, child.absolutePosition)
        assertSame(parent, child.parent)
    }

    @Test
    fun absolutePositionWalksTheWholeChain() {
        val root = Block(100)
        val middle = Block(110)
        val leaf = Name(115, 1, "x")

        middle.parent = root
        leaf.parent = middle

        assertEquals(10, middle.position)
        assertEquals(5, leaf.position)
        assertEquals(115, leaf.absolutePosition)
        assertEquals(2, leaf.depth())
    }

    @Test
    fun reparentingRestoresAbsolutePositionFirst() {
        val first = Block(10)
        val second = Block(4)
        val child = Name(15, 1, "x")

        child.parent = first
        assertEquals(5, child.position)
        child.parent = second
        assertEquals(11, child.position)
        assertEquals(15, child.absolutePosition)
    }

    @Test
    fun addChildStretchesTheParentLength() {
        val block = Block(0, 1)
        val child = Name(4, 3, "abc")
        block.addChild(child)

        assertEquals(7, block.length)
        assertSame(block, child.parent)
        assertSame(child, block.firstChild)
    }

    @Test
    fun linenoFallsBackToTheParentChain() {
        val parent = Block(0)
        parent.setLineColumnNumber(4, 1)
        val child = Name(0, 1, "x")
        child.parent = parent

        assertEquals(-1, Name(0, 1, "y").lineno)
        assertEquals(4, child.lineno)
    }

    @Test
    fun visitorSeesNodesInLexicalOrder() {
        val block = Block(0)
        block.addStatement(ExpressionStatement(Name(0, 1, "a")))
        block.addStatement(ExpressionStatement(Name(2, 1, "b")))

        val seen = mutableListOf<String>()
        block.visit { node ->
            seen.add(node.shortName())
            true
        }
        assertEquals(
            listOf("Block", "ExpressionStatement", "Name", "ExpressionStatement", "Name"),
            seen,
        )
    }

    @Test
    fun visitorCanPruneSubtrees() {
        val block = Block(0)
        block.addStatement(ExpressionStatement(Name(0, 1, "a")))

        val seen = mutableListOf<String>()
        block.visit { node ->
            seen.add(node.shortName())
            node !is ExpressionStatement
        }
        assertEquals(listOf("Block", "ExpressionStatement"), seen)
    }

    @Test
    fun literalsRenderTheirSource() {
        assertEquals("x", Name(0, 1, "x").toSource())
        assertEquals("1.5", NumberLiteral(0, "1.5").toSource())
        assertEquals("/a+/gi", RegExpLiteral(0).apply { value = "a+"; flags = "gi" }.toSource())
        assertEquals(
            "\"a\\nb\"",
            StringLiteral(0).apply { value = "a\nb"; quoteCharacter = '"' }.toSource(),
        )
        assertEquals("  x", Name(0, 1, "x").toSource(1))
    }

    @Test
    fun templateLiteralsRenderRawText() {
        val literal = TemplateLiteral(0)
        literal.addElement(TemplateCharacters(0).apply { value = "a"; rawValue = "a" })
        literal.addElement(Name(0, 1, "x"))
        literal.addElement(TemplateCharacters(0).apply { value = "b"; rawValue = "b" })

        assertEquals("`a\${x}b`", literal.toSource())
        assertEquals(2, literal.templateStrings.size)
        assertEquals(1, literal.substitutions.size)
    }

    @Test
    fun infixExpressionsRenderWithOperatorSpacing() {
        val expr = InfixExpression(Token.ADD, Name(0, 1, "a"), Name(4, 1, "b"), 2)
        assertEquals("a + b", expr.toSource())
        assertEquals(Token.ADD, expr.operator)
        assertEquals(2, expr.operatorPosition)
    }

    @Test
    fun propertyGetRendersDottedAccess() {
        val get = PropertyGet(Name(0, 1, "a"), Name(2, 1, "b"), 1)
        assertEquals("a.b", get.toSource())
        assertEquals(Token.GETPROP, get.type)
    }

    @Test
    fun blocksRenderBracesAndIndentation() {
        val block = Block(0)
        block.addStatement(ExpressionStatement(Name(0, 1, "a")))
        assertEquals("{\n  a;\n}\n", block.toSource())
    }

    @Test
    fun functionNodesRenderSignatureAndBody() {
        val fn = FunctionNode(0, Name(9, 1, "f"))
        fn.addParam(Name(11, 1, "a"))
        fn.body = Block(14).apply { addStatement(ReturnStatement(0, 1, Name(0, 1, "a"))) }
        fn.functionType = FunctionNode.FUNCTION_STATEMENT

        assertEquals("function f(a) {\n  return a;\n}\n", fn.toSource())
        assertEquals("f", fn.name)
    }

    @Test
    fun operatorToStringRejectsNonOperators() {
        assertEquals("+", AstNode.operatorToString(Token.ADD))
        assertEquals("typeof", AstNode.operatorToString(Token.TYPEOF))
        assertFailsWith<IllegalArgumentException> { AstNode.operatorToString(Token.NAME) }
    }

    @Test
    fun jumpsRefuseTheAstInterface() {
        val jump = Jump(Token.BREAK)
        assertFailsWith<UnsupportedOperationException> { jump.toSource(0) }
        assertFailsWith<UnsupportedOperationException> { jump.visit { true } }
    }

    @Test
    fun comparableOrdersByPositionThenLength() {
        val short = Name(5, 1, "a")
        val long = Name(5, 2, "ab")
        val later = Name(9, 1, "b")

        assertTrue(short.compareTo(long) < 0)
        assertTrue(short.compareTo(later) < 0)
        assertEquals(0, short.compareTo(short))
    }

    @Test
    fun astRootCollectsCommentsInPositionOrder() {
        val root = AstRoot(0)
        root.addComment(Comment(20, 4, Token.CommentType.LINE, "// b"))
        root.addComment(Comment(0, 4, Token.CommentType.LINE, "// a"))

        assertEquals(listOf("// a", "// b"), root.comments!!.map { it.value })
        assertSame(root, root.comments!!.first().parent)
    }

    @Test
    fun enclosingFunctionAndScopeSearchTheParentChain() {
        val fn = FunctionNode(0)
        val block = Block(2)
        val name = Name(4, 1, "x")
        block.parent = fn
        name.parent = block

        // Block is not a Scope, so the search skips past it to the function node.
        assertSame(fn, name.enclosingFunction)
        assertSame(fn, name.enclosingScope)
        assertNull(fn.enclosingFunction)
    }
}
