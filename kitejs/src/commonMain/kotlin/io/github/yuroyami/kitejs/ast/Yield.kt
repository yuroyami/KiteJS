/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * A yield expression. Node type is [Token.YIELD], or [Token.YIELD_STAR] for `yield*`.
 */
class Yield : AstNode {

    constructor() : super() {
        typeField = Token.YIELD
    }

    constructor(pos: Int) : super(pos) {
        typeField = Token.YIELD
    }

    constructor(pos: Int, len: Int) : super(pos, len) {
        typeField = Token.YIELD
    }

    constructor(pos: Int, len: Int, value: AstNode?, isStar: Boolean) : super(pos, len) {
        typeField = if (isStar) Token.YIELD_STAR else Token.YIELD
        this.value = value
    }

    /** The yielded expression, or null for a bare `yield`. Setting it reparents. */
    var value: AstNode? = null
        set(value) {
            field = value
            value?.parent = this
        }

    override fun toSource(depth: Int): String =
        if (value == null) "yield" else "yield " + value!!.toSource(0)

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            value?.visit(visitor)
        }
    }
}
