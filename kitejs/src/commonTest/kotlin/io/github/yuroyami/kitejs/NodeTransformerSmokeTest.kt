/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.ast.ScriptNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs the transform pass on every target. The exhaustive comparison against upstream lives in the
 * jvmTest oracle; this checks the pass runs and produces the expected shapes everywhere.
 */
class NodeTransformerSmokeTest {

    private fun transform(source: String, strict: Boolean = false): ScriptNode {
        val env = CompilerEnvirons()
        env.languageVersion = Context.VERSION_ES6
        val ast = Parser(env).parse(source, "smoke.js", 1)
        val tree = assertNotNull(
            IRFactory(env, "smoke.js", source, env.errorReporter).transformTree(ast),
        )
        NodeTransformer().transform(tree, strict, env)
        return tree
    }

    private fun typesOf(n: Node): List<Int> {
        val out = mutableListOf<Int>()
        fun walk(node: Node) {
            out.add(node.type)
            var kid = node.firstChild
            while (kid != null) {
                walk(kid)
                kid = kid.next
            }
        }
        walk(n)
        return out
    }

    @Test
    fun breakBecomesAGoto() {
        val types = typesOf(transform("while (a) { break; }"))
        assertTrue(types.contains(Token.GOTO))
        assertTrue(!types.contains(Token.BREAK), "break should have become a goto")
    }

    @Test
    fun continueBecomesAGoto() {
        val types = typesOf(transform("while (a) { continue; }"))
        assertTrue(types.contains(Token.GOTO))
        assertTrue(!types.contains(Token.CONTINUE), "continue should have become a goto")
    }

    @Test
    fun labeledBreakBecomesAGoto() {
        val types = typesOf(transform("outer: while (a) { break outer; }"))
        assertTrue(!types.contains(Token.BREAK))
        assertTrue(types.contains(Token.GOTO))
    }

    @Test
    fun varDeclarationsBecomeAssignments() {
        val types = typesOf(transform("var a = 1;"))
        assertTrue(types.contains(Token.SETNAME) || types.contains(Token.SETVAR))
        assertTrue(!types.contains(Token.VAR), "the var node should have been rewritten")
    }

    @Test
    fun constDeclarationsUseSetConst() {
        val types = typesOf(transform("const a = 1;"))
        assertTrue(
            types.contains(Token.SETCONST) || types.contains(Token.SETCONSTVAR),
            "expected a const store",
        )
    }

    @Test
    fun namesInsideAFunctionResolveToVariableSlots() {
        // A function without an activation flattens its symbol table, so local names become
        // variable slots rather than name lookups.
        val tree = transform("function f() { var a = 1; return a; }")
        val fnTypes = typesOf(tree.getFunctionNode(0))
        assertTrue(fnTypes.contains(Token.GETVAR), "a local read should become GETVAR")
        assertTrue(fnTypes.contains(Token.SETVAR), "a local write should become SETVAR")
    }

    @Test
    fun namesStayAsLookupsWhenTheFunctionNeedsAnActivation() {
        // eval forces an activation, which keeps the scope objects and the name lookups.
        val tree = transform("function f() { var a = 1; eval('a'); return a; }")
        val fnTypes = typesOf(tree.getFunctionNode(0))
        assertTrue(tree.getFunctionNode(0).requiresActivation)
        assertTrue(!fnTypes.contains(Token.GETVAR), "an activation keeps name lookups")
    }

    @Test
    fun strictModeRewritesStores() {
        val types = typesOf(transform("a = 1;", strict = true))
        assertTrue(types.contains(Token.STRICT_SETNAME), "strict mode uses STRICT_SETNAME")
    }

    @Test
    fun finallyIsThreadedOntoReturn() {
        val tree = transform("function f() { try { return 1; } finally { g(); } }")
        val fnTypes = typesOf(tree.getFunctionNode(0))
        assertTrue(fnTypes.contains(Token.JSR), "a return inside try must jsr to the finally")
        assertTrue(fnTypes.contains(Token.RETURN_RESULT))
    }

    @Test
    fun typeofOnAPropertySuppressesTheWarning() {
        val types = typesOf(transform("typeof o.p;"))
        assertTrue(
            types.contains(Token.GETPROPNOWARN),
            "typeof o.p should not warn about an undefined property",
        )
    }

    @Test
    fun generatorReturnsAreMarked() {
        val tree = transform("function* g() { yield 1; return 2; }")
        val fn = tree.getFunctionNode(0)
        assertTrue(fn.isGenerator)
        assertEquals(1, fn.resumptionPoints?.size)
    }

    @Test
    fun blockScopesBecomeWithObjectsWhenAnActivationIsNeeded() {
        val tree = transform("function f() { eval('x'); { let a = 1; g(a); } }")
        val fnTypes = typesOf(tree.getFunctionNode(0))
        assertTrue(fnTypes.contains(Token.ENTERWITH), "a let block becomes a with under activation")
        assertTrue(fnTypes.contains(Token.LEAVEWITH))
    }
}
