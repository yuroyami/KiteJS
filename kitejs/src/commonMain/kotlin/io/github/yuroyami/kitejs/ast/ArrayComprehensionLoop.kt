/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

/**
 * One `for` clause inside an [ArrayComprehension] or a generator expression. It carries no
 * body: the comprehension result plays that role.
 */
public class ArrayComprehensionLoop(pos: Int = -1, len: Int = 1) : ForInLoop(pos, len) {

    override var body: AstNode?
        get() = null
        set(value) {
            throw UnsupportedOperationException("this node type has no body")
        }

    override fun toSource(depth: Int): String =
        makeIndent(depth) +
            " for " +
            (if (isForEach) "each " else "") +
            "(" +
            iterator!!.toSource(0) +
            (if (isForOf) " of " else " in ") +
            iteratedObject!!.toSource(0) +
            ")"

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            iterator!!.visit(visitor)
            iteratedObject!!.visit(visitor)
        }
    }
}
