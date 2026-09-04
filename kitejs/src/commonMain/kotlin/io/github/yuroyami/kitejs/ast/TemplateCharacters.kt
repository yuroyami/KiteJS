/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * The literal text between substitutions in a template literal. Node type is
 * [Token.TEMPLATE_CHARS]. [value] is the cooked text and [rawValue] the source text.
 */
class TemplateCharacters(pos: Int = -1, len: Int = 1) : AstNode(pos, len) {

    /** The cooked value, with escapes resolved. Null when the escapes are invalid. */
    var value: String? = null

    /** The raw source text, escapes untouched. */
    var rawValue: String? = null
        set(value) {
            assertNotNull(value)
            field = value
        }

    init {
        typeField = Token.TEMPLATE_CHARS
    }

    override fun toSource(depth: Int): String = makeIndent(depth) + rawValue

    override fun visit(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
