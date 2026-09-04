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

/** Covers the lexical scope chain and its symbol table. */
class ScopeTest {

    private fun symbol(name: String, declType: Int = Token.VAR) = Symbol(declType, name)

    @Test
    fun scopesDefaultToBlockType() {
        assertEquals(Token.BLOCK, Scope().type)
        assertEquals(Token.SCRIPT, ScriptNode().type)
        assertEquals(Token.SCRIPT, AstRoot().type)
        assertEquals(Token.FUNCTION, FunctionNode().type)
    }

    @Test
    fun scriptNodeIsItsOwnTop() {
        val script = AstRoot(0)
        assertSame(script, script.top)
    }

    @Test
    fun addChildScopeLinksBothDirections() {
        val root = AstRoot(0)
        val inner = Scope(4)
        root.addChildScope(inner)

        assertSame(root, inner.parentScope)
        assertSame(root, inner.top)
        assertEquals(listOf(inner), root.childScopes)
    }

    @Test
    fun putSymbolRegistersInScopeAndTop() {
        val root = AstRoot(0)
        val sym = symbol("x")
        root.putSymbol(sym)

        assertSame(sym, root.getSymbol("x"))
        assertSame(root, sym.containingTable)
        assertEquals(listOf(sym), root.symbols)
    }

    @Test
    fun parametersBumpTheParamCount() {
        val fn = FunctionNode(0)
        fn.top = fn
        fn.putSymbol(symbol("a", Token.LP))
        fn.putSymbol(symbol("b", Token.LP))
        fn.putSymbol(symbol("c", Token.VAR))

        assertEquals(2, fn.paramCount)
        assertEquals(3, fn.symbols.size)
    }

    @Test
    fun getDefiningScopeWalksUpTheChain() {
        val root = AstRoot(0)
        val inner = Scope(4)
        root.addChildScope(inner)
        root.putSymbol(symbol("outer"))
        inner.putSymbol(symbol("inner"))

        assertSame(inner, inner.getDefiningScope("inner"))
        assertSame(root, inner.getDefiningScope("outer"))
        assertNull(inner.getDefiningScope("missing"))
    }

    @Test
    fun joinScopesMovesSymbolsAndRepointsThem() {
        val root = AstRoot(0)
        val source = Scope(4)
        val dest = Scope(8)
        root.addChildScope(source)
        root.addChildScope(dest)
        val sym = symbol("x")
        source.putSymbol(sym)

        Scope.joinScopes(source, dest)

        assertSame(sym, dest.getSymbol("x"))
        assertSame(dest, sym.containingTable)
    }

    @Test
    fun joinScopesRejectsOverlappingNames() {
        val root = AstRoot(0)
        val source = Scope(4)
        val dest = Scope(8)
        root.addChildScope(source)
        root.addChildScope(dest)
        source.putSymbol(symbol("x"))
        dest.putSymbol(symbol("x"))

        assertFailsWith<IllegalStateException> { Scope.joinScopes(source, dest) }
    }

    @Test
    fun splitScopeInsertsANewParent() {
        val root = AstRoot(0)
        val scope = Scope(4)
        root.addChildScope(scope)
        scope.putSymbol(symbol("x"))

        val inserted = Scope.splitScope(scope)

        assertSame(inserted, scope.parent)
        assertNull(scope.symbolTable)
        assertTrue(inserted.symbolTable!!.containsKey("x"))
        assertSame(root, inserted.parentScope)
    }

    @Test
    fun replaceWithMovesChildScopesAndSymbols() {
        val root = AstRoot(0)
        val old = Scope(4)
        val kid = Scope(6)
        root.addChildScope(old)
        old.addChildScope(kid)
        old.putSymbol(symbol("x"))

        val fresh = Scope(4)
        root.addChildScope(fresh)
        old.replaceWith(fresh)

        assertSame(fresh, kid.parentScope)
        assertNull(old.childScopes)
        assertEquals("x", fresh.getSymbol("x")?.name)
    }

    @Test
    fun flattenSymbolTableIndexesEverySymbol() {
        val fn = FunctionNode(0)
        fn.top = fn
        fn.putSymbol(symbol("a", Token.LP))
        fn.putSymbol(symbol("b", Token.CONST))
        fn.flattenSymbolTable(false)

        assertEquals(listOf("a", "b"), fn.paramAndVarNames.toList())
        assertEquals(listOf(false, true), fn.paramAndVarConst.toList())
        assertEquals(0, fn.symbols[0].index)
        assertEquals(1, fn.symbols[1].index)
    }

    @Test
    fun scopeRendersAsABlock() {
        val scope = Scope(0)
        scope.addChild(ExpressionStatement(Name(0, 1, "a")))
        assertEquals("{\n  a;\n}\n", scope.toSource())
    }

    @Test
    fun symbolRejectsInvalidDeclarationTypes() {
        assertFailsWith<IllegalArgumentException> { Symbol(Token.ADD, "x") }
        assertEquals("VAR", Symbol(Token.VAR, "x").declTypeName)
    }

    @Test
    fun statementsReturnsTheChildListAsAstNodes() {
        val scope = Scope(0)
        val a = ExpressionStatement(Name(0, 1, "a"))
        val b = ExpressionStatement(Name(2, 1, "b"))
        scope.addChild(a)
        scope.addChild(b)

        assertEquals(listOf<AstNode>(a, b), scope.statements)
    }
}
