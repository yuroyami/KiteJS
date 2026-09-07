/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/** A bracketed element access such as `a[b]`. Node type is [Token.GETELEM]. */
public class ElementGet : AstNode {

    init {
        typeField = Token.GETELEM
    }

    public constructor() : super()

    public constructor(pos: Int) : super(pos)

    public constructor(pos: Int, len: Int) : super(pos, len)

    public constructor(target: AstNode, element: AstNode) : super() {
        this.target = target
        this.element = element
    }

    /** The object being indexed. Setting it reparents and copies the line and column. */
    public var target: AstNode? = null
        set(value) {
            val newTarget = value!!
            field = newTarget
            newTarget.parent = this
            setLineColumnNumber(newTarget.lineno, newTarget.column)
        }

    /** The index expression. Setting it reparents the expression. */
    public var element: AstNode? = null
        set(value) {
            val newElement = value!!
            field = newElement
            newElement.parent = this
        }

    /** Position of the left bracket, relative to this node. */
    public var lb: Int = -1

    /** Position of the right bracket, relative to this node. */
    public var rb: Int = -1

    /** Sets both bracket positions. */
    public fun setParens(lb: Int, rb: Int) {
        this.lb = lb
        this.rb = rb
    }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        sb.append(target!!.toSource(0))
        sb.append("[")
        sb.append(element!!.toSource(0))
        sb.append("]")
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            target!!.visit(visitor)
            element!!.visit(visitor)
        }
    }
}
