/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * One `key: value` entry of an object literal, or a getter, setter or method definition. The
 * node type is [Token.COLON], [Token.GET], [Token.SET] or [Token.METHOD].
 */
public class ObjectProperty(pos: Int = -1, len: Int = 1) : AbstractObjectProperty(pos, len) {

    private var keyNode: AstNode? = null
    private var valueNode: AstNode? = null

    // Vestigial upstream: nothing in Rhino 1.9.1 ever sets it, so it stays false.
    private val shorthand = false

    init {
        typeField = Token.COLON
    }

    /** Sets the node type, rejecting anything that is not a valid property form. */
    public fun setNodeType(nodeType: Int) {
        if (nodeType != Token.COLON &&
            nodeType != Token.GET &&
            nodeType != Token.SET &&
            nodeType != Token.METHOD
        ) {
            throw IllegalArgumentException("invalid node type: $nodeType")
        }
        type = nodeType
    }

    /**
     * Sets both halves at once, computing this node's bounds from the children while their
     * positions are still absolute.
     */
    public fun setKeyAndValue(key: AstNode, value: AstNode) {
        keyNode = key
        valueNode = value
        val beg = key.position
        val end = value.position + value.length
        setBounds(beg, end)

        // These updates make the child positions parent-relative.
        setLineColumnNumber(key.lineno, key.column)
        key.parent = this
        value.parent = this
    }

    public fun setIsGetterMethod() {
        typeField = Token.GET
    }

    public val isGetterMethod: Boolean
        get() = typeField == Token.GET

    public fun setIsSetterMethod() {
        typeField = Token.SET
    }

    public val isSetterMethod: Boolean
        get() = typeField == Token.SET

    public fun setIsNormalMethod() {
        typeField = Token.METHOD
    }

    public val isNormalMethod: Boolean
        get() = typeField == Token.METHOD

    public val isMethod: Boolean
        get() = isGetterMethod || isSetterMethod || isNormalMethod

    public val key: AstNode?
        get() = keyNode

    public val value: AstNode?
        get() = valueNode

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth + 1))
        if (isGetterMethod) {
            sb.append("get ")
        } else if (isSetterMethod) {
            sb.append("set ")
        }
        sb.append(keyNode!!.toSource(if (type == Token.COLON) 0 else depth))
        if (!shorthand) {
            if (typeField == Token.COLON) {
                sb.append(": ")
            }
            sb.append(valueNode!!.toSource(if (type == Token.COLON) 0 else depth + 1))
        }
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            keyNode!!.visit(visitor)
            valueNode!!.visit(visitor)
        }
    }
}
