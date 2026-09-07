/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Node
import io.github.yuroyami.kitejs.Token

/**
 * Base type for [AstRoot] and [FunctionNode], which collect much of the same information.
 */
open class ScriptNode(pos: Int = -1) : Scope(pos) {

    /** The URI, path or descriptive text naming the origin of this script's source. */
    var sourceName: String? = null

    /** Start offset of the raw source. Only valid when [rawSource] is non-null. */
    var rawSourceStart: Int = -1

    /** End offset of the raw source. Only valid when [rawSource] is non-null. */
    var rawSourceEnd: Int = -1

    /** The raw source, or null if it was not recorded. Used by the code generator. */
    var rawSource: String? = null

    private var functionList: MutableList<FunctionNode>? = null
    private var regexps: MutableList<RegExpLiteral>? = null
    private var templateLiterals: MutableList<TemplateLiteral>? = null

    /** Every symbol in this script or function, in declaration order. */
    var symbols: MutableList<Symbol> = ArrayList(4)

    /** Number of parameters, counted as [Token.LP] symbols arrive. */
    var paramCount: Int = 0
        private set

    private var variableNames: Array<String>? = null
    private var isConsts: BooleanArray? = null

    private var tempNumber = 0

    var isInStrictMode: Boolean = false

    var isMethodDefinition: Boolean = false

    init {
        // During parsing a ScriptNode or FunctionNode's top scope is itself.
        this.top = this
        this.typeField = Token.SCRIPT
    }

    /** Used by the code generator. */
    fun setRawSourceBounds(start: Int, end: Int) {
        this.rawSourceStart = start
        this.rawSourceEnd = end
    }

    /**
     * Base (starting) line number for this script or function. Setting it is a one-time
     * operation and fails if the line number is already set.
     */
    var baseLineno: Int
        get() = linenoField
        set(value) {
            if (value < 0 || linenoField >= 0) codeBug()
            linenoField = value
        }

    var endLineno: Int = -1
        set(value) {
            // One time action.
            if (value < 0 || field >= 0) codeBug()
            field = value
        }

    val functionCount: Int
        get() = functionList?.size ?: 0

    fun getFunctionNode(i: Int): FunctionNode = functionList!![i]

    val functions: List<FunctionNode> get() = functionList ?: emptyList()

    /**
     * Adds a [FunctionNode] to the functions table for codegen. Does not set the parent of
     * the node.
     *
     * @return the index of the function within its parent
     */
    open fun addFunction(fnNode: FunctionNode): Int {
        if (functionList == null) functionList = mutableListOf()
        functionList!!.add(fnNode)
        return functionList!!.size - 1
    }

    val regexpCount: Int
        get() = regexps?.size ?: 0

    fun getRegexpString(index: Int): String? = regexps!![index].value

    fun getRegexpFlags(index: Int): String? = regexps!![index].flags

    /** Called by IRFactory to add a RegExp to the regexp table. */
    fun addRegExp(re: RegExpLiteral) {
        if (regexps == null) regexps = mutableListOf()
        regexps!!.add(re)
        re.putIntProp(REGEXP_PROP, regexps!!.size - 1)
    }

    val templateLiteralCount: Int
        get() = templateLiterals?.size ?: 0

    fun getTemplateLiteralStrings(index: Int): List<TemplateCharacters> =
        templateLiterals!![index].templateStrings

    /** Called by IRFactory to add a template literal to the table. */
    fun addTemplateLiteral(templateLiteral: TemplateLiteral) {
        if (templateLiterals == null) templateLiterals = mutableListOf()
        templateLiterals!!.add(templateLiteral)
        templateLiteral.putIntProp(TEMPLATE_LITERAL_PROP, templateLiterals!!.size - 1)
    }

    fun getIndexForNameNode(nameNode: Node): Int {
        if (variableNames == null) codeBug()
        val node = nameNode.scope
        var symbol: Symbol? = null
        if (node != null && nameNode is Name) {
            symbol = nameNode.identifier?.let { node.getSymbol(it) }
        }
        return symbol?.index ?: -1
    }

    fun getParamOrVarName(index: Int): String {
        if (variableNames == null) codeBug()
        return variableNames!![index]
    }

    val paramAndVarCount: Int
        get() {
            if (variableNames == null) codeBug()
            return symbols.size
        }

    val paramAndVarNames: Array<String>
        get() {
            if (variableNames == null) codeBug()
            return variableNames!!
        }

    val paramAndVarConst: BooleanArray
        get() {
            if (variableNames == null) codeBug()
            return isConsts!!
        }

    open val hasRestParameter: Boolean
        get() = false

    open val defaultParams: List<Any?>?
        get() = null

    open val destructuringRvalues: List<Array<Node>>?
        get() = null

    open val isShorthand: Boolean
        get() = false

    // Overridden in FunctionNode.
    open fun putDestructuringRvalues(left: Node, right: Node) {}

    internal fun addSymbol(symbol: Symbol) {
        if (variableNames != null) codeBug()
        if (symbol.declType == Token.LP) {
            paramCount++
        }
        symbols.add(symbol)
    }

    /**
     * Assigns every symbol a unique integer index and builds the name and constness arrays
     * that can be indexed by it.
     *
     * @param flattenAllTables true to flatten nested block-scope symbol tables too, false to
     *     flatten just this script's or function's own table.
     */
    fun flattenSymbolTable(flattenAllTables: Boolean) {
        if (!flattenAllTables) {
            val newSymbols = mutableListOf<Symbol>()
            if (this.symbolTable != null) {
                // Replace "symbols" with the symbols in this object's symbol table. The map
                // alone is not enough: duplicate parameters have to survive.
                for (symbol in symbols) {
                    if (symbol.containingTable === this) {
                        newSymbols.add(symbol)
                    }
                }
            }
            symbols = newSymbols
        }
        variableNames = Array(symbols.size) { symbols[it].name ?: "" }
        isConsts = BooleanArray(symbols.size) { symbols[it].declType == Token.CONST }
        for (i in symbols.indices) {
            symbols[i].index = i
        }
    }

    var compilerData: Any? = null
        set(value) {
            if (value == null) throw IllegalArgumentException("arg cannot be null")
            // Can only be set once.
            if (field != null) throw IllegalStateException()
            field = value
        }

    fun getNextTempName(): String = "$" + tempNumber++

    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            for (kid in this) {
                (kid as AstNode).visit(visitor)
            }
        }
    }
}
