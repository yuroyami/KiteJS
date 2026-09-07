/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * A keyword literal: `this`, `super`, `null`, `undefined`, `true`, `false` or `debugger`. The
 * node type is the keyword's own token, and setting it rejects anything else.
 */
public class KeywordLiteral : AstNode {

    public constructor() : super()

    public constructor(pos: Int) : super(pos)

    public constructor(pos: Int, len: Int) : super(pos, len)

    public constructor(pos: Int, len: Int, nodeType: Int) : super(pos, len) {
        type = nodeType
    }

    override var type: Int
        get() = typeField
        set(value) {
            if (!(value == Token.THIS ||
                    value == Token.SUPER ||
                    value == Token.NULL ||
                    value == Token.UNDEFINED ||
                    value == Token.TRUE ||
                    value == Token.FALSE ||
                    value == Token.DEBUGGER)
            ) {
                throw IllegalArgumentException("Invalid node type: $value")
            }
            typeField = value
        }

    public val isBooleanLiteral: Boolean
        get() = typeField == Token.TRUE || typeField == Token.FALSE

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        when (type) {
            Token.THIS -> sb.append("this")
            Token.SUPER -> sb.append("super")
            Token.NULL -> sb.append("null")
            Token.UNDEFINED -> sb.append("undefined")
            Token.TRUE -> sb.append("true")
            Token.FALSE -> sb.append("false")
            Token.DEBUGGER -> sb.append("debugger;\n")
            else -> throw IllegalStateException("Invalid keyword literal type: $type")
        }
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
