/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Node
import io.github.yuroyami.kitejs.Token

/** A symbol-table entry. */
public class Symbol() {

    /**
     * Symbol declaration type: one of [Token.FUNCTION], [Token.LP] (for parameters),
     * [Token.VAR], [Token.LET] or [Token.CONST].
     */
    public var declType: Int = 0
        set(value) {
            if (!(value == Token.FUNCTION ||
                    value == Token.LP ||
                    value == Token.VAR ||
                    value == Token.LET ||
                    value == Token.CONST)
            ) {
                throw IllegalArgumentException("Invalid declType: $value")
            }
            field = value
        }

    /** Symbol name. */
    public var name: String? = null

    /** The node associated with this identifier. */
    public var node: Node? = null

    /** The symbol's index in its scope. */
    public var index: Int = -1

    /** The scope in which this symbol is entered. */
    public var containingTable: Scope? = null

    public constructor(declType: Int, name: String?) : this() {
        this.name = name
        this.declType = declType
    }

    public val declTypeName: String
        get() = Token.typeToName(declType)

    override fun toString(): String {
        val result = StringBuilder()
        result.append("Symbol (")
        result.append(declTypeName)
        result.append(") name=")
        result.append(name)
        node?.let {
            result.append(" line=")
            result.append(it.lineno)
        }
        return result.toString()
    }
}
