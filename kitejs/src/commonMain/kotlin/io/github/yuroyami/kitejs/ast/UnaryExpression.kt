/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * A prefix unary expression such as `!a`, `-a`, `typeof a` or `void a`. The node type is the
 * operator token. Increment and decrement live in [UpdateExpression].
 */
public class UnaryExpression : AstNode {

    public constructor() : super()

    public constructor(pos: Int) : super(pos)

    public constructor(pos: Int, len: Int) : super(pos, len)

    /**
     * @param operatorPosition the operator position, kept for API parity; the bounds come from
     *     the operand alone, as upstream does it.
     */
    public constructor(operator: Int, operatorPosition: Int, operand: AstNode) : super() {
        val beg = operand.position
        val end = operand.position + operand.length
        setBounds(beg, end)
        this.operator = operator
        this.operand = operand
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

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        val type = type
        sb.append(operatorToString(type))
        if (type == Token.TYPEOF || type == Token.DELPROP || type == Token.VOID) {
            sb.append(" ")
        }
        sb.append(operand!!.toSource())
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            operand!!.visit(visitor)
        }
    }
}
