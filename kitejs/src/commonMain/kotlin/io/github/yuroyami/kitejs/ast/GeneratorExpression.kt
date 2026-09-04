/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * A generator expression such as `(expr for (x in y) if (cond))`. Node type is
 * [Token.GENEXPR]. It introduces a scope, so it extends [Scope].
 */
class GeneratorExpression(pos: Int = -1, len: Int = 1) : Scope(pos, len) {

    /** The result expression. Setting it reparents the node. */
    var result: AstNode? = null
        set(value) {
            val newResult = value!!
            field = newResult
            newResult.parent = this
        }

    private val loopList: MutableList<GeneratorExpressionLoop> = mutableListOf()

    /** The filter expression, or null when there is no `if` clause. */
    var filter: AstNode? = null
        set(value) {
            field = value
            value?.parent = this
        }

    /** Position of the `if` keyword, relative to this node. -1 if there is no filter. */
    var ifPosition: Int = -1

    /** Position of the left paren of the filter condition. -1 if there is no filter. */
    var filterLp: Int = -1

    /** Position of the right paren of the filter condition. -1 if there is no filter. */
    var filterRp: Int = -1

    init {
        typeField = Token.GENEXPR
    }

    val loops: List<GeneratorExpressionLoop>
        get() = loopList

    fun setLoops(loops: List<GeneratorExpressionLoop>) {
        loopList.clear()
        for (acl in loops) {
            addLoop(acl)
        }
    }

    fun addLoop(acl: GeneratorExpressionLoop) {
        loopList.add(acl)
        acl.parent = this
    }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder(250)
        sb.append("(")
        sb.append(result!!.toSource(0))
        for (loop in loopList) {
            sb.append(loop.toSource(0))
        }
        filter?.let {
            sb.append(" if (")
            sb.append(it.toSource(0))
            sb.append(")")
        }
        sb.append(")")
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (!visitor.visit(this)) {
            return
        }
        result!!.visit(visitor)
        for (loop in loopList) {
            loop.visit(visitor)
        }
        filter?.visit(visitor)
    }
}
