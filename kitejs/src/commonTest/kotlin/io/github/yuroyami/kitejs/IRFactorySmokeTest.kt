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
 * Lowers a few scripts to IR on every target. The exhaustive comparison against upstream lives in
 * the jvmTest oracle; this checks the transform runs and produces the expected shapes everywhere.
 */
class IRFactorySmokeTest {

    private fun lower(source: String): ScriptNode {
        val env = CompilerEnvirons()
        env.languageVersion = Context.VERSION_ES6
        val ast = Parser(env).parse(source, "smoke.js", 1)
        val tree = IRFactory(env, "smoke.js", source, env.errorReporter).transformTree(ast)
        return assertNotNull(tree, "the transform produced no tree for: $source")
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
    fun lowersAnEmptyScript() {
        val tree = lower("")
        assertEquals(Token.SCRIPT, tree.type)
    }

    @Test
    fun loopsBecomeLabeledJumps() {
        val types = typesOf(lower("while (a) { b(); }"))
        // A while loop lowers to a target, a jump and a conditional jump.
        assertTrue(types.contains(Token.LOOP), "expected a LOOP node")
        assertTrue(types.contains(Token.TARGET), "expected jump targets")
        assertTrue(types.contains(Token.IFEQ), "expected a conditional jump")
        assertTrue(types.contains(Token.GOTO), "expected a goto to the condition")
    }

    @Test
    fun ifStatementsBecomeConditionalJumps() {
        val types = typesOf(lower("if (a) { b(); } else { c(); }"))
        assertTrue(types.contains(Token.IFNE))
        assertTrue(types.contains(Token.GOTO))
        assertTrue(types.contains(Token.TARGET))
    }

    @Test
    fun switchBecomesAJumpTable() {
        val types = typesOf(lower("switch (a) { case 1: b(); break; default: c(); }"))
        assertTrue(types.contains(Token.SWITCH))
        assertTrue(types.contains(Token.CASE))
        assertTrue(types.contains(Token.GOTO))
    }

    @Test
    fun tryCatchBecomesLocalBlocksAndScopes() {
        val types = typesOf(lower("try { a(); } catch (e) { b(); } finally { c(); }"))
        assertTrue(types.contains(Token.LOCAL_BLOCK))
        assertTrue(types.contains(Token.TRY))
        assertTrue(types.contains(Token.CATCH_SCOPE))
        assertTrue(types.contains(Token.FINALLY))
        assertTrue(types.contains(Token.JSR), "the finally is reached through a jsr")
    }

    @Test
    fun constantsAreFolded() {
        // The IR factory folds arithmetic and string concatenation on literals.
        val types = typesOf(lower("var a = 1 + 2;"))
        assertTrue(types.contains(Token.NUMBER))
        assertTrue(!types.contains(Token.ADD), "1 + 2 should have been folded")

        val strTypes = typesOf(lower("var a = 'x' + 'y';"))
        assertTrue(!strTypes.contains(Token.ADD), "'x' + 'y' should have been folded")
    }

    @Test
    fun deadBranchesAreDropped() {
        // if (false) collapses, because the condition is always false.
        val types = typesOf(lower("if (false) { a(); }"))
        assertTrue(!types.contains(Token.IFNE), "an always-false if should collapse")
    }

    @Test
    fun functionsAreRegisteredInTheFunctionTable() {
        val tree = lower("function f() { return 1; } function g() { return 2; }")
        assertEquals(2, tree.functionCount)
        assertEquals("f", tree.getFunctionNode(0).name)
        assertEquals("g", tree.getFunctionNode(1).name)
    }

    @Test
    fun evalCallsForceAnActivation() {
        val tree = lower("function f() { return eval('1'); }")
        assertTrue(tree.getFunctionNode(0).requiresActivation)
    }

    @Test
    fun regexpsAndTemplatesAreRegistered() {
        assertEquals(1, lower("var a = /x/g;").regexpCount)
        assertEquals(1, lower("tag`a`;").templateLiteralCount)
    }

    @Test
    fun destructuringBecomesPlainAssignments() {
        val types = typesOf(lower("var [a, b] = c;"))
        // Destructuring lowers into a let scope holding a comma sequence of setname operations.
        assertTrue(types.contains(Token.LETEXPR))
        assertTrue(types.contains(Token.COMMA))
        assertTrue(types.contains(Token.SETNAME))
    }

    @Test
    fun forInBecomesAnEnumeration() {
        val types = typesOf(lower("for (var k in o) { a(k); }"))
        assertTrue(types.contains(Token.ENUM_INIT_KEYS))
        assertTrue(types.contains(Token.ENUM_NEXT))
        assertTrue(types.contains(Token.ENUM_ID))
    }

    @Test
    fun forOfUsesTheOrderedEnumeration() {
        val types = typesOf(lower("for (var v of list) { a(v); }"))
        assertTrue(types.contains(Token.ENUM_INIT_VALUES_IN_ORDER))
    }

    @Test
    fun withForcesAnActivation() {
        val tree = lower("function f() { with (o) { a(); } }")
        val fn = tree.getFunctionNode(0)
        assertTrue(fn.requiresActivation, "a with statement forces an activation object")

        // A nested function is its own tree. It is reachable through the function table, not
        // through the script node's child chain, so the body has to be walked from the function.
        val types = typesOf(fn)
        assertTrue(types.contains(Token.ENTERWITH))
        assertTrue(types.contains(Token.LEAVEWITH))
    }

    @Test
    fun aNestedFunctionBodyIsNotInTheScriptChildChain() {
        // The script node holds only a FUNCTION marker; the body lives in the function table.
        val tree = lower("function f() { a(); }")
        assertEquals(listOf(Token.SCRIPT, Token.FUNCTION), typesOf(tree))
        assertEquals(1, tree.functionCount)
    }

    @Test
    fun templateLiteralsBecomeStringConcatenation() {
        val types = typesOf(lower("var a = `x\${y}z`;"))
        assertTrue(types.contains(Token.STRING_CONCAT))
    }
}
