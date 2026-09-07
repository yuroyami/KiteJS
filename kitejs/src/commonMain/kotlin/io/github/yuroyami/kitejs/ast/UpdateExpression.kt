/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * An increment or decrement expression, prefix or postfix. The node type is [Token.INC] or
 * [Token.DEC].
 */
public class UpdateExpression : AstNode {

    public constructor() : super()

    public constructor(pos: Int) : super(pos)

    public constructor(pos: Int, len: Int) : super(pos, len)

    public constructor(
        operator: Int,
        operatorPosition: Int,
        operand: AstNode,
    ) : this(operator, operatorPosition, operand, false)

    public constructor(
        operator: Int,
        operatorPosition: Int,
        operand: AstNode,
        postFix: Boolean,
    ) : super() {
        val beg = if (postFix) operand.position else operatorPosition
        // JavaScript only has ++ and -- postfix operators, so the operator length is 2.
        val end = if (postFix) operatorPosition + 2 else operand.position + operand.length
        setBounds(beg, end)
        this.operator = operator
        this.operand = operand
        isPostfix = postFix
    }

    /** The operator token. Setting it rejects tokens that are not real token codes. */
    public var operator: Int
        get() = typeField
        set(value) {
            if (!Token.isValidToken(value)) {
                throw IllegalArgumentException("Invalid token: $value")
            }
            type = value
        }

    /** The operand. Setting it reparents the node. */
    public var operand: AstNode? = null
        set(value) {
            val newOperand = value!!
            field = newOperand
            newOperand.parent = this
        }

    public var isPostfix: Boolean = false

    public val isPrefix: Boolean
        get() = !isPostfix

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        val type = type
        if (!isPostfix) {
            sb.append(operatorToString(type))
        }
        sb.append(operand!!.toSource())
        if (isPostfix) {
            sb.append(operatorToString(type))
        }
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            operand!!.visit(visitor)
        }
    }
}
