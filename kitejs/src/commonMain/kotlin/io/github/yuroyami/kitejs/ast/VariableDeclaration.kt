/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * A `var`, `const` or `let` declaration holding one or more [VariableInitializer] entries. The
 * node type is the declaration keyword's token, and setting it rejects anything else.
 */
class VariableDeclaration(pos: Int = -1, len: Int = 1) : AstNode(pos, len) {

    private val variableList: MutableList<VariableInitializer> = mutableListOf()

    /** True when this is a statement rather than the head of a for loop. */
    var isStatement: Boolean = false

    init {
        typeField = Token.VAR
    }

    override var type: Int
        get() = typeField
        set(value) {
            if (value != Token.VAR && value != Token.CONST && value != Token.LET) {
                throw IllegalArgumentException("invalid decl type: $value")
            }
            typeField = value
        }

    val variables: List<VariableInitializer>
        get() = variableList

    fun setVariables(variables: List<VariableInitializer>) {
        variableList.clear()
        for (vi in variables) {
            addVariable(vi)
        }
    }

    fun addVariable(v: VariableInitializer) {
        variableList.add(v)
        v.parent = this
    }

    val isVar: Boolean
        get() = typeField == Token.VAR

    val isConst: Boolean
        get() = typeField == Token.CONST

    val isLet: Boolean
        get() = typeField == Token.LET

    private fun declTypeName(): String = Token.typeToName(typeField).lowercase()

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        sb.append(declTypeName())
        sb.append(" ")
        printList(variableList, sb)
        if (isStatement) {
            sb.append(";")
        }
        val comment = inlineComment
        if (comment != null) {
            sb.append(comment.toSource(depth)).append("\n")
        } else if (isStatement) {
            sb.append("\n")
        }
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            for (v in variableList) {
                v.visit(visitor)
            }
        }
    }
}
