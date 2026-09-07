/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * A break statement, with or without a label. Node type is [Token.BREAK].
 *
 * The constructors set position and length directly instead of delegating to [Jump], which is
 * how upstream does it too.
 */
public class BreakStatement(pos: Int = -1, len: Int = 1) : Jump() {

    private var targetNode: AstNode? = null

    init {
        typeField = Token.BREAK
        position = pos
        length = len
    }

    /** The label this break jumps to, or null for a plain `break`. Setting it reparents. */
    public var breakLabel: Name? = null
        set(value) {
            field = value
            value?.parent = this
        }

    /** The statement this break jumps out of. */
    public val breakTarget: AstNode?
        get() = targetNode

    /** Records the jump target. Also sets the jump statement, so it can only be called once. */
    public fun setBreakTarget(target: Jump) {
        targetNode = target
        jumpStatement = target
    }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        sb.append("break")
        breakLabel?.let {
            sb.append(" ")
            sb.append(it.toSource(0))
        }
        sb.append(";\n")
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            breakLabel?.visit(visitor)
        }
    }
}
