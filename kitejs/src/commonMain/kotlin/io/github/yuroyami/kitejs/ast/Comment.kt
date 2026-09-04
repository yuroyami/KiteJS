/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * A comment. Node type is [Token.COMMENT].
 *
 * JavaScript has five comment shapes: line comments, block comments, jsdoc comments (block
 * comments with formatting conventions), and the two HTML forms. SpiderMonkey and Rhino
 * support the HTML syntax, where everything from `<!--` to the end of the line is a comment,
 * and a line whose first non-whitespace token is `-->` is a line comment. That exists to
 * parse scripts hidden from very old browsers inside HTML comment delimiters.
 *
 * The node start position is relative to the parent as usual, but comments are always stored
 * directly in the [AstRoot], so it is also an absolute offset.
 *
 * @param len the length including the delimiters
 */
class Comment(
    pos: Int,
    len: Int,
    type: Token.CommentType,
    value: String,
) : AstNode(pos, len) {

    /** The comment style. */
    var commentType: Token.CommentType = type

    /** The comment text. Setting it also updates the node length. */
    var value: String = value
        set(value) {
            field = value
            length = value.length
        }

    init {
        typeField = Token.COMMENT
    }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder(length + 10)
        sb.append(makeIndent(depth))
        sb.append(value)
        if (Token.CommentType.BLOCK_COMMENT == commentType) {
            sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * Comment nodes are not visited during normal traversals, but they honor the
     * [AstNode.visit] contract.
     */
    override fun visit(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
