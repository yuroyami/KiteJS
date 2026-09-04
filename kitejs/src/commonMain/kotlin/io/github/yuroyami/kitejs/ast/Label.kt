/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * One label in a [LabeledStatement], for example the `outer` in `outer: for (;;) {}`. Node
 * type is [Token.LABEL].
 *
 * The constructors set position and length directly instead of delegating to [Jump], which is
 * how upstream does it too. Note the single-argument form leaves the length at -1.
 */
class Label : Jump {

    init {
        typeField = Token.LABEL
    }

    constructor() : super()

    constructor(pos: Int) : this(pos, -1)

    constructor(pos: Int, len: Int) : super() {
        position = pos
        length = len
    }

    constructor(pos: Int, len: Int, name: String?) : this(pos, len) {
        this.name = name
    }

    /** The label name. Setting it trims the value and rejects a blank one. */
    var name: String? = null
        set(value) {
            val trimmed = value?.trim()
            if (trimmed.isNullOrEmpty()) {
                throw IllegalArgumentException("invalid label name")
            }
            field = trimmed
        }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        sb.append(name)
        sb.append(":\n")
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
