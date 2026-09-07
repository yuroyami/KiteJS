/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/** A function or method call. Node type is [Token.CALL]. */
open class FunctionCall(pos: Int = -1, len: Int = 1) : AstNode(pos, len) {

    protected var argumentList: MutableList<AstNode>? = null

    init {
        typeField = Token.CALL
    }

    /** The callee. Setting it reparents the node and copies its line and column. */
    var target: AstNode? = null
        set(value) {
            val newTarget = value!!
            field = newTarget
            newTarget.parent = this
            setLineColumnNumber(newTarget.lineno, newTarget.column)
        }

    /** Position of the left paren, relative to this node. */
    var lp: Int = -1

    /** Position of the right paren, relative to this node. */
    var rp: Int = -1

    /** True for an optional call `a?.()`. */
    var isOptionalCall: Boolean = false
        private set

    /** The call arguments, or an empty list if there are none. */
    val arguments: List<AstNode> get() = argumentList ?: NO_ARGS

    /** Replaces the argument list and reparents every element. Null means no arguments. */
    fun setArguments(arguments: List<AstNode>?) {
        if (arguments == null) {
            this.argumentList = null
        } else {
            this.argumentList?.clear()
            for (arg in arguments) {
                addArgument(arg)
            }
        }
    }

    fun addArgument(arg: AstNode) {
        val list = argumentList ?: mutableListOf<AstNode>().also { argumentList = it }
        list.add(arg)
        arg.parent = this
    }

    /** Sets both paren positions. */
    fun setParens(lp: Int, rp: Int) {
        this.lp = lp
        this.rp = rp
    }

    fun markIsOptionalCall() {
        isOptionalCall = true
    }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        sb.append(target!!.toSource(0))
        if (isOptionalCall) {
            sb.append("?.")
        }
        sb.append("(")
        argumentList?.let { printList(it, sb) }
        sb.append(")")
        inlineComment?.let { sb.append(it.toSource(depth)).append("\n") }
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            target!!.visit(visitor)
            for (arg in arguments) {
                arg.visit(visitor)
            }
        }
    }

    companion object {
        internal val NO_ARGS: List<AstNode> = emptyList()
    }
}
