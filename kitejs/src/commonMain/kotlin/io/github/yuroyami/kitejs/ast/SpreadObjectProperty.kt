/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/** A `...expr` entry of an object literal. Node type is [Token.DOTDOTDOT]. */
class SpreadObjectProperty(
    val spreadNode: Spread,
) : AbstractObjectProperty(spreadNode.position, spreadNode.length) {

    init {
        typeField = Token.DOTDOTDOT
        spreadNode.parent = this
        setLineColumnNumber(spreadNode.lineno, spreadNode.column)
    }

    override fun hasSideEffects(): Boolean = spreadNode.hasSideEffects()

    override fun toSource(depth: Int): String = spreadNode.toSource(depth)

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            spreadNode.visit(visitor)
        }
    }
}
