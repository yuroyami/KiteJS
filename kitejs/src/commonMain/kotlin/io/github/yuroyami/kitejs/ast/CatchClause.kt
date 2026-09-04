/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * One catch clause of a try statement. Node type is [Token.CATCH]. The [catchCondition] is the
 * SpiderMonkey `catch (e if cond)` extension.
 */
class CatchClause(pos: Int = -1, len: Int = 1) : AstNode(pos, len) {

    init {
        typeField = Token.CATCH
    }

    /**
     * The caught binding: a [Name], or an [ArrayLiteral] or [ObjectLiteral] for a destructuring
     * catch. Setting it reparents the node.
     */
    var varName: AstNode? = null
        set(value) {
            field = value
            value?.parent = this
        }

    /** The guard expression of a conditional catch, or null. Setting it reparents. */
    var catchCondition: AstNode? = null
        set(value) {
            field = value
            value?.parent = this
        }

    /** The catch body. Setting it reparents the block. */
    var body: Scope? = null
        set(value) {
            val newBody = value!!
            field = newBody
            newBody.parent = this
        }

    /** Position of the `if` keyword in a conditional catch, relative to this node. */
    var ifPosition: Int = -1

    /** Position of the left paren, relative to this node. */
    var lp: Int = -1

    /** Position of the right paren, relative to this node. */
    var rp: Int = -1

    /** Sets both paren positions. */
    fun setParens(lp: Int, rp: Int) {
        this.lp = lp
        this.rp = rp
    }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        sb.append("catch (")
        sb.append(varName!!.toSource(0))
        catchCondition?.let {
            sb.append(" if ")
            sb.append(it.toSource(0))
        }
        sb.append(") ")
        sb.append(body!!.toSource(0))
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            varName!!.visit(visitor)
            catchCondition?.visit(visitor)
            body!!.visit(visitor)
        }
    }
}
