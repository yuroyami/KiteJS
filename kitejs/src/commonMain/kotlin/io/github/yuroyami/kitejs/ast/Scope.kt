/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Node
import io.github.yuroyami.kitejs.Token

/**
 * A scope in the lexical scope chain. Base type for every [AstNode] that introduces a scope.
 */
public open class Scope(pos: Int = -1, len: Int = 1) : Jump() {

    /** Insertion-ordered so that iteration follows declaration order. */
    public var symbolTable: MutableMap<String, Symbol>? = null

    /**
     * KMP: upstream's protected `parentScope` field. `clearParentScope` writes it without the
     * `top` fix-up the [parentScope] accessor performs, so both halves stay.
     */
    internal var parentScopeField: Scope? = null

    /** The current script or function scope. */
    public var top: ScriptNode? = null

    private var childScopeList: MutableList<Scope>? = null

    init {
        this.typeField = Token.BLOCK
        this.position = pos
        this.length = len
    }

    public var parentScope: Scope?
        get() = parentScopeField
        set(value) {
            parentScopeField = value
            this.top = if (value == null) this as ScriptNode else value.top
        }

    /** Used only for code generation. */
    public fun clearParentScope() {
        parentScopeField = null
    }

    /** The scopes whose parent is this scope, or null if there are none. */
    public val childScopes: List<Scope>?
        get() = childScopeList

    /** Adds a scope to the child list and sets the child's parent scope to this scope. */
    public fun addChildScope(child: Scope) {
        if (childScopeList == null) {
            childScopeList = mutableListOf()
        }
        childScopeList!!.add(child)
        child.parentScope = this
    }

    /**
     * Used by the parser. Repoints this scope's child scopes at [newScope] and copies this
     * scope's symbols into it.
     */
    public fun replaceWith(newScope: Scope) {
        childScopeList?.let { kids ->
            for (kid in kids) {
                newScope.addChildScope(kid) // sets kid's parent
            }
            kids.clear()
            childScopeList = null
        }
        val table = symbolTable
        if (table != null && table.isNotEmpty()) {
            joinScopes(this, newScope)
        }
    }

    /**
     * The scope in which [name] is defined: this scope, one of its parents, or null when the
     * name is not defined anywhere in this scope chain.
     */
    public fun getDefiningScope(name: String): Scope? {
        var s: Scope? = this
        while (s != null) {
            val table = s.symbolTable
            if (table != null && table.containsKey(name)) {
                return s
            }
            s = s.parentScopeField
        }
        return null
    }

    /** Looks up a symbol in this scope, or null if it is not there. */
    public fun getSymbol(name: String): Symbol? = symbolTable?.get(name)

    /** Enters a symbol into this scope. */
    public fun putSymbol(symbol: Symbol) {
        val name = symbol.name ?: throw IllegalArgumentException("null symbol name")
        ensureSymbolTable()
        symbolTable!![name] = symbol
        symbol.containingTable = this
        top!!.addSymbol(symbol)
    }

    private fun ensureSymbolTable(): MutableMap<String, Symbol> {
        if (symbolTable == null) {
            symbolTable = LinkedHashMap(5)
        }
        return symbolTable!!
    }

    /**
     * A copy of the child list, with each child cast to an [AstNode]. Throws once the code
     * generator has begun the tree transformation and non-AstNode children appear.
     */
    public val statements: List<AstNode>
        get() {
            val stmts = mutableListOf<AstNode>()
            var n = firstChild
            while (n != null) {
                stmts.add(n as AstNode)
                n = n.next
            }
            return stmts
        }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        sb.append(makeIndent(depth))
        sb.append("{\n")
        for (kid in this) {
            val astNodeKid = kid as AstNode
            sb.append(astNodeKid.toSource(depth + 1))
            if (astNodeKid.type == Token.COMMENT) {
                sb.append("\n")
            }
        }
        sb.append(makeIndent(depth))
        sb.append("}\n")
        return sb.toString()
    }

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            for (kid in this) {
                (kid as AstNode).visit(visitor)
            }
        }
    }

    public companion object {
        /**
         * Creates a new scope node, moving the symbol table from [scope] into it and making
         * [scope] a nested scope contained by the new node. Useful for injecting a scope into
         * a scope chain.
         */
        public fun splitScope(scope: Scope): Scope {
            val result = Scope(scope.position, scope.length)
            result.symbolTable = scope.symbolTable
            scope.symbolTable = null
            result.parentField = scope.parentField
            result.parentScope = scope.parentScope
            scope.parentField = result
            result.top = scope.top
            return result
        }

        /** Copies all symbols from [source] to [dest]. */
        public fun joinScopes(source: Scope, dest: Scope) {
            val src = source.ensureSymbolTable()
            val dst = dest.ensureSymbolTable()
            if (src.keys.any { it in dst.keys }) {
                codeBug()
            }
            for ((key, sym) in src) {
                sym.containingTable = dest
                dst[key] = sym
            }
        }
    }
}
