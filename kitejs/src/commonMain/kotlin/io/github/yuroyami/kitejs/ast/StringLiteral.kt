/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.ScriptRuntime
import io.github.yuroyami.kitejs.Token

/** A single- or double-quoted string literal. Node type is [Token.STRING]. */
public class StringLiteral : AstNode {

    /** The parsed string without the enclosing quotes. */
    public var value: String? = null
        set(value) {
            assertNotNull(value)
            field = value
        }

    /** The character used as the delimiter for this string. */
    public var quoteCharacter: Char = ' '

    init {
        typeField = Token.STRING
    }

    public constructor() : super()

    public constructor(pos: Int) : super(pos)

    /** @param len the length including the enclosing quotes */
    public constructor(pos: Int, len: Int) : super(pos, len)

    /** The string value, optionally including the enclosing quotes. */
    public fun getValue(includeQuotes: Boolean): String? =
        if (!includeQuotes) value else quoteCharacter + value!! + quoteCharacter

    override fun toSource(depth: Int): String =
        makeIndent(depth) +
            quoteCharacter +
            ScriptRuntime.escapeString(value!!, quoteCharacter) +
            quoteCharacter

    /** Visits this node. There are no children to visit. */
    override fun visit(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
