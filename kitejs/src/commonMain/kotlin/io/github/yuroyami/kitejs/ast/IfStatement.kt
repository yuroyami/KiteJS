/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/** An if statement with an optional else part. Node type is [Token.IF]. */
public class IfStatement(pos: Int = -1, len: Int = 1) : AstNode(pos, len) {

    init {
        typeField = Token.IF
    }

    /** The condition. Setting it reparents the expression. */
    public var condition: AstNode? = null
        set(value) {
            val newCondition = value!!
            field = newCondition
            newCondition.parent = this
        }

    /** The then branch. Setting it reparents the statement. */
    public var thenPart: AstNode? = null
        set(value) {
            val newThen = value!!
            field = newThen
            newThen.parent = this
        }

    /** The else branch, or null. Setting it reparents the statement. */
    public var elsePart: AstNode? = null
        set(value) {
            field = value
            value?.parent = this
        }

    /** Position of the `else` keyword, relative to this node. -1 if there is no else. */
    public var elsePosition: Int = -1

    /** A comment on the same line as the `else` keyword. */
    public var elseKeyWordInlineComment: AstNode? = null

    /** Position of the left paren, relative to this node. */
    public var lp: Int = -1

    /** Position of the right paren, relative to this node. */
    public var rp: Int = -1

    /** Sets both paren positions. */
    public fun setParens(lp: Int, rp: Int) {
        this.lp = lp
        this.rp = rp
    }

    override fun toSource(depth: Int): String {
        val pad = makeIndent(depth)
        val sb = StringBuilder(32)
        sb.append(pad)
        sb.append("if (")
        sb.append(condition!!.toSource(0))
        sb.append(") ")
        inlineComment?.let { sb.append("    ").append(it.toSource()).append("\n") }
        val then = thenPart!!
        if (then.type != Token.BLOCK) {
            if (inlineComment == null) {
                sb.append("\n")
            }
            sb.append(makeIndent(depth + 1))
        }
        sb.append(then.toSource(depth).trim())
        elsePart?.let { elseBranch ->
            if (then.type != Token.BLOCK) {
                sb.append("\n").append(pad).append("else ")
            } else {
                sb.append(" else ")
            }
            elseKeyWordInlineComment?.let {
                sb.append("    ").append(it.toSource()).append("\n")
            }
            if (elseBranch.type != Token.BLOCK && elseBranch.type != Token.IF) {
                if (elseKeyWordInlineComment == null) {
                    sb.append("\n")
                }
                sb.append(makeIndent(depth + 1))
            }
            sb.append(elseBranch.toSource(depth).trim())
        }
        sb.append("\n")
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            condition!!.visit(visitor)
            thenPart!!.visit(visitor)
            elsePart?.visit(visitor)
        }
    }
}
