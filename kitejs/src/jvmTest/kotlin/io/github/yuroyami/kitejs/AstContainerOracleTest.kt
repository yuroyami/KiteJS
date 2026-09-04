/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.ast.ArrayLiteral
import io.github.yuroyami.kitejs.ast.Block
import io.github.yuroyami.kitejs.ast.CatchClause
import io.github.yuroyami.kitejs.ast.ErrorCollector
import io.github.yuroyami.kitejs.ast.ExpressionStatement
import io.github.yuroyami.kitejs.ast.ForLoop
import io.github.yuroyami.kitejs.ast.GeneratorExpression
import io.github.yuroyami.kitejs.ast.GeneratorExpressionLoop
import io.github.yuroyami.kitejs.ast.Label
import io.github.yuroyami.kitejs.ast.LabeledStatement
import io.github.yuroyami.kitejs.ast.LetNode
import io.github.yuroyami.kitejs.ast.Name
import io.github.yuroyami.kitejs.ast.NewExpression
import io.github.yuroyami.kitejs.ast.NumberLiteral
import io.github.yuroyami.kitejs.ast.ObjectLiteral
import io.github.yuroyami.kitejs.ast.ObjectProperty
import io.github.yuroyami.kitejs.ast.Scope
import io.github.yuroyami.kitejs.ast.Spread
import io.github.yuroyami.kitejs.ast.SpreadObjectProperty
import io.github.yuroyami.kitejs.ast.SwitchCase
import io.github.yuroyami.kitejs.ast.SwitchStatement
import io.github.yuroyami.kitejs.ast.TryStatement
import io.github.yuroyami.kitejs.ast.VariableDeclaration
import io.github.yuroyami.kitejs.ast.VariableInitializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mozilla.javascript.Token as UToken
import org.mozilla.javascript.ast.ArrayLiteral as UArrayLiteral
import org.mozilla.javascript.ast.Block as UBlock
import org.mozilla.javascript.ast.CatchClause as UCatchClause
import org.mozilla.javascript.ast.ErrorCollector as UErrorCollector
import org.mozilla.javascript.ast.ExpressionStatement as UExpressionStatement
import org.mozilla.javascript.ast.ForLoop as UForLoop
import org.mozilla.javascript.ast.GeneratorExpression as UGeneratorExpression
import org.mozilla.javascript.ast.GeneratorExpressionLoop as UGeneratorExpressionLoop
import org.mozilla.javascript.ast.Label as ULabel
import org.mozilla.javascript.ast.LabeledStatement as ULabeledStatement
import org.mozilla.javascript.ast.LetNode as ULetNode
import org.mozilla.javascript.ast.Name as UName
import org.mozilla.javascript.ast.NewExpression as UNewExpression
import org.mozilla.javascript.ast.NumberLiteral as UNumberLiteral
import org.mozilla.javascript.ast.ObjectLiteral as UObjectLiteral
import org.mozilla.javascript.ast.ObjectProperty as UObjectProperty
import org.mozilla.javascript.ast.Scope as UScope
import org.mozilla.javascript.ast.Spread as USpread
import org.mozilla.javascript.ast.SpreadObjectProperty as USpreadObjectProperty
import org.mozilla.javascript.ast.SwitchCase as USwitchCase
import org.mozilla.javascript.ast.SwitchStatement as USwitchStatement
import org.mozilla.javascript.ast.TryStatement as UTryStatement
import org.mozilla.javascript.ast.VariableDeclaration as UVariableDeclaration
import org.mozilla.javascript.ast.VariableInitializer as UVariableInitializer

/**
 * Differential test against the upstream Rhino jar for the container AST node types: the
 * literals, declarations and control-flow statements that hold other nodes.
 */
class AstContainerOracleTest {

    @Test
    fun arrayLiteralMatchesUpstream() {
        val u = UArrayLiteral(0, 8)
        u.addElement(UName(1, 1, "a"))
        u.addElement(UNumberLiteral(4, "2"))

        val k = ArrayLiteral(0, 8)
        k.addElement(Name(1, 1, "a"))
        k.addElement(NumberLiteral(4, "2"))

        assertEquals(u.toSource(0), k.toSource(0))
        assertEquals(u.getSize(), k.size)
        assertEquals(u.isDestructuring(), k.isDestructuring)

        u.setIsDestructuring(true)
        k.isDestructuring = true
        assertEquals(u.isDestructuring(), k.isDestructuring)

        assertEquals(UArrayLiteral(0, 2).toSource(0), ArrayLiteral(0, 2).toSource(0))
    }

    @Test
    fun objectLiteralAndPropertiesMatchUpstream() {
        val up = UObjectProperty()
        up.setKeyAndValue(UName(2, 1, "a"), UNumberLiteral(5, "1"))
        val up2 = UObjectProperty()
        up2.setKeyAndValue(UName(8, 1, "b"), UNumberLiteral(11, "2"))
        val u = UObjectLiteral(0, 14)
        u.addElement(up)
        u.addElement(up2)

        val kp = ObjectProperty()
        kp.setKeyAndValue(Name(2, 1, "a"), NumberLiteral(5, "1"))
        val kp2 = ObjectProperty()
        kp2.setKeyAndValue(Name(8, 1, "b"), NumberLiteral(11, "2"))
        val k = ObjectLiteral(0, 14)
        k.addElement(kp)
        k.addElement(kp2)

        assertEquals(u.toSource(0), k.toSource(0))
        assertEquals(u.toSource(1), k.toSource(1))
        assertEquals(UObjectLiteral(0, 2).toSource(0), ObjectLiteral(0, 2).toSource(0))
    }

    @Test
    fun getterAndSetterPropertiesMatchUpstream() {
        val u = UObjectProperty()
        u.setKeyAndValue(UName(6, 1, "a"), UName(8, 1, "f"))
        u.setIsGetterMethod()

        val k = ObjectProperty()
        k.setKeyAndValue(Name(6, 1, "a"), Name(8, 1, "f"))
        k.setIsGetterMethod()

        assertEquals(u.toSource(0), k.toSource(0))
        assertEquals(u.isGetterMethod(), k.isGetterMethod)
        assertEquals(u.isMethod(), k.isMethod)

        u.setIsSetterMethod()
        k.setIsSetterMethod()
        assertEquals(u.toSource(0), k.toSource(0))

        u.setIsNormalMethod()
        k.setIsNormalMethod()
        assertEquals(u.toSource(0), k.toSource(0))
        assertEquals(u.isNormalMethod(), k.isNormalMethod)

        assertFailsWith<IllegalArgumentException> { ObjectProperty().setNodeType(Token.NAME) }
    }

    @Test
    fun spreadObjectPropertyMatchesUpstream() {
        val us = USpread(2, 4)
        us.setExpression(UName(5, 1, "a"))
        val u = USpreadObjectProperty(us)

        val ks = Spread(2, 4)
        ks.expression = Name(5, 1, "a")
        val k = SpreadObjectProperty(ks)

        assertEquals(u.toSource(0), k.toSource(0))
        assertEquals(u.hasSideEffects(), k.hasSideEffects())
        assertEquals(u.getPosition(), k.position)
        assertEquals(u.getLength(), k.length)
    }

    @Test
    fun switchStatementMatchesUpstream() {
        val uc = USwitchCase(11)
        uc.setExpression(UName(16, 1, "a"))
        uc.addStatement(UExpressionStatement(UName(19, 1, "b")))
        val ud = USwitchCase(22)
        ud.addStatement(UExpressionStatement(UName(31, 1, "c")))
        val u = USwitchStatement(0)
        u.setExpression(UName(8, 1, "x"))
        u.addCase(uc)
        u.addCase(ud)

        val kc = SwitchCase(11)
        kc.expression = Name(16, 1, "a")
        kc.addStatement(ExpressionStatement(Name(19, 1, "b")))
        val kd = SwitchCase(22)
        kd.addStatement(ExpressionStatement(Name(31, 1, "c")))
        val k = SwitchStatement(0)
        k.expression = Name(8, 1, "x")
        k.addCase(kc)
        k.addCase(kd)

        assertEquals(u.toSource(0), k.toSource(0))
        assertEquals(u.toSource(1), k.toSource(1))
        assertEquals(u.getCases().size, k.getCases().size)
        assertTrue(USwitchStatement(0).getCases().isEmpty())
        assertTrue(SwitchStatement(0).getCases().isEmpty())
    }

    @Test
    fun labeledStatementMatchesUpstream() {
        val u = ULabeledStatement(0)
        u.addLabel(ULabel(0, 6, "outer"))
        u.addLabel(ULabel(7, 6, "inner"))
        u.setStatement(UExpressionStatement(UName(14, 1, "a")))

        val k = LabeledStatement(0)
        k.addLabel(Label(0, 6, "outer"))
        k.addLabel(Label(7, 6, "inner"))
        k.statement = ExpressionStatement(Name(14, 1, "a"))

        assertEquals(u.toSource(0), k.toSource(0))
        assertEquals(u.hasSideEffects(), k.hasSideEffects())
        assertEquals(u.getFirstLabel().getName(), k.firstLabel.name)
        assertEquals(u.getLabelByName("inner")?.getName(), k.getLabelByName("inner")?.name)
        assertNull(u.getLabelByName("nope"))
        assertNull(k.getLabelByName("nope"))
    }

    @Test
    fun tryStatementMatchesUpstream() {
        val ucatchBody = UScope(20)
        ucatchBody.addChild(UExpressionStatement(UName(22, 1, "b")))
        val ucc = UCatchClause(10)
        ucc.setVarName(UName(17, 1, "e"))
        ucc.setBody(ucatchBody)

        val u = UTryStatement(0)
        val utryBlock = UBlock(4)
        utryBlock.addStatement(UExpressionStatement(UName(6, 1, "a")))
        u.setTryBlock(utryBlock)
        u.addCatchClause(ucc)

        val kcatchBody = Scope(20)
        kcatchBody.addChild(ExpressionStatement(Name(22, 1, "b")))
        val kcc = CatchClause(10)
        kcc.varName = Name(17, 1, "e")
        kcc.body = kcatchBody

        val k = TryStatement(0)
        val ktryBlock = Block(4)
        ktryBlock.addStatement(ExpressionStatement(Name(6, 1, "a")))
        k.tryBlock = ktryBlock
        k.addCatchClause(kcc)

        assertEquals(u.toSource(0), k.toSource(0))

        val ufinally = UBlock(30)
        ufinally.addStatement(UExpressionStatement(UName(32, 1, "c")))
        u.setFinallyBlock(ufinally)
        val kfinally = Block(30)
        kfinally.addStatement(ExpressionStatement(Name(32, 1, "c")))
        k.finallyBlock = kfinally
        assertEquals(u.toSource(0), k.toSource(0))
    }

    @Test
    fun catchClauseWithGuardMatchesUpstream() {
        val ubody = UScope(20)
        val u = UCatchClause(0)
        u.setVarName(UName(7, 1, "e"))
        u.setCatchCondition(UName(12, 1, "c"))
        u.setBody(ubody)

        val kbody = Scope(20)
        val k = CatchClause(0)
        k.varName = Name(7, 1, "e")
        k.catchCondition = Name(12, 1, "c")
        k.body = kbody

        assertEquals(u.toSource(0), k.toSource(0))
    }

    @Test
    fun forLoopMatchesUpstream() {
        val u = UForLoop(0)
        u.setInitializer(UName(5, 1, "i"))
        u.setCondition(UName(8, 1, "c"))
        u.setIncrement(UName(11, 1, "n"))
        val ub = UBlock(14)
        ub.addStatement(UExpressionStatement(UName(16, 1, "a")))
        u.setBody(ub)

        val k = ForLoop(0)
        k.initializer = Name(5, 1, "i")
        k.condition = Name(8, 1, "c")
        k.increment = Name(11, 1, "n")
        val kb = Block(14)
        kb.addStatement(ExpressionStatement(Name(16, 1, "a")))
        k.body = kb

        assertEquals(u.toSource(0), k.toSource(0))
        assertEquals(u.getLength(), k.length)

        // Non-block body takes the other branch of the printer.
        val u2 = UForLoop(0)
        u2.setInitializer(UName(5, 1, "i"))
        u2.setCondition(UName(8, 1, "c"))
        u2.setIncrement(UName(11, 1, "n"))
        u2.setBody(UExpressionStatement(UName(14, 1, "a")))
        val k2 = ForLoop(0)
        k2.initializer = Name(5, 1, "i")
        k2.condition = Name(8, 1, "c")
        k2.increment = Name(11, 1, "n")
        k2.body = ExpressionStatement(Name(14, 1, "a"))
        assertEquals(u2.toSource(0), k2.toSource(0))
    }

    @Test
    fun variableDeclarationMatchesUpstream() {
        for ((utype, ktype) in listOf(
            UToken.VAR to Token.VAR,
            UToken.CONST to Token.CONST,
            UToken.LET to Token.LET,
        )) {
            val uvi = UVariableInitializer(4)
            uvi.setTarget(UName(4, 1, "a"))
            uvi.setInitializer(UNumberLiteral(8, "1"))
            val u = UVariableDeclaration(0)
            u.setType(utype)
            u.addVariable(uvi)
            u.setIsStatement(true)

            val kvi = VariableInitializer(4)
            kvi.target = Name(4, 1, "a")
            kvi.initializer = NumberLiteral(8, "1")
            val k = VariableDeclaration(0)
            k.type = ktype
            k.addVariable(kvi)
            k.isStatement = true

            assertEquals(u.toSource(0), k.toSource(0), "decl type $ktype")
            assertEquals(u.isVar(), k.isVar)
            assertEquals(u.isConst(), k.isConst)
            assertEquals(u.isLet(), k.isLet)
        }

        assertFailsWith<IllegalArgumentException> { VariableDeclaration(0).type = Token.NAME }
        assertFailsWith<IllegalArgumentException> { VariableInitializer(0).setNodeType(Token.NAME) }
    }

    @Test
    fun variableInitializerMatchesUpstream() {
        val uBare = UVariableInitializer(0)
        uBare.setTarget(UName(0, 1, "a"))
        val kBare = VariableInitializer(0)
        kBare.target = Name(0, 1, "a")
        assertEquals(uBare.toSource(0), kBare.toSource(0))
        assertEquals(uBare.isDestructuring(), kBare.isDestructuring)

        val uDestructuring = UVariableInitializer(0)
        uDestructuring.setTarget(UArrayLiteral(0, 6))
        val kDestructuring = VariableInitializer(0)
        kDestructuring.target = ArrayLiteral(0, 6)
        assertEquals(uDestructuring.isDestructuring(), kDestructuring.isDestructuring)
    }

    @Test
    fun letNodeMatchesUpstream() {
        val uvi = UVariableInitializer(5)
        uvi.setTarget(UName(5, 1, "a"))
        uvi.setInitializer(UNumberLiteral(9, "1"))
        val uvd = UVariableDeclaration(5)
        uvd.addVariable(uvi)
        val u = ULetNode(0)
        u.setVariables(uvd)
        u.setBody(UBlock(12).apply { addStatement(UExpressionStatement(UName(14, 1, "b"))) })

        val kvi = VariableInitializer(5)
        kvi.target = Name(5, 1, "a")
        kvi.initializer = NumberLiteral(9, "1")
        val kvd = VariableDeclaration(5)
        kvd.addVariable(kvi)
        val k = LetNode(0)
        k.variables = kvd
        k.body = Block(12).apply { addStatement(ExpressionStatement(Name(14, 1, "b"))) }

        assertEquals(u.toSource(0), k.toSource(0))
    }

    @Test
    fun newExpressionMatchesUpstream() {
        val u = UNewExpression(0, 9)
        u.setTarget(UName(4, 3, "Foo"))
        u.addArgument(UNumberLiteral(8, "1"))

        val k = NewExpression(0, 9)
        k.target = Name(4, 3, "Foo")
        k.addArgument(NumberLiteral(8, "1"))

        assertEquals(u.toSource(0), k.toSource(0))

        val uinit = UObjectLiteral(11, 2)
        u.setInitializer(uinit)
        val kinit = ObjectLiteral(11, 2)
        k.initializer = kinit
        assertEquals(u.toSource(0), k.toSource(0))

        val uNoArgs = UNewExpression(0, 9)
        uNoArgs.setTarget(UName(4, 3, "Foo"))
        val kNoArgs = NewExpression(0, 9)
        kNoArgs.target = Name(4, 3, "Foo")
        assertEquals(uNoArgs.toSource(0), kNoArgs.toSource(0))
    }

    @Test
    fun generatorExpressionMatchesUpstream() {
        val uloop = UGeneratorExpressionLoop(5)
        uloop.setIterator(UName(10, 1, "k"))
        uloop.setIteratedObject(UName(15, 1, "o"))
        val u = UGeneratorExpression(0)
        u.setResult(UName(1, 1, "k"))
        u.addLoop(uloop)

        val kloop = GeneratorExpressionLoop(5)
        kloop.iterator = Name(10, 1, "k")
        kloop.iteratedObject = Name(15, 1, "o")
        val k = GeneratorExpression(0)
        k.result = Name(1, 1, "k")
        k.addLoop(kloop)

        assertEquals(u.toSource(0), k.toSource(0))

        u.setFilter(UName(20, 1, "c"))
        k.filter = Name(20, 1, "c")
        assertEquals(u.toSource(0), k.toSource(0))
        assertEquals(u.getLoops().size, k.loops.size)
    }

    @Test
    fun errorCollectorMatchesUpstream() {
        val u = UErrorCollector()
        u.error("bad", "a.js", 4, 2)
        u.warning("meh", "a.js", 9, 1)

        val k = ErrorCollector()
        k.error("bad", "a.js", 4, 2)
        k.warning("meh", "a.js", 9, 1)

        assertEquals(u.toString(), k.toString())
        assertEquals(u.getErrors().size, k.errors.size)
        assertFailsWith<UnsupportedOperationException> { k.error("x", "a.js", 1, "src", 0) }
        assertFailsWith<UnsupportedOperationException> { k.warning("x", "a.js", 1, "src", 0) }
        assertFailsWith<UnsupportedOperationException> { k.runtimeError("x", "a.js", 1, "src", 0) }
    }
}
