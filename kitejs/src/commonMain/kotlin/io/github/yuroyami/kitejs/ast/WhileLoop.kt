/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/** A while loop. Node type is [Token.WHILE]. */
public class WhileLoop(pos: Int = -1, len: Int = 1) : Loop(pos, len) {

    init {
        typeField = Token.WHILE
    }

    /** The loop condition. Setting it reparents the expression. */
    public var condition: AstNode? = null
        set(value) {
            val newCondition = value!!
            field = newCondition
            newCondition.parent = this
        }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        sb.append("while (")
        sb.append(condition!!.toSource(0))
        sb.append(") ")
        inlineComment?.let { sb.append(it.toSource(depth + 1)).append("\n") }
        val loopBody = bodyField!!
        if (loopBody.type == Token.BLOCK) {
            sb.append(loopBody.toSource(depth).trim())
            sb.append("\n")
        } else {
            if (inlineComment == null) {
                sb.append("\n")
            }
            sb.append(loopBody.toSource(depth + 1))
        }
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            condition!!.visit(visitor)
            bodyField!!.visit(visitor)
        }
    }
}
