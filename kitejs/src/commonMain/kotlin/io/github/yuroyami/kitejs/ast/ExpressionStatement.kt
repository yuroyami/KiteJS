/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * An expression statement. Node type is [Token.EXPR_VOID] when the value is discarded, and
 * [Token.EXPR_RESULT] when it becomes the script result.
 */
public class ExpressionStatement(pos: Int = -1, len: Int = 1) : AstNode(pos, len) {

    private var expr: AstNode? = null

    init {
        typeField = Token.EXPR_VOID
    }

    public constructor(pos: Int, len: Int, expr: AstNode) : this(pos, len) {
        expression = expr
    }

    public constructor(expr: AstNode) : this(expr.position, expr.length, expr)

    public constructor(expr: AstNode, hasResult: Boolean) : this(expr) {
        if (hasResult) setHasResult()
    }

    /** Marks this statement as producing the script result. */
    public fun setHasResult() {
        typeField = Token.EXPR_RESULT
    }

    /** The wrapped expression. Setting it reparents the expression and copies its position. */
    public var expression: AstNode?
        get() = expr
        set(value) {
            val newExpression = value!!
            expr = newExpression
            newExpression.parent = this
            setLineColumnNumber(newExpression.lineno, newExpression.column)
        }

    override fun hasSideEffects(): Boolean =
        typeField == Token.EXPR_RESULT || expr!!.hasSideEffects()

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(expr!!.toSource(depth))
        sb.append(";")
        inlineComment?.let { sb.append(it.toSource(depth)) }
        sb.append("\n")
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            expr!!.visit(visitor)
        }
    }
}
