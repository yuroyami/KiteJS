/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * A `new` expression. Node type is [Token.NEW]. The [initializer] is Rhino's non-standard
 * `new Foo() {a: 1}` object-initializer extension.
 */
public class NewExpression(pos: Int = -1, len: Int = 1) : FunctionCall(pos, len) {

    init {
        typeField = Token.NEW
    }

    /** The trailing object initializer, or null. Setting it reparents the literal. */
    public var initializer: ObjectLiteral? = null
        set(value) {
            field = value
            value?.parent = this
        }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        sb.append("new ")
        sb.append(target!!.toSource(0))
        sb.append("(")
        argumentList?.let { printList(it, sb) }
        sb.append(")")
        initializer?.let {
            sb.append(" ")
            sb.append(it.toSource(0))
        }
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            target!!.visit(visitor)
            for (arg in arguments) {
                arg.visit(visitor)
            }
            initializer?.visit(visitor)
        }
    }
}
