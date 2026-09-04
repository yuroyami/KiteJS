/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * A regular expression literal. Node type is [Token.REGEXP]. The [value] is the pattern
 * without the delimiting slashes and [flags] are the trailing letters.
 */
class RegExpLiteral(pos: Int = -1, len: Int = 1) : AstNode(pos, len) {

    /** The regexp pattern, without the enclosing slashes. */
    var value: String? = null
        set(value) {
            assertNotNull(value)
            field = value
        }

    /** The regexp flags, or null if there are none. */
    var flags: String? = null

    init {
        typeField = Token.REGEXP
    }

    override fun toSource(depth: Int): String =
        makeIndent(depth) + "/" + value + "/" + (flags ?: "")

    override fun visit(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
