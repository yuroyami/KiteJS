/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * One `name = value` entry of a [VariableDeclaration]. The target is a [Name], or an array or
 * object literal for a destructuring binding. Node type matches the declaration keyword.
 */
class VariableInitializer(pos: Int = -1, len: Int = 1) : AstNode(pos, len) {

    init {
        typeField = Token.VAR
    }

    /** Sets the node type, rejecting anything that is not a declaration keyword. */
    fun setNodeType(nodeType: Int) {
        if (nodeType != Token.VAR && nodeType != Token.CONST && nodeType != Token.LET) {
            throw IllegalArgumentException("invalid node type")
        }
        type = nodeType
    }

    val isDestructuring: Boolean
        get() = targetNode !is Name

    private var targetNode: AstNode? = null

    /**
     * The binding target. Setting it reparents the node. An "invalid" node type is allowed on
     * purpose: see mozilla/js/tests/js1_7/block/regress-350279.js.
     */
    var target: AstNode?
        get() = targetNode
        set(value) {
            if (value == null) throw IllegalArgumentException("invalid target arg")
            targetNode = value
            value.parent = this
        }

    /** The initial value, or null for a bare declaration. Setting it reparents. */
    var initializer: AstNode? = null
        set(value) {
            field = value
            value?.parent = this
        }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        sb.append(targetNode!!.toSource(0))
        initializer?.let {
            sb.append(" = ")
            sb.append(it.toSource(0))
        }
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            targetNode!!.visit(visitor)
            initializer?.visit(visitor)
        }
    }
}
