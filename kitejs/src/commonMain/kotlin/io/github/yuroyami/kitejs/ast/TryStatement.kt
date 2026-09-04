/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/** A try statement with its catch clauses and optional finally block. Node type is [Token.TRY]. */
class TryStatement(pos: Int = -1, len: Int = 1) : AstNode(pos, len) {

    private var catchClauseList: MutableList<CatchClause>? = null

    init {
        typeField = Token.TRY
    }

    /** The try block. Setting it reparents the block. */
    var tryBlock: AstNode? = null
        set(value) {
            val newBlock = value!!
            field = newBlock
            newBlock.parent = this
        }

    /** The finally block, or null. Setting it reparents the block. */
    var finallyBlock: AstNode? = null
        set(value) {
            field = value
            value?.parent = this
        }

    /** Position of the `finally` keyword, relative to this node. -1 if there is none. */
    var finallyPosition: Int = -1

    /** The catch clauses, or an empty list if there are none. */
    fun getCatchClauses(): List<CatchClause> = catchClauseList ?: NO_CATCHES

    fun setCatchClauses(catchClauses: List<CatchClause>?) {
        if (catchClauses == null) {
            this.catchClauseList = null
        } else {
            this.catchClauseList?.clear()
            for (cc in catchClauses) {
                addCatchClause(cc)
            }
        }
    }

    fun addCatchClause(clause: CatchClause) {
        val list = catchClauseList ?: mutableListOf<CatchClause>().also { catchClauseList = it }
        list.add(clause)
        clause.parent = this
    }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder(250)
        sb.append(makeIndent(depth))
        sb.append("try ")
        inlineComment?.let { sb.append(it.toSource(depth + 1)).append("\n") }
        sb.append(tryBlock!!.toSource(depth).trim())
        for (cc in getCatchClauses()) {
            sb.append(cc.toSource(depth))
        }
        finallyBlock?.let {
            sb.append(" finally ")
            sb.append(it.toSource(depth))
        }
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            tryBlock!!.visit(visitor)
            for (cc in getCatchClauses()) {
                cc.visit(visitor)
            }
            finallyBlock?.visit(visitor)
        }
    }

    companion object {
        private val NO_CATCHES: List<CatchClause> = emptyList()
    }
}
