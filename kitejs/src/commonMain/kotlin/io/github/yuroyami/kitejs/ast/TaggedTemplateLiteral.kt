/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * A tagged template such as ``tag`a${b}c` ``. Node type is
 * [Token.TAGGED_TEMPLATE_LITERAL].
 */
class TaggedTemplateLiteral(pos: Int = -1, len: Int = 1) : AstNode(pos, len) {

    init {
        typeField = Token.TAGGED_TEMPLATE_LITERAL
    }

    /** The tag function expression. Setting it reparents the node. */
    var target: AstNode? = null
        set(value) {
            field = value
            value!!.parent = this
        }

    /** The template literal being tagged. Setting it reparents the node. */
    var templateLiteral: AstNode? = null
        set(value) {
            field = value
            value!!.parent = this
        }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        sb.append(target!!.toSource(0))
        sb.append(templateLiteral!!.toSource(0))
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            target!!.visit(visitor)
            templateLiteral!!.visit(visitor)
        }
    }
}
