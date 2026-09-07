/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * A computed property key such as the `[a + b]` in `{ [a + b]: 1 }`. Node type is
 * [Token.COMPUTED_PROPERTY].
 */
public class ComputedPropertyKey(pos: Int, len: Int) : AstNode(pos, len) {

    init {
        typeField = Token.COMPUTED_PROPERTY
    }

    /** The key expression. Setting it reparents the expression. */
    public var expression: AstNode? = null
        set(value) {
            val newExpression = value!!
            field = newExpression
            newExpression.parent = this
        }

    override fun hasSideEffects(): Boolean {
        if (expression == null) codeBug()
        return expression!!.hasSideEffects()
    }

    override fun toSource(depth: Int): String =
        makeIndent(depth) + '[' + expression!!.toSource(depth) + ']'

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            expression!!.visit(visitor)
        }
    }
}
