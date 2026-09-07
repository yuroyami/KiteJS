/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * A continue statement, with or without a label. Node type is [Token.CONTINUE].
 *
 * The constructors set position and length directly instead of delegating to [Jump], which is
 * how upstream does it too. Note the single-argument form leaves the length at -1.
 */
public class ContinueStatement : Jump {

    private var loopTarget: Loop? = null

    init {
        typeField = Token.CONTINUE
    }

    public constructor() : super()

    public constructor(pos: Int) : this(pos, -1)

    public constructor(pos: Int, len: Int) : super() {
        position = pos
        length = len
    }

    public constructor(label: Name?) : super() {
        this.label = label
    }

    public constructor(pos: Int, label: Name?) : this(pos) {
        this.label = label
    }

    public constructor(pos: Int, len: Int, label: Name?) : this(pos, len) {
        this.label = label
    }

    /**
     * The loop this continue jumps to. Setting it also records the jump statement, so it can
     * only be set once.
     *
     * KMP: upstream calls this `target`, but [Jump.target] is a public field there and a
     * property here, so the two would collide (D-11).
     */
    public var targetLoop: Loop?
        get() = loopTarget
        set(value) {
            val loop = value!!
            loopTarget = loop
            jumpStatement = loop
        }

    /** The label, or null for a plain `continue`. Setting it reparents the label. */
    public var label: Name? = null
        set(value) {
            field = value
            value?.parent = this
        }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        sb.append("continue")
        label?.let {
            sb.append(" ")
            sb.append(it.toSource(0))
        }
        sb.append(";\n")
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            label?.visit(visitor)
        }
    }
}
