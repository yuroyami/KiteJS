/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * An object literal, which doubles as an object destructuring target. Node type is
 * [Token.OBJECTLIT].
 */
class ObjectLiteral(pos: Int = -1, len: Int = 1) : AstNode(pos, len), DestructuringForm {

    private var elementList: MutableList<AbstractObjectProperty>? = null

    override var isDestructuring: Boolean = false

    init {
        typeField = Token.OBJECTLIT
    }

    /** The properties, or an empty list if none were added. */
    val elements: List<AbstractObjectProperty> get() = elementList ?: NO_ELEMS

    fun setElements(elements: List<AbstractObjectProperty>?) {
        if (elements == null) {
            this.elementList = null
        } else {
            this.elementList?.clear()
            for (o in elements) addElement(o)
        }
    }

    fun addElement(element: AbstractObjectProperty) {
        val list = elementList
            ?: mutableListOf<AbstractObjectProperty>().also { elementList = it }
        list.add(element)
        element.parent = this
    }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        sb.append("{")
        elementList?.let { elements ->
            sb.append("\n")
            for ((i, element) in elements.withIndex()) {
                sb.append(element.toSource(depth))
                if (sb[sb.length - 1] == '\n') {
                    sb.deleteAt(sb.length - 1)
                }
                if (i < elements.size - 1) {
                    sb.append(",")
                }
                sb.append("\n")
            }
        }
        sb.append("}")
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            for (prop in elements) {
                prop.visit(visitor)
            }
        }
    }

    companion object {
        private val NO_ELEMS: List<AbstractObjectProperty> = emptyList()
    }
}
