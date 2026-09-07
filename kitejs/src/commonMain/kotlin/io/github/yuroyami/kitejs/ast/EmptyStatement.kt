/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/** An empty statement, a lone semicolon. Node type is [Token.EMPTY]. */
public class EmptyStatement(pos: Int = -1, len: Int = 1) : AstNode(pos, len) {

    init {
        typeField = Token.EMPTY
    }

    override fun toSource(depth: Int): String = makeIndent(depth) + ";\n"

    override fun visit(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
