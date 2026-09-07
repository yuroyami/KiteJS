/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Node
import io.github.yuroyami.kitejs.Token

/**
 * A JavaScript function declaration or expression. Node type is [Token.FUNCTION].
 *
 * ```
 * FunctionDeclaration : function Identifier ( FormalParameterList? ) { FunctionBody }
 * FunctionExpression  : function Identifier? ( FormalParameterList? ) { FunctionBody }
 * ```
 *
 * JavaScript 1.8 also allows a "function closure" of the form `function ([params]) Expression`.
 * Such a node has no body but does have an expression.
 */
public open class FunctionNode(pos: Int = -1, name: Name? = null) : ScriptNode(pos) {

    /**
     * Sets the function name and reparents it to this node. Null means an anonymous function.
     */
    public var functionName: Name? = null
        set(value) {
            field = value
            value?.parent = this
        }

    private var paramList: MutableList<AstNode>? = null

    private var bodyNode: AstNode? = null

    public var isExpressionClosure: Boolean = false

    private var functionForm: Form = Form.FUNCTION

    /** Left paren position, -1 if missing. */
    public var lp: Int = -1

    /** Right paren position, -1 if missing. */
    public var rp: Int = -1

    override var hasRestParameter: Boolean = false

    override var isShorthand: Boolean = false

    private var defaultParamsList: MutableList<Any?>? = null
    private var destructuringRvaluesList: MutableList<Array<Node>>? = null

    override val defaultParams: List<Any?>?
        get() = defaultParamsList

    override val destructuringRvalues: List<Array<Node>>?
        get() = destructuringRvaluesList

    // Codegen variables.

    /** The function type: statement, expression, or expression statement. */
    public var functionType: Int = 0

    /**
     * True when this function requires an Ecma-262 Activation object. The activation is
     * expensive to create, so the interpreter uses a plain call frame when it can. A lexical
     * closure is one of several situations that force one.
     */
    public var requiresActivation: Boolean = false

    public var requiresArgumentObject: Boolean = false

    public var isGenerator: Boolean = false

    public var isES6Generator: Boolean = false
        set(value) {
            field = value
            if (value) {
                isGenerator = true
                // Generators always need activation, because their calling convention is
                // always different. Set it now, even with no "yield" statements in the body.
                requiresActivation = true
            }
        }

    private var generatorResumePoints: MutableList<Node>? = null
    private var liveLocalsMap: MutableMap<Node, IntArray>? = null

    /** IR block for default parameter init in generators. */
    public var generatorParamInitBlock: Node? = null

    /**
     * Rhino supports a nonstandard extension letting you write `function a.b.c(arg) {...}`,
     * rewritten at codegen time to `a.b.c = function(arg) {...}`. When the parser sees an
     * expression other than a simple [Name] where the function name belongs, it records that
     * expression here. Enabled by the `allowMemberExprAsFunctionName` compiler option.
     */
    public var memberExprNode: AstNode? = null
        set(value) {
            field = value
            value?.parent = this
        }

    init {
        typeField = Token.FUNCTION
        functionName = name
    }

    /** How the function was written: a plain function, an accessor, or a method. */
    public enum class Form {
        FUNCTION,
        GETTER,
        SETTER,
        METHOD,
    }

    public fun putDefaultParams(left: Any?, right: Any?) {
        if (defaultParamsList == null) {
            defaultParamsList = mutableListOf()
        }
        defaultParamsList!!.add(left)
        defaultParamsList!!.add(right)
    }

    override fun putDestructuringRvalues(left: Node, right: Node) {
        if (destructuringRvaluesList == null) {
            destructuringRvaluesList = mutableListOf()
        }
        destructuringRvaluesList!!.add(arrayOf(left, right))
    }

    /** The function name as a string, or "" if anonymous. */
    public val name: String
        get() = functionName?.identifier ?: ""

    /** The parameter list, or an empty list if there are no parameters. */
    public val params: List<AstNode> get() = paramList ?: NO_PARAMS

    /** Sets the parameter list and reparents every element. Null means no parameters. */
    public fun setParams(params: List<AstNode>?) {
        if (params == null) {
            paramList = null
        } else {
            paramList?.clear()
            for (param in params) addParam(param)
        }
    }

    /** Adds a parameter and reparents it to this node. */
    public fun addParam(param: AstNode) {
        if (paramList == null) {
            paramList = mutableListOf()
        }
        paramList!!.add(param)
        param.parent = this
    }

    /**
     * True when [node] is a parameter of this function. Lets a traversal tell the function
     * name node apart from the parameter nodes.
     */
    public fun isParam(node: AstNode): Boolean = params.contains(node)

    /**
     * The function body: normally a [Block], or a plain [AstNode] for a function closure.
     * Null only when the AST is malformed.
     *
     * Setting it reparents the body and derives the raw source bounds from the body bounds.
     * The function node's absolute position must already be set, along with the body node's
     * absolute position and length.
     */
    public var body: AstNode?
        get() = bodyNode
        set(value) {
            val newBody = value!!
            bodyNode = newBody
            if (newBody.getProp(EXPRESSION_CLOSURE_PROP) == true) {
                isExpressionClosure = true
            }
            val absEnd = newBody.position + newBody.length
            newBody.parent = this
            this.length = absEnd - this.position
            setRawSourceBounds(this.position, absEnd)
        }

    /** Sets both paren positions. */
    public fun setParens(lp: Int, rp: Int) {
        this.lp = lp
        this.rp = rp
    }

    public fun addResumptionPoint(target: Node) {
        if (generatorResumePoints == null) generatorResumePoints = mutableListOf()
        generatorResumePoints!!.add(target)
    }

    public val resumptionPoints: List<Node>?
        get() = generatorResumePoints

    public val liveLocals: Map<Node, IntArray>?
        get() = liveLocalsMap

    public fun addLiveLocals(node: Node, locals: IntArray) {
        if (liveLocalsMap == null) liveLocalsMap = HashMap()
        liveLocalsMap!![node] = locals
    }

    override fun addFunction(fnNode: FunctionNode): Int {
        val result = super.addFunction(fnNode)
        if (functionCount > 0) {
            requiresActivation = true
        }
        return result
    }

    public val isMethod: Boolean
        get() = functionForm == Form.GETTER ||
            functionForm == Form.SETTER ||
            functionForm == Form.METHOD

    public val isGetterMethod: Boolean
        get() = functionForm == Form.GETTER

    public val isSetterMethod: Boolean
        get() = functionForm == Form.SETTER

    public val isNormalMethod: Boolean
        get() = functionForm == Form.METHOD

    public fun setFunctionIsGetterMethod() {
        functionForm = Form.GETTER
    }

    public fun setFunctionIsSetterMethod() {
        functionForm = Form.SETTER
    }

    public fun setFunctionIsNormalMethod() {
        functionForm = Form.METHOD
    }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        val isArrow = functionType == ARROW_FUNCTION
        if (!isMethod) {
            sb.append(makeIndent(depth))
            if (!isArrow) {
                sb.append("function")
            }
        }
        functionName?.let {
            sb.append(" ")
            sb.append(it.toSource(0))
        }
        val currentParams = params
        if (currentParams == null) {
            sb.append("() ")
        } else if (isArrow && lp == -1) {
            // No paren.
            printList(currentParams, sb)
            sb.append(" ")
        } else {
            sb.append("(")
            printList(currentParams, sb)
            if (getIntProp(TRAILING_COMMA, 0) == 1) {
                sb.append(", ")
            }
            sb.append(") ")
        }
        if (isArrow) {
            sb.append("=> ")
        }
        if (isExpressionClosure) {
            var closureBody = body!!
            val lastChild = closureBody.lastChild
            if (lastChild is ReturnStatement) {
                // Omit the "return" keyword, print just the expression.
                closureBody = lastChild.returnValue!!
                sb.append(closureBody.toSource(0))
                if (functionType == FUNCTION_STATEMENT) {
                    sb.append(";")
                }
            } else {
                // Should never happen.
                sb.append(" ")
                sb.append(closureBody.toSource(0))
            }
        } else {
            sb.append(body!!.toSource(depth).trim())
        }
        if (functionType == FUNCTION_STATEMENT || isMethod) {
            sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * Visits this node, the function name if present, the parameters and the body. A
     * member-expr node is visited last.
     */
    override fun visit(visitor: NodeVisitor) {
        if (visitor.visit(this)) {
            functionName?.visit(visitor)
            for (param in params) {
                param.visit(visitor)
            }
            body!!.visit(visitor)
            if (!isExpressionClosure) {
                memberExprNode?.visit(visitor)
            }
        }
    }

    public companion object {
        /**
         * A function statement is a function appearing as a top-level statement, not nested
         * inside some other statement, in either a script or a function.
         */
        public const val FUNCTION_STATEMENT: Int = 1

        /** A function expression is a function appearing in an expression. */
        public const val FUNCTION_EXPRESSION: Int = 2

        /** A function expression that is the top-level expression of an expression statement. */
        public const val FUNCTION_EXPRESSION_STATEMENT: Int = 3

        public const val ARROW_FUNCTION: Int = 4

        private val NO_PARAMS: List<AstNode> = emptyList()

        /**
         * Calculates the arity (function.length). Per spec the length property counts only the
         * parameters before the first one with a default value, and rest parameters do not
         * count at all.
         *
         * Ref: ECMA 2026, 15.1.5 Static Semantics: ExpectedArgumentCount
         */
        public fun calculateFunctionArity(scriptOrFn: ScriptNode): Int {
            val paramCount = scriptOrFn.paramCount
            var arity = paramCount

            if (scriptOrFn is FunctionNode) {
                val defaultParams = scriptOrFn.defaultParams

                if (defaultParams != null && defaultParams.isNotEmpty()) {
                    // defaultParams holds pairs: [paramName (String), defaultValue (AstNode)].
                    // Count up to the first parameter that has a default value.
                    val params = scriptOrFn.params
                    if (params.isNotEmpty()) {
                        var i = 0
                        while (i < defaultParams.size) {
                            val entry = defaultParams[i]
                            if (entry is String) {
                                for (paramIndex in params.indices) {
                                    val param = params[paramIndex]
                                    if (param is Name && param.identifier == entry) {
                                        arity = paramIndex
                                        break
                                    }
                                }
                                if (arity != paramCount) break
                            }
                            i += 2
                        }
                    }
                }
            }

            // Rest parameters do not count toward length.
            if (scriptOrFn.hasRestParameter && arity == paramCount) {
                arity = maxOf(0, arity - 1)
            }

            return arity
        }
    }
}
