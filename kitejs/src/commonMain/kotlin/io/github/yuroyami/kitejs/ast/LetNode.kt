/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * A `let` expression or statement, as in `let (a = 1) expr`. Node type is [Token.LETEXPR]. It
 * introduces a scope.
 */
public class LetNode(pos: Int = -1, len: Int = 1) : Scope(pos, len) {

    init {
        typeField = Token.LETEXPR
    }

    /** The bound variables. Setting it reparents the declaration. */
    public var variables: VariableDeclaration? = null
        set(value) {
            val newVariables = value!!
            field = newVariables
            newVariables.parent = this
        }

    /** The body, or null for a bare let statement. Setting it reparents. */
    public var body: AstNode? = null
        set(value) {
            field = value
            value?.parent = this
        }

    /** Position of the left paren, relative to this node. */
    public var lp: Int = -1

    /** Position of the right paren, relative to this node. */
    public var rp: Int = -1

    /** Sets both paren positions. */
    public fun setParens(lp: Int, rp: Int) {
        this.lp = lp
        this.rp = rp
    }

    override fun toSource(depth: Int): String {
        val pad = makeIndent(depth)
        val sb = StringBuilder()
        sb.append(pad)
        sb.append("let (")
        printList(variables!!.variables, sb)
        sb.append(") ")
        body?.let { sb.append(it.toSource(depth)) }
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            variables!!.visit(visitor)
            body?.visit(visitor)
        }
    }
}
