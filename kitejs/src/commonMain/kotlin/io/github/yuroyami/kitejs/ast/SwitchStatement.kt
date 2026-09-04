/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/** A switch statement. Node type is [Token.SWITCH]. It introduces a scope. */
class SwitchStatement(pos: Int = -1) : Scope() {

    private var caseList: MutableList<SwitchCase>? = null

    init {
        typeField = Token.SWITCH
        position = pos
    }

    /** The switch expression. Setting it reparents the expression. */
    var expression: AstNode? = null
        set(value) {
            val newExpression = value!!
            field = newExpression
            newExpression.parent = this
        }

    /** Position of the left paren, relative to this node. */
    var lp: Int = -1

    /** Position of the right paren, relative to this node. */
    var rp: Int = -1

    /** The case clauses, or an empty list if none were added. */
    fun getCases(): List<SwitchCase> = caseList ?: NO_CASES

    fun setCases(cases: List<SwitchCase>?) {
        if (cases == null) {
            this.caseList = null
        } else {
            this.caseList?.clear()
            for (sc in cases) addCase(sc)
        }
    }

    fun addCase(switchCase: SwitchCase) {
        val list = caseList ?: mutableListOf<SwitchCase>().also { caseList = it }
        list.add(switchCase)
        switchCase.parent = this
    }

    /** Sets both paren positions. */
    fun setParens(lp: Int, rp: Int) {
        this.lp = lp
        this.rp = rp
    }

    override fun toSource(depth: Int): String {
        val pad = makeIndent(depth)
        val sb = StringBuilder()
        sb.append(pad)
        sb.append("switch (")
        sb.append(expression!!.toSource(0))
        sb.append(") {\n")
        caseList?.forEach { sb.append(it.toSource(depth + 1)) }
        sb.append(pad)
        sb.append("}\n")
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            expression!!.visit(visitor)
            for (sc in getCases()) {
                sc.visit(visitor)
            }
        }
    }

    companion object {
        private val NO_CASES: List<SwitchCase> = emptyList()
    }
}
