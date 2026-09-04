/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/** A with statement. Node type is [Token.WITH]. */
class WithStatement(pos: Int = -1, len: Int = 1) : AstNode(pos, len) {

    init {
        typeField = Token.WITH
    }

    /** The object expression. Setting it reparents the expression. */
    var expression: AstNode? = null
        set(value) {
            val newExpression = value!!
            field = newExpression
            newExpression.parent = this
        }

    /** The body statement. Setting it reparents the statement. */
    var statement: AstNode? = null
        set(value) {
            val newStatement = value!!
            field = newStatement
            newStatement.parent = this
        }

    /** Position of the left paren, relative to this node. */
    var lp: Int = -1

    /** Position of the right paren, relative to this node. */
    var rp: Int = -1

    /** Sets both paren positions. */
    fun setParens(lp: Int, rp: Int) {
        this.lp = lp
        this.rp = rp
    }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        sb.append("with (")
        sb.append(expression!!.toSource(0))
        sb.append(") ")
        inlineComment?.let { sb.append(it.toSource(depth + 1)) }
        val body = statement!!
        if (body.type == Token.BLOCK) {
            if (inlineComment != null) {
                sb.append("\n")
            }
            sb.append(body.toSource(depth).trim())
            sb.append("\n")
        } else {
            sb.append("\n").append(body.toSource(depth + 1))
        }
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            expression!!.visit(visitor)
            statement!!.visit(visitor)
        }
    }
}
