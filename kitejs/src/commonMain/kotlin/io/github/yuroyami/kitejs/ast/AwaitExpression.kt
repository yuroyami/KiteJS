/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * An `await` expression (ECMAScript 2017, 14.7). Node type is [Token.AWAIT]. Upstream has no
 * async functions, so no node for it either (D-97).
 */
public class AwaitExpression : AstNode {

    public constructor(pos: Int, len: Int, operand: AstNode) : super(pos, len) {
        typeField = Token.AWAIT
        this.operand = operand
    }

    /** The awaited expression. Setting it reparents. */
    public var operand: AstNode? = null
        set(value) {
            field = value
            value?.parent = this
        }

    override fun toSource(depth: Int): String = makeIndent(depth) + "await " + operand!!.toSource(0)

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            operand?.visit(visitor)
        }
    }
}
