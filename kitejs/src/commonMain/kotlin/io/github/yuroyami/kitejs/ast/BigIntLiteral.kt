/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.KBigInt
import io.github.yuroyami.kitejs.Token

/**
 * A BigInt literal such as `123n`. Node type is [Token.BIGINT].
 *
 * KMP: [bigInt] holds a [KBigInt], the digits-and-radix stand-in for BigInteger (D-5). Its
 * `toString` matches upstream only for decimal literals until Phase 5 lands real arithmetic.
 */
class BigIntLiteral : AstNode {

    /** The node's string value: the original source token. */
    var value: String? = null
        set(value) {
            assertNotNull(value)
            field = value
        }

    private var bigIntValue: KBigInt? = null

    init {
        typeField = Token.BIGINT
    }

    constructor() : super()

    constructor(pos: Int) : super(pos)

    constructor(pos: Int, len: Int) : super(pos, len)

    constructor(pos: Int, value: String) : super(pos) {
        this.value = value
        length = value.length
    }

    constructor(pos: Int, value: String, bigInt: KBigInt?) : this(pos, value) {
        this.bigInt = bigInt
    }

    override var bigInt: KBigInt?
        get() = bigIntValue
        set(value) {
            bigIntValue = value
        }

    override fun toSource(depth: Int): String =
        makeIndent(depth) + (bigIntValue?.let { it.toString() + "n" } ?: "<null>")

    override fun visit(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
