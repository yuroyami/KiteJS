/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/** A parenthesized expression. Node type is [Token.LP]. */
public class ParenthesizedExpression : AstNode {

    init {
        typeField = Token.LP
    }

    public constructor() : super()

    public constructor(pos: Int) : super(pos)

    public constructor(pos: Int, len: Int) : super(pos, len)

    public constructor(expr: AstNode) : this(expr.position, expr.length, expr)

    public constructor(pos: Int, len: Int, expr: AstNode) : super(pos, len) {
        expression = expr
    }

    /** The wrapped expression. Setting it reparents the expression. */
    public var expression: AstNode? = null
        set(value) {
            val newExpression = value!!
            field = newExpression
            newExpression.parent = this
        }

    override fun toSource(depth: Int): String =
        makeIndent(depth) + "(" + expression!!.toSource(0) + ")"

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            expression!!.visit(visitor)
        }
    }
}
