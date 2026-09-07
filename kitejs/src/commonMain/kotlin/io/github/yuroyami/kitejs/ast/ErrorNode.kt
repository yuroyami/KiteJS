/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * A placeholder the parser leaves where an expression failed to parse, in error-recovery
 * mode. Node type is [Token.ERROR]. It renders as the empty string.
 */
public class ErrorNode(pos: Int = -1, len: Int = 1) : AstNode(pos, len) {

    init {
        typeField = Token.ERROR
    }

    public var message: String? = null

    override fun toSource(depth: Int): String = ""

    override fun visit(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
