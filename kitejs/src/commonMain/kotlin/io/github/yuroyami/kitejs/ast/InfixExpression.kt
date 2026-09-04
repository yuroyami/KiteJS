/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * A binary expression: `left op right`. The node type is the operator token, so a plus
 * expression has type [Token.ADD].
 */
open class InfixExpression(pos: Int = -1, len: Int = 1) : AstNode(pos, len) {

    /** The left operand. Setting it copies the operand's line and column and reparents it. */
    var left: AstNode? = null
        set(value) {
            val newLeft = value!!
            field = newLeft
            // Line and column number should agree with the source position.
            setLineColumnNumber(newLeft.lineno, newLeft.column)
            newLeft.parent = this
        }

    /** The right operand. Setting it reparents the operand. */
    var right: AstNode? = null
        set(value) {
            val newRight = value!!
            field = newRight
            newRight.parent = this
        }

    /** Operator position, relative to this node. -1 if unknown. */
    var operatorPosition: Int = -1

    constructor(pos: Int, len: Int, left: AstNode, right: AstNode) : this(pos, len) {
        this.left = left
        this.right = right
    }

    constructor(left: AstNode, right: AstNode) : this() {
        setLeftAndRight(left, right)
    }

    constructor(
        operator: Int,
        left: AstNode,
        right: AstNode,
        operatorPos: Int,
    ) : this() {
        type = operator
        operatorPosition = operatorPos - left.position
        setLeftAndRight(left, right)
    }

    fun setLeftAndRight(left: AstNode, right: AstNode) {
        // Compute our bounds while the children still have absolute positions.
        val beg = left.position
        val end = right.position + right.length
        setBounds(beg, end)

        // These update the child positions to be parent-relative.
        this.left = left
        this.right = right
    }

    /** The operator token. Setting it rejects tokens that are not real token codes. */
    var operator: Int
        get() = type
        set(value) {
            if (!Token.isValidToken(value)) {
                throw IllegalArgumentException("Invalid token: $value")
            }
            type = value
        }

    override fun hasSideEffects(): Boolean = when (type) {
        // The null checks are for malformed expressions in IDE mode.
        Token.COMMA -> right?.hasSideEffects() ?: false
        Token.AND, Token.OR ->
            (left?.hasSideEffects() ?: false) || (right?.hasSideEffects() ?: false)
        else -> super.hasSideEffects()
    }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        sb.append(left!!.toSource())
        sb.append(" ")
        sb.append(operatorToString(type))
        sb.append(" ")
        sb.append(right!!.toSource())
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            left!!.visit(visitor)
            right!!.visit(visitor)
        }
    }
}
