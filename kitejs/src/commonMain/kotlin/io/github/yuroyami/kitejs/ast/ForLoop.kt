/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/** A C-style `for (init; cond; incr)` loop. Node type is [Token.FOR]. */
public class ForLoop(pos: Int = -1, len: Int = 1) : Loop(pos, len) {

    init {
        typeField = Token.FOR
    }

    /** The initializer clause. Setting it reparents the node. */
    public var initializer: AstNode? = null
        set(value) {
            val newInitializer = value!!
            field = newInitializer
            newInitializer.parent = this
        }

    /** The condition clause. Setting it reparents the node. */
    public var condition: AstNode? = null
        set(value) {
            val newCondition = value!!
            field = newCondition
            newCondition.parent = this
        }

    /** The increment clause. Setting it reparents the node. */
    public var increment: AstNode? = null
        set(value) {
            val newIncrement = value!!
            field = newIncrement
            newIncrement.parent = this
        }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        sb.append("for (")
        sb.append(initializer!!.toSource(0))
        sb.append("; ")
        sb.append(condition!!.toSource(0))
        sb.append("; ")
        sb.append(increment!!.toSource(0))
        sb.append(") ")
        inlineComment?.let { sb.append(it.toSource()).append("\n") }
        val loopBody = bodyField!!
        if (loopBody.type == Token.BLOCK) {
            var bodySource = loopBody.toSource(depth)
            if (inlineComment == null) {
                bodySource = bodySource.trim()
            }
            sb.append(bodySource).append("\n")
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
            initializer!!.visit(visitor)
            condition!!.visit(visitor)
            increment!!.visit(visitor)
            bodyField!!.visit(visitor)
        }
    }
}
