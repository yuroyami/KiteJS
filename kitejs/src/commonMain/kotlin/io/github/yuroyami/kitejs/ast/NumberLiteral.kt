/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/** A Number literal. Node type is [Token.NUMBER]. */
class NumberLiteral : AstNode {

    /** The node's string value: the original source token. */
    var value: String? = null
        set(value) {
            assertNotNull(value)
            field = value
        }

    /** The node's `double` value. */
    var number: Double = 0.0

    init {
        typeField = Token.NUMBER
    }

    constructor() : super()

    constructor(pos: Int) : super(pos)

    constructor(pos: Int, len: Int) : super(pos, len)

    /** Sets the length to the length of the [value] string. */
    constructor(pos: Int, value: String) : super(pos) {
        this.value = value
        length = value.length
    }

    /** Sets the length to the length of the [value] string. */
    constructor(pos: Int, value: String, number: Double) : this(pos, value) {
        this.number = number
    }

    constructor(number: Double) : super() {
        this.number = number
        this.value = number.toString()
    }

    override fun toSource(depth: Int): String = makeIndent(depth) + (value ?: "<null>")

    /** Visits this node. There are no children to visit. */
    override fun visit(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
