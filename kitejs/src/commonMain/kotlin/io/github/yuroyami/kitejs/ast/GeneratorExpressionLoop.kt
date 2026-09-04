/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

/**
 * One `for` clause inside a generator expression. Unlike [ArrayComprehensionLoop] it does not
 * support the `for each` form.
 */
class GeneratorExpressionLoop(pos: Int = -1, len: Int = 1) : ForInLoop(pos, len) {

    override var isForEach: Boolean
        get() = false
        set(value) {
            throw UnsupportedOperationException("this node type does not support for each")
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
