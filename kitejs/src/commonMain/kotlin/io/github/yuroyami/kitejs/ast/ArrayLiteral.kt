/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * An array literal, which doubles as an array destructuring target. Node type is
 * [Token.ARRAYLIT].
 */
class ArrayLiteral(pos: Int = -1, len: Int = 1) : AstNode(pos, len), DestructuringForm {

    private var elementList: MutableList<AstNode>? = null

    /** Number of targets in a destructuring pattern, including the elisions. */
    var destructuringLength: Int = 0

    /** Number of elided elements, as in `[a, , b]`. */
    var skipCount: Int = 0

    override var isDestructuring: Boolean = false

    init {
        typeField = Token.ARRAYLIT
    }

    /** The elements, or an empty list if none were added. */
    val elements: List<AstNode> get() = elementList ?: NO_ELEMS

    fun setElements(elements: List<AstNode>?) {
        if (elements == null) {
            this.elementList = null
        } else {
            this.elementList?.clear()
            for (e in elements) addElement(e)
        }
    }

    fun addElement(element: AstNode) {
        val list = elementList ?: mutableListOf<AstNode>().also { elementList = it }
        list.add(element)
        element.parent = this
    }

    val size: Int
        get() = elementList?.size ?: 0

    fun getElement(index: Int): AstNode {
        val list = elementList ?: throw IndexOutOfBoundsException("no elements")
        return list[index]
    }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        sb.append("[")
        elementList?.let { printList(it, sb) }
        sb.append("]")
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            for (e in elements) {
                e.visit(visitor)
            }
        }
    }

    companion object {
        private val NO_ELEMS: List<AstNode> = emptyList()
    }
}
