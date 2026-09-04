/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.ast.Assignment
import io.github.yuroyami.kitejs.ast.BigIntLiteral
import io.github.yuroyami.kitejs.ast.Block
import io.github.yuroyami.kitejs.ast.BreakStatement
import io.github.yuroyami.kitejs.ast.ComputedPropertyKey
import io.github.yuroyami.kitejs.ast.ConditionalExpression
import io.github.yuroyami.kitejs.ast.ContinueStatement
import io.github.yuroyami.kitejs.ast.DoLoop
import io.github.yuroyami.kitejs.ast.ElementGet
import io.github.yuroyami.kitejs.ast.EmptyStatement
import io.github.yuroyami.kitejs.ast.ErrorNode
import io.github.yuroyami.kitejs.ast.ExpressionStatement
import io.github.yuroyami.kitejs.ast.FunctionCall
import io.github.yuroyami.kitejs.ast.GeneratorExpressionLoop
import io.github.yuroyami.kitejs.ast.GeneratorMethodDefinition
import io.github.yuroyami.kitejs.ast.IfStatement
import io.github.yuroyami.kitejs.ast.KeywordLiteral
import io.github.yuroyami.kitejs.ast.Label
import io.github.yuroyami.kitejs.ast.Name
import io.github.yuroyami.kitejs.ast.NumberLiteral
import io.github.yuroyami.kitejs.ast.ParenthesizedExpression
import io.github.yuroyami.kitejs.ast.ParseProblem
import io.github.yuroyami.kitejs.ast.Spread
import io.github.yuroyami.kitejs.ast.SwitchCase
import io.github.yuroyami.kitejs.ast.TaggedTemplateLiteral
import io.github.yuroyami.kitejs.ast.TemplateCharacters
import io.github.yuroyami.kitejs.ast.TemplateLiteral
import io.github.yuroyami.kitejs.ast.ThrowStatement
import io.github.yuroyami.kitejs.ast.UnaryExpression
import io.github.yuroyami.kitejs.ast.UpdateExpression
import io.github.yuroyami.kitejs.ast.WhileLoop
import io.github.yuroyami.kitejs.ast.WithStatement
import io.github.yuroyami.kitejs.ast.Yield
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.mozilla.javascript.Token as UToken
import org.mozilla.javascript.ast.Assignment as UAssignment
import org.mozilla.javascript.ast.BigIntLiteral as UBigIntLiteral
import org.mozilla.javascript.ast.Block as UBlock
import org.mozilla.javascript.ast.BreakStatement as UBreakStatement
import org.mozilla.javascript.ast.ComputedPropertyKey as UComputedPropertyKey
import org.mozilla.javascript.ast.ConditionalExpression as UConditionalExpression
import org.mozilla.javascript.ast.ContinueStatement as UContinueStatement
import org.mozilla.javascript.ast.DoLoop as UDoLoop
import org.mozilla.javascript.ast.ElementGet as UElementGet
import org.mozilla.javascript.ast.EmptyStatement as UEmptyStatement
import org.mozilla.javascript.ast.ErrorNode as UErrorNode
import org.mozilla.javascript.ast.ExpressionStatement as UExpressionStatement
import org.mozilla.javascript.ast.FunctionCall as UFunctionCall
import org.mozilla.javascript.ast.GeneratorExpressionLoop as UGeneratorExpressionLoop
import org.mozilla.javascript.ast.GeneratorMethodDefinition as UGeneratorMethodDefinition
import org.mozilla.javascript.ast.IfStatement as UIfStatement
import org.mozilla.javascript.ast.KeywordLiteral as UKeywordLiteral
import org.mozilla.javascript.ast.Label as ULabel
import org.mozilla.javascript.ast.Name as UName
import org.mozilla.javascript.ast.NumberLiteral as UNumberLiteral
import org.mozilla.javascript.ast.ParenthesizedExpression as UParenthesizedExpression
import org.mozilla.javascript.ast.ParseProblem as UParseProblem
import org.mozilla.javascript.ast.Spread as USpread
import org.mozilla.javascript.ast.SwitchCase as USwitchCase
import org.mozilla.javascript.ast.TaggedTemplateLiteral as UTaggedTemplateLiteral
import org.mozilla.javascript.ast.TemplateCharacters as UTemplateCharacters
import org.mozilla.javascript.ast.TemplateLiteral as UTemplateLiteral
import org.mozilla.javascript.ast.ThrowStatement as UThrowStatement
import org.mozilla.javascript.ast.UnaryExpression as UUnaryExpression
import org.mozilla.javascript.ast.UpdateExpression as UUpdateExpression
import org.mozilla.javascript.ast.WhileLoop as UWhileLoop
import org.mozilla.javascript.ast.WithStatement as UWithStatement
import org.mozilla.javascript.ast.Yield as UYield

/**
 * Differential test against the upstream Rhino jar for the leaf AST node types. Both sides
 * build the same hand-made node and the rendered source must match exactly.
 */
class AstLeafOracleTest {

    @Test
    fun assignmentMatchesUpstream() {
        val u = UAssignment(UToken.ASSIGN, UName(0, 1, "a"), UName(4, 1, "b"), 2)
        val k = Assignment(Token.ASSIGN, Name(0, 1, "a"), Name(4, 1, "b"), 2)
        assertEquals(u.toSource(0), k.toSource(0))

        val uAdd = UAssignment(UToken.ASSIGN_ADD, UName(0, 1, "a"), UName(5, 1, "b"), 2)
        val kAdd = Assignment(Token.ASSIGN_ADD, Name(0, 1, "a"), Name(5, 1, "b"), 2)
        assertEquals(uAdd.toSource(0), kAdd.toSource(0))
        assertTrue(kAdd.hasSideEffects())
        assertEquals(uAdd.hasSideEffects(), kAdd.hasSideEffects())
    }

    @Test
    fun bigIntLiteralMatchesUpstreamForDecimalDigits() {
        val u = UBigIntLiteral(0, "123n", BigInteger("123"))
        val k = BigIntLiteral(0, "123n", KBigInt("123", 10))
        assertEquals(u.toSource(0), k.toSource(0))

        assertEquals(UBigIntLiteral(0, "1n").toSource(0), BigIntLiteral(0, "1n").toSource(0))
    }

    @Test
    fun breakAndContinueMatchUpstream() {
        val ub = UBreakStatement(0, 6)
        val kb = BreakStatement(0, 6)
        assertEquals(ub.toSource(0), kb.toSource(0))

        ub.setBreakLabel(UName(6, 1, "L"))
        kb.breakLabel = Name(6, 1, "L")
        assertEquals(ub.toSource(0), kb.toSource(0))

        val uc = UContinueStatement(0, 9)
        val kc = ContinueStatement(0, 9)
        assertEquals(uc.toSource(0), kc.toSource(0))
        assertEquals(uc.getLength(), kc.length)

        // The one-argument form leaves the length at -1 upstream; confirm the port matches.
        assertEquals(UContinueStatement(3).getLength(), ContinueStatement(3).length)
        assertEquals(ULabel(3).getLength(), Label(3).length)

        val ucl = UContinueStatement(0, 12, UName(9, 1, "L"))
        val kcl = ContinueStatement(0, 12, Name(9, 1, "L"))
        assertEquals(ucl.toSource(0), kcl.toSource(0))
    }

    @Test
    fun computedPropertyKeyMatchesUpstream() {
        val u = UComputedPropertyKey(0, 5)
        u.setExpression(UName(1, 1, "k"))
        val k = ComputedPropertyKey(0, 5)
        k.expression = Name(1, 1, "k")
        assertEquals(u.toSource(0), k.toSource(0))
        assertEquals(u.hasSideEffects(), k.hasSideEffects())
    }

    @Test
    fun conditionalExpressionMatchesUpstream() {
        val u = UConditionalExpression(0, 9)
        u.setTestExpression(UName(0, 1, "a"))
        u.setTrueExpression(UName(4, 1, "b"))
        u.setFalseExpression(UName(8, 1, "c"))

        val k = ConditionalExpression(0, 9)
        k.testExpression = Name(0, 1, "a")
        k.trueExpression = Name(4, 1, "b")
        k.falseExpression = Name(8, 1, "c")

        assertEquals(u.toSource(0), k.toSource(0))
        assertEquals(u.toSource(2), k.toSource(2))
        assertEquals(u.hasSideEffects(), k.hasSideEffects())
    }

    @Test
    fun loopsMatchUpstream() {
        val ud = UDoLoop(0)
        ud.setCondition(UName(20, 1, "c"))
        val udb = UBlock(3)
        udb.addStatement(UExpressionStatement(UName(5, 1, "a")))
        ud.setBody(udb)

        val kd = DoLoop(0)
        kd.condition = Name(20, 1, "c")
        val kdb = Block(3)
        kdb.addStatement(ExpressionStatement(Name(5, 1, "a")))
        kd.body = kdb
        assertEquals(ud.toSource(0), kd.toSource(0))

        val uw = UWhileLoop(0)
        uw.setCondition(UName(7, 1, "c"))
        val uwb = UBlock(10)
        uwb.addStatement(UExpressionStatement(UName(12, 1, "a")))
        uw.setBody(uwb)

        val kw = WhileLoop(0)
        kw.condition = Name(7, 1, "c")
        val kwb = Block(10)
        kwb.addStatement(ExpressionStatement(Name(12, 1, "a")))
        kw.body = kwb
        assertEquals(uw.toSource(0), kw.toSource(0))

        // Non-block body takes the other branch of the printer.
        val uw2 = UWhileLoop(0)
        uw2.setCondition(UName(7, 1, "c"))
        uw2.setBody(UExpressionStatement(UName(10, 1, "a")))
        val kw2 = WhileLoop(0)
        kw2.condition = Name(7, 1, "c")
        kw2.body = ExpressionStatement(Name(10, 1, "a"))
        assertEquals(uw2.toSource(0), kw2.toSource(0))
    }

    @Test
    fun generatorExpressionLoopMatchesUpstream() {
        val u = UGeneratorExpressionLoop(0)
        u.setIterator(UName(5, 1, "k"))
        u.setIteratedObject(UName(10, 1, "o"))

        val k = GeneratorExpressionLoop(0)
        k.iterator = Name(5, 1, "k")
        k.iteratedObject = Name(10, 1, "o")

        assertEquals(u.toSource(0), k.toSource(0))
        assertEquals(u.isForEach(), k.isForEach)
        assertFailsWith<UnsupportedOperationException> { k.isForEach = true }
    }

    @Test
    fun elementGetAndCallMatchUpstream() {
        val ue = UElementGet(UName(0, 1, "a"), UName(2, 1, "b"))
        val ke = ElementGet(Name(0, 1, "a"), Name(2, 1, "b"))
        assertEquals(ue.toSource(0), ke.toSource(0))

        val uc = UFunctionCall(0, 6)
        uc.setTarget(UName(0, 1, "f"))
        uc.addArgument(UName(2, 1, "a"))
        uc.addArgument(UName(5, 1, "b"))

        val kc = FunctionCall(0, 6)
        kc.target = Name(0, 1, "f")
        kc.addArgument(Name(2, 1, "a"))
        kc.addArgument(Name(5, 1, "b"))
        assertEquals(uc.toSource(0), kc.toSource(0))

        uc.markIsOptionalCall()
        kc.markIsOptionalCall()
        assertEquals(uc.toSource(0), kc.toSource(0))
        assertEquals(uc.isOptionalCall(), kc.isOptionalCall)

        // No arguments at all renders differently from an empty list.
        val uEmpty = UFunctionCall(0, 3)
        uEmpty.setTarget(UName(0, 1, "f"))
        val kEmpty = FunctionCall(0, 3)
        kEmpty.target = Name(0, 1, "f")
        assertEquals(uEmpty.toSource(0), kEmpty.toSource(0))
    }

    @Test
    fun emptyAndErrorNodesMatchUpstream() {
        assertEquals(UEmptyStatement(0, 1).toSource(0), EmptyStatement(0, 1).toSource(0))
        assertEquals(UEmptyStatement(0, 1).toSource(3), EmptyStatement(0, 1).toSource(3))
        assertEquals(UErrorNode(0, 4).toSource(0), ErrorNode(0, 4).toSource(0))
    }

    @Test
    fun generatorMethodDefinitionMatchesUpstream() {
        val u = UGeneratorMethodDefinition(0, 5, UName(1, 4, "next"))
        val k = GeneratorMethodDefinition(0, 5, Name(1, 4, "next"))
        assertEquals(u.toSource(0), k.toSource(0))
        assertEquals(u.getType(), k.type)
    }

    @Test
    fun ifStatementMatchesUpstream() {
        fun build(withElse: Boolean, blockBodies: Boolean): Pair<String, String> {
            val u = UIfStatement(0)
            u.setCondition(UName(4, 1, "c"))
            u.setThenPart(
                if (blockBodies) {
                    UBlock(7).apply { addStatement(UExpressionStatement(UName(9, 1, "a"))) }
                } else {
                    UExpressionStatement(UName(7, 1, "a"))
                },
            )
            if (withElse) {
                u.setElsePart(
                    if (blockBodies) {
                        UBlock(20).apply { addStatement(UExpressionStatement(UName(22, 1, "b"))) }
                    } else {
                        UExpressionStatement(UName(20, 1, "b"))
                    },
                )
            }

            val k = IfStatement(0)
            k.condition = Name(4, 1, "c")
            k.thenPart =
                if (blockBodies) {
                    Block(7).apply { addStatement(ExpressionStatement(Name(9, 1, "a"))) }
                } else {
                    ExpressionStatement(Name(7, 1, "a"))
                }
            if (withElse) {
                k.elsePart =
                    if (blockBodies) {
                        Block(20).apply { addStatement(ExpressionStatement(Name(22, 1, "b"))) }
                    } else {
                        ExpressionStatement(Name(20, 1, "b"))
                    }
            }
            return u.toSource(0) to k.toSource(0)
        }

        for (withElse in listOf(false, true)) {
            for (blockBodies in listOf(false, true)) {
                val (up, ported) = build(withElse, blockBodies)
                assertEquals(up, ported, "withElse=$withElse blockBodies=$blockBodies")
            }
        }
    }

    @Test
    fun keywordLiteralsMatchUpstream() {
        val types = listOf(
            UToken.THIS to Token.THIS,
            UToken.SUPER to Token.SUPER,
            UToken.NULL to Token.NULL,
            UToken.UNDEFINED to Token.UNDEFINED,
            UToken.TRUE to Token.TRUE,
            UToken.FALSE to Token.FALSE,
            UToken.DEBUGGER to Token.DEBUGGER,
        )
        for ((ut, kt) in types) {
            val u = UKeywordLiteral(0, 4, ut)
            val k = KeywordLiteral(0, 4, kt)
            assertEquals(u.toSource(0), k.toSource(0), "token $kt")
            assertEquals(u.isBooleanLiteral(), k.isBooleanLiteral)
        }
        assertFailsWith<IllegalArgumentException> { KeywordLiteral(0, 1, Token.NAME) }
    }

    @Test
    fun labelMatchesUpstream() {
        val u = ULabel(0, 6, "outer")
        val k = Label(0, 6, "outer")
        assertEquals(u.toSource(0), k.toSource(0))
        assertEquals(u.getName(), k.name)
        assertFailsWith<IllegalArgumentException> { Label(0, 1, "  ") }
    }

    @Test
    fun parenthesizedAndSpreadMatchUpstream() {
        val u = UParenthesizedExpression(UName(1, 1, "a"))
        val k = ParenthesizedExpression(Name(1, 1, "a"))
        assertEquals(u.toSource(0), k.toSource(0))

        val us = USpread(0, 4)
        us.setExpression(UName(3, 1, "a"))
        val ks = Spread(0, 4)
        ks.expression = Name(3, 1, "a")
        assertEquals(us.toSource(0), ks.toSource(0))
        assertEquals(us.hasSideEffects(), ks.hasSideEffects())
    }

    @Test
    fun parseProblemMatchesUpstream() {
        val u = UParseProblem(UParseProblem.Type.Error, "boom", "a.js", 4, 2)
        val k = ParseProblem(ParseProblem.Type.Error, "boom", "a.js", 4, 2)
        assertEquals(u.toString(), k.toString())

        val uw = UParseProblem(UParseProblem.Type.Warning, "hmm", "a.js", 1, 1)
        val kw = ParseProblem(ParseProblem.Type.Warning, "hmm", "a.js", 1, 1)
        assertEquals(uw.toString(), kw.toString())
    }

    @Test
    fun switchCaseMatchesUpstream() {
        val u = USwitchCase(0)
        u.setExpression(UName(5, 1, "a"))
        u.addStatement(UExpressionStatement(UName(8, 1, "b")))
        val k = SwitchCase(0)
        k.expression = Name(5, 1, "a")
        k.addStatement(ExpressionStatement(Name(8, 1, "b")))
        assertEquals(u.toSource(0), k.toSource(0))
        assertEquals(u.getLength(), k.length)
        assertEquals(u.isDefault(), k.isDefault)

        val ud = USwitchCase(0)
        ud.addStatement(UExpressionStatement(UName(9, 1, "b")))
        val kd = SwitchCase(0)
        kd.addStatement(ExpressionStatement(Name(9, 1, "b")))
        assertEquals(ud.toSource(0), kd.toSource(0))
        assertEquals(ud.isDefault(), kd.isDefault)
    }

    @Test
    fun taggedTemplateMatchesUpstream() {
        val ut = UTemplateLiteral(0)
        ut.addElement(UTemplateCharacters(0).apply { setValue("a"); setRawValue("a") })
        val u = UTaggedTemplateLiteral(0, 6)
        u.setTarget(UName(0, 3, "tag"))
        u.setTemplateLiteral(ut)

        val kt = TemplateLiteral(0)
        kt.addElement(TemplateCharacters(0).apply { value = "a"; rawValue = "a" })
        val k = TaggedTemplateLiteral(0, 6)
        k.target = Name(0, 3, "tag")
        k.templateLiteral = kt

        assertEquals(u.toSource(0), k.toSource(0))
    }

    @Test
    fun throwStatementMatchesUpstream() {
        val u = UThrowStatement(0, 9, UName(6, 1, "e"))
        val k = ThrowStatement(0, 9, Name(6, 1, "e"))
        assertEquals(u.toSource(0), k.toSource(0))
        assertEquals(u.toSource(1), k.toSource(1))

        // The (pos, expr) form derives the length from the expression.
        assertEquals(
            UThrowStatement(0, UName(6, 1, "e")).getLength(),
            ThrowStatement(0, Name(6, 1, "e")).length,
        )
    }

    @Test
    fun unaryAndUpdateExpressionsMatchUpstream() {
        val unaryOps = listOf(
            UToken.NOT to Token.NOT,
            UToken.BITNOT to Token.BITNOT,
            UToken.NEG to Token.NEG,
            UToken.POS to Token.POS,
            UToken.TYPEOF to Token.TYPEOF,
            UToken.VOID to Token.VOID,
            UToken.DELPROP to Token.DELPROP,
        )
        for ((ut, kt) in unaryOps) {
            val u = UUnaryExpression(ut, 0, UName(1, 1, "a"))
            val k = UnaryExpression(kt, 0, Name(1, 1, "a"))
            assertEquals(u.toSource(0), k.toSource(0), "unary $kt")
            assertEquals(u.getPosition(), k.position)
            assertEquals(u.getLength(), k.length)
        }

        for (postfix in listOf(false, true)) {
            for ((ut, kt) in listOf(UToken.INC to Token.INC, UToken.DEC to Token.DEC)) {
                val u = UUpdateExpression(ut, 3, UName(1, 1, "a"), postfix)
                val k = UpdateExpression(kt, 3, Name(1, 1, "a"), postfix)
                assertEquals(u.toSource(0), k.toSource(0), "update $kt postfix=$postfix")
                assertEquals(u.getPosition(), k.position)
                assertEquals(u.getLength(), k.length)
                assertEquals(u.isPrefix(), k.isPrefix)
            }
        }
    }

    @Test
    fun withStatementMatchesUpstream() {
        val u = UWithStatement(0)
        u.setExpression(UName(6, 1, "o"))
        u.setStatement(UBlock(9).apply { addStatement(UExpressionStatement(UName(11, 1, "a"))) })
        val k = WithStatement(0)
        k.expression = Name(6, 1, "o")
        k.statement = Block(9).apply { addStatement(ExpressionStatement(Name(11, 1, "a"))) }
        assertEquals(u.toSource(0), k.toSource(0))

        val u2 = UWithStatement(0)
        u2.setExpression(UName(6, 1, "o"))
        u2.setStatement(UExpressionStatement(UName(9, 1, "a")))
        val k2 = WithStatement(0)
        k2.expression = Name(6, 1, "o")
        k2.statement = ExpressionStatement(Name(9, 1, "a"))
        assertEquals(u2.toSource(0), k2.toSource(0))
    }

    @Test
    fun yieldMatchesUpstream() {
        assertEquals(UYield(0, 5).toSource(0), Yield(0, 5).toSource(0))

        for (isStar in listOf(false, true)) {
            val u = UYield(0, 7, UName(6, 1, "a"), isStar)
            val k = Yield(0, 7, Name(6, 1, "a"), isStar)
            assertEquals(u.toSource(0), k.toSource(0), "isStar=$isStar")
            assertEquals(u.getType(), k.type)
        }
    }

    @Test
    fun numberLiteralRoundTripsMatchUpstream() {
        for (d in listOf(0.0, 1.0, -1.5, 1e21, 0.1, Double.NaN)) {
            assertEquals(UNumberLiteral(d).toSource(0), NumberLiteral(d).toSource(0), "d=$d")
        }
    }
}
