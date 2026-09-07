/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * A dotted property access such as `a.b`. Node type is [Token.GETPROP]. The [target] is the
 * left side and the [property] is the [Name] on the right.
 */
public class PropertyGet : InfixExpression {

    init {
        typeField = Token.GETPROP
    }

    public constructor() : super()

    public constructor(pos: Int) : super(pos)

    public constructor(pos: Int, len: Int) : super(pos, len)

    public constructor(pos: Int, len: Int, target: AstNode, property: Name) : super(
        pos,
        len,
        target,
        property,
    )

    public constructor(target: AstNode, property: Name) : super(target, property) {
        setLineColumnNumber(property.lineno, property.column)
    }

    public constructor(target: AstNode, property: Name, dotPosition: Int) : super(
        Token.GETPROP,
        target,
        property,
        dotPosition,
    ) {
        setLineColumnNumber(property.lineno, property.column)
    }

    public var target: AstNode?
        get() = left
        set(value) {
            left = value
        }

    public var property: Name?
        get() = right as Name?
        set(value) {
            right = value
        }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        sb.append(left!!.toSource(0))
        sb.append(".")
        sb.append(right!!.toSource(0))
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            target!!.visit(visitor)
            property!!.visit(visitor)
        }
    }
}
