/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * The `*name` part of a generator method in an object literal. Node type is [Token.MUL].
 */
class GeneratorMethodDefinition(pos: Int, len: Int, methodName: AstNode) : AstNode(pos, len) {

    /** The method name node. Setting it reparents the node. */
    var methodName: AstNode? = null
        set(value) {
            val newName = value!!
            field = newName
            newName.parent = this
        }

    init {
        type = Token.MUL
        this.methodName = methodName
    }

    override fun toSource(depth: Int): String =
        makeIndent(depth) + '*' + methodName!!.toSource(depth)

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            methodName!!.visit(visitor)
        }
    }
}
