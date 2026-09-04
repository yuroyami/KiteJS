/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * A block statement delimited by curly braces. The node position is the open curly and the
 * length reaches the close curly. Node type is [Token.BLOCK].
 *
 * ```
 * Block : { Statement* }
 * ```
 */
class Block(pos: Int = -1, len: Int = 1) : AstNode(pos, len) {

    init {
        typeField = Token.BLOCK
    }

    /** Alias for [addChild]. */
    fun addStatement(statement: AstNode) {
        addChild(statement)
    }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        sb.append("{\n")
        for (kid in this) {
            val astNodeKid = kid as AstNode
            sb.append(astNodeKid.toSource(depth + 1))
            if (astNodeKid.type == Token.COMMENT) {
                sb.append("\n")
            }
        }
        sb.append(makeIndent(depth))
        sb.append("}")
        inlineComment?.let { sb.append(it.toSource(depth)) }
        sb.append("\n")
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            for (kid in this) {
                (kid as AstNode).visit(visitor)
            }
        }
    }
}
