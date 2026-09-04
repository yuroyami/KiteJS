/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/** A ternary expression `test ? then : else`. Node type is [Token.HOOK]. */
class ConditionalExpression(pos: Int = -1, len: Int = 1) : AstNode(pos, len) {

    init {
        typeField = Token.HOOK
    }

    /** The condition. Setting it reparents the expression. */
    var testExpression: AstNode? = null
        set(value) {
            val newTest = value!!
            field = newTest
            newTest.parent = this
        }

    /** The value when the condition holds. Setting it reparents the expression. */
    var trueExpression: AstNode? = null
        set(value) {
            val newTrue = value!!
            field = newTrue
            newTrue.parent = this
        }

    /** The value when the condition fails. Setting it reparents the expression. */
    var falseExpression: AstNode? = null
        set(value) {
            val newFalse = value!!
            field = newFalse
            newFalse.parent = this
        }

    /** Position of the `?`, relative to this node. */
    var questionMarkPosition: Int = -1

    /** Position of the `:`, relative to this node. */
    var colonPosition: Int = -1

    override fun hasSideEffects(): Boolean {
        if (testExpression == null || trueExpression == null || falseExpression == null) codeBug()
        return trueExpression!!.hasSideEffects() && falseExpression!!.hasSideEffects()
    }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        sb.append(testExpression!!.toSource(depth))
        sb.append(" ? ")
        sb.append(trueExpression!!.toSource(0))
        sb.append(" : ")
        sb.append(falseExpression!!.toSource(0))
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            testExpression!!.visit(visitor)
            trueExpression!!.visit(visitor)
            falseExpression!!.visit(visitor)
        }
    }
}
