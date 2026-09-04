/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/** A `do ... while` loop. Node type is [Token.DO]. */
class DoLoop(pos: Int = -1, len: Int = 1) : Loop(pos, len) {

    init {
        typeField = Token.DO
    }

    /** The loop condition. Setting it reparents the expression. */
    var condition: AstNode? = null
        set(value) {
            val newCondition = value!!
            field = newCondition
            newCondition.parent = this
        }

    /** Position of the `while` keyword, relative to this node. */
    var whilePosition: Int = -1

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        sb.append("do ")
        inlineComment?.let { sb.append(it.toSource(depth + 1)).append("\n") }
        sb.append(bodyField!!.toSource(depth).trim())
        sb.append(" while (")
        sb.append(condition!!.toSource(0))
        sb.append(");\n")
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            bodyField!!.visit(visitor)
            condition!!.visit(visitor)
        }
    }
}
