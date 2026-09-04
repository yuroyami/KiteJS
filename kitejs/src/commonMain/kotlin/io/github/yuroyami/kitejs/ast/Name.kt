/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * A simple name: an identifier that is not a keyword. Node type is [Token.NAME].
 *
 * Also used for a few non-identifier names that are part of the language syntax, such as the
 * "get" and "set" pseudo-keywords in object initializers.
 */
class Name : AstNode {

    /** The node's identifier. Setting it also updates the node length. */
    var identifier: String? = null
        set(value) {
            assertNotNull(value)
            field = value
            length = value!!.length
        }

    private var scopeField: Scope? = null

    init {
        typeField = Token.NAME
    }

    constructor() : super()

    constructor(pos: Int) : super(pos)

    constructor(pos: Int, len: Int) : super(pos, len)

    constructor(pos: Int, len: Int, name: String) : super(pos, len) {
        identifier = name
    }

    constructor(pos: Int, name: String) : super(pos) {
        identifier = name
        length = name.length
    }

    /**
     * The [Scope] associated with this node. Only used by (and set by) the code generator, so
     * it is always null in frontend AST-processing code. Use [definingScope] to find the
     * lexical scope in which this name is defined.
     *
     * Setting it does not touch any field in the scope. Not every name has one: typically
     * only function and variable names, not property names, are registered in a scope.
     */
    override var scope: Scope?
        get() = scopeField
        set(value) {
            scopeField = value
        }

    /**
     * The [Scope] in which this name is defined, or null if it is not defined in the current
     * lexical scope chain.
     */
    val definingScope: Scope?
        get() {
            val enclosing = enclosingScope ?: return null
            val name = identifier ?: return null
            return enclosing.getDefiningScope(name)
        }

    /**
     * True when this name is known to be defined as a symbol in a lexical scope other than
     * the top-level one: a local variable, a non-global let binding, a function parameter, a
     * loop variable, and so on. False when it resolves to the top-level scope or is not in
     * the symbol table at all, which may mean an external or built-in name.
     */
    val isLocalName: Boolean
        get() {
            val scope = definingScope
            return scope != null && scope.parentScope != null
        }

    /**
     * The length of this node's identifier, so you can pretend it is a String. Not the same
     * as [AstNode.length], which is the source range the node covers.
     */
    fun length(): Int = identifier?.length ?: 0

    override fun toSource(depth: Int): String = makeIndent(depth) + (identifier ?: "<null>")

    fun withPrefix(prefix: String): Name {
        val clone = Name(this.position, this.length, prefix + this.identifier)
        clone.setLineColumnNumber(this.lineno, this.column)
        return clone
    }

    /** Visits this node. There are no children to visit. */
    override fun visit(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}
