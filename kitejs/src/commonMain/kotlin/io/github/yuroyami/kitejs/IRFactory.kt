/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.ast.AbstractObjectProperty
import io.github.yuroyami.kitejs.ast.ArrayComprehension
import io.github.yuroyami.kitejs.ast.ArrayComprehensionLoop
import io.github.yuroyami.kitejs.ast.ArrayLiteral
import io.github.yuroyami.kitejs.ast.Assignment
import io.github.yuroyami.kitejs.ast.AstNode
import io.github.yuroyami.kitejs.ast.AstRoot
import io.github.yuroyami.kitejs.ast.BigIntLiteral
import io.github.yuroyami.kitejs.ast.Block
import io.github.yuroyami.kitejs.ast.BreakStatement
import io.github.yuroyami.kitejs.ast.ComputedPropertyKey
import io.github.yuroyami.kitejs.ast.ConditionalExpression
import io.github.yuroyami.kitejs.ast.ContinueStatement
import io.github.yuroyami.kitejs.ast.DestructuringForm
import io.github.yuroyami.kitejs.ast.DoLoop
import io.github.yuroyami.kitejs.ast.ElementGet
import io.github.yuroyami.kitejs.ast.EmptyExpression
import io.github.yuroyami.kitejs.ast.ExpressionStatement
import io.github.yuroyami.kitejs.ast.ForInLoop
import io.github.yuroyami.kitejs.ast.ForLoop
import io.github.yuroyami.kitejs.ast.FunctionCall
import io.github.yuroyami.kitejs.ast.FunctionNode
import io.github.yuroyami.kitejs.ast.GeneratorExpression
import io.github.yuroyami.kitejs.ast.GeneratorExpressionLoop
import io.github.yuroyami.kitejs.ast.GeneratorMethodDefinition
import io.github.yuroyami.kitejs.ast.IfStatement
import io.github.yuroyami.kitejs.ast.InfixExpression
import io.github.yuroyami.kitejs.ast.Jump
import io.github.yuroyami.kitejs.ast.KeywordLiteral
import io.github.yuroyami.kitejs.ast.LabeledStatement
import io.github.yuroyami.kitejs.ast.LetNode
import io.github.yuroyami.kitejs.ast.Name
import io.github.yuroyami.kitejs.ast.NewExpression
import io.github.yuroyami.kitejs.ast.NumberLiteral
import io.github.yuroyami.kitejs.ast.ObjectLiteral
import io.github.yuroyami.kitejs.ast.ObjectProperty
import io.github.yuroyami.kitejs.ast.ParenthesizedExpression
import io.github.yuroyami.kitejs.ast.PropertyGet
import io.github.yuroyami.kitejs.ast.RegExpLiteral
import io.github.yuroyami.kitejs.ast.ReturnStatement
import io.github.yuroyami.kitejs.ast.Scope
import io.github.yuroyami.kitejs.ast.ScriptNode
import io.github.yuroyami.kitejs.ast.Spread
import io.github.yuroyami.kitejs.ast.SpreadObjectProperty
import io.github.yuroyami.kitejs.ast.StringLiteral
import io.github.yuroyami.kitejs.ast.SwitchStatement
import io.github.yuroyami.kitejs.ast.Symbol
import io.github.yuroyami.kitejs.ast.TaggedTemplateLiteral
import io.github.yuroyami.kitejs.ast.TemplateCharacters
import io.github.yuroyami.kitejs.ast.TemplateLiteral
import io.github.yuroyami.kitejs.ast.ThrowStatement
import io.github.yuroyami.kitejs.ast.TryStatement
import io.github.yuroyami.kitejs.ast.UnaryExpression
import io.github.yuroyami.kitejs.ast.UpdateExpression
import io.github.yuroyami.kitejs.ast.VariableDeclaration
import io.github.yuroyami.kitejs.ast.VariableInitializer
import io.github.yuroyami.kitejs.ast.WhileLoop
import io.github.yuroyami.kitejs.ast.WithStatement
import io.github.yuroyami.kitejs.ast.Yield

/**
 * Lowers the AST into the [Node] IR that the code generator consumes.
 *
 * The transform methods walk the tree the parser built and rewrite the high-level constructs into
 * the flat, jump-based form the interpreter wants. Loops become labeled targets, switches become a
 * jump table plus labels, comprehensions become nested loops, and destructuring becomes a chain of
 * plain assignments.
 *
 * KMP: the E4X transforms are not ported, following D-16.
 */
class IRFactory(
    env: CompilerEnvirons,
    sourceName: String?,
    sourceString: String,
    errorReporter: ErrorReporter = env.errorReporter,
) {

    private val parser = Parser(env, errorReporter)
    private val astNodePos = AstNodePosition(sourceString)
    private var outerScopeIsStrict = false

    constructor(env: CompilerEnvirons, sourceString: String) :
        this(env, null, sourceString, env.errorReporter)

    init {
        parser.currentPos = astNodePos
        parser.sourceURI = sourceName
    }

    /** Transforms the tree into the lower-level IR the code generator uses. */
    fun transformTree(root: AstRoot): ScriptNode? {
        parser.currentScriptOrFn = root
        parser.inUseStrictDirective = root.isInStrictMode

        astNodePos.push(root)
        return try {
            transform(root) as ScriptNode
        } catch (e: Parser.ParserException) {
            parser.reportErrorsIfExists(root.lineno)
            null
        } finally {
            astNodePos.pop()
        }
    }

    private fun transform(node: AstNode): Node {
        when (node.type) {
            Token.ARRAYCOMP -> return transformArrayComp(node as ArrayComprehension)
            Token.ARRAYLIT -> return transformArrayLiteral(node as ArrayLiteral)
            Token.BIGINT -> return transformBigInt(node as BigIntLiteral)
            Token.BLOCK -> return transformBlock(node)
            Token.BREAK -> return transformBreak(node as BreakStatement)
            Token.CALL -> return transformFunctionCall(node as FunctionCall)
            Token.CONTINUE -> return transformContinue(node as ContinueStatement)
            Token.DO -> return transformDoLoop(node as DoLoop)
            Token.EMPTY, Token.COMMENT -> return node
            Token.FOR -> {
                if (node is ForInLoop) {
                    return transformForInLoop(node)
                }
                return transformForLoop(node as ForLoop)
            }
            Token.FUNCTION -> return transformFunction(node as FunctionNode)
            Token.GENEXPR -> return transformGenExpr(node as GeneratorExpression)
            Token.GETELEM -> return transformElementGet(node as ElementGet)
            Token.GETPROP -> return transformPropertyGet(node as PropertyGet)
            Token.QUESTION_DOT -> {
                return if (node is ElementGet) {
                    transformElementGet(node)
                } else {
                    transformPropertyGet(node as PropertyGet)
                }
            }
            Token.HOOK -> return transformCondExpr(node as ConditionalExpression)
            Token.IF -> return transformIf(node as IfStatement)

            Token.TRUE,
            Token.FALSE,
            Token.THIS,
            Token.NULL,
            Token.UNDEFINED,
            Token.DEBUGGER,
            -> return transformLiteral(node)

            Token.SUPER -> {
                parser.setRequiresActivation()
                return transformLiteral(node)
            }
            Token.NAME -> return transformName(node as Name)
            Token.NUMBER -> return transformNumber(node as NumberLiteral)
            Token.NEW -> return transformNewExpr(node as NewExpression)
            Token.OBJECTLIT -> return transformObjectLiteral(node as ObjectLiteral)
            Token.TEMPLATE_LITERAL -> return transformTemplateLiteral(node as TemplateLiteral)
            Token.TAGGED_TEMPLATE_LITERAL ->
                return transformTemplateLiteralCall(node as TaggedTemplateLiteral)
            Token.REGEXP -> return transformRegExp(node as RegExpLiteral)
            Token.RETURN -> return transformReturn(node as ReturnStatement)
            Token.SCRIPT -> return transformScript(node as ScriptNode)
            Token.STRING -> return transformString(node as StringLiteral)
            Token.SWITCH -> return transformSwitch(node as SwitchStatement)
            Token.THROW -> return transformThrow(node as ThrowStatement)
            Token.TRY -> return transformTry(node as TryStatement)
            Token.WHILE -> return transformWhileLoop(node as WhileLoop)
            Token.WITH -> return transformWith(node as WithStatement)
            Token.YIELD, Token.YIELD_STAR -> return transformYield(node as Yield)
        }

        return when (node) {
            is ExpressionStatement -> transformExprStmt(node)
            is Assignment -> transformAssignment(node)
            is UnaryExpression -> transformUnary(node)
            is UpdateExpression -> transformUpdate(node)
            is InfixExpression -> transformInfix(node)
            is VariableDeclaration -> transformVariables(node)
            is ParenthesizedExpression -> transformParenExpr(node)
            is ComputedPropertyKey -> transformComputedPropertyKey(node)
            is LabeledStatement -> transformLabeledStatement(node)
            is LetNode -> transformLetNode(node)
            is GeneratorMethodDefinition -> transformGeneratorMethodDefinition(node)
            is Spread -> transformSpread(node)
            else -> throw IllegalArgumentException("Can't transform: $node")
        }
    }

    private fun transformArrayComp(node: ArrayComprehension): Node {
        /*
         * An array comprehension such as
         *     [expr for (x in foo) for each ([y, z] in bar) if (cond)]
         * becomes roughly
         *     new Scope(ARRAYCOMP) {
         *       new Node(BLOCK) {
         *         let tmp1 = new Array;
         *         for (let x in foo) {
         *           for each (let tmp2 in bar) {
         *             if (cond) { tmp1.push([y, z] = tmp2, expr); }
         *           }
         *         }
         *       }
         *       createName(tmp1)
         *     }
         */
        val lineno = node.lineno
        val column = node.column
        val scopeNode = parser.createScopeNode(Token.ARRAYCOMP, lineno, column)
        val arrayName = parser.currentScriptOrFn!!.getNextTempName()
        parser.pushScope(scopeNode)
        try {
            astNodePos.push(node)
            try {
                parser.defineSymbol(Token.LET, arrayName, false)
                val block = Node(Token.BLOCK)
                block.setLineColumnNumber(lineno, column)
                val newArray = createCallOrNew(Token.NEW, parser.createName("Array"))
                val init = Node(
                    Token.EXPR_VOID,
                    createAssignment(Token.ASSIGN, parser.createName(arrayName), newArray),
                    lineno,
                    column,
                )
                block.addChildToBack(init)
                block.addChildToBack(arrayCompTransformHelper(node, arrayName))
                scopeNode.addChildToBack(block)
                scopeNode.addChildToBack(parser.createName(arrayName))
                return scopeNode
            } finally {
                astNodePos.pop()
            }
        } finally {
            parser.popScope()
        }
    }

    private fun arrayCompTransformHelper(node: ArrayComprehension, arrayName: String): Node {
        val lineno = node.lineno
        val column = node.column
        var expr = transform(node.result!!)

        val loops = node.loops
        val numLoops = loops.size

        // Walk the loops, collecting and defining their iterator symbols.
        val iterators = arrayOfNulls<Node>(numLoops)
        val iteratedObjs = arrayOfNulls<Node>(numLoops)

        for (i in 0 until numLoops) {
            val acl = loops[i]
            val iter = acl.iterator!!
            astNodePos.push(iter)
            try {
                val name: String
                if (iter.type == Token.NAME) {
                    name = iter.string!!
                } else {
                    // Destructuring assignment.
                    name = parser.currentScriptOrFn!!.getNextTempName()
                    parser.defineSymbol(Token.LP, name, false)
                    expr = createBinary(
                        Token.COMMA,
                        createAssignment(Token.ASSIGN, iter, parser.createName(name)),
                        expr,
                    )
                }
                val init = parser.createName(name)
                // Define it as a let, so the variable stays scoped to the comprehension.
                parser.defineSymbol(Token.LET, name, false)
                iterators[i] = init
            } finally {
                astNodePos.pop()
            }

            iteratedObjs[i] = transform(acl.iteratedObject!!)
        }

        // Generate tmpArray.push(body).
        val call = createCallOrNew(
            Token.CALL,
            createPropertyGet(parser.createName(arrayName), null, "push", 0, node.type),
        )

        var body: Node = Node(Token.EXPR_VOID, call)
        body.setLineColumnNumber(lineno, column)

        node.filter?.let {
            body = createIf(transform(it), body, null, lineno, column)
        }

        // Walk the loops in reverse to build up the body statement.
        var pushed = 0
        try {
            for (i in numLoops - 1 downTo 0) {
                val acl = loops[i]
                val loop = createLoopNode(null, acl.lineno, acl.column) // no label
                parser.pushScope(loop)
                pushed++
                body = createForIn(
                    Token.LET,
                    loop,
                    iterators[i]!!,
                    iteratedObjs[i]!!,
                    body,
                    acl,
                    acl.isForEach,
                    acl.isForOf,
                )
            }
        } finally {
            for (i in 0 until pushed) {
                parser.popScope()
            }
        }

        // The destructuring forms are accumulated now, so the expression can join the call node.
        // It is pushed on every iteration.
        call.addChildToBack(expr)
        return body
    }

    private fun transformArrayLiteral(node: ArrayLiteral): Node {
        if (node.isDestructuring) {
            return node
        }
        val elems = node.elements
        val array = Node(Token.ARRAYLIT)
        var skipIndexes: MutableList<Int>? = null
        for (i in elems.indices) {
            val elem = elems[i]
            if (elem.type == Token.DOTDOTDOT) {
                val transformedSpreadNode = transform(elem as Spread)
                array.addChildToBack(transformedSpreadNode)
                array.putIntProp(
                    Node.NUMBER_OF_SPREAD,
                    array.getIntProp(Node.NUMBER_OF_SPREAD, 0) + 1,
                )
            } else if (elem.type != Token.EMPTY) {
                array.addChildToBack(transform(elem))
            } else {
                val skips = skipIndexes ?: mutableListOf<Int>().also { skipIndexes = it }
                skips.add(i)
            }
        }
        array.putIntProp(Node.DESTRUCTURING_ARRAY_LENGTH, node.destructuringLength)
        skipIndexes?.let { array.putProp(Node.SKIP_INDEXES_PROP, it.toIntArray()) }
        return array
    }

    private fun transformAssignment(node: Assignment): Node {
        val right = node.right!!
        val originalLeft = node.left!!
        var left = parser.removeParens(originalLeft)
        // Removing parens means the name is not inferred.
        var shouldTryToInferName = originalLeft === left
        left = transformAssignmentLeft(node, left, right)

        val target: Node
        if (isDestructuring(left)) {
            target = left
            shouldTryToInferName = false
        } else {
            target = transform(left)
        }

        astNodePos.push(left)
        try {
            val transformedRight = transform(right)
            if (shouldTryToInferName) {
                inferNameIfMissing(node.left!!, transformedRight, null)
            }
            return createAssignment(node.type, target, transformedRight)
        } finally {
            astNodePos.pop()
        }
    }

    private fun transformAssignmentLeft(
        node: Assignment,
        left: AstNode,
        right: AstNode,
    ): AstNode {
        if (right.type == Token.NULL &&
            node.type == Token.ASSIGN &&
            left is Name &&
            right is KeywordLiteral
        ) {
            val identifier = left.identifier
            var p = node.parent
            while (p != null) {
                if (p is FunctionNode) {
                    val functionName = p.functionName
                    if (functionName != null && functionName.identifier == identifier) {
                        val propertyGet = PropertyGet()
                        val thisKeyword = KeywordLiteral()
                        thisKeyword.type = Token.THIS
                        propertyGet.left = thisKeyword
                        propertyGet.right = left
                        node.left = propertyGet
                        return propertyGet
                    }
                }
                p = p.parent
            }
        }
        return left
    }

    private fun transformBigInt(node: BigIntLiteral): Node = node

    private fun transformBlock(node: AstNode): Node {
        if (node is Scope) {
            parser.pushScope(node)
        }
        try {
            val kids = mutableListOf<Node>()
            // Function declarations inside blocks are hoisted to the top of the block, so the
            // statements above them can still refer to them.
            val functions = mutableListOf<Node>()

            for (kid in node) {
                if (kid is FunctionNode &&
                    kid.functionType == FunctionNode.FUNCTION_EXPRESSION_STATEMENT
                ) {
                    functions.add(transform(kid))
                } else {
                    kids.add(transform(kid as AstNode))
                }
            }
            node.removeChildren()

            for (function in functions) {
                node.addChildToBack(function)
            }
            for (kid in kids) {
                node.addChildToBack(kid)
            }
            return node
        } finally {
            if (node is Scope) {
                parser.popScope()
            }
        }
    }

    private fun transformBreak(node: BreakStatement): Node = node

    private fun transformCondExpr(node: ConditionalExpression): Node {
        val test = transform(node.testExpression!!)
        val ifTrue = transform(node.trueExpression!!)
        val ifFalse = transform(node.falseExpression!!)
        return createCondExpr(test, ifTrue, ifFalse)
    }

    private fun transformContinue(node: ContinueStatement): Node = node

    private fun transformDoLoop(loop: DoLoop): Node {
        loop.type = Token.LOOP
        parser.pushScope(loop)
        try {
            val body = transform(loop.body!!)
            val cond = transform(loop.condition!!)
            return createLoop(loop, LOOP_DO_WHILE, body, cond, null, null)
        } finally {
            parser.popScope()
        }
    }

    private fun transformElementGet(node: ElementGet): Node {
        // Could be optimized into createPropertyGet when the element is a string that cannot be
        // a number.
        val target = transform(node.target!!)
        val element = transform(node.element!!)
        val getElem = Node(Token.GETELEM, target, element)
        if (node.type == Token.QUESTION_DOT) {
            getElem.putIntProp(Node.OPTIONAL_CHAINING, 1)
        }
        if (target.type == Token.SUPER) {
            getElem.putIntProp(Node.SUPER_PROPERTY_ACCESS, 1)
        }
        return getElem
    }

    private fun transformExprStmt(node: ExpressionStatement): Node {
        val expr = transform(node.expression!!)
        return Node(node.type, expr, node.lineno, node.column)
    }

    private fun transformForInLoop(loop: ForInLoop): Node {
        loop.type = Token.LOOP
        parser.pushScope(loop)
        try {
            var declType = -1
            val iter = loop.iterator!!
            if (iter is VariableDeclaration) {
                declType = iter.type
            }
            val lhs = transform(iter)
            val obj = transform(loop.iteratedObject!!)
            val body = transform(loop.body!!)
            return createForIn(declType, loop, lhs, obj, body, loop, loop.isForEach, loop.isForOf)
        } finally {
            parser.popScope()
        }
    }

    private fun transformForLoop(loop: ForLoop): Node {
        loop.type = Token.LOOP
        // pushScope and popScope cannot be used here, because createFor may split the scope.
        val savedScope = parser.currentScope
        parser.currentScope = loop
        try {
            val init = transform(loop.initializer!!)
            val test = transform(loop.condition!!)
            val incr = transform(loop.increment!!)
            val body = transform(loop.body!!)
            return createFor(loop, init, test, incr, body)
        } finally {
            parser.currentScope = savedScope
        }
    }

    private fun transformFunction(fn: FunctionNode): Node {
        val mexpr = decompileFunctionHeader(fn)
        val index = parser.currentScriptOrFn!!.addFunction(fn)

        val savedStrict = outerScopeIsStrict
        outerScopeIsStrict = outerScopeIsStrict || fn.isInStrictMode
        val savedVars = parser.createPerFunctionVariables(fn)
        try {
            val destructuring = fn.getProp(Node.DESTRUCTURING_PARAMS) as Node?
            fn.removeProp(Node.DESTRUCTURING_PARAMS)

            val lineno = fn.body!!.lineno
            val column = fn.body!!.column
            ++parser.nestingOfFunction // only the body, not the params
            val body = transform(fn.body!!)

            // Process the simple default parameters.
            val defaultParams = fn.defaultParams
            if (defaultParams != null) {
                var paramInitBlock: Node? = null
                var i = defaultParams.size - 1
                while (i > 0) {
                    val entry = defaultParams[i]
                    val nameEntry = defaultParams[i - 1]
                    if (entry is AstNode && nameEntry is String) {
                        val paramInit = createIf(
                            createBinary(
                                Token.SHEQ,
                                parser.createName(nameEntry),
                                KeywordLiteral().apply { type = Token.UNDEFINED },
                            ),
                            Node(
                                Token.EXPR_VOID,
                                createAssignment(
                                    Token.ASSIGN,
                                    parser.createName(nameEntry),
                                    transform(entry),
                                ),
                                body.lineno,
                                body.column,
                            ),
                            null,
                            body.lineno,
                            body.column,
                        )
                        if (fn.isGenerator) {
                            val block = paramInitBlock ?: Node(Token.BLOCK).also {
                                paramInitBlock = it
                            }
                            block.addChildToFront(paramInit)
                        } else {
                            body.addChildToFront(paramInit)
                        }
                    }
                    i -= 2
                }
                if (fn.isGenerator && paramInitBlock != null) {
                    fn.generatorParamInitBlock = paramInitBlock
                }
            }

            // Transform the nodes used as default parameters.
            fn.destructuringRvalues?.forEach { pair ->
                val a = pair[0]
                val b = pair[1]
                if (b is AstNode) {
                    a.replaceChild(b, transform(b))
                }
            }

            if (destructuring != null) {
                body.addChildToFront(Node(Token.EXPR_VOID, destructuring, lineno, column))
            }

            val syntheticType = fn.functionType
            var pn = initFunction(fn, index, body, syntheticType)
            if (mexpr != null) {
                astNodePos.push(fn)
                try {
                    pn = createAssignment(Token.ASSIGN, mexpr, pn)
                } finally {
                    astNodePos.pop()
                }
                if (syntheticType != FunctionNode.FUNCTION_EXPRESSION) {
                    pn = createExprStatementNoReturn(pn, fn.lineno, fn.column)
                }
            }
            return pn
        } finally {
            --parser.nestingOfFunction
            savedVars.restore()
            outerScopeIsStrict = savedStrict
        }
    }

    private fun transformFunctionCall(node: FunctionCall): Node {
        astNodePos.push(node)
        try {
            val transformedTarget = transform(node.target!!)
            val call = createCallOrNew(Token.CALL, transformedTarget)
            call.setLineColumnNumber(node.lineno, node.column)
            for (arg in node.arguments) {
                call.addChildToBack(transform(arg))
            }
            if (node.isOptionalCall) {
                call.putIntProp(Node.OPTIONAL_CHAINING, 1)
            }
            if (transformedTarget.getIntProp(Node.SUPER_PROPERTY_ACCESS, 0) == 1) {
                call.putIntProp(Node.SUPER_PROPERTY_ACCESS, 1)
            }
            return call
        } finally {
            astNodePos.pop()
        }
    }

    private fun transformGenExpr(node: GeneratorExpression): Node {
        var pn: Node

        val fn = FunctionNode()
        fn.sourceName = parser.currentScriptOrFn!!.getNextTempName()
        fn.isGenerator = true
        fn.functionType = FunctionNode.FUNCTION_EXPRESSION
        fn.requiresActivation = true

        val mexpr = decompileFunctionHeader(fn)
        val index = parser.currentScriptOrFn!!.addFunction(fn)

        val savedVars = parser.createPerFunctionVariables(fn)
        try {
            val destructuring = fn.getProp(Node.DESTRUCTURING_PARAMS) as Node?
            fn.removeProp(Node.DESTRUCTURING_PARAMS)

            val lineno = node.lineno
            val column = node.column
            ++parser.nestingOfFunction // only the body, not the params
            val body = genExprTransformHelper(node)

            if (destructuring != null) {
                body.addChildToFront(Node(Token.EXPR_VOID, destructuring, lineno, column))
            }

            val syntheticType = fn.functionType
            pn = initFunction(fn, index, body, syntheticType)
            if (mexpr != null) {
                astNodePos.push(fn)
                try {
                    pn = createAssignment(Token.ASSIGN, mexpr, pn)
                } finally {
                    astNodePos.pop()
                }
                if (syntheticType != FunctionNode.FUNCTION_EXPRESSION) {
                    pn = createExprStatementNoReturn(pn, fn.lineno, fn.column)
                }
            }
        } finally {
            --parser.nestingOfFunction
            savedVars.restore()
        }

        val call = createCallOrNew(Token.CALL, pn)
        call.setLineColumnNumber(node.lineno, node.column)
        return call
    }

    private fun genExprTransformHelper(node: GeneratorExpression): Node {
        val lineno = node.lineno
        val column = node.column
        var expr = transform(node.result!!)

        val loops = node.loops
        val numLoops = loops.size

        // Walk the loops, collecting and defining their iterator symbols.
        val iterators = arrayOfNulls<Node>(numLoops)
        val iteratedObjs = arrayOfNulls<Node>(numLoops)

        for (i in 0 until numLoops) {
            val acl = loops[i]

            val iter = acl.iterator!!
            astNodePos.push(iter)
            try {
                val name: String
                if (iter.type == Token.NAME) {
                    name = iter.string!!
                } else {
                    // Destructuring assignment.
                    name = parser.currentScriptOrFn!!.getNextTempName()
                    parser.defineSymbol(Token.LP, name, false)
                    expr = createBinary(
                        Token.COMMA,
                        createAssignment(Token.ASSIGN, iter, parser.createName(name)),
                        expr,
                    )
                }
                val init = parser.createName(name)
                // Define it as a let, so the variable stays scoped to the comprehension.
                parser.defineSymbol(Token.LET, name, false)
                iterators[i] = init
            } finally {
                astNodePos.pop()
            }

            iteratedObjs[i] = transform(acl.iteratedObject!!)
        }

        val yield = Node(Token.YIELD, expr, node.lineno, node.column)

        var body: Node = Node(Token.EXPR_VOID, yield, lineno, column)

        node.filter?.let {
            body = createIf(transform(it), body, null, lineno, column)
        }

        // Walk the loops in reverse to build up the body statement.
        var pushed = 0
        try {
            for (i in numLoops - 1 downTo 0) {
                val acl = loops[i]
                val loop = createLoopNode(null, acl.lineno, acl.column) // no label
                parser.pushScope(loop)
                pushed++
                body = createForIn(
                    Token.LET,
                    loop,
                    iterators[i]!!,
                    iteratedObjs[i]!!,
                    body,
                    acl,
                    acl.isForEach,
                    acl.isForOf,
                )
            }
        } finally {
            for (i in 0 until pushed) {
                parser.popScope()
            }
        }

        return body
    }

    private fun transformIf(n: IfStatement): Node {
        val cond = transform(n.condition!!)
        val ifTrue = transform(n.thenPart!!)
        var ifFalse: Node? = null
        n.elsePart?.let { ifFalse = transform(it) }
        return createIf(cond, ifTrue, ifFalse, n.lineno, n.column)
    }

    private fun transformInfix(node: InfixExpression): Node {
        val left = transform(node.left!!)
        val right = transform(node.right!!)
        val binaryNode = createBinary(node.type, left, right)

        // The node type changes from InfixExpression to Node, so the line and column have to be
        // copied over, but only onto a newly made node. createBinary can fold and hand back one of
        // its operands, as in `true && other`, which already carries the right position.
        val nodeCreated = (binaryNode !== left) && (binaryNode !== right)
        if (nodeCreated) {
            binaryNode.setLineColumnNumber(node.lineno, node.column)
        }

        return binaryNode
    }

    private fun transformLabeledStatement(ls: LabeledStatement): Node {
        val label = ls.firstLabel
        val statement = transform(ls.statement!!)

        // Put a target after the statement node and add the LABEL node, so breaks find the right
        // target.
        val breakTarget = Node.newTarget()
        val block = Node(Token.BLOCK, label, statement, breakTarget)
        label.target = breakTarget

        return block
    }

    private fun transformLetNode(node: LetNode): Node {
        parser.pushScope(node)
        try {
            val vars = transformVariableInitializers(node.variables!!)
            node.addChildToBack(vars)
            node.body?.let { node.addChildToBack(transform(it)) }
            return node
        } finally {
            parser.popScope()
        }
    }

    private fun transformLiteral(node: AstNode): Node {
        // Trying to call super as a function. See 15.4.2 Static Semantics: HasDirectSuper. This
        // has to change when classes land, because calling super() in a class constructor is
        // allowed.
        if (node.parent is FunctionCall && node.type == Token.SUPER) {
            parser.reportError("msg.super.shorthand.function")
        }
        return node
    }

    private fun transformName(node: Name): Node = node

    private fun transformNewExpr(node: NewExpression): Node {
        val nx = createCallOrNew(Token.NEW, transform(node.target!!))
        nx.setLineColumnNumber(node.lineno, node.column)
        for (arg in node.arguments) {
            nx.addChildToBack(transform(arg))
        }
        node.initializer?.let { nx.addChildToBack(transformObjectLiteral(it)) }
        return nx
    }

    private fun transformNumber(node: NumberLiteral): Node = node

    private fun transformObjectLiteral(node: ObjectLiteral): Node {
        if (node.isDestructuring) {
            return node
        }
        // The literal is rewritten into object creation plus property entries, so the later
        // compiler stages never see an object literal.
        val elems = node.elements
        val object_ = Node(Token.OBJECTLIT)
        object_.setLineColumnNumber(node.lineno, node.column)
        val properties: Array<Any?>
        if (elems.isEmpty()) {
            properties = ScriptRuntime.emptyArgs
        } else {
            properties = arrayOfNulls(elems.size)
            var i = 0
            for (abstractProp in elems) {
                if (abstractProp is SpreadObjectProperty) {
                    val transformedSpreadNode = transform(abstractProp.spreadNode)
                    properties[i++] = transformedSpreadNode
                    object_.putIntProp(
                        Node.NUMBER_OF_SPREAD,
                        object_.getIntProp(Node.NUMBER_OF_SPREAD, 0) + 1,
                    )
                    object_.addChildToBack(transformedSpreadNode)
                } else {
                    val prop = abstractProp as ObjectProperty
                    val propKey = Parser.getPropKey(prop.key)
                    var inferrableName: Node? = null
                    if (propKey == null) {
                        properties[i++] = transform(prop.key!!)
                    } else {
                        properties[i++] = propKey
                        inferrableName = parser.createName(propKey.toString())
                        inferrableName.setLineColumnNumber(prop.key!!.lineno, prop.key!!.column)
                    }

                    var right = transform(prop.value!!)
                    if (inferrableName != null) {
                        inferNameIfMissing(
                            inferrableName,
                            right,
                            if (prop.isGetterMethod) {
                                "get "
                            } else if (prop.isSetterMethod) {
                                "set "
                            } else {
                                null
                            },
                        )
                    }

                    if (prop.isGetterMethod) {
                        right = createUnary(Token.GET, right)
                    } else if (prop.isSetterMethod) {
                        right = createUnary(Token.SET, right)
                    } else if (prop.isNormalMethod) {
                        right = createUnary(Token.METHOD, right)
                    }
                    object_.addChildToBack(right)
                }
            }
        }
        object_.putProp(Node.OBJECT_IDS_PROP, properties)
        return object_
    }

    private fun transformParenExpr(node: ParenthesizedExpression): Node {
        var expr: AstNode = node.expression!!
        while (expr is ParenthesizedExpression) {
            expr = expr.expression!!
        }
        val result = transform(expr)
        result.putProp(Node.PARENTHESIZED_PROP, true)
        return result
    }

    private fun transformComputedPropertyKey(node: ComputedPropertyKey): Node {
        val transformedExpression = transform(node.expression!!)
        return Node(node.type, transformedExpression)
    }

    private fun transformPropertyGet(node: PropertyGet): Node {
        val target = transform(node.target!!)
        val name = node.property!!.identifier!!
        return createPropertyGet(target, null, name, 0, node.type)
    }

    private fun transformTemplateLiteral(node: TemplateLiteral): Node {
        val elems = node.elements
        // Start with an empty string, which forces ToString on every substitution.
        var pn = Node.newString("")
        for (elem in elems) {
            if (elem.type != Token.TEMPLATE_CHARS) {
                pn = createBinary(Token.STRING_CONCAT, pn, transform(elem))
            } else {
                // Skip the empty parts, as in `xx${expr}xx` where xx is the empty string.
                val value = (elem as TemplateCharacters).value!!
                if (value.isNotEmpty()) {
                    pn = createBinary(Token.STRING_CONCAT, pn, Node.newString(value))
                }
            }
        }
        return pn
    }

    private fun transformTemplateLiteralCall(node: TaggedTemplateLiteral): Node {
        val transformedTarget = transform(node.target!!)
        val call = createCallOrNew(Token.CALL, transformedTarget)
        call.setLineColumnNumber(node.lineno, node.column)
        if (transformedTarget.getIntProp(Node.SUPER_PROPERTY_ACCESS, 0) == 1) {
            call.putIntProp(Node.SUPER_PROPERTY_ACCESS, 1)
        }
        val templateLiteral = node.templateLiteral as TemplateLiteral
        val elems = templateLiteral.elements
        call.addChildToBack(templateLiteral)
        for (elem in elems) {
            if (elem.type != Token.TEMPLATE_CHARS) {
                call.addChildToBack(transform(elem))
            }
        }
        parser.currentScriptOrFn!!.addTemplateLiteral(templateLiteral)
        return call
    }

    private fun transformRegExp(node: RegExpLiteral): Node {
        parser.currentScriptOrFn!!.addRegExp(node)
        return node
    }

    private fun transformReturn(node: ReturnStatement): Node {
        val rv = node.returnValue
        val value = if (rv == null) null else transform(rv)
        return if (rv == null) {
            Node(Token.RETURN, node.lineno, node.column)
        } else {
            Node(Token.RETURN, value!!, node.lineno, node.column)
        }
    }

    private fun transformScript(node: ScriptNode): Node {
        if (parser.currentScope != null) Kit.codeBug()
        parser.currentScope = node
        outerScopeIsStrict = node.isInStrictMode
        val body = Node(Token.BLOCK)
        for (kid in node) {
            body.addChildToBack(transform(kid as AstNode))
        }
        node.removeChildren()

        body.firstChild?.let { node.addChildrenToBack(it) }
        return node
    }

    private fun transformString(node: StringLiteral): Node {
        val stringNode = Node.newString(node.value!!)
        stringNode.setLineColumnNumber(node.lineno, node.column)
        return stringNode
    }

    private fun transformSwitch(node: SwitchStatement): Node {
        /*
         * The switch is rewritten from
         *     switch (expr) { case test1: statements1; ... default: statementsDefault; ...
         *                     case testN: statementsN; }
         * to
         *     { switch (expr) { case test1: goto label1; ... case testN: goto labelN; }
         *       goto labelDefault;
         *     label1: statements1; ... labelDefault: statementsDefault; ...
         *     labelN: statementsN; breakLabel: }
         * where every unlabeled "break;" inside the switch becomes "goto breakLabel".
         *
         * A switch with no default gets "goto breakLabel" in place of "goto labelDefault".
         */
        val block = Scope.splitScope(node)
        block.setLineColumnNumber(node.lineno, node.column)
        block.addChildToBack(node)
        node.parentScope = block

        // pushScope and popScope cannot be used here, since splitScope moved the symbol table.
        // currentScope becomes 'node', not 'block', so nested scopes can still be pushed: their
        // parent pointers were set to 'node' during parsing. Variable lookup still works, because
        // it walks the parentScope chain and node.parentScope is block.
        val savedScope = parser.currentScope
        parser.currentScope = node
        try {
            val switchExpr = transform(node.expression!!)
            node.addChildToBack(switchExpr)

            for (sc in node.cases) {
                val expr = sc.expression
                var caseExpr: Node? = null

                if (expr != null) {
                    caseExpr = transform(expr)
                }

                val stmts = sc.statements
                val body: Node = Block()
                stmts?.forEach { body.addChildToBack(transform(it)) }
                addSwitchCase(block, caseExpr, body)
            }
            closeSwitch(block)
            return block
        } finally {
            parser.currentScope = savedScope
        }
    }

    private fun transformThrow(node: ThrowStatement): Node {
        val value = transform(node.expression!!)
        value.setLineColumnNumber(node.lineno, node.column)
        val nx = Node(Token.THROW, value)
        nx.setLineColumnNumber(node.lineno, node.column)
        return nx
    }

    private fun transformTry(node: TryStatement): Node {
        val tryBlock = transform(node.tryBlock!!)

        val catchBlocks: Node = Block()
        for (cc in node.catchClauses) {
            val varName = cc.varName
            var catchCond: Node? = null
            var varNameNode: Node? = null
            val catchBody = cc.body!!

            if (varName != null) {
                if (varName is Name) {
                    // Simple identifier.
                    varNameNode = parser.createName(varName.identifier!!)

                    val ccc = cc.catchCondition
                    catchCond = if (ccc != null) transform(ccc) else EmptyExpression()
                } else if (varName is ArrayLiteral || varName is ObjectLiteral) {
                    /*
                     * Destructuring pattern. This rewrites
                     *     catch ( {message} ) { body }
                     * into
                     *     catch ( $tempname ) { let {message} = $tempname; body }
                     */
                    val tempVarName = parser.currentScriptOrFn!!.getNextTempName()
                    varNameNode = parser.createName(tempVarName)

                    val letStatement = VariableDeclaration()
                    letStatement.type = Token.LET

                    val letVar = VariableInitializer()
                    letStatement.addVariable(letVar)

                    // Left side: the destructuring declaration.
                    letVar.target = varName

                    // Right side: the temp name, wrapped in a fresh name node.
                    val tempVarNameNode = Name()
                    tempVarNameNode.identifier = tempVarName
                    letVar.initializer = tempVarNameNode

                    catchBody.addChildToFront(letStatement)

                    // The non-standard catch condition is rejected at parse time for a
                    // destructuring binding, so it can be forced empty here.
                    catchCond = EmptyExpression()
                } else {
                    throw IllegalArgumentException(
                        "Unexpected catch parameter type: ${varName::class.simpleName}",
                    )
                }
            }

            val body = transform(catchBody)

            catchBlocks.addChildToBack(
                createCatch(varNameNode, catchCond, body, cc.lineno, cc.column),
            )
        }
        var finallyBlock: Node? = null
        node.finallyBlock?.let { finallyBlock = transform(it) }
        return createTryCatchFinally(
            tryBlock,
            catchBlocks,
            finallyBlock,
            node.lineno,
            node.column,
        )
    }

    private fun transformUnary(node: UnaryExpression): Node {
        val type = node.type
        if (type == Token.DEFAULTNAMESPACE) {
            // KMP: E4X is out of scope (D-16).
            parser.reportError("msg.XML.not.available")
            return node
        }

        val child = transform(node.operand!!)
        return createUnary(type, child)
    }

    private fun transformUpdate(node: UpdateExpression): Node {
        val type = node.type
        val child = transform(node.operand!!)
        return createIncDec(type, node.isPostfix, child)
    }

    private fun transformVariables(node: VariableDeclaration): Node {
        transformVariableInitializers(node)
        return node
    }

    private fun transformVariableInitializers(node: VariableDeclaration): Node {
        for (v in node.variables) {
            val target = v.target!!
            val init = v.initializer

            val left: Node = if (v.isDestructuring) target else transform(target)

            var right: Node? = null
            if (init != null) {
                right = transform(init)
            }

            if (v.isDestructuring) {
                if (right == null) {
                    node.addChildToBack(left)
                } else {
                    astNodePos.push(v)
                    try {
                        val d = parser.createDestructuringAssignment(
                            node.type,
                            left,
                            right,
                            Parser.Transformer { transform(it) },
                        )
                        node.addChildToBack(d)
                    } finally {
                        astNodePos.pop()
                    }
                }
            } else {
                inferNameIfMissing(left, right, null)
                if (right != null) {
                    left.addChildToBack(right)
                }
                node.addChildToBack(left)
            }
        }
        return node
    }

    private fun transformWhileLoop(loop: WhileLoop): Node {
        loop.type = Token.LOOP
        parser.pushScope(loop)
        try {
            val cond = transform(loop.condition!!)
            val body = transform(loop.body!!)
            return createLoop(loop, LOOP_WHILE, body, cond, null, null)
        } finally {
            parser.popScope()
        }
    }

    private fun transformWith(node: WithStatement): Node {
        val expr = transform(node.expression!!)
        val stmt = transform(node.statement!!)
        return createWith(expr, stmt, node.lineno, node.column)
    }

    private fun transformYield(node: Yield): Node {
        val kid = node.value?.let { transform(it) }
        if (kid != null) return Node(node.type, kid, node.lineno, node.column)
        return Node(node.type, node.lineno, node.column)
    }

    private fun transformSpread(node: Spread): Node {
        val kid = transform(node.expression!!)
        return Node(node.type, kid, node.lineno, node.column)
    }

    private fun transformGeneratorMethodDefinition(node: GeneratorMethodDefinition): Node =
        // Unwrap the temporary AST node.
        transform(node.methodName!!)

    private fun createCatch(
        varName: Node?,
        catchCond: Node?,
        stmts: Node,
        lineno: Int,
        column: Int,
    ): Node {
        val name = varName ?: Node(Token.EMPTY)
        val cond = catchCond ?: Node(Token.EMPTY)
        return Node(Token.CATCH, name, cond, stmts, lineno, column)
    }

    private fun initFunction(
        fnNode: FunctionNode,
        functionIndex: Int,
        statements: Node,
        functionType: Int,
    ): Node {
        fnNode.functionType = functionType
        fnNode.addChildToBack(statements)

        if (outerScopeIsStrict && !fnNode.isInStrictMode) {
            fnNode.isInStrictMode = true
        }

        if (fnNode.functionCount != 0) {
            // A function holding other functions needs an activation object.
            fnNode.requiresActivation = true
            propagateRequiresArgumentObjectFromNestedArrowFunctions(fnNode)
        }

        if (functionType == FunctionNode.FUNCTION_EXPRESSION) {
            val name = fnNode.functionName
            if (name != null && name.length() != 0 && fnNode.getSymbol(name.identifier!!) == null) {
                // A function expression needs its own name as a variable, unless one is already
                // allocated. See ECMA chapter 13. Code goes at the front of the function to bind a
                // local of the function's name to the function value, but only when the function
                // does not already declare a parameter, var or nested function of that name.
                fnNode.putSymbol(Symbol(Token.FUNCTION, name.identifier))
                val setFn = Node(
                    Token.EXPR_VOID,
                    Node(
                        Token.SETNAME,
                        Node.newString(Token.BINDNAME, name.identifier!!),
                        Node(Token.THISFN),
                    ),
                )
                statements.addChildrenToFront(setFn)
            }
        }

        // Add a return at the end when one is missing.
        val lastStmt = statements.lastChild
        if (lastStmt == null || lastStmt.type != Token.RETURN) {
            statements.addChildToBack(Node(Token.RETURN))
        }

        val result = Node.newString(Token.FUNCTION, fnNode.name)
        result.putIntProp(Node.FUNCTION_PROP, functionIndex)
        return result
    }

    /**
     * Creates a loop node. The code generator later calls createWhile, createDoWhile, createFor or
     * createForIn to finish the loop.
     */
    private fun createLoopNode(loopLabel: Node?, lineno: Int, column: Int): Scope {
        val result = parser.createScopeNode(Token.LOOP, lineno, column)
        if (loopLabel != null) {
            (loopLabel as Jump).loop = result
        }
        return result
    }

    /** Generates IR for a for..in loop. */
    private fun createForIn(
        declType: Int,
        loop: Node,
        lhs: Node,
        obj: Node,
        body: Node,
        ast: AstNode,
        isForEach: Boolean,
        isForOf: Boolean,
    ): Node {
        astNodePos.push(ast)
        try {
            var destructuring = -1
            var destructuringLen = 0
            val lvalue: Node
            var type = lhs.type
            if (type == Token.VAR || type == Token.LET) {
                val kid = lhs.lastChild!!
                val kidType = kid.type
                if (kidType == Token.ARRAYLIT || kidType == Token.OBJECTLIT) {
                    type = kidType
                    destructuring = kidType
                    lvalue = kid
                    destructuringLen = if (kid is ArrayLiteral) kid.destructuringLength else 0
                } else if (kidType == Token.NAME) {
                    lvalue = Node.newString(Token.NAME, kid.string!!)
                } else {
                    parser.reportError("msg.bad.for.in.lhs")
                    // KMP: upstream returns null here and the caller dereferences it. Unwinding
                    // instead abandons the subtree cleanly in IDE mode (D-19).
                    throw Parser.ParserException()
                }
            } else if (type == Token.ARRAYLIT || type == Token.OBJECTLIT) {
                destructuring = type
                lvalue = lhs
                destructuringLen = if (lhs is ArrayLiteral) lhs.destructuringLength else 0
            } else {
                lvalue = makeReference(lhs) ?: run {
                    parser.reportError("msg.bad.for.in.lhs")
                    throw Parser.ParserException()
                }
            }

            val localBlock = Node(Token.LOCAL_BLOCK)
            val initType = when {
                isForEach -> Token.ENUM_INIT_VALUES
                isForOf -> Token.ENUM_INIT_VALUES_IN_ORDER
                destructuring != -1 -> Token.ENUM_INIT_ARRAY
                else -> Token.ENUM_INIT_KEYS
            }
            val init = Node(initType, obj)
            init.putProp(Node.LOCAL_BLOCK_PROP, localBlock)
            val cond = Node(Token.ENUM_NEXT)
            cond.putProp(Node.LOCAL_BLOCK_PROP, localBlock)
            val id = Node(Token.ENUM_ID)
            id.putProp(Node.LOCAL_BLOCK_PROP, localBlock)

            val newBody = Node(Token.BLOCK)
            val assign: Node
            if (destructuring != -1) {
                assign = parser.createDestructuringAssignment(
                    declType,
                    lvalue,
                    id,
                    Parser.Transformer { transform(it) },
                )
                if (!isForEach &&
                    !isForOf &&
                    (destructuring == Token.OBJECTLIT || destructuringLen != 2)
                ) {
                    // Destructuring is only allowed in for..each, or with an array of length 2 so
                    // it can hold the key and the value.
                    parser.reportError("msg.bad.for.in.destruct")
                }
            } else {
                assign = parser.simpleAssignment(lvalue, id)
            }
            newBody.addChildToBack(Node(Token.EXPR_VOID, assign))
            newBody.addChildToBack(body)

            val builtLoop = createLoop(loop as Jump, LOOP_WHILE, newBody, cond, null, null)
            builtLoop.addChildToFront(init)
            if (type == Token.VAR || type == Token.LET) builtLoop.addChildToFront(lhs)
            localBlock.addChildToBack(builtLoop)

            return localBlock
        } finally {
            astNodePos.pop()
        }
    }

    /**
     * Builds the try/catch/finally IR. Most of the shape lives in the tree; the code generator
     * only adds the handlers, and a goto around them. Either TARGET or FINALLY may be absent, but
     * not both.
     */
    private fun createTryCatchFinally(
        tryBlock: Node,
        catchBlocks: Node,
        finallyBlock: Node?,
        lineno: Int,
        column: Int,
    ): Node {
        val hasFinally = finallyBlock != null &&
            (finallyBlock.type != Token.BLOCK || finallyBlock.hasChildren())

        // Short circuit.
        if (tryBlock.type == Token.BLOCK && !tryBlock.hasChildren() && !hasFinally) {
            return tryBlock
        }

        val hasCatch = catchBlocks.hasChildren()

        // Short circuit, because the finally may be an empty block.
        if (!hasFinally && !hasCatch) {
            return tryBlock
        }

        val handlerBlock = Node(Token.LOCAL_BLOCK)
        val pn = Jump(Token.TRY, tryBlock)
        pn.setLineColumnNumber(lineno, column)
        pn.putProp(Node.LOCAL_BLOCK_PROP, handlerBlock)

        if (hasCatch) {
            // Jump around the catch code.
            val endCatch = Node.newTarget()
            pn.addChildToBack(makeJump(Token.GOTO, endCatch))

            // A TARGET for the catch that the try node knows about.
            val catchTarget = Node.newTarget()
            pn.target = catchTarget
            pn.addChildToBack(catchTarget)

            /*
             * Given
             *     try { tryBlock; }
             *     catch (e if condition1) { something1; }
             *     ...
             *     catch (e if conditionN) { somethingN; }
             *     catch (e) { somethingDefault; }
             * this rewrites to
             *     try { tryBlock; goto after_catch: }
             *     catch (x) {
             *         with (newCatchScope(e, x)) {
             *             if (condition1) { something1; goto after_catch; }
             *         }
             *         ...
             *         with (newCatchScope(e, x)) { somethingDefault; goto after_catch; }
             *     }
             *   after_catch:
             *
             * With no default catch, the last "with" block becomes a rethrow instead. The catch
             * handler is expected to store the exception object in the handlerBlock register.
             */

            // A block with a local for the exception scope objects.
            val catchScopeBlock = Node(Token.LOCAL_BLOCK)

            // The catchBlocks children come in (cond, block) pairs.
            var cb = catchBlocks.firstChild
            var hasDefault = false
            var scopeIndex = 0
            while (cb != null) {
                val catchLineno = cb.lineno
                val catchColumn = cb.column

                val name = cb.firstChild!!
                val cond = name.next!!
                val catchStatement = cond.next!!
                cb.removeChild(name)
                cb.removeChild(cond)
                cb.removeChild(catchStatement)

                // Add a goto so the catch statement jumps out, prefixed with LEAVEWITH because
                // try/catch produces "with" code that scopes the exception object.
                catchStatement.addChildToBack(Node(Token.LEAVEWITH))
                catchStatement.addChildToBack(makeJump(Token.GOTO, endCatch))

                // Build the condition "if" when there is one.
                val condStmt: Node
                if (cond.type == Token.EMPTY) {
                    condStmt = catchStatement
                    hasDefault = true
                } else {
                    condStmt = createIf(cond, catchStatement, null, catchLineno, catchColumn)
                }

                // Create the scope object and store it in the catchScopeBlock register.
                val catchScope = Node(Token.CATCH_SCOPE, name, createUseLocal(handlerBlock))
                catchScope.putProp(Node.LOCAL_BLOCK_PROP, catchScopeBlock)
                catchScope.putIntProp(Node.CATCH_SCOPE_PROP, scopeIndex)
                catchScopeBlock.addChildToBack(catchScope)

                // Add the "with" statement over the catch scope object.
                catchScopeBlock.addChildToBack(
                    createWith(
                        createUseLocal(catchScopeBlock),
                        condStmt,
                        catchLineno,
                        catchColumn,
                    ),
                )

                cb = cb.next
                ++scopeIndex
            }
            pn.addChildToBack(catchScopeBlock)
            if (!hasDefault) {
                // Rethrow when no catch clause ran.
                val rethrow = Node(Token.RETHROW)
                rethrow.putProp(Node.LOCAL_BLOCK_PROP, handlerBlock)
                pn.addChildToBack(rethrow)
            }

            pn.addChildToBack(endCatch)
        }

        if (hasFinally) {
            val finallyTargetNode = Node.newTarget()
            pn.finallyTarget = finallyTargetNode

            // Add the jsr to the finally in the try block.
            pn.addChildToBack(makeJump(Token.JSR, finallyTargetNode))

            // Jump around the finally code.
            val finallyEnd = Node.newTarget()
            pn.addChildToBack(makeJump(Token.GOTO, finallyEnd))

            pn.addChildToBack(finallyTargetNode)
            val fBlock = Node(Token.FINALLY, finallyBlock)
            fBlock.putProp(Node.LOCAL_BLOCK_PROP, handlerBlock)
            pn.addChildToBack(fBlock)

            pn.addChildToBack(finallyEnd)
        }
        handlerBlock.addChildToBack(pn)
        return handlerBlock
    }

    private fun createWith(obj: Node, body: Node, lineno: Int, column: Int): Node {
        parser.setRequiresActivation()
        val result = Node(Token.BLOCK, lineno, column)
        result.addChildToBack(Node(Token.ENTERWITH, obj))
        val bodyNode = Node(Token.WITH, body, lineno, column)
        result.addChildrenToBack(bodyNode)
        result.addChildToBack(Node(Token.LEAVEWITH))
        return result
    }

    private fun createCallOrNew(nodeType: Int, child: Node): Node {
        var type = Node.NON_SPECIALCALL
        if (child.type == Token.NAME) {
            val name = child.string
            if ("eval" == name) {
                type = Node.SPECIALCALL_EVAL
            } else if ("With" == name) {
                type = Node.SPECIALCALL_WITH
            }
        } else if (child.type == Token.GETPROP) {
            if ("eval" == child.lastChild!!.string) {
                type = Node.SPECIALCALL_EVAL
            }
        }
        val node = Node(nodeType, child)
        if (type != Node.NON_SPECIALCALL) {
            // Calls to these need activation objects.
            parser.setRequiresActivation()
            node.putIntProp(Node.SPECIALCALL_PROP, type)
        }
        return node
    }

    private fun createPropertyGet(
        target: Node?,
        namespace: String?,
        name: String,
        memberTypeFlags: Int,
        type: Int,
    ): Node {
        var flags = memberTypeFlags
        if (namespace == null && flags == 0) {
            if (target == null) {
                return parser.createName(name)
            }
            parser.checkActivationName(name, Token.GETPROP)

            if (parser.compilerEnv.languageVersion < Context.VERSION_ES6 &&
                ScriptRuntime.isSpecialProperty(name)
            ) {
                val ref = Node(Token.REF_SPECIAL, target)
                ref.putProp(Node.NAME_PROP, name)
                val getRef = Node(Token.GET_REF, ref)
                if (type == Token.QUESTION_DOT) {
                    ref.putIntProp(Node.OPTIONAL_CHAINING, 1)
                    getRef.putIntProp(Node.OPTIONAL_CHAINING, 1)
                }
                return getRef
            }

            val node = Node(Token.GETPROP, target, Node.newString(name))
            if (type == Token.QUESTION_DOT) {
                node.putIntProp(Node.OPTIONAL_CHAINING, 1)
            }
            if (target.type == Token.SUPER) {
                node.putIntProp(Node.SUPER_PROPERTY_ACCESS, 1)
            }
            return node
        }
        val elem = Node.newString(name)
        flags = flags or Node.PROPERTY_FLAG
        return createMemberRefGet(target, namespace, elem, flags)
    }

    private fun createMemberRefGet(
        target: Node?,
        namespace: String?,
        elem: Node,
        memberTypeFlags: Int,
    ): Node {
        var nsNode: Node? = null
        if (namespace != null) {
            // See 11.1.2 in ECMA 357.
            nsNode = if ("*" == namespace) Node(Token.NULL) else parser.createName(namespace)
        }
        val ref: Node = if (target == null) {
            if (namespace == null) {
                Node(Token.REF_NAME, elem)
            } else {
                Node(Token.REF_NS_NAME, nsNode!!, elem)
            }
        } else {
            if (namespace == null) {
                Node(Token.REF_MEMBER, target, elem)
            } else {
                Node(Token.REF_NS_MEMBER, target, nsNode!!, elem)
            }
        }
        if (memberTypeFlags != 0) {
            ref.putIntProp(Node.MEMBER_TYPE_PROP, memberTypeFlags)
        }
        return Node(Token.GET_REF, ref)
    }

    private fun createAssignment(assignType: Int, left: Node, right: Node): Node {
        val ref = makeReference(left)
        if (ref == null) {
            if (left.type == Token.ARRAYLIT || left.type == Token.OBJECTLIT) {
                if (assignType != Token.ASSIGN) {
                    parser.reportError("msg.bad.destruct.op")
                    return right
                }
                return parser.createDestructuringAssignment(
                    -1,
                    left,
                    right,
                    Parser.Transformer { transform(it) },
                )
            }
            parser.reportError("msg.bad.assign.left")
            return right
        }
        val lhs = ref

        val assignOp: Int = when (assignType) {
            Token.ASSIGN -> return propagateSuperFromLhs(parser.simpleAssignment(lhs, right), lhs)
            Token.ASSIGN_BITOR -> Token.BITOR
            Token.ASSIGN_LOGICAL_OR -> Token.OR
            Token.ASSIGN_BITXOR -> Token.BITXOR
            Token.ASSIGN_BITAND -> Token.BITAND
            Token.ASSIGN_LOGICAL_AND -> Token.AND
            Token.ASSIGN_LSH -> Token.LSH
            Token.ASSIGN_RSH -> Token.RSH
            Token.ASSIGN_URSH -> Token.URSH
            Token.ASSIGN_ADD -> Token.ADD
            Token.ASSIGN_SUB -> Token.SUB
            Token.ASSIGN_MUL -> Token.MUL
            Token.ASSIGN_DIV -> Token.DIV
            Token.ASSIGN_MOD -> Token.MOD
            Token.ASSIGN_EXP -> Token.EXP
            Token.ASSIGN_NULLISH -> Token.NULLISH_COALESCING
            else -> throw Kit.codeBug()
        }

        when (lhs.type) {
            Token.NAME -> {
                val op = Node(assignOp, lhs, right)
                val lvalueLeft = Node.newString(Token.BINDNAME, lhs.string!!)
                return propagateSuperFromLhs(Node(Token.SETNAME, lvalueLeft, op), lhs)
            }
            Token.GETPROP, Token.GETELEM -> {
                val obj = lhs.firstChild!!
                val id = lhs.lastChild!!

                val type =
                    if (lhs.type == Token.GETPROP) Token.SETPROP_OP else Token.SETELEM_OP

                val opLeft = Node(Token.USE_STACK)
                val op = Node(assignOp, opLeft, right)
                return propagateSuperFromLhs(Node(type, obj, id, op), lhs)
            }
            Token.GET_REF -> {
                val innerRef = lhs.firstChild!!
                parser.checkMutableReference(innerRef)
                val opLeft = Node(Token.USE_STACK)
                val op = Node(assignOp, opLeft, right)
                return propagateSuperFromLhs(Node(Token.SET_REF_OP, innerRef, op), lhs)
            }
        }

        throw Kit.codeBug()
    }

    /** Infers a function name when the right side is missing one. */
    private fun inferNameIfMissing(left: Any?, right: Node?, prefix: String?) {
        if (parser.compilerEnv.languageVersion < Context.VERSION_ES6) {
            return
        }

        if (left is Name && right != null && right.type == Token.FUNCTION) {
            if (left.identifier == NativeObject.PROTO_PROPERTY) {
                // Ignore this odd edge case.
                return
            }

            val fnIndex = right.getExistingIntProp(Node.FUNCTION_PROP)
            val functionNode = parser.currentScriptOrFn!!.getFunctionNode(fnIndex)
            if (functionNode.type != 0 && functionNode.functionName == null) {
                functionNode.functionName =
                    if (prefix != null) left.withPrefix(prefix) else left
            }
        }
    }

    private fun propagateSuperFromLhs(result: Node, left: Node): Node {
        if (left.getIntProp(Node.SUPER_PROPERTY_ACCESS, 0) == 1) {
            result.putIntProp(Node.SUPER_PROPERTY_ACCESS, 1)
        }
        return result
    }

    /** True when [n] is the target of a destructuring bind. */
    internal fun isDestructuring(n: Node): Boolean =
        n is DestructuringForm && n.isDestructuring

    internal fun decompileFunctionHeader(fn: FunctionNode): Node? {
        if (fn.functionName != null) {
            return null
        } else if (fn.memberExprNode != null) {
            return transform(fn.memberExprNode!!)
        }
        return null
    }

    /** Reports the source position of the AST node currently being transformed. */
    class AstNodePosition(private val sourceString: String) : Parser.CurrentPositionReporter {

        private val stack = ArrayDeque<AstNode>()

        private var savedLineno = -1
        private var savedLine: String? = null
        private var savedLineOffset = 0

        fun push(node: AstNode) {
            stack.addFirst(node)
        }

        fun pop() {
            stack.removeFirst()
        }

        override val position: Int
            get() = stack.first().absolutePosition

        override val length: Int
            get() = stack.first().length

        override val lineno: Int
            get() = stack.first().lineno

        private fun cutAndSaveLine() {
            val lineno = lineno
            if (savedLineno == lineno) {
                return
            }

            var l = 1
            var isPrevCR = false
            var begin = 0
            while (begin < sourceString.length) {
                val c = sourceString[begin]
                if (isPrevCR && c == '\n') {
                    begin++
                    continue
                }
                isPrevCR = c == '\r'

                if (l == lineno) {
                    break
                }
                if (ScriptRuntime.isJSLineTerminator(c.code)) {
                    l++
                }
                begin++
            }

            var end = begin
            while (end < sourceString.length) {
                if (ScriptRuntime.isJSLineTerminator(sourceString[end].code)) {
                    break
                }
                end++
            }

            savedLineno = lineno
            if (end == 0) {
                savedLine = ""
                savedLineOffset = 0
            } else {
                savedLine = sourceString.substring(begin, end)
                savedLineOffset = position - begin + 1
            }
        }

        override val line: String?
            get() {
                cutAndSaveLine()
                return savedLine
            }

        override val offset: Int
            get() {
                cutAndSaveLine()
                return savedLineOffset
            }
    }

    companion object {
        private const val LOOP_DO_WHILE = 0
        private const val LOOP_WHILE = 1
        private const val LOOP_FOR = 2

        private const val ALWAYS_TRUE_BOOLEAN = 1
        private const val ALWAYS_FALSE_BOOLEAN = -1

        /** If [caseExpression] is null this is the default label. */
        private fun addSwitchCase(switchBlock: Node, caseExpression: Node?, statements: Node) {
            if (switchBlock.type != Token.BLOCK) throw Kit.codeBug()
            val switchNode = switchBlock.firstChild as Jump
            if (switchNode.type != Token.SWITCH) throw Kit.codeBug()

            val gotoTarget = Node.newTarget()
            if (caseExpression != null) {
                val caseNode = Jump(Token.CASE, caseExpression)
                caseNode.target = gotoTarget
                switchNode.addChildToBack(caseNode)
            } else {
                switchNode.defaultTarget = gotoTarget
            }
            switchBlock.addChildToBack(gotoTarget)
            switchBlock.addChildToBack(statements)
        }

        private fun closeSwitch(switchBlock: Node) {
            if (switchBlock.type != Token.BLOCK) throw Kit.codeBug()
            val switchNode = switchBlock.firstChild as Jump
            if (switchNode.type != Token.SWITCH) throw Kit.codeBug()

            val switchBreakTarget = Node.newTarget()
            // switchNode.target is only used by NodeTransformer, to find the switch end.
            switchNode.target = switchBreakTarget

            val defaultTarget = switchNode.defaultTarget ?: switchBreakTarget

            switchBlock.addChildAfter(makeJump(Token.GOTO, defaultTarget), switchNode)
            switchBlock.addChildToBack(switchBreakTarget)
        }

        private fun createExprStatementNoReturn(expr: Node, lineno: Int, column: Int): Node =
            Node(Token.EXPR_VOID, expr, lineno, column)

        private fun propagateRequiresArgumentObjectFromNestedArrowFunctions(fnNode: FunctionNode) {
            if (fnNode.requiresArgumentObject) {
                return
            }

            // A nested lambda that needs arguments forces the outer function to need them too.
            val toVisit = ArrayDeque(fnNode.functions)
            while (toVisit.isNotEmpty()) {
                val nestedFunction = toVisit.removeFirst()
                if (nestedFunction.functionType == FunctionNode.ARROW_FUNCTION) {
                    if (nestedFunction.requiresArgumentObject) {
                        fnNode.requiresArgumentObject = true
                        return
                    }

                    // Every nested arrow function, recursively.
                    toVisit.addAll(nestedFunction.functions)
                }
            }
        }

        private fun createFor(loop: Scope, init: Node, test: Node, incr: Node, body: Node): Node {
            if (init.type == Token.LET) {
                // Rewrite "for (let i=s; i < N; i++)..." as "let (i=s) { for (; i < N; i++)..."
                // so that "s" is evaluated outside the scope of the for.
                val let = Scope.splitScope(loop)
                let.type = Token.LET
                let.addChildrenToBack(init)
                let.addChildToBack(
                    createLoop(loop, LOOP_FOR, body, test, Node(Token.EMPTY), incr),
                )
                return let
            }
            return createLoop(loop, LOOP_FOR, body, test, init, incr)
        }

        private fun createLoop(
            loop: Jump,
            loopType: Int,
            body: Node,
            cond: Node,
            init: Node?,
            incr: Node?,
        ): Node {
            var condition = cond
            val bodyTarget = Node.newTarget()
            val condTarget = Node.newTarget()
            if (loopType == LOOP_FOR && condition.type == Token.EMPTY) {
                condition = Node(Token.TRUE)
            }
            val ifeq = Jump(Token.IFEQ, condition)
            ifeq.target = bodyTarget
            val breakTarget = Node.newTarget()

            loop.addChildToBack(bodyTarget)
            loop.addChildrenToBack(body)
            if (loopType == LOOP_WHILE || loopType == LOOP_FOR) {
                // Propagate the line number to the condition.
                loop.addChildrenToBack(Node(Token.EMPTY, loop.lineno, loop.column))
            }
            loop.addChildToBack(condTarget)
            loop.addChildToBack(ifeq)
            loop.addChildToBack(breakTarget)

            loop.target = breakTarget
            var continueTarget = condTarget

            if (loopType == LOOP_WHILE || loopType == LOOP_FOR) {
                // A do..while just gets a GOTO to the condition.
                loop.addChildToFront(makeJump(Token.GOTO, condTarget))

                if (loopType == LOOP_FOR) {
                    var initNode = init!!
                    val initType = initNode.type
                    if (initType != Token.EMPTY) {
                        if (initType != Token.VAR && initType != Token.LET) {
                            initNode = Node(Token.EXPR_VOID, initNode)
                        }
                        loop.addChildToFront(initNode)
                    }
                    val incrTarget = Node.newTarget()
                    loop.addChildAfter(incrTarget, body)
                    var incrNode = incr!!
                    if (incrNode.type != Token.EMPTY) {
                        incrNode = Node(Token.EXPR_VOID, incrNode)
                        loop.addChildAfter(incrNode, incrTarget)
                    }
                    continueTarget = incrTarget
                }
            }

            loop.continueTarget = continueTarget
            return loop
        }

        private fun createIf(
            cond: Node,
            ifTrue: Node,
            ifFalse: Node?,
            lineno: Int,
            column: Int,
        ): Node {
            val condStatus = isAlwaysDefinedBoolean(cond)
            if (condStatus == ALWAYS_TRUE_BOOLEAN) {
                return ifTrue
            } else if (condStatus == ALWAYS_FALSE_BOOLEAN) {
                if (ifFalse != null) {
                    return ifFalse
                }
                // Replace "if (false) xxx" with an empty block.
                return Node(Token.BLOCK, lineno, column)
            }

            val result = Node(Token.BLOCK, lineno, column)
            val ifNotTarget = Node.newTarget()
            val ifne = Jump(Token.IFNE, cond)
            ifne.target = ifNotTarget

            result.addChildToBack(ifne)
            result.addChildrenToBack(ifTrue)

            if (ifFalse != null) {
                val endTarget = Node.newTarget()
                result.addChildToBack(makeJump(Token.GOTO, endTarget))
                result.addChildToBack(ifNotTarget)
                result.addChildrenToBack(ifFalse)
                result.addChildToBack(endTarget)
            } else {
                result.addChildToBack(ifNotTarget)
            }

            cond.firstChild?.let { result.setLineColumnNumber(it.lineno, it.column) }

            return result
        }

        private fun createCondExpr(cond: Node, ifTrue: Node, ifFalse: Node): Node {
            val condStatus = isAlwaysDefinedBoolean(cond)
            if (condStatus == ALWAYS_TRUE_BOOLEAN) {
                return ifTrue
            } else if (condStatus == ALWAYS_FALSE_BOOLEAN) {
                return ifFalse
            }
            return Node(Token.HOOK, cond, ifTrue, ifFalse)
        }

        private fun createUnary(nodeType: Int, child: Node): Node {
            val childType = child.type
            when (nodeType) {
                Token.DELPROP -> {
                    val n: Node
                    if (childType == Token.NAME) {
                        // Delete(Name "a") becomes Delete(Bind("a"), String("a")).
                        child.type = Token.BINDNAME
                        val right = Node.newString(child.string!!)
                        n = Node(nodeType, child, right)
                    } else if (childType == Token.UNDEFINED) {
                        val name = Node.newString(Token.BINDNAME, "undefined")
                        val right = Node.newString("undefined")
                        n = Node(nodeType, name, right)
                    } else if (childType == Token.GETPROP || childType == Token.GETELEM) {
                        val left = child.firstChild!!
                        val right = child.lastChild!!
                        child.removeChild(left)
                        child.removeChild(right)
                        n = Node(nodeType, left, right)
                    } else if (childType == Token.GET_REF) {
                        val ref = child.firstChild!!
                        child.removeChild(ref)
                        n = Node(Token.DEL_REF, ref)
                    } else {
                        // Always evaluate the delete operand. See ES5 11.4.1 and bug 726121.
                        n = Node(nodeType, Node(Token.TRUE), child)
                    }
                    if (child.getIntProp(Node.SUPER_PROPERTY_ACCESS, 0) == 1) {
                        n.putIntProp(Node.SUPER_PROPERTY_ACCESS, 1)
                    }
                    return n
                }
                Token.TYPEOF -> {
                    if (childType == Token.NAME) {
                        child.type = Token.TYPEOFNAME
                        return child
                    }
                }
                Token.BITNOT -> {
                    if (childType == Token.NUMBER) {
                        val value = ScriptRuntime.toInt32(child.double)
                        child.double = value.inv().toDouble()
                        return child
                    }
                }
                Token.NEG -> {
                    if (childType == Token.NUMBER) {
                        child.double = -child.double
                        return child
                    }
                }
                Token.NOT -> {
                    val status = isAlwaysDefinedBoolean(child)
                    if (status != 0) {
                        val type =
                            if (status == ALWAYS_TRUE_BOOLEAN) Token.FALSE else Token.TRUE
                        if (childType == Token.TRUE || childType == Token.FALSE) {
                            child.type = type
                            return child
                        }
                        return Node(type)
                    }
                }
            }
            return Node(nodeType, child)
        }

        private fun createIncDec(nodeType: Int, post: Boolean, child: Node): Node {
            val ref = makeReference(child) ?: throw Kit.codeBug()
            when (ref.type) {
                Token.NAME, Token.GETPROP, Token.GETELEM, Token.GET_REF -> {
                    val n = Node(nodeType, ref)
                    var incrDecrMask = 0
                    if (nodeType == Token.DEC) {
                        incrDecrMask = incrDecrMask or Node.DECR_FLAG
                    }
                    if (post) {
                        incrDecrMask = incrDecrMask or Node.POST_FLAG
                    }
                    n.putIntProp(Node.INCRDECR_PROP, incrDecrMask)
                    return n
                }
            }
            throw Kit.codeBug()
        }

        private fun createBinary(nodeType: Int, left: Node, right: Node): Node {
            when (nodeType) {
                Token.ADD -> {
                    // Numeric addition and string concatenation.
                    if (left.type == Token.STRING) {
                        val s2: String
                        if (right.type == Token.STRING) {
                            s2 = right.string!!
                        } else if (right.type == Token.NUMBER) {
                            s2 = ScriptRuntime.numberToString(right.double, 10)
                        } else {
                            return Node(nodeType, left, right)
                        }
                        left.string = left.string!! + s2
                        return left
                    } else if (left.type == Token.NUMBER) {
                        if (right.type == Token.NUMBER) {
                            left.double = left.double + right.double
                            return left
                        } else if (right.type == Token.STRING) {
                            val s1 = ScriptRuntime.numberToString(left.double, 10)
                            right.string = s1 + right.string!!
                            return right
                        }
                    }
                    // Nothing can be folded without knowing both types, since 0 + object has to
                    // call toString and concatenate rather than add.
                }

                Token.SUB -> {
                    if (left.type == Token.NUMBER) {
                        val ld = left.double
                        if (right.type == Token.NUMBER) {
                            left.double = ld - right.double
                            return left
                        } else if (ld == 0.0) {
                            // 0 - x becomes -x.
                            return Node(Token.NEG, right)
                        }
                    } else if (right.type == Token.NUMBER) {
                        if (right.double == 0.0) {
                            // x - 0 becomes +x. It cannot become plain x, because the result has
                            // to be a number.
                            return Node(Token.POS, left)
                        }
                    }
                }

                Token.MUL -> {
                    if (left.type == Token.NUMBER) {
                        val ld = left.double
                        if (right.type == Token.NUMBER) {
                            left.double = ld * right.double
                            return left
                        } else if (ld == 1.0) {
                            // 1 * x becomes +x.
                            return Node(Token.POS, right)
                        }
                    } else if (right.type == Token.NUMBER) {
                        if (right.double == 1.0) {
                            // x * 1 becomes +x.
                            return Node(Token.POS, left)
                        }
                    }
                    // x * 0 cannot be folded: Infinity * 0 is NaN, not 0.
                }

                Token.DIV -> {
                    if (right.type == Token.NUMBER) {
                        val rd = right.double
                        if (left.type == Token.NUMBER) {
                            // Both constant, so just divide and let the platform handle x/0.
                            left.double = left.double / rd
                            return left
                        } else if (rd == 1.0) {
                            // x / 1 becomes +x, not plain x, to force the number conversion.
                            return Node(Token.POS, left)
                        }
                    }
                }

                Token.AND -> {
                    // x && y gives x, not false, when Boolean(x) is false, and y, not Boolean(y),
                    // when Boolean(x) is true, so it can only be folded when x is defined.
                    // See bug 309957.
                    val leftStatus = isAlwaysDefinedBoolean(left)
                    if (leftStatus == ALWAYS_FALSE_BOOLEAN) {
                        return left
                    } else if (leftStatus == ALWAYS_TRUE_BOOLEAN) {
                        return right
                    }
                }

                Token.OR -> {
                    // Mirror of the AND case above.
                    val leftStatus = isAlwaysDefinedBoolean(left)
                    if (leftStatus == ALWAYS_TRUE_BOOLEAN) {
                        return left
                    } else if (leftStatus == ALWAYS_FALSE_BOOLEAN) {
                        return right
                    }
                }
            }

            return Node(nodeType, left, right)
        }

        private fun createUseLocal(localBlock: Node): Node {
            if (Token.LOCAL_BLOCK != localBlock.type) throw Kit.codeBug()
            val result = Node(Token.LOCAL_LOAD)
            result.putProp(Node.LOCAL_BLOCK_PROP, localBlock)
            return result
        }

        private fun makeJump(type: Int, target: Node): Jump {
            val n = Jump(type)
            n.target = target
            return n
        }

        private fun makeReference(node: Node): Node? {
            when (node.type) {
                Token.NAME, Token.UNDEFINED, Token.GETPROP, Token.GETELEM, Token.GET_REF ->
                    return node
                Token.CALL -> {
                    node.type = Token.REF_CALL
                    return Node(Token.GET_REF, node)
                }
            }
            // Signal the caller to report an error.
            return null
        }

        /** Whether the node always reads as true or false in a boolean context. */
        private fun isAlwaysDefinedBoolean(node: Node): Int {
            when (node.type) {
                Token.FALSE, Token.NULL, Token.UNDEFINED -> return ALWAYS_FALSE_BOOLEAN
                Token.TRUE -> return ALWAYS_TRUE_BOOLEAN
                Token.NUMBER -> {
                    val num = node.double
                    if (!num.isNaN() && num != 0.0) {
                        return ALWAYS_TRUE_BOOLEAN
                    }
                    return ALWAYS_FALSE_BOOLEAN
                }
            }
            return 0
        }
    }
}
