/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * A return statement. Node type is [Token.RETURN]. The [returnValue] is null for a bare
 * `return;`.
 */
class ReturnStatement(pos: Int = -1, len: Int = 1) : AstNode(pos, len) {

    init {
        typeField = Token.RETURN
    }

    constructor(pos: Int, len: Int, returnValue: AstNode?) : this(pos, len) {
        this.returnValue = returnValue
    }

    /** The returned expression, or null. Setting it reparents the expression. */
    var returnValue: AstNode? = null
        set(value) {
            field = value
            value?.parent = this
        }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        sb.append("return")
        returnValue?.let {
            sb.append(" ")
            sb.append(it.toSource(0))
        }
        sb.append(";\n")
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            returnValue?.visit(visitor)
        }
    }
}
