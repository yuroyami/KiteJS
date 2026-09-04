/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * One `case` or `default` clause of a switch. Node type is [Token.CASE]; a null [expression]
 * means it is the default clause.
 */
class SwitchCase(pos: Int = -1, len: Int = 1) : AstNode(pos, len) {

    private var statementList: MutableList<AstNode>? = null

    init {
        typeField = Token.CASE
    }

    /** The case expression, or null for the default clause. Setting it reparents. */
    var expression: AstNode? = null
        set(value) {
            field = value
            value?.parent = this
        }

    val isDefault: Boolean
        get() = expression == null

    /** The statements in this clause, or null if none were added. */
    val statements: List<AstNode>?
        get() = statementList

    fun setStatements(statements: List<AstNode>) {
        statementList?.clear()
        for (s in statements) {
            addStatement(s)
        }
    }

    /** Adds a statement, reparents it and grows this node to include it. */
    fun addStatement(statement: AstNode) {
        val list = statementList ?: mutableListOf<AstNode>().also { statementList = it }
        val end = statement.position + statement.length
        this.length = end - this.position
        list.add(statement)
        statement.parent = this
    }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        val caseExpression = expression
        if (caseExpression == null) {
            sb.append("default:\n")
        } else {
            sb.append("case ")
            sb.append(caseExpression.toSource(0))
            sb.append(":")
            inlineComment?.let { sb.append(it.toSource(depth + 1)) }
            sb.append("\n")
        }
        statementList?.forEach { s ->
            sb.append(s.toSource(depth + 1))
            if (s.type == Token.COMMENT && (s as Comment).commentType == Token.CommentType.LINE) {
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            expression?.visit(visitor)
            statementList?.forEach { it.visit(visitor) }
        }
    }
}
