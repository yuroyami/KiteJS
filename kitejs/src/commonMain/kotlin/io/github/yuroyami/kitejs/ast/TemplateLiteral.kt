/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * A template literal. Node type is [Token.TEMPLATE_LITERAL]. Its elements alternate between
 * [TemplateCharacters] runs and substitution expressions.
 */
class TemplateLiteral(pos: Int = -1, len: Int = 1) : AstNode(pos, len) {

    private var elementList: MutableList<AstNode>? = null

    init {
        typeField = Token.TEMPLATE_LITERAL
    }

    /** The literal text runs, in source order. */
    val templateStrings: List<TemplateCharacters>
        get() = elementList
            ?.filterIsInstance<TemplateCharacters>()
            ?: emptyList()

    /** The substitution expressions, in source order. */
    val substitutions: List<AstNode>
        get() = elementList?.filter { it.type != Token.TEMPLATE_CHARS } ?: emptyList()

    /** Every element, text runs and substitutions interleaved in source order. */
    val elements: List<AstNode>
        get() = elementList ?: emptyList()

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
        sb.append("`")
        for (e in elements) {
            if (e.type == Token.TEMPLATE_CHARS) {
                sb.append(e.toSource(0))
            } else {
                sb.append("\${").append(e.toSource(0)).append("}")
            }
        }
        sb.append("`")
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            for (e in elements) {
                e.visit(visitor)
            }
        }
    }
}
