/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * A `for..in` or `for..of` loop, and with [isForEach] the SpiderMonkey `for each..in` form.
 * Node type is [Token.FOR].
 */
open class ForInLoop(pos: Int = -1, len: Int = 1) : Loop(pos, len) {

    /** The loop variable. Setting it reparents the node. */
    var iterator: AstNode? = null
        set(value) {
            val newIterator = value!!
            field = newIterator
            newIterator.parent = this
        }

    /** The object being iterated. Setting it reparents the node. */
    var iteratedObject: AstNode? = null
        set(value) {
            val newObject = value!!
            field = newObject
            newObject.parent = this
        }

    /** Position of the `in` or `of` keyword, relative to this node. */
    var inPosition: Int = -1

    /** Position of the `each` keyword in a `for each..in` loop, relative to this node. */
    var eachPosition: Int = -1

    open var isForEach: Boolean = false

    var isForOf: Boolean = false

    init {
        typeField = Token.FOR
    }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        sb.append("for ")
        if (isForEach) {
            sb.append("each ")
        }
        sb.append("(")
        sb.append(iterator!!.toSource(0))
        if (isForOf) {
            sb.append(" of ")
        } else {
            sb.append(" in ")
        }
        sb.append(iteratedObject!!.toSource(0))
        sb.append(") ")
        val loopBody = bodyField!!
        if (loopBody.type == Token.BLOCK) {
            sb.append(loopBody.toSource(depth).trim()).append("\n")
        } else {
            sb.append("\n").append(loopBody.toSource(depth + 1))
        }
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            iterator!!.visit(visitor)
            iteratedObject!!.visit(visitor)
            bodyField!!.visit(visitor)
        }
    }
}
