/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Node
import io.github.yuroyami.kitejs.Token

/**
 * Used for code generation. During codegen the AST is lowered to an IR where loops, ifs,
 * switches and other control flow become labeled jumps. In IDE mode the resulting AST holds
 * no instances of this class.
 *
 * KMP: `getFinally`, `getContinue` and `getDefault` become `finallyTarget`,
 * `continueTarget` and `defaultTarget`, since `finally` and `continue` are Kotlin keywords.
 */
public open class Jump(nodeType: Int = Token.ERROR) : AstNode() {

    public var target: Node? = null
    private var target2: Node? = null
    private var jumpNode: Jump? = null

    init {
        typeField = nodeType
    }

    public constructor(nodeType: Int, child: Node) : this(nodeType) {
        addChildToBack(child)
    }

    public var jumpStatement: Jump?
        get() {
            if (typeField != Token.BREAK && typeField != Token.CONTINUE) codeBug()
            return jumpNode
        }
        set(value) {
            if (typeField != Token.BREAK && typeField != Token.CONTINUE) codeBug()
            if (value == null) codeBug()
            if (jumpNode != null) codeBug() // only once
            jumpNode = value
        }

    public var defaultTarget: Node?
        get() {
            if (typeField != Token.SWITCH) codeBug()
            return target2
        }
        set(value) {
            if (typeField != Token.SWITCH) codeBug()
            if (value!!.type != Token.TARGET) codeBug()
            if (target2 != null) codeBug() // only once
            target2 = value
        }

    public var finallyTarget: Node?
        get() {
            if (typeField != Token.TRY) codeBug()
            return target2
        }
        set(value) {
            if (typeField != Token.TRY) codeBug()
            if (value!!.type != Token.TARGET) codeBug()
            if (target2 != null) codeBug() // only once
            target2 = value
        }

    public var loop: Jump?
        get() {
            if (typeField != Token.LABEL) codeBug()
            return jumpNode
        }
        set(value) {
            if (typeField != Token.LABEL) codeBug()
            if (value == null) codeBug()
            if (jumpNode != null) codeBug() // only once
            jumpNode = value
        }

    public var continueTarget: Node?
        get() {
            if (typeField != Token.LOOP) codeBug()
            return target2
        }
        set(value) {
            if (typeField != Token.LOOP) codeBug()
            if (value!!.type != Token.TARGET) codeBug()
            if (target2 != null) codeBug() // only once
            target2 = value
        }

    /** Jumps are only used during code generation and do not support the visitor interface. */
    override fun visit(visitor: NodeVisitor) {
        throw UnsupportedOperationException(this.toString())
    }

    /** Jumps are only used during code generation and cannot render source. */
    override fun toSource(depth: Int): String =
        throw UnsupportedOperationException(this.toString())
}
