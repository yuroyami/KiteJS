/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/** A throw statement. Node type is [Token.THROW]. */
class ThrowStatement : AstNode {

    init {
        typeField = Token.THROW
    }

    constructor() : super()

    constructor(pos: Int) : super(pos)

    constructor(pos: Int, len: Int) : super(pos, len)

    constructor(expr: AstNode) : super() {
        expression = expr
    }

    constructor(pos: Int, expr: AstNode) : super(pos, expr.length) {
        expression = expr
    }

    constructor(pos: Int, len: Int, expr: AstNode) : super(pos, len) {
        expression = expr
    }

    /** The thrown expression. Setting it reparents the expression. */
    var expression: AstNode? = null
        set(value) {
            val newExpression = value!!
            field = newExpression
            newExpression.parent = this
        }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        sb.append("throw")
        sb.append(" ")
        sb.append(expression!!.toSource(0))
        sb.append(";\n")
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            expression!!.visit(visitor)
        }
    }
}
