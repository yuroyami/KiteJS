/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * A labeled statement. A statement can carry several labels, as in `a: b: c: while (...) {}`.
 * Node type is [Token.EXPR_VOID].
 */
public class LabeledStatement(pos: Int = -1, len: Int = 1) : AstNode(pos, len) {

    /** Always holds at least one label once the parser is done. */
    private val labelList: MutableList<Label> = mutableListOf()

    init {
        typeField = Token.EXPR_VOID
    }

    public val labels: List<Label>
        get() = labelList

    public fun setLabels(labels: List<Label>) {
        labelList.clear()
        for (l in labels) {
            addLabel(l)
        }
    }

    public fun addLabel(label: Label) {
        labelList.add(label)
        label.parent = this
    }

    /** The labeled statement. Setting it reparents the statement. */
    public var statement: AstNode? = null
        set(value) {
            val newStatement = value!!
            field = newStatement
            newStatement.parent = this
        }

    public fun getLabelByName(name: String): Label? = labelList.firstOrNull { name == it.name }

    public val firstLabel: Label
        get() = labelList[0]

    override fun hasSideEffects(): Boolean =
        // Just to avoid the default case for EXPR_VOID in AstNode.
        true

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        for (label in labelList) {
            sb.append(label.toSource(depth)) // prints a newline
        }
        sb.append(statement!!.toSource(depth + 1))
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            for (label in labelList) {
                label.visit(visitor)
            }
            statement!!.visit(visitor)
        }
    }
}
