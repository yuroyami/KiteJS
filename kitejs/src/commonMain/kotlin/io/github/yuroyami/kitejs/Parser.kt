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
import io.github.yuroyami.kitejs.ast.CatchClause
import io.github.yuroyami.kitejs.ast.Comment
import io.github.yuroyami.kitejs.ast.ComputedPropertyKey
import io.github.yuroyami.kitejs.ast.ConditionalExpression
import io.github.yuroyami.kitejs.ast.ContinueStatement
import io.github.yuroyami.kitejs.ast.DestructuringForm
import io.github.yuroyami.kitejs.ast.DoLoop
import io.github.yuroyami.kitejs.ast.ElementGet
import io.github.yuroyami.kitejs.ast.EmptyExpression
import io.github.yuroyami.kitejs.ast.EmptyStatement
import io.github.yuroyami.kitejs.ast.ErrorNode
import io.github.yuroyami.kitejs.ast.ExpressionStatement
import io.github.yuroyami.kitejs.ast.ForInLoop
import io.github.yuroyami.kitejs.ast.ForLoop
import io.github.yuroyami.kitejs.ast.FunctionCall
import io.github.yuroyami.kitejs.ast.FunctionNode
import io.github.yuroyami.kitejs.ast.GeneratorExpression
import io.github.yuroyami.kitejs.ast.GeneratorExpressionLoop
import io.github.yuroyami.kitejs.ast.GeneratorMethodDefinition
import io.github.yuroyami.kitejs.ast.IdeErrorReporter
import io.github.yuroyami.kitejs.ast.IfStatement
import io.github.yuroyami.kitejs.ast.InfixExpression
import io.github.yuroyami.kitejs.ast.Jump
import io.github.yuroyami.kitejs.ast.KeywordLiteral
import io.github.yuroyami.kitejs.ast.Label
import io.github.yuroyami.kitejs.ast.LabeledStatement
import io.github.yuroyami.kitejs.ast.LetNode
import io.github.yuroyami.kitejs.ast.Loop
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
import io.github.yuroyami.kitejs.ast.SwitchCase
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
 * The JavaScript parser, based on the SpiderMonkey sources jsparse.c and jsparse.h.
 *
 * It produces an [AstRoot] parse tree that mirrors the source exactly: no tree rewriting
 * happens here, so the tree stays useful for IDEs and pretty-printers.
 *
 * A Parser is single use. Calling [parse] twice throws.
 *
 * Not ported: the Reader-based source path (D-2) and the E4X methods, so `defaultXmlNamespace`,
 * `xmlInitializer`, `attributeAccess` and the XML branches of `propertyName` and `memberExprTail`
 * report "XML not available" instead of parsing E4X syntax.
 */
class Parser(
    internal val compilerEnv: CompilerEnvirons = CompilerEnvirons(),
    private val errorReporter: ErrorReporter = compilerEnv.errorReporter,
) {

    private val errorCollector: IdeErrorReporter? = errorReporter as? IdeErrorReporter

    var sourceURI: String? = null

    private var sourceChars: CharArray? = null

    /** Ugly, but faithful: Context sets this directly. */
    internal var calledByCompileFunction = false

    /** Set when the parse finishes, so a reused parser can be rejected. */
    private var parseFinished = false

    private lateinit var ts: TokenStream

    internal lateinit var currentPos: CurrentPositionReporter

    private var currentFlaggedToken = Token.EOF
    internal var currentToken = 0
    private var syntaxErrorCount = 0

    private var scannedComments: MutableList<Comment>? = null
    private var currentJsDocComment: Comment? = null

    protected var nestingOfFunction = 0
    protected var nestingOfFunctionParams = 0
    private var currentLabel: LabeledStatement? = null
    private var inDestructuringAssignment = false

    internal var inUseStrictDirective = false

    // The following are per function variables, saved and restored around function parsing.
    // See PerFunctionVariables below.
    internal var currentScriptOrFn: ScriptNode? = null
    private var insideMethod = false
    internal var currentScope: Scope? = null
    private var endFlags = 0
    private var inForInit = false // bound temporarily during forStatement()
    private var labelSet: MutableMap<String, LabeledStatement>? = null
    private var loopSet: MutableList<Loop>? = null
    private var loopAndSwitchSet: MutableList<Jump>? = null
    private var hasUndefinedBeenRedefined = false
    // end of per function variables

    // Lacking two-token lookahead, labels are a problem. These hold the token info of the last
    // matched name, but only when it was not the last matched token.
    private var prevNameTokenStart = 0
    private var prevNameTokenString = ""
    private var prevNameTokenLineno = 0
    private var prevNameTokenColumn = 0
    private var lastTokenLineno = -1
    private var lastTokenColumn = -1

    /** Exception used to unwind out of a failed parse. */
    class ParserException : RuntimeException()

    /** Lowers an AST node to an IR node. Implemented by IRFactory. */
    internal fun interface Transformer {
        fun transform(node: AstNode): Node
    }

    // Add a strict warning on the last matched token.
    internal fun addStrictWarning(messageId: String, messageArg: String?) {
        addStrictWarning(messageId, messageArg, currentPos.position, currentPos.length)
    }

    internal fun addStrictWarning(
        messageId: String,
        messageArg: String?,
        position: Int,
        length: Int,
    ) {
        if (compilerEnv.strictMode) addWarning(messageId, messageArg, position, length)
    }

    internal fun addWarning(messageId: String, messageArg: String?) {
        addWarning(messageId, messageArg, currentPos.position, currentPos.length)
    }

    internal fun addWarning(messageId: String, position: Int, length: Int) {
        addWarning(messageId, null, position, length)
    }

    internal fun addWarning(
        messageId: String,
        messageArg: String?,
        position: Int,
        length: Int,
    ) {
        val message = lookupMessage(messageId, messageArg)
        if (compilerEnv.reportWarningAsError()) {
            addError(messageId, messageArg, position, length)
        } else if (errorCollector != null) {
            errorCollector.warning(message, sourceURI, position, length)
        } else {
            errorReporter.warning(
                message,
                sourceURI,
                currentPos.lineno,
                currentPos.line,
                currentPos.offset,
            )
        }
    }

    internal fun addError(messageId: String) {
        addError(messageId, currentPos.position, currentPos.length)
    }

    internal fun addError(messageId: String, position: Int, length: Int) {
        addError(messageId, null, position, length)
    }

    internal fun addError(messageId: String, messageArg: String?) {
        addError(messageId, messageArg, currentPos.position, currentPos.length)
    }

    internal fun addError(messageId: String, c: Int) {
        addError(messageId, c.toChar().toString())
    }

    internal fun addError(messageId: String, messageArg: String?, position: Int, length: Int) {
        ++syntaxErrorCount
        val message = lookupMessage(messageId, messageArg)
        if (errorCollector != null) {
            errorCollector.error(message, sourceURI, position, length)
        } else {
            errorReporter.error(
                message,
                sourceURI,
                currentPos.lineno,
                currentPos.line,
                currentPos.offset,
            )
        }
    }

    private fun addStrictWarning(
        messageId: String,
        messageArg: String?,
        position: Int,
        length: Int,
        line: Int,
        lineSource: String?,
        lineOffset: Int,
    ) {
        if (compilerEnv.strictMode) {
            addWarning(messageId, messageArg, position, length, line, lineSource, lineOffset)
        }
    }

    private fun addWarning(
        messageId: String,
        messageArg: String?,
        position: Int,
        length: Int,
        line: Int,
        lineSource: String?,
        lineOffset: Int,
    ) {
        val message = lookupMessage(messageId, messageArg)
        if (compilerEnv.reportWarningAsError()) {
            addError(messageId, messageArg, position, length, line, lineSource, lineOffset)
        } else if (errorCollector != null) {
            errorCollector.warning(message, sourceURI, position, length)
        } else {
            errorReporter.warning(message, sourceURI, line, lineSource, lineOffset)
        }
    }

    private fun addError(
        messageId: String,
        messageArg: String?,
        position: Int,
        length: Int,
        line: Int,
        lineSource: String?,
        lineOffset: Int,
    ) {
        ++syntaxErrorCount
        val message = lookupMessage(messageId, messageArg)
        if (errorCollector != null) {
            errorCollector.error(message, sourceURI, position, length)
        } else {
            errorReporter.error(message, sourceURI, line, lineSource, lineOffset)
        }
    }

    internal fun lookupMessage(messageId: String): String = lookupMessage(messageId, null)

    internal fun lookupMessage(messageId: String, messageArg: String?): String =
        if (messageArg == null) {
            ScriptRuntime.getMessageById(messageId)
        } else {
            ScriptRuntime.getMessageById(messageId, messageArg)
        }

    internal fun reportError(messageId: String) {
        reportError(messageId, null)
    }

    internal fun reportError(messageId: String, messageArg: String?) {
        reportError(messageId, messageArg, currentPos.position, currentPos.length)
    }

    internal fun reportError(messageId: String, position: Int, length: Int) {
        reportError(messageId, null, position, length)
    }

    internal fun reportError(messageId: String, messageArg: String?, position: Int, length: Int) {
        addError(messageId, messageArg, position, length)

        if (!compilerEnv.recoverFromErrors) {
            throw ParserException()
        }
    }

    private fun recordComment(lineno: Int, column: Int, comment: String) {
        val comments = scannedComments ?: mutableListOf<Comment>().also { scannedComments = it }
        val commentNode = Comment(ts.tokenBeg, ts.length, ts.commentType!!, comment)
        if (ts.commentType == Token.CommentType.JSDOC &&
            compilerEnv.recordingLocalJsDocComments
        ) {
            currentJsDocComment =
                Comment(ts.tokenBeg, ts.length, ts.commentType!!, comment).apply {
                    setLineColumnNumber(lineno, column)
                }
        }
        commentNode.setLineColumnNumber(lineno, column)
        comments.add(commentNode)
    }

    private fun getAndResetJsDoc(): Comment? {
        val saved = currentJsDocComment
        currentJsDocComment = null
        return saved
    }

    private fun lastScannedComment(): Comment = scannedComments!!.last()

    /**
     * Returns the next token without consuming it. If the previous token was not consumed this
     * returns it again, so the call is idempotent.
     *
     * It never returns [Token.EOL]: newlines are gobbled and the following token is flagged as
     * coming right after one. It never returns [Token.COMMENT] either; comments are recorded in
     * the scanned-comments list instead.
     *
     * The returned token is always unflagged. The flags live in `currentFlaggedToken`.
     */
    private fun peekToken(): Int {
        // By far the most common case: the last token was not consumed, so return it.
        if (currentFlaggedToken != Token.EOF) {
            return currentToken
        }

        var tt = ts.getToken()
        var sawEOL = false

        // Process comments and whitespace.
        while (tt == Token.EOL || tt == Token.COMMENT) {
            if (tt == Token.EOL) {
                sawEOL = true
                tt = ts.getToken()
            } else {
                if (compilerEnv.recordingComments) {
                    val comment = ts.getAndResetCurrentComment()
                    recordComment(ts.tokenStartLineno, ts.tokenColumn, comment)
                    break
                }
                tt = ts.getToken()
            }
        }

        currentToken = tt
        currentFlaggedToken = tt or (if (sawEOL) TI_AFTER_EOL else 0)
        return currentToken // return unflagged token
    }

    private fun lineNumber(): Int = lastTokenLineno

    private fun columnNumber(): Int = lastTokenColumn

    private fun peekFlaggedToken(): Int {
        peekToken()
        return currentFlaggedToken
    }

    private fun consumeToken() {
        currentFlaggedToken = Token.EOF
        lastTokenLineno = ts.tokenStartLineno
        lastTokenColumn = ts.tokenColumn
    }

    private fun nextToken(): Int {
        val tt = peekToken()
        consumeToken()
        return tt
    }

    private fun matchToken(toMatch: Int, ignoreComment: Boolean): Boolean {
        var tt = peekToken()
        while (tt == Token.COMMENT && ignoreComment) {
            consumeToken()
            tt = peekToken()
        }
        if (tt != toMatch) {
            return false
        }
        consumeToken()
        return true
    }

    /**
     * Returns [Token.EOL] when the current token follows a newline, else the current token. Used
     * where a newline makes a token invalid, such as the postfix `++` that has to sit on the same
     * line as its operand.
     */
    private fun peekTokenOrEOL(): Int {
        val tt = peekToken()
        // Check the flags on the last peeked token.
        return if ((currentFlaggedToken and TI_AFTER_EOL) != 0) Token.EOL else tt
    }

    private fun mustMatchToken(toMatch: Int, messageId: String, ignoreComment: Boolean): Boolean =
        mustMatchToken(toMatch, messageId, ts.tokenBeg, ts.tokenEnd - ts.tokenBeg, ignoreComment)

    private fun mustMatchToken(
        toMatch: Int,
        msgId: String,
        pos: Int,
        len: Int,
        ignoreComment: Boolean,
    ): Boolean {
        if (matchToken(toMatch, ignoreComment)) {
            return true
        }
        reportError(msgId, pos, len)
        return false
    }

    private fun mustHaveXML() {
        if (!compilerEnv.xmlAvailable) {
            reportError("msg.XML.not.available")
        }
    }

    fun eof(): Boolean = ts.eof

    internal fun insideFunctionBody(): Boolean = nestingOfFunction != 0

    internal fun insideFunctionParams(): Boolean = nestingOfFunctionParams != 0

    internal fun pushScope(scope: Scope) {
        val parent = scope.parentScope
        // During codegen the parent scope chain may already be set up, in which case only the
        // currentScope variable needs updating.
        if (parent != null) {
            if (parent !== currentScope) codeBug()
        } else {
            currentScope!!.addChildScope(scope)
        }
        currentScope = scope
    }

    internal fun popScope() {
        currentScope = currentScope!!.parentScope
    }

    private fun enterLoop(loop: Loop) {
        val loops = loopSet ?: mutableListOf<Loop>().also { loopSet = it }
        loops.add(loop)
        val both = loopAndSwitchSet ?: mutableListOf<Jump>().also { loopAndSwitchSet = it }
        both.add(loop)
        pushScope(loop)
        currentLabel?.let { label ->
            label.statement = loop
            label.firstLabel.loop = loop
            // This is the only place during parsing where a node's parent is set before its
            // children are parsed. To keep the child offsets right, the loop's reported position
            // goes back to an absolute source offset and is restored by
            // restoreRelativeLoopPosition, called just before setBody.
            loop.setRelative(-label.position)
        }
    }

    private fun exitLoop() {
        loopSet!!.removeAt(loopSet!!.size - 1)
        loopAndSwitchSet!!.removeAt(loopAndSwitchSet!!.size - 1)
        popScope()
    }

    private fun restoreRelativeLoopPosition(loop: Loop) {
        loop.parent?.let { loop.setRelative(it.position) } // see the comment in enterLoop
    }

    private fun enterSwitch(node: SwitchStatement) {
        val both = loopAndSwitchSet ?: mutableListOf<Jump>().also { loopAndSwitchSet = it }
        both.add(node)
    }

    private fun exitSwitch() {
        loopAndSwitchSet!!.removeAt(loopAndSwitchSet!!.size - 1)
    }

    /**
     * Builds a parse tree from [sourceString].
     *
     * @return the parsed program. A failed parse reports through the [ErrorReporter] configured in
     *     [CompilerEnvirons].
     */
    fun parse(sourceString: String, sourceURI: String?, lineno: Int): AstRoot {
        check(!parseFinished) { "parser reused" }
        this.sourceURI = sourceURI
        if (compilerEnv.ideMode) {
            this.sourceChars = sourceString.toCharArray()
        }
        ts = TokenStream(this, sourceString, lineno)
        currentPos = ts
        try {
            return parse()
        } finally {
            parseFinished = true
        }
    }

    private fun parse(): AstRoot {
        val pos = 0
        val root = AstRoot(pos)
        currentScriptOrFn = root
        currentScope = root

        val baseLineno = ts.lineno // line number where the source starts
        prevNameTokenLineno = ts.lineno
        prevNameTokenColumn = ts.tokenColumn
        var end = pos // in case the source is empty

        var inDirectivePrologue = true
        val savedStrictMode = inUseStrictDirective

        inUseStrictDirective = compilerEnv.strictMode
        if (inUseStrictDirective) {
            root.isInStrictMode = true
        }

        try {
            while (true) {
                val tt = peekToken()
                if (tt <= Token.EOF) {
                    break
                }

                val n: AstNode
                if (tt == Token.FUNCTION) {
                    consumeToken()
                    n = try {
                        function(
                            if (calledByCompileFunction) {
                                FunctionNode.FUNCTION_EXPRESSION
                            } else {
                                FunctionNode.FUNCTION_STATEMENT
                            },
                        )
                    } catch (e: ParserException) {
                        break
                    }
                } else if (tt == Token.COMMENT) {
                    n = lastScannedComment()
                    consumeToken()
                } else {
                    n = statement()
                    if (inDirectivePrologue) {
                        val directive = getDirective(n)
                        if (directive == null) {
                            inDirectivePrologue = false
                        } else if ("use strict" == directive) {
                            inUseStrictDirective = true
                            root.isInStrictMode = true
                        }
                    }
                }
                end = getNodeEnd(n)
                root.addChildToBack(n)
                n.parent = root
            }
        } finally {
            inUseStrictDirective = savedStrictMode
        }

        reportErrorsIfExists(baseLineno)

        // Add comments to the root in lexical order.
        scannedComments?.let { comments ->
            // A comment past the last statement or function extends the root bounds.
            end = maxOf(end, getNodeEnd(comments[comments.size - 1]))
            for (c in comments) {
                root.addComment(c)
            }
        }

        root.length = end - pos
        root.sourceName = sourceURI
        root.baseLineno = baseLineno
        root.endLineno = ts.lineno
        return root
    }

    private fun parseFunctionBody(type: Int, fnNode: FunctionNode): AstNode {
        var isExpressionClosure = false
        if (!matchToken(Token.LC, true)) {
            if (compilerEnv.languageVersion < Context.VERSION_1_8 &&
                type != FunctionNode.ARROW_FUNCTION
            ) {
                reportError("msg.no.brace.body")
            } else {
                isExpressionClosure = true
            }
        }
        val isArrow = type == FunctionNode.ARROW_FUNCTION
        ++nestingOfFunction
        val pos = ts.tokenBeg
        val pn = Block(pos) // starts at the LC position

        // Code supplied as the last argument to the Function, Generator and AsyncFunction
        // constructors is strict mode code when that argument is a function body whose directive
        // prologue holds a use strict directive.
        var inDirectivePrologue = true
        val savedStrictMode = inUseStrictDirective

        pn.setLineColumnNumber(lineNumber(), columnNumber())
        try {
            if (isExpressionClosure) {
                val returnValue = assignExpr()
                val n = ReturnStatement(returnValue.position, returnValue.length, returnValue)
                // The expression closure flag is required on both nodes.
                n.putProp(Node.EXPRESSION_CLOSURE_PROP, true)
                n.setLineColumnNumber(returnValue.lineno, returnValue.column)
                pn.putProp(Node.EXPRESSION_CLOSURE_PROP, true)
                if (isArrow) {
                    n.putProp(Node.ARROW_FUNCTION_PROP, true)
                }
                pn.addStatement(n)
                pn.length = n.length
            } else {
                bodyLoop@ while (true) {
                    val n: AstNode
                    when (peekToken()) {
                        Token.ERROR, Token.EOF, Token.RC -> break@bodyLoop
                        Token.COMMENT -> {
                            consumeToken()
                            n = lastScannedComment()
                        }
                        Token.FUNCTION -> {
                            consumeToken()
                            n = function(FunctionNode.FUNCTION_STATEMENT)
                        }
                        else -> {
                            n = statement()
                            if (inDirectivePrologue) {
                                val directive = getDirective(n)
                                if (directive == null) {
                                    inDirectivePrologue = false
                                } else if ("use strict" == directive) {
                                    if (fnNode.defaultParams != null) {
                                        reportError("msg.default.args.use.strict")
                                    }
                                    inUseStrictDirective = true
                                    fnNode.isInStrictMode = true
                                    if (!savedStrictMode) {
                                        setRequiresActivation()
                                    }
                                }
                            }
                        }
                    }
                    pn.addStatement(n)
                }
                var end = ts.tokenEnd
                if (mustMatchToken(Token.RC, "msg.no.brace.after.body", true)) end = ts.tokenEnd
                pn.length = end - pos
            }
        } catch (e: ParserException) {
            // Ignore it.
        } finally {
            --nestingOfFunction
            inUseStrictDirective = savedStrictMode
        }

        getAndResetJsDoc()
        return pn
    }

    private fun parseFunctionParams(fnNode: FunctionNode) {
        ++nestingOfFunctionParams
        try {
            if (matchToken(Token.RP, true)) {
                fnNode.rp = ts.tokenBeg - fnNode.position
                return
            }
            // Would prefer to defer createDestructuringAssignment to codegen, but the symbol
            // definitions have to happen now, before the body is parsed.
            var destructuring: MutableMap<String, Node>? = null
            var destructuringDefault: MutableMap<String, AstNode>? = null

            val paramNames = HashSet<String>()
            do {
                val tt = peekToken()
                if (tt == Token.RP) {
                    if (fnNode.hasRestParameter) {
                        // Error: parameter after rest parameter.
                        reportError("msg.parm.after.rest", ts.tokenBeg, ts.tokenEnd - ts.tokenBeg)
                    }

                    fnNode.putIntProp(Node.TRAILING_COMMA, 1)
                    break
                }
                if (tt == Token.LB || tt == Token.LC) {
                    if (fnNode.hasRestParameter) {
                        // Error: parameter after rest parameter.
                        reportError("msg.parm.after.rest", ts.tokenBeg, ts.tokenEnd - ts.tokenBeg)
                    }

                    val expr = destructuringAssignExpr()
                    if (destructuring == null) {
                        destructuring = HashMap()
                    }

                    if (expr is Assignment) {
                        // Default arguments inside destructured function parameters, as in
                        // f([x = 1] = [2]) { ... }, become:
                        // f(x) {
                        //      if ($1 == undefined)
                        //          var $1 = [2];
                        //      if (x == undefined)
                        //          if (($1[0]) == undefined)
                        //              var x = 1;
                        //          else
                        //              var x = $1[0];
                        // }
                        val lhs = expr.left!! // [x = 1]
                        val rhs = expr.right!! // [2]
                        markDestructuring(lhs)
                        fnNode.addParam(lhs)
                        val pname = currentScriptOrFn!!.getNextTempName()
                        defineSymbol(Token.LP, pname, false)
                        if (destructuringDefault == null) {
                            destructuringDefault = HashMap()
                        }
                        destructuring[pname] = lhs
                        destructuringDefault[pname] = rhs
                    } else {
                        markDestructuring(expr)
                        fnNode.addParam(expr)
                        // Destructuring assignment for parameters: add a dummy parameter name and
                        // a statement in the body that initializes the real variables from it.
                        val pname = currentScriptOrFn!!.getNextTempName()
                        defineSymbol(Token.LP, pname, false)
                        destructuring[pname] = expr
                    }
                } else {
                    var wasRest = false
                    var restStartLineno = -1
                    var restStartColumn = -1
                    if (tt == Token.DOTDOTDOT) {
                        if (fnNode.hasRestParameter) {
                            // Error: parameter after rest parameter.
                            reportError(
                                "msg.parm.after.rest",
                                ts.tokenBeg,
                                ts.tokenEnd - ts.tokenBeg,
                            )
                        }

                        fnNode.hasRestParameter = true
                        wasRest = true
                        consumeToken()
                        restStartLineno = lineNumber()
                        restStartColumn = columnNumber()
                    }

                    if (matchToken(Token.UNDEFINED, true) ||
                        mustMatchToken(Token.NAME, "msg.no.parm", true)
                    ) {
                        if (!wasRest && fnNode.hasRestParameter) {
                            // Error: parameter after rest parameter.
                            reportError(
                                "msg.parm.after.rest",
                                ts.tokenBeg,
                                ts.tokenEnd - ts.tokenBeg,
                            )
                        }

                        val paramNameNode = createNameNode()
                        if (wasRest) {
                            paramNameNode.setLineColumnNumber(restStartLineno, restStartColumn)
                        }
                        getAndResetJsDoc()?.let { paramNameNode.jsDocNode = it }
                        fnNode.addParam(paramNameNode)
                        val paramName = ts.string!!
                        defineSymbol(Token.LP, paramName)
                        if (this.inUseStrictDirective) {
                            if ("eval" == paramName || "arguments" == paramName) {
                                reportError("msg.bad.id.strict", paramName)
                            }
                            if (paramNames.contains(paramName)) {
                                addError("msg.dup.param.strict", paramName)
                            }
                            paramNames.add(paramName)
                        }

                        if (matchToken(Token.ASSIGN, true)) {
                            if (compilerEnv.languageVersion >= Context.VERSION_ES6) {
                                fnNode.putDefaultParams(paramName, assignExpr())
                            } else {
                                reportError("msg.default.args")
                            }
                        }
                    } else {
                        fnNode.addParam(makeErrorNode())
                    }
                }
            } while (matchToken(Token.COMMA, true))

            if (destructuring != null) {
                val destructuringNode = Node(Token.COMMA)
                // Add an assignment helper for each destructuring parameter.
                for ((key, value) in destructuring) {
                    val defaultValue = destructuringDefault?.get(key)
                    val assign =
                        createDestructuringAssignment(
                            Token.VAR,
                            value,
                            createName(key),
                            defaultValue,
                        )
                    destructuringNode.addChildToBack(assign)
                }
                fnNode.putProp(Node.DESTRUCTURING_PARAMS, destructuringNode)
            }

            if (mustMatchToken(Token.RP, "msg.no.paren.after.parms", true)) {
                fnNode.rp = ts.tokenBeg - fnNode.position
            }
        } finally {
            --nestingOfFunctionParams
        }
    }

    private fun function(type: Int): FunctionNode = function(type, false)

    private fun function(type: Int, isMethodDefiniton: Boolean): FunctionNode {
        var isGenerator = false
        var syntheticType = type
        val baseLineno = lineNumber() // line number where the source starts
        val functionSourceStart = ts.tokenBeg // start of the "function" keyword
        val functionStartColumn = columnNumber()
        var name: Name? = null
        var memberExprNode: AstNode? = null

        do {
            if (matchToken(Token.NAME, true) || matchToken(Token.UNDEFINED, true)) {
                name = createNameNode(true, Token.NAME)
                if (inUseStrictDirective) {
                    val id = name.identifier
                    if ("eval" == id || "arguments" == id) {
                        reportError("msg.bad.id.strict", id)
                    }
                }
                if (!matchToken(Token.LP, true)) {
                    if (compilerEnv.allowMemberExprAsFunctionName) {
                        val memberExprHead: AstNode = name
                        name = null
                        memberExprNode = memberExprTail(false, memberExprHead)
                    }
                    mustMatchToken(Token.LP, "msg.no.paren.parms", true)
                }
            } else if (matchToken(Token.LP, true)) {
                // Anonymous function: leave the name null.
            } else if (matchToken(Token.MUL, true) &&
                compilerEnv.languageVersion >= Context.VERSION_ES6
            ) {
                // ES6 generator function.
                isGenerator = true
                continue
            } else {
                if (compilerEnv.allowMemberExprAsFunctionName) {
                    // memberExpr cannot start with '(' as in function (1+2).toString(), because
                    // "function (" has already been processed as an anonymous function.
                    memberExprNode = memberExpr(false)
                }
                mustMatchToken(Token.LP, "msg.no.paren.parms", true)
            }
            break
        } while (isGenerator)
        val lpPos = if (currentToken == Token.LP) ts.tokenBeg else -1

        if (memberExprNode != null) {
            syntheticType = FunctionNode.FUNCTION_EXPRESSION
        }

        if (syntheticType != FunctionNode.FUNCTION_EXPRESSION &&
            name != null &&
            name.length() > 0
        ) {
            // Function statements define a symbol in the enclosing scope.
            defineSymbol(Token.FUNCTION, name.identifier!!)
        }

        val fnNode = FunctionNode(functionSourceStart, name)
        fnNode.isMethodDefinition = isMethodDefiniton
        fnNode.functionType = type
        if (isGenerator) {
            fnNode.isES6Generator = true
        }
        if (lpPos != -1) fnNode.lp = lpPos - functionSourceStart

        fnNode.jsDocNode = getAndResetJsDoc()

        val savedVars = PerFunctionVariables(fnNode)
        val wasInsideMethod = insideMethod
        insideMethod = isMethodDefiniton
        try {
            parseFunctionParams(fnNode)
            val body = parseFunctionBody(type, fnNode)
            fnNode.body = body
            val end = functionSourceStart + body.position + body.length
            fnNode.setRawSourceBounds(functionSourceStart, end)
            fnNode.length = end - functionSourceStart

            if (compilerEnv.strictMode && !fnNode.body!!.hasConsistentReturnUsage()) {
                val msg =
                    if (name != null && name.length() > 0) {
                        "msg.no.return.value"
                    } else {
                        "msg.anon.no.return.value"
                    }
                addStrictWarning(msg, if (name == null) "" else name.identifier)
            }
        } finally {
            savedVars.restore()
            insideMethod = wasInsideMethod
        }

        if (memberExprNode != null) {
            // TODO(stevey): fix missing functionality
            Kit.codeBug()
        }

        fnNode.sourceName = sourceURI
        fnNode.setLineColumnNumber(baseLineno, functionStartColumn)
        fnNode.endLineno = lineNumber()

        // Set the parent scope. This has to wait until after the function is parsed, because
        // defineSymbol needs the defining-scope check to stop at the function boundary while
        // looking for redeclarations.
        if (compilerEnv.ideMode) {
            fnNode.parentScope = currentScope
        }
        return fnNode
    }

    private fun arrowFunction(params: AstNode?, startLine: Int, startColumn: Int): AstNode {
        val baseLineno = lineNumber() // line number where the source starts
        val functionSourceStart = params?.position ?: -1

        val fnNode = FunctionNode(functionSourceStart)
        fnNode.functionType = FunctionNode.ARROW_FUNCTION
        fnNode.jsDocNode = getAndResetJsDoc()

        // Would prefer to defer createDestructuringAssignment to codegen, but the symbol
        // definitions have to happen now, before the body is parsed.
        val destructuring = HashMap<String, Node>()
        val destructuringDefault = HashMap<String, AstNode>()
        val paramNames = HashSet<String>()

        val savedVars = PerFunctionVariables(fnNode)
        // Intentionally not overwriting insideMethod: it propagates from the enclosing function.
        try {
            if (params is ParenthesizedExpression) {
                fnNode.setParens(0, params.length)
                if (params.getIntProp(Node.TRAILING_COMMA, 0) == 1) {
                    fnNode.putIntProp(Node.TRAILING_COMMA, 1)
                }
                val p = params.expression
                if (p !is EmptyExpression) {
                    arrowFunctionParams(fnNode, p, destructuring, destructuringDefault, paramNames)
                }
            } else {
                arrowFunctionParams(fnNode, params, destructuring, destructuringDefault, paramNames)
            }

            if (destructuring.isNotEmpty()) {
                val destructuringNode = Node(Token.COMMA)
                // Add an assignment helper for each destructuring parameter.
                for ((key, value) in destructuring) {
                    val defaultValue = destructuringDefault[key]
                    val assign =
                        createDestructuringAssignment(
                            Token.VAR,
                            value,
                            createName(key),
                            defaultValue,
                        )
                    destructuringNode.addChildToBack(assign)
                }
                fnNode.putProp(Node.DESTRUCTURING_PARAMS, destructuringNode)
            }

            val body = parseFunctionBody(FunctionNode.ARROW_FUNCTION, fnNode)
            fnNode.body = body
            val end = functionSourceStart + body.position + body.length
            fnNode.setRawSourceBounds(functionSourceStart, end)
            fnNode.length = end - functionSourceStart
        } finally {
            savedVars.restore()
        }

        if (fnNode.isGenerator) {
            reportError("msg.arrowfunction.generator")
            return makeErrorNode()
        }

        fnNode.sourceName = sourceURI
        fnNode.baseLineno = baseLineno
        fnNode.endLineno = lineNumber()
        fnNode.setLineColumnNumber(startLine, startColumn)

        return fnNode
    }

    private fun arrowFunctionParams(
        fnNode: FunctionNode,
        params: AstNode?,
        destructuring: MutableMap<String, Node>,
        destructuringDefault: MutableMap<String, AstNode>,
        paramNames: MutableSet<String>,
    ) {
        if (params is ArrayLiteral || params is ObjectLiteral) {
            markDestructuring(params)
            fnNode.addParam(params)
            val pname = currentScriptOrFn!!.getNextTempName()
            defineSymbol(Token.LP, pname, false)
            destructuring[pname] = params
        } else if (params is InfixExpression && params.type == Token.COMMA) {
            arrowFunctionParams(
                fnNode,
                params.left,
                destructuring,
                destructuringDefault,
                paramNames,
            )
            arrowFunctionParams(
                fnNode,
                params.right,
                destructuring,
                destructuringDefault,
                paramNames,
            )
        } else if (params is Name) {
            fnNode.addParam(params)
            val paramName = params.identifier!!
            defineSymbol(Token.LP, paramName)

            if (this.inUseStrictDirective) {
                if ("eval" == paramName || "arguments" == paramName) {
                    reportError("msg.bad.id.strict", paramName)
                }
                if (paramNames.contains(paramName)) addError("msg.dup.param.strict", paramName)
                paramNames.add(paramName)
            }
        } else if (params is Assignment) {
            if (compilerEnv.languageVersion >= Context.VERSION_ES6) {
                val rhs = params.right!!
                val lhs = params.left!!

                // Copy the default values for use in the IR.
                if (lhs is Name) {
                    val paramName = lhs.identifier!!
                    fnNode.putDefaultParams(paramName, rhs)
                    arrowFunctionParams(
                        fnNode,
                        lhs,
                        destructuring,
                        destructuringDefault,
                        paramNames,
                    )
                } else if (lhs is ArrayLiteral || lhs is ObjectLiteral) {
                    markDestructuring(lhs)
                    fnNode.addParam(lhs)
                    val pname = currentScriptOrFn!!.getNextTempName()
                    defineSymbol(Token.LP, pname, false)
                    destructuring[pname] = lhs
                    destructuringDefault[pname] = rhs
                } else {
                    reportError("msg.no.parm", params.position, params.length)
                    fnNode.addParam(makeErrorNode())
                }
            } else {
                reportError("msg.default.args")
            }
        } else {
            reportError("msg.no.parm", params!!.position, params.length)
            fnNode.addParam(makeErrorNode())
        }
    }

    /**
     * Does not match the closing RC: the caller matches it so it can produce a suitable error
     * message. That means the caller also sets the node length to include the closing RC. The
     * node start is an absolute buffer position, which the caller fixes up to be relative to the
     * parent. Every child gets a relative start position and a correct length.
     */
    private fun statements(parent: AstNode?): AstNode {
        if (currentToken != Token.LC && !compilerEnv.ideMode) {
            // The assertion can be invalid in bad code.
            codeBug()
        }
        val pos = ts.tokenBeg
        val block = parent ?: Block(pos)
        block.setLineColumnNumber(lineNumber(), columnNumber())

        var tt = peekToken()
        while (tt > Token.EOF && tt != Token.RC) {
            block.addChild(statement())
            tt = peekToken()
        }
        block.length = ts.tokenBeg - pos
        return block
    }

    private fun statements(): AstNode = statements(null)

    private class ConditionData {
        var condition: AstNode? = null
        var lp = -1
        var rp = -1
    }

    // Parse and return a parenthesized expression.
    private fun condition(): ConditionData {
        val data = ConditionData()

        if (mustMatchToken(Token.LP, "msg.no.paren.cond", true)) data.lp = ts.tokenBeg

        data.condition = expr(false)

        if (mustMatchToken(Token.RP, "msg.no.paren.after.cond", true)) data.rp = ts.tokenBeg

        // Strict warning on code like "if (a = 7) ...", suppressed when the condition is
        // parenthesized as in "if ((a = 7)) ...".
        val condition = data.condition!!
        if (condition is Assignment) {
            addStrictWarning("msg.equal.as.assign", "", condition.position, condition.length)
        }
        return data
    }

    private fun statement(): AstNode {
        val pos = ts.tokenBeg
        try {
            val pn = statementHelper()
            if (pn != null) {
                if (compilerEnv.strictMode && !pn.hasSideEffects()) {
                    var beg = pn.position
                    beg = maxOf(beg, lineBeginningFor(beg))
                    addStrictWarning(
                        if (pn is EmptyStatement) {
                            "msg.extra.trailing.semi"
                        } else {
                            "msg.no.side.effects"
                        },
                        "",
                        beg,
                        nodeEnd(pn) - beg,
                    )
                }
                val ntt = peekToken()
                if (ntt == Token.COMMENT && pn.lineno == lastScannedComment().lineno) {
                    pn.inlineComment = lastScannedComment()
                    consumeToken()
                }
                return pn
            }
        } catch (e: ParserException) {
            // An ErrorNode was added to the ErrorReporter.
        }

        // Error: skip ahead to a probable statement boundary.
        guessingStatementEnd@ while (true) {
            val tt = peekTokenOrEOL()
            consumeToken()
            when (tt) {
                Token.ERROR, Token.EOF, Token.EOL, Token.SEMI -> break@guessingStatementEnd
            }
        }
        // Error nodes are not made part of the tree; they are reported to the ErrorReporter.
        return EmptyStatement(pos, ts.tokenBeg - pos)
    }

    private fun statementHelper(): AstNode? {
        // If the statement is set, it has been told its label by now.
        if (currentLabel != null && currentLabel!!.statement != null) currentLabel = null

        var pn: AstNode? = null
        val tt = peekToken()
        var pos = ts.tokenBeg
        val lineno: Int
        val column: Int

        when (tt) {
            Token.IF -> return ifStatement()

            Token.SWITCH -> return switchStatement()

            Token.WHILE -> return whileLoop()

            Token.DO -> return doLoop()

            Token.FOR -> return forLoop()

            Token.TRY -> return tryStatement()

            Token.THROW -> pn = throwStatement()

            Token.BREAK -> pn = breakStatement()

            Token.CONTINUE -> pn = continueStatement()

            Token.WITH -> {
                if (this.inUseStrictDirective) {
                    reportError("msg.no.with.strict")
                }
                return withStatement()
            }

            Token.CONST, Token.VAR -> {
                consumeToken()
                lineno = lineNumber()
                column = columnNumber()
                pn = variables(currentToken, ts.tokenBeg, true)
                pn.setLineColumnNumber(lineno, column)
            }

            Token.LET -> {
                pn = letStatement()
                if (!(pn is VariableDeclaration && peekToken() == Token.SEMI)) {
                    return pn
                }
            }

            Token.RETURN, Token.YIELD -> pn = returnOrYield(tt, false)

            Token.DEBUGGER -> {
                consumeToken()
                pn = KeywordLiteral(ts.tokenBeg, ts.tokenEnd - ts.tokenBeg, tt)
                pn.setLineColumnNumber(lineNumber(), columnNumber())
            }

            Token.LC -> return block()

            Token.ERROR -> {
                consumeToken()
                return makeErrorNode()
            }

            Token.SEMI -> {
                consumeToken()
                pos = ts.tokenBeg
                val empty = EmptyStatement(pos, ts.tokenEnd - pos)
                empty.setLineColumnNumber(lineNumber(), columnNumber())
                return empty
            }

            Token.FUNCTION -> {
                consumeToken()
                return function(FunctionNode.FUNCTION_EXPRESSION_STATEMENT)
            }

            Token.DEFAULT -> pn = defaultXmlNamespace()

            Token.NAME -> {
                pn = nameOrLabel()
                if (pn !is ExpressionStatement) {
                    return pn // LabeledStatement
                }
            }

            Token.COMMENT -> {
                // Do not consume the token here.
                return lastScannedComment()
            }

            else -> {
                // Intentionally not calling lineNumber/columnNumber here: no token has been
                // consumed yet, so the position would be wrong.
                lineno = ts.lineno
                column = ts.tokenColumn
                pn = ExpressionStatement(expr(false), !insideFunctionBody())
                pn.setLineColumnNumber(lineno, column)
            }
        }

        autoInsertSemicolon(pn)
        return pn
    }

    private fun autoInsertSemicolon(pn: AstNode) {
        val ttFlagged = peekFlaggedToken()
        val pos = pn.position
        when (ttFlagged and CLEAR_TI_MASK) {
            Token.SEMI -> {
                // Consume ';' as part of the expression.
                consumeToken()
                // Extend the node bounds to include the semicolon.
                pn.length = ts.tokenEnd - pos
            }
            Token.ERROR, Token.EOF, Token.RC -> {
                // Auto-insert the semicolon. Token.EOF can produce a negative length and a
                // negative nodeEnd, so keep the end at least pos + 1.
                warnMissingSemi(pos, maxOf(pos + 1, nodeEnd(pn)))
            }
            else -> {
                if ((ttFlagged and TI_AFTER_EOL) == 0) {
                    // Report an error when there is no EOL, otherwise auto-insert.
                    reportError("msg.no.semi.stmt")
                } else {
                    warnMissingSemi(pos, nodeEnd(pn))
                }
            }
        }
    }

    private fun ifStatement(): IfStatement {
        if (currentToken != Token.IF) codeBug()
        consumeToken()
        val pos = ts.tokenBeg
        val lineno = lineNumber()
        val column = columnNumber()
        var elsePos = -1
        val pn = IfStatement(pos)
        val data = condition()
        val ifTrue = getNextStatementAfterInlineComments(pn)
        var ifFalse: AstNode? = null
        if (matchToken(Token.ELSE, true)) {
            if (peekToken() == Token.COMMENT) {
                pn.elseKeyWordInlineComment = lastScannedComment()
                consumeToken()
            }
            elsePos = ts.tokenBeg - pos
            ifFalse = statement()
        }
        val end = getNodeEnd(ifFalse ?: ifTrue)
        pn.length = end - pos
        pn.condition = data.condition
        pn.setParens(data.lp - pos, data.rp - pos)
        pn.thenPart = ifTrue
        pn.elsePart = ifFalse
        pn.elsePosition = elsePos
        pn.setLineColumnNumber(lineno, column)
        return pn
    }

    private fun switchStatement(): SwitchStatement {
        if (currentToken != Token.SWITCH) codeBug()
        consumeToken()
        val pos = ts.tokenBeg

        val pn = SwitchStatement(pos)
        pn.setLineColumnNumber(lineNumber(), columnNumber())
        pushScope(pn)
        try {
            if (mustMatchToken(Token.LP, "msg.no.paren.switch", true)) pn.lp = ts.tokenBeg - pos

            val discriminant = expr(false)
            pn.expression = discriminant
            enterSwitch(pn)

            try {
                if (mustMatchToken(Token.RP, "msg.no.paren.after.switch", true)) {
                    pn.rp = ts.tokenBeg - pos
                }

                mustMatchToken(Token.LC, "msg.no.brace.switch", true)

                var hasDefault = false
                switchLoop@ while (true) {
                    var tt = nextToken()
                    val casePos = ts.tokenBeg
                    val caseLineno = lineNumber()
                    val caseColumn = columnNumber()
                    var caseExpression: AstNode? = null
                    when (tt) {
                        Token.RC -> {
                            pn.length = ts.tokenEnd - pos
                            break@switchLoop
                        }

                        Token.CASE -> {
                            caseExpression = expr(false)
                            mustMatchToken(Token.COLON, "msg.no.colon.case", true)
                        }

                        Token.DEFAULT -> {
                            if (hasDefault) {
                                reportError("msg.double.switch.default")
                            }
                            hasDefault = true
                            mustMatchToken(Token.COLON, "msg.no.colon.case", true)
                        }

                        Token.COMMENT -> {
                            pn.addChild(lastScannedComment())
                            continue@switchLoop
                        }

                        else -> {
                            reportError("msg.bad.switch")
                            break@switchLoop
                        }
                    }

                    val caseNode = SwitchCase(casePos)
                    caseNode.expression = caseExpression
                    caseNode.length = ts.tokenEnd - pos // include the colon
                    caseNode.setLineColumnNumber(caseLineno, caseColumn)

                    tt = peekToken()
                    while (tt != Token.RC &&
                        tt != Token.CASE &&
                        tt != Token.DEFAULT &&
                        tt != Token.EOF
                    ) {
                        if (tt == Token.COMMENT) {
                            val inlineComment = lastScannedComment()
                            if (caseNode.inlineComment == null &&
                                inlineComment.lineno == caseNode.lineno
                            ) {
                                caseNode.inlineComment = inlineComment
                            } else {
                                caseNode.addStatement(inlineComment)
                            }
                            consumeToken()
                            tt = peekToken()
                            continue
                        }
                        caseNode.addStatement(statement()) // updates the length
                        tt = peekToken()
                    }
                    pn.addCase(caseNode)
                }
            } finally {
                exitSwitch()
            }
            return pn
        } finally {
            popScope()
        }
    }

    private fun whileLoop(): WhileLoop {
        if (currentToken != Token.WHILE) codeBug()
        consumeToken()
        val pos = ts.tokenBeg
        val pn = WhileLoop(pos)
        pn.setLineColumnNumber(lineNumber(), columnNumber())
        enterLoop(pn)
        try {
            val data = condition()
            pn.condition = data.condition
            pn.setParens(data.lp - pos, data.rp - pos)
            val body = getNextStatementAfterInlineComments(pn)
            pn.length = getNodeEnd(body) - pos
            restoreRelativeLoopPosition(pn)
            pn.body = body
        } finally {
            exitLoop()
        }
        return pn
    }

    private fun doLoop(): DoLoop {
        if (currentToken != Token.DO) codeBug()
        consumeToken()
        val pos = ts.tokenBeg
        var end: Int
        val pn = DoLoop(pos)
        pn.setLineColumnNumber(lineNumber(), columnNumber())
        enterLoop(pn)
        try {
            val body = getNextStatementAfterInlineComments(pn)
            mustMatchToken(Token.WHILE, "msg.no.while.do", true)
            pn.whilePosition = ts.tokenBeg - pos
            val data = condition()
            pn.condition = data.condition
            pn.setParens(data.lp - pos, data.rp - pos)
            end = getNodeEnd(body)
            restoreRelativeLoopPosition(pn)
            pn.body = body
        } finally {
            exitLoop()
        }
        // Always auto-insert the semicolon, following SpiderMonkey: ECMAScript requires it but
        // the rest of the world ignores it. See bug 238945.
        if (matchToken(Token.SEMI, true)) {
            end = ts.tokenEnd
        }
        pn.length = end - pos
        return pn
    }

    private fun peekUntilNonComment(tt: Int): Int {
        var t = tt
        while (t == Token.COMMENT) {
            consumeToken()
            t = peekToken()
        }
        return t
    }

    private fun getNextStatementAfterInlineComments(pn: AstNode?): AstNode {
        var body = statement()
        if (Token.COMMENT == body.type) {
            val commentNode = body
            body = statement()
            if (pn != null) {
                pn.inlineComment = commentNode
            } else {
                body.inlineComment = commentNode
            }
        }
        return body
    }

    private fun forLoop(): Loop {
        if (currentToken != Token.FOR) codeBug()
        consumeToken()
        val forPos = ts.tokenBeg
        val lineno = lineNumber()
        val column = columnNumber()
        var isForEach = false
        var isForIn = false
        var isForOf = false
        var eachPos = -1
        var inPos = -1
        var lp = -1
        var rp = -1
        val init: AstNode? // init is also foo in 'foo in object'
        var cond: AstNode? = null // cond is also object in 'foo in object'
        var incr: AstNode? = null
        val pn: Loop

        val tempScope = Scope()
        pushScope(tempScope) // decide below which AST class to use
        try {
            // See whether this is a "for each ()" rather than just a "for ()".
            if (matchToken(Token.NAME, true)) {
                if ("each" == ts.string) {
                    isForEach = true
                    eachPos = ts.tokenBeg - forPos
                } else {
                    reportError("msg.no.paren.for")
                }
            }

            if (mustMatchToken(Token.LP, "msg.no.paren.for", true)) lp = ts.tokenBeg - forPos
            val tt = peekToken()

            init = forLoopInit(tt)
            if (matchToken(Token.IN, true)) {
                isForIn = true
                inPos = ts.tokenBeg - forPos
                markDestructuring(init)
                cond = expr(false) // object being iterated
            } else if (compilerEnv.languageVersion >= Context.VERSION_ES6 &&
                matchToken(Token.NAME, true) &&
                "of" == ts.string
            ) {
                isForOf = true
                inPos = ts.tokenBeg - forPos
                markDestructuring(init)
                cond = expr(false) // object being iterated
            } else { // ordinary for loop
                // In an ordinary for loop a destructuring declaration must have an initializer.
                if (init is VariableDeclaration) {
                    for (vi in init.variables) {
                        if (vi.isDestructuring && vi.initializer == null) {
                            reportError("msg.destruct.assign.no.init")
                        }
                    }
                }

                mustMatchToken(Token.SEMI, "msg.no.semi.for", true)
                if (peekToken() == Token.SEMI) {
                    // No loop condition.
                    cond = EmptyExpression(ts.tokenBeg, 1)
                    // The token is not consumed, so use the CURRENT lexer position.
                    cond.setLineColumnNumber(ts.lineno, ts.tokenColumn)
                } else {
                    cond = expr(false)
                }

                mustMatchToken(Token.SEMI, "msg.no.semi.for.cond", true)
                val tmpPos = ts.tokenEnd
                if (peekToken() == Token.RP) {
                    incr = EmptyExpression(tmpPos, 1)
                    // The token is not consumed, so use the CURRENT lexer position.
                    incr.setLineColumnNumber(ts.lineno, ts.tokenColumn)
                } else {
                    incr = expr(false)
                }
            }

            if (mustMatchToken(Token.RP, "msg.no.paren.for.ctrl", true)) rp = ts.tokenBeg - forPos

            if (isForIn || isForOf) {
                val fis = ForInLoop(forPos)
                if (init is VariableDeclaration) {
                    // Check that only one variable was given.
                    if (init.variables.size > 1) {
                        reportError("msg.mult.index")
                    }
                }
                if (isForOf && isForEach) {
                    reportError("msg.invalid.for.each")
                }
                fis.iterator = init
                fis.iteratedObject = cond
                fis.inPosition = inPos
                fis.isForEach = isForEach
                fis.eachPosition = eachPos
                fis.isForOf = isForOf
                pn = fis
            } else {
                val fl = ForLoop(forPos)
                fl.initializer = init
                fl.condition = cond
                fl.increment = incr
                pn = fl
            }

            // Replace the temp scope with the new loop object.
            currentScope!!.replaceWith(pn)
            popScope()

            // The body has to be parsed after the loop node exists, so that the node is in
            // loopSet and break/continue can find the enclosing loop.
            enterLoop(pn)
            try {
                val body = getNextStatementAfterInlineComments(pn)
                pn.length = getNodeEnd(body) - forPos
                restoreRelativeLoopPosition(pn)
                pn.body = body
            } finally {
                exitLoop()
            }
        } finally {
            if (currentScope === tempScope) {
                popScope()
            }
        }
        pn.setParens(lp, rp)
        pn.setLineColumnNumber(lineno, column)
        return pn
    }

    private fun forLoopInit(tt: Int): AstNode {
        try {
            inForInit = true // checked by variables() and relExpr()
            val init: AstNode
            if (tt == Token.SEMI) {
                init = EmptyExpression(ts.tokenBeg, 1)
                // The token is not consumed, so use the CURRENT lexer position.
                init.setLineColumnNumber(ts.lineno, ts.tokenColumn)
            } else if (tt == Token.VAR || tt == Token.LET) {
                consumeToken()
                init = variables(tt, ts.tokenBeg, false)
            } else {
                init = expr(false)
            }
            return init
        } finally {
            inForInit = false
        }
    }

    private fun tryStatement(): TryStatement {
        if (currentToken != Token.TRY) codeBug()
        consumeToken()

        // Pull out the JSDoc info and reset it before recursing.
        val jsdocNode = getAndResetJsDoc()

        val tryPos = ts.tokenBeg
        val lineno = lineNumber()
        val column = columnNumber()
        var finallyPos = -1

        val pn = TryStatement(tryPos)
        // Comments are handled here because there cannot be a try without an LC.
        var lctt = peekToken()
        while (lctt == Token.COMMENT) {
            pn.inlineComment = lastScannedComment()
            consumeToken()
            lctt = peekToken()
        }
        if (lctt != Token.LC) {
            reportError("msg.no.brace.try")
        }
        val tryBlock = getNextStatementAfterInlineComments(pn)
        var tryEnd = getNodeEnd(tryBlock)

        var clauses: MutableList<CatchClause>? = null

        var sawDefaultCatch = false
        var peek = peekToken()
        while (peek == Token.COMMENT) {
            pn.inlineComment = lastScannedComment()
            consumeToken()
            peek = peekToken()
        }

        val previous = hasUndefinedBeenRedefined
        if (peek == Token.CATCH) {
            while (matchToken(Token.CATCH, true)) {
                if (sawDefaultCatch) {
                    reportError("msg.catch.unreachable")
                }
                val catchPos = ts.tokenBeg
                var lp = -1
                var rp = -1
                var guardPos = -1
                val catchLine = lineNumber()
                val catchColumn = columnNumber()
                var varName: AstNode? = null
                var catchCond: AstNode? = null

                when (peekToken()) {
                    Token.LP -> {
                        matchToken(Token.LP, true)
                        lp = ts.tokenBeg

                        val tt = peekToken()
                        if (tt == Token.LB || tt == Token.LC) {
                            // Destructuring pattern.
                            if (compilerEnv.languageVersion >= Context.VERSION_ES6) {
                                varName = destructuringPrimaryExpr()
                                markDestructuring(varName)
                            } else {
                                reportError("msg.catch.destructuring.requires.es6")
                            }
                        } else {
                            // Simple identifier.
                            if (!matchToken(Token.UNDEFINED, true)) {
                                mustMatchToken(Token.NAME, "msg.bad.catchcond", true)
                            }

                            varName = createNameNode()
                            getAndResetJsDoc()?.let { varName.jsDocNode = it }
                            val varNameString = varName.identifier
                            if ("undefined" == varNameString) {
                                hasUndefinedBeenRedefined = true
                            }
                            if (inUseStrictDirective) {
                                if ("eval" == varNameString || "arguments" == varNameString) {
                                    reportError("msg.bad.id.strict", varNameString)
                                }
                            }
                        }

                        // Non-standard extension: "catch (e if cond)" is supported.
                        if (varName is Name && matchToken(Token.IF, true)) {
                            guardPos = ts.tokenBeg
                            catchCond = expr(false)
                        } else {
                            sawDefaultCatch = true
                        }

                        if (mustMatchToken(Token.RP, "msg.bad.catchcond", true)) {
                            rp = ts.tokenBeg
                        }
                        mustMatchToken(Token.LC, "msg.no.brace.catchblock", true)
                    }

                    Token.LC -> {
                        if (compilerEnv.languageVersion >= Context.VERSION_ES6) {
                            matchToken(Token.LC, true)
                        } else {
                            reportError("msg.no.paren.catch")
                        }
                    }

                    else -> reportError("msg.no.paren.catch")
                }

                val catchScope = Scope(catchPos)
                val catchNode = CatchClause(catchPos)
                catchNode.setLineColumnNumber(catchLine, catchColumn)
                pushScope(catchScope)
                try {
                    statements(catchScope)
                } finally {
                    hasUndefinedBeenRedefined = previous
                    popScope()
                }

                tryEnd = getNodeEnd(catchScope)
                catchNode.varName = varName
                catchNode.catchCondition = catchCond
                catchNode.body = catchScope
                if (guardPos != -1) {
                    catchNode.ifPosition = guardPos - catchPos
                }
                catchNode.setParens(lp, rp)

                if (mustMatchToken(Token.RC, "msg.no.brace.after.body", true)) tryEnd = ts.tokenEnd
                catchNode.length = tryEnd - catchPos
                val list = clauses ?: mutableListOf<CatchClause>().also { clauses = it }
                list.add(catchNode)
            }
        } else if (peek != Token.FINALLY) {
            mustMatchToken(Token.FINALLY, "msg.try.no.catchfinally", true)
        }

        var finallyBlock: AstNode? = null
        if (matchToken(Token.FINALLY, true)) {
            finallyPos = ts.tokenBeg
            finallyBlock = statement()
            tryEnd = getNodeEnd(finallyBlock)
        }

        pn.length = tryEnd - tryPos
        pn.tryBlock = tryBlock
        pn.setCatchClauses(clauses)
        pn.finallyBlock = finallyBlock
        if (finallyPos != -1) {
            pn.finallyPosition = finallyPos - tryPos
        }
        pn.setLineColumnNumber(lineno, column)

        if (jsdocNode != null) {
            pn.jsDocNode = jsdocNode
        }

        return pn
    }

    private fun throwStatement(): ThrowStatement {
        if (currentToken != Token.THROW) codeBug()
        consumeToken()
        val pos = ts.tokenBeg
        val lineno = lineNumber()
        val column = columnNumber()
        if (peekTokenOrEOL() == Token.EOL) {
            // ECMAScript does not allow a newline before the throw expression, see bug 256617.
            reportError("msg.bad.throw.eol")
        }
        val expr = expr(false)
        val pn = ThrowStatement(pos, expr)
        pn.setLineColumnNumber(lineno, column)
        return pn
    }

    /**
     * Matches a NAME, consumes it and returns the statement carrying that label. Reports an error
     * when the name does not match an existing label. Returns null when the peeked token was not
     * a name. Side effect: sets the scanner token info for the label identifier.
     */
    private fun matchJumpLabelName(): LabeledStatement? {
        var label: LabeledStatement? = null

        if (peekTokenOrEOL() == Token.NAME) {
            consumeToken()
            label = labelSet?.get(ts.string)
            if (label == null) {
                reportError("msg.undef.label")
            }
        }

        return label
    }

    private fun breakStatement(): BreakStatement {
        if (currentToken != Token.BREAK) codeBug()
        consumeToken()
        val lineno = lineNumber()
        val pos = ts.tokenBeg
        var end = ts.tokenEnd
        val column = columnNumber()
        var breakLabel: Name? = null
        if (peekTokenOrEOL() == Token.NAME) {
            breakLabel = createNameNode()
            end = getNodeEnd(breakLabel)
        }

        // matchJumpLabelName only matches when there is one.
        val labels = matchJumpLabelName()
        // Always use the first label as the target.
        var breakTarget: Jump? = labels?.firstLabel

        if (breakTarget == null && breakLabel == null) {
            val set = loopAndSwitchSet
            if (set == null || set.size == 0) {
                reportError("msg.bad.break", pos, end - pos)
            } else {
                breakTarget = set[set.size - 1]
            }
        }

        breakLabel?.setLineColumnNumber(lineNumber(), columnNumber())

        val pn = BreakStatement(pos, end - pos)
        pn.breakLabel = breakLabel
        // Can be null for a bad break in error-recovery mode.
        if (breakTarget != null) pn.setBreakTarget(breakTarget)
        pn.setLineColumnNumber(lineno, column)
        return pn
    }

    private fun continueStatement(): ContinueStatement {
        if (currentToken != Token.CONTINUE) codeBug()
        consumeToken()
        val lineno = lineNumber()
        val pos = ts.tokenBeg
        var end = ts.tokenEnd
        val column = columnNumber()
        var label: Name? = null
        if (peekTokenOrEOL() == Token.NAME) {
            label = createNameNode()
            end = getNodeEnd(label)
        }

        // matchJumpLabelName only matches when there is one.
        val labels = matchJumpLabelName()
        var target: Loop? = null
        if (labels == null && label == null) {
            val set = loopSet
            if (set == null || set.size == 0) {
                reportError("msg.continue.outside")
            } else {
                target = set[set.size - 1]
            }
        } else {
            if (labels == null || labels.statement !is Loop) {
                reportError("msg.continue.nonloop", pos, end - pos)
            }
            target = labels?.statement as Loop?
        }

        label?.setLineColumnNumber(lineNumber(), columnNumber())

        val pn = ContinueStatement(pos, end - pos)
        // Can be null in error-recovery mode.
        if (target != null) pn.targetLoop = target
        pn.label = label
        pn.setLineColumnNumber(lineno, column)
        return pn
    }

    private fun withStatement(): WithStatement {
        if (currentToken != Token.WITH) codeBug()
        consumeToken()

        val withComment = getAndResetJsDoc()

        val lineno = lineNumber()
        val column = columnNumber()
        val pos = ts.tokenBeg
        var lp = -1
        var rp = -1
        if (mustMatchToken(Token.LP, "msg.no.paren.with", true)) lp = ts.tokenBeg

        val obj = expr(false)

        if (mustMatchToken(Token.RP, "msg.no.paren.after.with", true)) rp = ts.tokenBeg

        val pn = WithStatement(pos)

        val previous = hasUndefinedBeenRedefined
        try {
            hasUndefinedBeenRedefined = true
            val body = getNextStatementAfterInlineComments(pn)

            pn.length = getNodeEnd(body) - pos
            pn.jsDocNode = withComment
            pn.expression = obj
            pn.statement = body
            pn.setParens(lp, rp)
            pn.setLineColumnNumber(lineno, column)
        } finally {
            hasUndefinedBeenRedefined = previous
        }

        return pn
    }

    private fun letStatement(): AstNode {
        if (currentToken != Token.LET) codeBug()
        consumeToken()
        val lineno = lineNumber()
        val pos = ts.tokenBeg
        val column = columnNumber()
        val pn: AstNode = if (peekToken() == Token.LP) {
            let(true, pos)
        } else {
            variables(Token.LET, pos, true) // else, e.g.: let x=6, y=7;
        }
        pn.setLineColumnNumber(lineno, column)
        return pn
    }

    private fun returnOrYield(tt: Int, exprContext: Boolean): AstNode {
        if (!insideFunctionBody()) {
            reportError(if (tt == Token.RETURN) "msg.bad.return" else "msg.bad.yield")
        }
        consumeToken()
        val lineno = lineNumber()
        val column = columnNumber()
        val pos = ts.tokenBeg
        var end = ts.tokenEnd

        var yieldStar = false
        if (tt == Token.YIELD &&
            compilerEnv.languageVersion >= Context.VERSION_ES6 &&
            peekToken() == Token.MUL
        ) {
            yieldStar = true
            consumeToken()
        }

        var e: AstNode? = null
        // Ugly, but a semicolon must not be required here.
        when (val next = peekTokenOrEOL()) {
            Token.SEMI, Token.RC, Token.RB, Token.RP, Token.EOF, Token.EOL, Token.ERROR -> {}
            else -> {
                // Take extra care to preserve language compatibility for a bare "yield".
                if (!(next == Token.YIELD &&
                        compilerEnv.languageVersion < Context.VERSION_ES6)
                ) {
                    e = expr(false)
                    end = getNodeEnd(e)
                }
            }
        }

        val before = endFlags
        var ret: AstNode

        if (tt == Token.RETURN) {
            endFlags = endFlags or (if (e == null) Node.END_RETURNS else Node.END_RETURNS_VALUE)
            ret = ReturnStatement(pos, end - pos, e)

            // See whether a strict-mode warning is needed.
            if (nowAllSet(before, endFlags, Node.END_RETURNS or Node.END_RETURNS_VALUE)) {
                addStrictWarning("msg.return.inconsistent", "", pos, end - pos)
            }
        } else {
            if (!insideFunctionBody()) reportError("msg.bad.yield")
            endFlags = endFlags or Node.END_YIELDS
            ret = Yield(pos, end - pos, e, yieldStar)
            setRequiresActivation()
            setIsGenerator()
            if (!exprContext) {
                ret.setLineColumnNumber(lineno, column)
                ret = ExpressionStatement(ret)
            }
        }

        // See whether yields and value returns are being mixed.
        if (insideFunctionBody() &&
            nowAllSet(before, endFlags, Node.END_YIELDS or Node.END_RETURNS_VALUE)
        ) {
            val fn = currentScriptOrFn as FunctionNode
            if (!fn.isES6Generator) {
                val name = fn.functionName
                if (name == null || name.length() == 0) {
                    addError("msg.anon.generator.returns", "")
                } else {
                    addError("msg.generator.returns", name.identifier)
                }
            }
        }

        ret.setLineColumnNumber(lineno, column)
        return ret
    }

    private fun block(): AstNode {
        if (currentToken != Token.LC) codeBug()
        consumeToken()
        val pos = ts.tokenBeg
        val block = Scope(pos)
        block.setLineColumnNumber(lineNumber(), columnNumber())
        pushScope(block)
        try {
            statements(block)
            mustMatchToken(Token.RC, "msg.no.brace.block", true)
            block.length = ts.tokenEnd - pos
            return block
        } finally {
            popScope()
        }
    }

    /**
     * KMP: E4X is out of scope, so this reports "XML not available" through [mustHaveXML] and
     * never parses a `default xml namespace` declaration.
     */
    private fun defaultXmlNamespace(): AstNode {
        if (currentToken != Token.DEFAULT) codeBug()
        consumeToken()
        mustHaveXML()
        setRequiresActivation()
        val lineno = lineNumber()
        val column = columnNumber()
        val pos = ts.tokenBeg

        if (!(matchToken(Token.NAME, true) && "xml" == ts.string)) {
            reportError("msg.bad.namespace")
        }
        if (!(matchToken(Token.NAME, true) && "namespace" == ts.string)) {
            reportError("msg.bad.namespace")
        }
        if (!matchToken(Token.ASSIGN, true)) {
            reportError("msg.bad.namespace")
        }

        val e = expr(false)
        val dxmln = UnaryExpression(pos, getNodeEnd(e) - pos)
        dxmln.operator = Token.DEFAULTNAMESPACE
        dxmln.operand = e
        dxmln.setLineColumnNumber(lineno, column)

        return ExpressionStatement(dxmln, true)
    }

    private fun recordLabel(label: Label, bundle: LabeledStatement) {
        // The current token should be the colon that primaryExpr left untouched.
        if (peekToken() != Token.COLON) codeBug()
        consumeToken()
        val name = label.name!!
        val set = labelSet
        if (set == null) {
            labelSet = HashMap()
        } else {
            val ls = set[name]
            if (ls != null) {
                if (compilerEnv.ideMode) {
                    val dup = ls.getLabelByName(name)!!
                    reportError("msg.dup.label", dup.absolutePosition, dup.length)
                }
                reportError("msg.dup.label", label.position, label.length)
            }
        }
        bundle.addLabel(label)
        labelSet!![name] = bundle
    }

    /**
     * Found a name in a statement context. When it is a label, the following labels and the next
     * non-label statement are gathered into a [LabeledStatement] bundle. Otherwise the expression
     * is parsed and wrapped in an [ExpressionStatement].
     */
    private fun nameOrLabel(): AstNode {
        if (currentToken != Token.NAME) throw codeBug()
        val pos = ts.tokenBeg

        // Set the label check and call down to primaryExpr.
        currentFlaggedToken = currentFlaggedToken or TI_CHECK_LABEL
        var expr = expr(false)

        if (expr.type != Token.LABEL) {
            val n = ExpressionStatement(expr, !insideFunctionBody())
            n.setLineColumnNumber(expr.lineno, expr.column)
            return n
        }

        val bundle = LabeledStatement(pos)
        recordLabel(expr as Label, bundle)
        bundle.setLineColumnNumber(expr.lineno, expr.column)
        // Look for more labels.
        var stmt: AstNode? = null
        while (peekToken() == Token.NAME) {
            currentFlaggedToken = currentFlaggedToken or TI_CHECK_LABEL
            expr = expr(false)
            if (expr.type != Token.LABEL) {
                stmt = ExpressionStatement(expr, !insideFunctionBody())
                autoInsertSemicolon(stmt)
                break
            }
            recordLabel(expr as Label, bundle)
        }

        // No more labels; now parse the labeled statement.
        try {
            currentLabel = bundle
            if (stmt == null) {
                stmt = statementHelper()
                val ntt = peekToken()
                if (ntt == Token.COMMENT && stmt!!.lineno == lastScannedComment().lineno) {
                    stmt.inlineComment = lastScannedComment()
                    consumeToken()
                }
            }
        } finally {
            currentLabel = null
            // Remove this statement's labels from the global set.
            for (lb in bundle.labels) {
                labelSet!!.remove(lb.name)
            }
        }

        // When stmt already has a parent its position is relative. See bug #710225.
        val body = stmt!!
        bundle.length = if (body.parent == null) getNodeEnd(body) - pos else getNodeEnd(body)
        bundle.statement = body
        return bundle
    }

    /**
     * Parses a `var` or `const` statement, or a `var` init list in a for statement.
     *
     * @param declType VAR, CONST or LET, depending on the context.
     * @param pos where the node starts. Sometimes the var/const/let keyword, other times the start
     *     of the first token of the first variable declaration.
     */
    private fun variables(declType: Int, pos: Int, isStatement: Boolean): VariableDeclaration {
        var end: Int
        val pn = VariableDeclaration(pos)
        pn.type = declType
        pn.setLineColumnNumber(lineNumber(), columnNumber())
        getAndResetJsDoc()?.let { pn.jsDocNode = it }
        // Example:
        // var foo = {a: 1, b: 2}, bar = [3, 4];
        // var {b: s2, a: s1} = foo, x = 6, y, [s3, s4] = bar;
        while (true) {
            var destructuring: AstNode? = null
            var name: Name? = null
            val tt = peekToken()
            val kidPos = ts.tokenBeg
            end = ts.tokenEnd

            if (tt == Token.LB || tt == Token.LC) {
                // Destructuring assignment, e.g. var [a,b] = ...
                // TODO: support default values inside a destructured assignment,
                // as in for (let { x = 3 } = {}) ...
                destructuring = destructuringPrimaryExpr()
                end = getNodeEnd(destructuring)

                if (destructuring !is DestructuringForm) {
                    reportError("msg.bad.assign.left", kidPos, end - kidPos)
                }
                markDestructuring(destructuring)
            } else {
                // Simple variable name.
                if (tt == Token.UNDEFINED) {
                    consumeToken()
                } else {
                    mustMatchToken(Token.NAME, "msg.bad.var", true)
                }
                name = createNameNode()
                name.setLineColumnNumber(lineNumber(), columnNumber())
                if (inUseStrictDirective) {
                    val id = ts.string
                    if ("eval" == id || "arguments" == ts.string) {
                        reportError("msg.bad.id.strict", id)
                    }
                }
                defineSymbol(declType, ts.string, inForInit)
            }

            val lineno = lineNumber()
            val column = columnNumber()

            val jsdocNode = getAndResetJsDoc()

            var init: AstNode? = null
            if (matchToken(Token.ASSIGN, true)) {
                init = assignExpr()
                end = getNodeEnd(init)
            }

            val vi = VariableInitializer(kidPos, end - kidPos)
            if (destructuring != null) {
                if (init == null && !inForInit) {
                    reportError("msg.destruct.assign.no.init")
                }
                vi.target = destructuring
            } else {
                vi.target = name
            }
            vi.initializer = init
            vi.type = declType
            vi.jsDocNode = jsdocNode
            vi.setLineColumnNumber(lineno, column)
            pn.addVariable(vi)

            if (!matchToken(Token.COMMA, true)) break
        }
        pn.length = end - pos
        pn.isStatement = isStatement
        return pn
    }

    // The 'let' keyword position has to be passed in so the child offsets come out right.
    private fun let(isStatement: Boolean, pos: Int): AstNode {
        val pn = LetNode(pos)
        pn.setLineColumnNumber(lineNumber(), columnNumber())
        if (mustMatchToken(Token.LP, "msg.no.paren.after.let", true)) pn.lp = ts.tokenBeg - pos
        pushScope(pn)
        try {
            val vars = variables(Token.LET, ts.tokenBeg, isStatement)
            pn.variables = vars
            if (mustMatchToken(Token.RP, "msg.no.paren.let", true)) {
                pn.rp = ts.tokenBeg - pos
            }
            if (isStatement && peekToken() == Token.LC) {
                // let statement
                consumeToken()
                val beg = ts.tokenBeg // position the statement at the LC
                val stmt = statements()
                mustMatchToken(Token.RC, "msg.no.curly.let", true)
                stmt.length = ts.tokenEnd - beg
                pn.length = ts.tokenEnd - pos
                pn.body = stmt
                pn.type = Token.LET
            } else {
                // let expression
                val expr = expr(false)
                pn.length = getNodeEnd(expr) - pos
                pn.body = expr
                if (isStatement) {
                    // A let expression in statement context.
                    val es = ExpressionStatement(pn, !insideFunctionBody())
                    es.setLineColumnNumber(pn.lineno, pn.column)
                    return es
                }
            }
        } finally {
            popScope()
        }
        return pn
    }

    internal fun defineSymbol(declType: Int, name: String?) {
        defineSymbol(declType, name, false)
    }

    internal fun defineSymbol(declType: Int, name: String?, ignoreNotInBlock: Boolean) {
        if (name == null) {
            if (compilerEnv.ideMode) { // stay robust in IDE mode
                return
            }
            codeBug()
        } else if ("undefined" == name) {
            hasUndefinedBeenRedefined = true
        }
        val scope = currentScope!!
        val definingScope = scope.getDefiningScope(name!!)
        val symbol = definingScope?.getSymbol(name)
        val symDeclType = symbol?.declType ?: -1
        if (symbol != null &&
            (symDeclType == Token.CONST ||
                declType == Token.CONST ||
                (definingScope === scope && symDeclType == Token.LET))
        ) {
            addError(
                when (symDeclType) {
                    Token.CONST -> "msg.const.redecl"
                    Token.LET -> "msg.let.redecl"
                    Token.VAR -> "msg.var.redecl"
                    Token.FUNCTION -> "msg.fn.redecl"
                    else -> "msg.parm.redecl"
                },
                name,
            )
            return
        }
        when (declType) {
            Token.LET -> {
                if (!ignoreNotInBlock &&
                    (scope.type == Token.IF || scope is Loop)
                ) {
                    addError("msg.let.decl.not.in.block")
                    return
                }
                scope.putSymbol(Symbol(declType, name))
            }

            Token.VAR, Token.CONST, Token.FUNCTION -> {
                if (symbol != null) {
                    if (symDeclType == Token.VAR) {
                        addStrictWarning("msg.var.redecl", name)
                    } else if (symDeclType == Token.LP) {
                        addStrictWarning("msg.var.hides.arg", name)
                    }
                } else {
                    currentScriptOrFn!!.putSymbol(Symbol(declType, name))
                }
            }

            Token.LP -> {
                if (symbol != null) {
                    // Must be a duplicate parameter. The second one hides the first, so add it.
                    addWarning("msg.dup.parms", name)
                }
                currentScriptOrFn!!.putSymbol(Symbol(declType, name))
            }

            else -> throw codeBug()
        }
    }

    private fun expr(allowTrailingComma: Boolean): AstNode {
        var pn = assignExpr()
        val pos = pn.position
        while (matchToken(Token.COMMA, true)) {
            val opPos = ts.tokenBeg
            if (compilerEnv.strictMode && !pn.hasSideEffects()) {
                addStrictWarning("msg.no.side.effects", "", pos, nodeEnd(pn) - pos)
            }
            if (peekToken() == Token.YIELD) reportError("msg.yield.parenthesized")
            if (allowTrailingComma && peekToken() == Token.RP) {
                pn.putIntProp(Node.TRAILING_COMMA, 1)
                return pn
            }
            pn = InfixExpression(Token.COMMA, pn, assignExpr(), opPos)
        }
        return pn
    }

    private fun assignExpr(): AstNode {
        var tt = peekToken()
        if (tt == Token.YIELD) {
            return returnOrYield(tt, true)
        }

        // Intentionally not calling lineNumber/columnNumber here: no token has been consumed
        // yet, so the position would be wrong.
        val startLine = ts.lineno
        val startColumn = ts.tokenColumn

        var pn = condExpr()
        var hasEOL = false
        tt = peekTokenOrEOL()
        if (tt == Token.EOL) {
            hasEOL = true
            tt = peekToken()
        }
        if (Token.FIRST_ASSIGN <= tt && tt <= Token.LAST_ASSIGN) {
            consumeToken()

            // Pull out the JSDoc info and reset it before recursing.
            val jsdocNode = getAndResetJsDoc()

            markDestructuring(pn)
            val opPos = ts.tokenBeg
            if (isNotValidSimpleAssignmentTarget(pn)) {
                reportError("msg.syntax.invalid.assignment.lhs")
            }

            pn = Assignment(tt, pn, assignExpr(), opPos)

            if (jsdocNode != null) {
                pn.jsDocNode = jsdocNode
            }
        } else if (tt == Token.SEMI) {
            // This may be dead code added on purpose, for JSDoc, as in
            // /** @type Number */ C.prototype.x;
            if (currentJsDocComment != null) {
                pn.jsDocNode = getAndResetJsDoc()
            }
        } else if (!hasEOL && tt == Token.ARROW) {
            consumeToken()
            pn = arrowFunction(pn, startLine, startColumn)
        } else if (pn.getIntProp(Node.OBJECT_LITERAL_DESTRUCTURING, 0) == 1 &&
            !inDestructuringAssignment
        ) {
            reportError("msg.syntax")
        }
        return pn
    }

    private fun condExpr(): AstNode {
        var pn = nullishCoalescingExpr()
        if (matchToken(Token.HOOK, true)) {
            val qmarkPos = ts.tokenBeg
            var colonPos = -1
            // Always accept the 'in' operator in the middle clause of a ternary, where it is
            // unambiguous, even while parsing the init of a for statement.
            val wasInForInit = inForInit
            inForInit = false
            val ifTrue: AstNode
            try {
                ifTrue = assignExpr()
            } finally {
                inForInit = wasInForInit
            }
            if (mustMatchToken(Token.COLON, "msg.no.colon.cond", true)) colonPos = ts.tokenBeg
            val ifFalse = assignExpr()
            val beg = pn.position
            val len = getNodeEnd(ifFalse) - beg
            val ce = ConditionalExpression(beg, len)
            ce.setLineColumnNumber(pn.lineno, pn.column)
            ce.testExpression = pn
            ce.trueExpression = ifTrue
            ce.falseExpression = ifFalse
            ce.questionMarkPosition = qmarkPos - beg
            ce.colonPosition = colonPos - beg
            pn = ce
        }
        return pn
    }

    private fun nullishCoalescingExpr(): AstNode {
        var pn = orExpr()
        if (matchToken(Token.NULLISH_COALESCING, true)) {
            val opPos = ts.tokenBeg
            val rn = nullishCoalescingExpr()

            // Cannot immediately contain, or be contained within, an && or || operation.
            if (pn.type == Token.OR ||
                pn.type == Token.AND ||
                rn.type == Token.OR ||
                rn.type == Token.AND
            ) {
                reportError("msg.nullish.bad.token")
            }

            pn = InfixExpression(Token.NULLISH_COALESCING, pn, rn, opPos)
        }
        return pn
    }

    private fun orExpr(): AstNode {
        var pn = andExpr()
        if (matchToken(Token.OR, true)) {
            val opPos = ts.tokenBeg
            pn = InfixExpression(Token.OR, pn, orExpr(), opPos)
        }
        return pn
    }

    private fun andExpr(): AstNode {
        var pn = bitOrExpr()
        if (matchToken(Token.AND, true)) {
            val opPos = ts.tokenBeg
            pn = InfixExpression(Token.AND, pn, andExpr(), opPos)
        }
        return pn
    }

    private fun bitOrExpr(): AstNode {
        var pn = bitXorExpr()
        while (matchToken(Token.BITOR, true)) {
            val opPos = ts.tokenBeg
            pn = InfixExpression(Token.BITOR, pn, bitXorExpr(), opPos)
        }
        return pn
    }

    private fun bitXorExpr(): AstNode {
        var pn = bitAndExpr()
        while (matchToken(Token.BITXOR, true)) {
            val opPos = ts.tokenBeg
            pn = InfixExpression(Token.BITXOR, pn, bitAndExpr(), opPos)
        }
        return pn
    }

    private fun bitAndExpr(): AstNode {
        var pn = eqExpr()
        while (matchToken(Token.BITAND, true)) {
            val opPos = ts.tokenBeg
            pn = InfixExpression(Token.BITAND, pn, eqExpr(), opPos)
        }
        return pn
    }

    private fun eqExpr(): AstNode {
        var pn = relExpr()
        while (true) {
            val tt = peekToken()
            val opPos = ts.tokenBeg
            when (tt) {
                Token.EQ, Token.NE, Token.SHEQ, Token.SHNE -> {
                    consumeToken()
                    var parseToken = tt
                    if (compilerEnv.languageVersion == Context.VERSION_1_2) {
                        // JavaScript 1.2 uses shallow equality for == and !=.
                        if (tt == Token.EQ) {
                            parseToken = Token.SHEQ
                        } else if (tt == Token.NE) {
                            parseToken = Token.SHNE
                        }
                    }
                    pn = InfixExpression(parseToken, pn, relExpr(), opPos)
                }
                else -> return pn
            }
        }
    }

    private fun relExpr(): AstNode {
        var pn = shiftExpr()
        while (true) {
            val tt = peekToken()
            val opPos = ts.tokenBeg
            when (tt) {
                Token.IN -> {
                    if (inForInit) return pn
                    consumeToken()
                    pn = InfixExpression(tt, pn, shiftExpr(), opPos)
                }
                Token.INSTANCEOF, Token.LE, Token.LT, Token.GE, Token.GT -> {
                    consumeToken()
                    pn = InfixExpression(tt, pn, shiftExpr(), opPos)
                }
                else -> return pn
            }
        }
    }

    private fun shiftExpr(): AstNode {
        var pn = addExpr()
        while (true) {
            val tt = peekToken()
            val opPos = ts.tokenBeg
            when (tt) {
                Token.LSH, Token.URSH, Token.RSH -> {
                    consumeToken()
                    pn = InfixExpression(tt, pn, addExpr(), opPos)
                }
                else -> return pn
            }
        }
    }

    private fun addExpr(): AstNode {
        var pn = mulExpr()
        while (true) {
            val tt = peekToken()
            val opPos = ts.tokenBeg
            if (tt == Token.ADD || tt == Token.SUB) {
                consumeToken()
                pn = InfixExpression(tt, pn, mulExpr(), opPos)
                continue
            }
            return pn
        }
    }

    private fun mulExpr(): AstNode {
        var pn = expExpr()
        while (true) {
            val tt = peekToken()
            val opPos = ts.tokenBeg
            when (tt) {
                Token.MUL, Token.DIV, Token.MOD -> {
                    consumeToken()
                    pn = InfixExpression(tt, pn, expExpr(), opPos)
                }
                else -> return pn
            }
        }
    }

    private fun expExpr(): AstNode {
        var pn = unaryExpr()
        while (true) {
            val tt = peekToken()
            val opPos = ts.tokenBeg
            when (tt) {
                Token.EXP -> {
                    if (pn is UnaryExpression) {
                        reportError(
                            "msg.no.unary.expr.on.left.exp",
                            AstNode.operatorToString(pn.type),
                        )
                        return makeErrorNode()
                    }
                    consumeToken()
                    pn = InfixExpression(tt, pn, expExpr(), opPos)
                }
                else -> return pn
            }
        }
    }

    private fun unaryExpr(): AstNode {
        val node: AstNode
        var tt = peekToken()
        if (tt == Token.COMMENT) {
            consumeToken()
            tt = peekUntilNonComment(tt)
        }
        val line: Int
        val column: Int

        when (tt) {
            Token.VOID, Token.NOT, Token.BITNOT, Token.TYPEOF -> {
                consumeToken()
                line = lineNumber()
                column = columnNumber()
                node = UnaryExpression(tt, ts.tokenBeg, unaryExpr())
                node.setLineColumnNumber(line, column)
                return node
            }

            Token.ADD -> {
                consumeToken()
                line = lineNumber()
                column = columnNumber()
                // Convert to the special POS token in the parse tree.
                node = UnaryExpression(Token.POS, ts.tokenBeg, unaryExpr())
                node.setLineColumnNumber(line, column)
                return node
            }

            Token.SUB -> {
                consumeToken()
                line = lineNumber()
                column = columnNumber()
                // Convert to the special NEG token in the parse tree.
                node = UnaryExpression(Token.NEG, ts.tokenBeg, unaryExpr())
                node.setLineColumnNumber(line, column)
                return node
            }

            Token.INC, Token.DEC -> {
                consumeToken()
                line = lineNumber()
                column = columnNumber()
                val expr = UpdateExpression(tt, ts.tokenBeg, memberExpr(true))
                expr.setLineColumnNumber(line, column)
                checkBadIncDec(expr)
                return expr
            }

            Token.DELPROP -> {
                consumeToken()
                line = lineNumber()
                column = columnNumber()
                node = UnaryExpression(tt, ts.tokenBeg, unaryExpr())
                node.setLineColumnNumber(line, column)
                return node
            }

            Token.ERROR -> {
                consumeToken()
                return makeErrorNode()
            }

            Token.LT -> {
                // An XML stream in expression position.
                if (compilerEnv.xmlAvailable) {
                    consumeToken()
                    return memberExprTail(true, xmlInitializer())
                }
                // Otherwise fall through to the default handling of RELOP.
                return unaryExprTail()
            }

            else -> return unaryExprTail()
        }
    }

    /** The default branch of [unaryExpr], split out so the XML case can fall through to it. */
    private fun unaryExprTail(): AstNode {
        val pn = memberExpr(true)
        // Do not look across a newline boundary for a postfix increment or decrement.
        val tt = peekTokenOrEOL()
        if (!(tt == Token.INC || tt == Token.DEC)) {
            return pn
        }
        consumeToken()
        val uexpr = UpdateExpression(tt, ts.tokenBeg, pn, true)
        uexpr.setLineColumnNumber(pn.lineno, pn.column)
        checkBadIncDec(uexpr)
        return uexpr
    }

    /**
     * KMP: E4X is out of scope, so an XML literal is reported as unavailable instead of parsed
     * (D-16). Upstream builds an `XmlLiteral` here.
     */
    private fun xmlInitializer(): AstNode {
        if (currentToken != Token.LT) codeBug()
        reportError("msg.XML.not.available")
        return makeErrorNode()
    }

    private fun argumentList(): MutableList<AstNode>? {
        if (matchToken(Token.RP, true)) return null

        val result = mutableListOf<AstNode>()
        val wasInForInit = inForInit
        inForInit = false
        try {
            do {
                if (peekToken() == Token.RP) {
                    // Handles f1(a,) without breaking f1(a,b
                    break
                }
                if (peekToken() == Token.YIELD) {
                    reportError("msg.yield.parenthesized")
                }
                val en = assignExpr()
                if (peekToken() == Token.FOR) {
                    result.add(generatorExpression(en, 0, true))
                } else {
                    result.add(en)
                }
            } while (matchToken(Token.COMMA, true))
        } finally {
            inForInit = wasInForInit
        }

        mustMatchToken(Token.RP, "msg.no.paren.arg", true)
        return result
    }

    /**
     * Parses a new-expression, or a primary expression when the next token is not [Token.NEW].
     *
     * @param allowCallSyntax passed down to [memberExprTail]
     */
    private fun memberExpr(allowCallSyntax: Boolean): AstNode {
        val tt = peekToken()
        val pn: AstNode

        if (tt != Token.NEW) {
            pn = primaryExpr()
        } else {
            consumeToken()
            val pos = ts.tokenBeg
            val lineno = lineNumber()
            val column = columnNumber()
            val nx = NewExpression(pos)

            val target = memberExpr(false)
            var end = getNodeEnd(target)
            nx.target = target
            nx.setLineColumnNumber(lineno, column)

            if (matchToken(Token.LP, true)) {
                val lp = ts.tokenBeg
                val args = argumentList()
                if (args != null && args.size > ARGC_LIMIT) {
                    reportError("msg.too.many.constructor.args")
                }
                val rp = ts.tokenBeg
                end = ts.tokenEnd
                if (args != null) nx.setArguments(args)
                nx.setParens(lp - pos, rp - pos)
            }

            // Experimental syntax: an object literal may follow a new expression, which means a
            // kind of anonymous class built with the JavaAdapter. The literal is passed as an
            // extra argument to the constructor.
            if (matchToken(Token.LC, true)) {
                val initializer = objectLiteral()
                end = getNodeEnd(initializer)
                nx.initializer = initializer
            }
            nx.length = end - pos
            pn = nx
        }
        return memberExprTail(allowCallSyntax, pn)
    }

    /**
     * Parses any number of `(expr)`, `[expr]`, `.expr`, `?.expr`, `..expr`, `.(expr)` or
     * `?.(expr)` constructs trailing [pn].
     *
     * @return the outermost, lexically last expression, which has [pn] as a descendant
     */
    private fun memberExprTail(allowCallSyntax: Boolean, pn: AstNode): AstNode {
        var node = pn
        val pos = node.position
        var lineno: Int
        var column: Int
        var isOptionalChain = false
        tailLoop@ while (true) {
            lineno = lineNumber()
            column = columnNumber()
            val tt = peekToken()
            when (tt) {
                Token.DOT, Token.QUESTION_DOT, Token.DOTDOT -> {
                    isOptionalChain = isOptionalChain or (tt == Token.QUESTION_DOT)
                    node = propertyAccess(tt, node, isOptionalChain)
                }

                Token.DOTQUERY -> {
                    // KMP: the E4X filtering predicate is out of scope (D-16).
                    consumeToken()
                    mustHaveXML()
                    reportError("msg.XML.not.available")
                    return makeErrorNode()
                }

                Token.LB -> {
                    consumeToken()
                    node = makeElemGet(node, ts.tokenBeg)
                }

                Token.LP -> {
                    if (!allowCallSyntax) {
                        break@tailLoop
                    }
                    node = makeFunctionCall(node, pos, isOptionalChain)
                }

                Token.COMMENT -> {
                    // Ignore all comments: the previous statement may not be terminated properly.
                    val savedFlaggedToken = currentFlaggedToken
                    peekUntilNonComment(tt)
                    currentFlaggedToken =
                        if ((currentFlaggedToken and TI_AFTER_EOL) != 0) {
                            currentFlaggedToken
                        } else {
                            savedFlaggedToken
                        }
                }

                Token.TEMPLATE_LITERAL -> {
                    consumeToken()
                    node = taggedTemplateLiteral(node)
                }

                else -> break@tailLoop
            }
        }
        return node
    }

    private fun makeFunctionCall(pn: AstNode, pos: Int, isOptionalChain: Boolean): FunctionCall {
        consumeToken()
        checkCallRequiresActivation(pn)
        val f = FunctionCall(pos)
        f.target = pn
        f.lp = ts.tokenBeg - pos
        val args = argumentList()
        if (args != null && args.size > ARGC_LIMIT) reportError("msg.too.many.function.args")
        f.setArguments(args)
        f.rp = ts.tokenBeg - pos
        f.length = ts.tokenEnd - pos
        if (isOptionalChain) {
            f.markIsOptionalCall()
        }
        return f
    }

    private fun taggedTemplateLiteral(pn: AstNode): AstNode {
        val templateLiteral = templateLiteral(true)
        val tagged = TaggedTemplateLiteral()
        tagged.target = pn
        tagged.templateLiteral = templateLiteral
        tagged.setLineColumnNumber(pn.lineno, pn.column)
        return tagged
    }

    /**
     * Handles anything following a `.` or `..` operator.
     *
     * @param pn the left-hand side of the operator
     * @param isOptionalChain true inside an optional chain, i.e. a preceding property access used
     *     the `?.` operator
     */
    private fun propertyAccess(tt: Int, pn: AstNode, isOptionalChain: Boolean): AstNode {
        if (pn.type == Token.SUPER && isOptionalChain) {
            reportError("msg.optional.super")
            return makeErrorNode()
        }

        var memberTypeFlags = 0
        val lineno = lineNumber()
        val dotPos = ts.tokenBeg
        val column = columnNumber()
        consumeToken()

        if (tt == Token.DOTDOT) {
            mustHaveXML()
            memberTypeFlags = Node.DESCENDANTS_FLAG
        }

        val ref: AstNode // right side of the . or .. operator
        when (val token = nextToken()) {
            Token.THROW -> {
                // Needed for generator.throw().
                saveNameTokenData(ts.tokenBeg, "throw", lineNumber(), columnNumber())
                ref = propertyName(-1, memberTypeFlags)
            }

            Token.NAME -> {
                // Handles: name, ns::name, ns::*, ns::[expr]
                ref = propertyName(-1, memberTypeFlags)
            }

            Token.MUL -> {
                if (compilerEnv.xmlAvailable) {
                    // Handles: *, *::name, *::*, *::[expr]
                    saveNameTokenData(ts.tokenBeg, "*", lineNumber(), columnNumber())
                    ref = propertyName(-1, memberTypeFlags)
                } else {
                    reportError("msg.no.name.after.dot")
                    return makeErrorNode()
                }
            }

            Token.XMLATTR -> {
                if (compilerEnv.xmlAvailable) {
                    // Handles: @attr, @ns::attr, @ns::*, @::attr, @::*, @*, @*::attr, @*::*
                    ref = attributeAccess()
                } else {
                    reportError("msg.no.name.after.dot")
                    return makeErrorNode()
                }
            }

            Token.RESERVED -> {
                saveNameTokenData(ts.tokenBeg, ts.string, lineNumber(), columnNumber())
                ref = propertyName(-1, memberTypeFlags)
            }

            Token.LB -> {
                if (tt == Token.QUESTION_DOT) {
                    // a ?.[ expr ]
                    consumeToken()
                    val g = makeElemGet(pn, ts.tokenBeg)
                    g.type = Token.QUESTION_DOT
                    return g
                } else {
                    reportError("msg.no.name.after.dot")
                    return makeErrorNode()
                }
            }

            Token.LP -> {
                if (tt == Token.QUESTION_DOT) {
                    // A function call such as f?.()
                    return makeFunctionCall(pn, pn.position, isOptionalChain)
                } else {
                    reportError("msg.no.name.after.dot")
                    return makeErrorNode()
                }
            }

            else -> {
                if (compilerEnv.reservedKeywordAsIdentifier) {
                    // Allow keywords as property names, e.g. ({if: 1})
                    val name = Token.keywordToName(token)
                    if (name != null) {
                        saveNameTokenData(ts.tokenBeg, name, lineNumber(), columnNumber())
                        ref = propertyName(-1, memberTypeFlags)
                    } else {
                        reportError("msg.no.name.after.dot")
                        return makeErrorNode()
                    }
                } else {
                    reportError("msg.no.name.after.dot")
                    return makeErrorNode()
                }
            }
        }

        // KMP: upstream builds an XmlMemberGet when ref is an XmlRef. E4X is out of scope, so
        // propertyName never returns one and a PropertyGet is always the right node (D-16).
        val result: InfixExpression = PropertyGet()
        if (isOptionalChain) {
            result.type = Token.QUESTION_DOT
        }
        val pos = pn.position
        result.position = pos
        result.length = getNodeEnd(ref) - pos
        result.operatorPosition = dotPos - pos
        result.setLineColumnNumber(lineno, column)
        result.left = pn // do this after setting the position
        result.right = ref
        return result
    }

    private fun makeElemGet(pn: AstNode, lb: Int): ElementGet {
        val pos = pn.position
        val expr = expr(false)
        var end = getNodeEnd(expr)
        var rb = -1
        if (mustMatchToken(Token.RB, "msg.no.bracket.index", true)) {
            rb = ts.tokenBeg
            end = ts.tokenEnd
        }
        val g = ElementGet(pos, end - pos)
        g.target = pn
        g.element = expr
        g.setParens(lb, rb)
        return g
    }

    /**
     * KMP: E4X attribute expressions such as `@attr` and `@ns::*` are out of scope (D-16), so
     * this reports the syntax as unavailable instead of parsing it.
     */
    private fun attributeAccess(): AstNode {
        nextToken()
        reportError("msg.XML.not.available")
        return makeErrorNode()
    }

    /**
     * Checks whether `::` follows the name, in which case it becomes a qualified name.
     *
     * @param atPos a natural number when an '@' token was just read, else -1
     * @param memberTypeFlags flags tracking whether this is a '.' or '..' child
     * @return a [Name] for a plain name. KMP: upstream returns an XmlRef for a qualified name,
     *     an attribute access or a '..' child; E4X is out of scope, so those report an error and
     *     return an error node (D-16).
     */
    private fun propertyName(atPos: Int, memberTypeFlags: Int): AstNode {
        var name = createNameNode(true, currentToken)
        var ns: Name? = null

        if (matchToken(Token.COLONCOLON, true)) {
            ns = name

            when (val nt = nextToken()) {
                // Handles name::name
                Token.NAME -> name = createNameNode()

                Token.RESERVED -> {
                    saveNameTokenData(ts.tokenBeg, ts.string, lineNumber(), columnNumber())
                    name = createNameNode(false, -1)
                }

                Token.MUL -> {
                    saveNameTokenData(ts.tokenBeg, "*", lineNumber(), columnNumber())
                    name = createNameNode(false, -1)
                }

                // Handles name::[expr] or *::[expr]
                Token.LB -> return xmlElemRef()

                else -> {
                    if (compilerEnv.reservedKeywordAsIdentifier) {
                        // Allow keywords as property names, e.g. ({if: 1})
                        val realName = Token.keywordToName(nt)
                        saveNameTokenData(ts.tokenBeg, realName, lineNumber(), columnNumber())
                        name = createNameNode(false, -1)
                    } else {
                        reportError("msg.no.name.after.coloncolon")
                        return makeErrorNode()
                    }
                }
            }
        }

        if (ns == null && memberTypeFlags == 0 && atPos == -1) {
            return name
        }

        // KMP: an XmlPropRef would go here. E4X is out of scope (D-16).
        reportError("msg.XML.not.available")
        return makeErrorNode()
    }

    /**
     * KMP: the `[expr]` portion of an E4X element reference is out of scope (D-16).
     */
    private fun xmlElemRef(): AstNode {
        reportError("msg.XML.not.available")
        return makeErrorNode()
    }

    private fun destructuringAssignExpr(): AstNode {
        try {
            inDestructuringAssignment = true
            return assignExpr()
        } finally {
            inDestructuringAssignment = false
        }
    }

    private fun destructuringPrimaryExpr(): AstNode {
        try {
            inDestructuringAssignment = true
            return primaryExpr()
        } finally {
            inDestructuringAssignment = false
        }
    }

    private fun primaryExpr(): AstNode {
        val ttFlagged = peekFlaggedToken()
        val tt = ttFlagged and CLEAR_TI_MASK
        val pos: Int
        val end: Int

        when (tt) {
            Token.FUNCTION -> {
                consumeToken()
                return function(FunctionNode.FUNCTION_EXPRESSION)
            }

            Token.LB -> {
                consumeToken()
                return arrayLiteral()
            }

            Token.LC -> {
                consumeToken()
                return objectLiteral()
            }

            Token.LET -> {
                consumeToken()
                return let(false, ts.tokenBeg)
            }

            Token.LP -> {
                consumeToken()
                return parenExpr()
            }

            Token.XMLATTR -> {
                consumeToken()
                mustHaveXML()
                return attributeAccess()
            }

            Token.NAME -> {
                consumeToken()
                return name(ttFlagged, tt)
            }

            Token.NUMBER, Token.BIGINT -> {
                consumeToken()
                return createNumericLiteral(tt, false)
            }

            Token.STRING -> {
                consumeToken()
                return createStringLiteral()
            }

            Token.DIV, Token.ASSIGN_DIV -> {
                consumeToken()
                // Got / or /=, which in this context means a regexp.
                ts.readRegExp(tt)
                pos = ts.tokenBeg
                end = ts.tokenEnd
                val re = RegExpLiteral(pos, end - pos)
                re.value = ts.string
                re.flags = ts.readAndClearRegExpFlags()
                re.setLineColumnNumber(lineNumber(), columnNumber())
                return re
            }

            Token.UNDEFINED -> {
                consumeToken()
                pos = ts.tokenBeg
                end = ts.tokenEnd
                if (hasUndefinedBeenRedefined) {
                    return Name(pos, end - pos, "undefined")
                }

                val keywordLiteral = KeywordLiteral(pos, end - pos, tt)
                keywordLiteral.setLineColumnNumber(lineNumber(), columnNumber())
                return keywordLiteral
            }

            Token.NULL, Token.THIS, Token.FALSE, Token.TRUE -> {
                consumeToken()
                pos = ts.tokenBeg
                end = ts.tokenEnd
                val keywordLiteral = KeywordLiteral(pos, end - pos, tt)
                keywordLiteral.setLineColumnNumber(lineNumber(), columnNumber())
                return keywordLiteral
            }

            Token.SUPER -> {
                if (((insideFunctionParams() || insideFunctionBody()) && insideMethod) ||
                    compilerEnv.allowSuper
                ) {
                    consumeToken()
                    pos = ts.tokenBeg
                    end = ts.tokenEnd
                    val keywordLiteral = KeywordLiteral(pos, end - pos, tt)
                    keywordLiteral.setLineColumnNumber(lineNumber(), columnNumber())
                    return keywordLiteral
                } else {
                    reportError("msg.super.shorthand.function")
                }
            }

            Token.TEMPLATE_LITERAL -> {
                consumeToken()
                return templateLiteral(false)
            }

            Token.RESERVED -> {
                consumeToken()
                reportError("msg.reserved.id", ts.string)
            }

            Token.ERROR -> {
                consumeToken()
                // The scanner or one of its subroutines already reported the error.
            }

            Token.EOF -> {
                consumeToken()
                reportError("msg.unexpected.eof")
            }

            else -> {
                consumeToken()
                reportError("msg.syntax")
            }
        }
        // Should only be reachable in IDE or error-recovery mode.
        consumeToken()
        return makeErrorNode()
    }

    private fun parenExpr(): AstNode {
        val wasInForInit = inForInit
        inForInit = false
        try {
            var jsdocNode = getAndResetJsDoc()
            val lineno = lineNumber()
            val column = columnNumber()
            val begin = ts.tokenBeg
            val e = if (peekToken() == Token.RP) EmptyExpression(begin) else expr(true)
            if (peekToken() == Token.FOR) {
                return generatorExpression(e, begin)
            }
            mustMatchToken(Token.RP, "msg.no.paren", true)

            val length = ts.tokenEnd - begin

            val hasObjectLiteralDestructuring =
                e.getIntProp(Node.OBJECT_LITERAL_DESTRUCTURING, 0) == 1
            val hasTrailingComma = e.getIntProp(Node.TRAILING_COMMA, 0) == 1
            if ((hasTrailingComma || hasObjectLiteralDestructuring || e.type == Token.EMPTY) &&
                peekToken() != Token.ARROW
            ) {
                reportError("msg.syntax")
                return makeErrorNode()
            }

            val pn = ParenthesizedExpression(begin, length, e)
            pn.setLineColumnNumber(lineno, column)
            if (jsdocNode == null) {
                jsdocNode = getAndResetJsDoc()
            }
            if (jsdocNode != null) {
                pn.jsDocNode = jsdocNode
            }
            if (hasTrailingComma) {
                pn.putIntProp(Node.TRAILING_COMMA, 1)
            }
            return pn
        } finally {
            inForInit = wasInForInit
        }
    }

    private fun name(ttFlagged: Int, tt: Int): AstNode {
        val nameString = ts.string
        val namePos = ts.tokenBeg
        val nameLineno = lineNumber()
        val nameColumn = columnNumber()
        if (0 != (ttFlagged and TI_CHECK_LABEL) && peekToken() == Token.COLON) {
            // Do not consume the colon: it is the unwind indicator that returns to
            // statementHelper.
            val label = Label(namePos, ts.tokenEnd - namePos)
            label.name = nameString
            label.setLineColumnNumber(lineNumber(), columnNumber())
            return label
        }
        // Not a label. Peeking the next token to check for a colon has clobbered ts.tokenBeg and
        // ts.tokenEnd, so the name bounds go into instance vars that createNameNode reads.
        saveNameTokenData(namePos, nameString, nameLineno, nameColumn)

        if (compilerEnv.xmlAvailable) {
            return propertyName(-1, 0)
        }
        return createNameNode(true, Token.NAME)
    }

    /** May return an [ArrayLiteral] or an [ArrayComprehension]. */
    private fun arrayLiteral(): AstNode {
        if (currentToken != Token.LB) codeBug()
        val pos = ts.tokenBeg
        var end = ts.tokenEnd
        val lineno = lineNumber()
        val column = columnNumber()
        val elements = mutableListOf<AstNode>()
        val pn = ArrayLiteral(pos)
        var afterLbOrComma = true
        var afterComma = -1
        var skipCount = 0
        while (true) {
            val tt = peekToken()
            if (tt == Token.COMMA) {
                consumeToken()
                afterComma = ts.tokenEnd
                if (!afterLbOrComma) {
                    afterLbOrComma = true
                } else {
                    elements.add(EmptyExpression(ts.tokenBeg, 1))
                    skipCount++
                }
            } else if (tt == Token.COMMENT) {
                consumeToken()
            } else if (tt == Token.RB) {
                consumeToken()
                // "for ([a,] in obj)" is legal but "for ([a] in obj)" is not, since that supplies
                // both key and value. The trick is that [a,] and [a] are equivalent in other array
                // literal contexts, so a special length is computed just for destructuring.
                end = ts.tokenEnd
                pn.destructuringLength = elements.size + (if (afterLbOrComma) 1 else 0)
                pn.skipCount = skipCount
                if (afterComma != -1) warnTrailingComma(pos, elements, afterComma)
                break
            } else if (tt == Token.FOR && !afterLbOrComma && elements.size == 1) {
                return arrayComprehension(elements[0], pos)
            } else if (tt == Token.EOF) {
                reportError("msg.no.bracket.arg")
                break
            } else {
                if (!afterLbOrComma) {
                    reportError("msg.no.bracket.arg")
                }
                val element: AstNode
                if (tt == Token.DOTDOTDOT &&
                    compilerEnv.languageVersion >= Context.VERSION_ES6
                ) {
                    consumeToken()
                    val spreadPos = ts.tokenBeg
                    val spreadLineno = lineNumber()
                    val spreadColumn = columnNumber()
                    val exprNode = assignExpr()
                    val spread = Spread(spreadPos, ts.tokenEnd - spreadPos)
                    spread.setLineColumnNumber(spreadLineno, spreadColumn)
                    spread.expression = exprNode
                    element = spread
                } else {
                    element = assignExpr()
                }
                elements.add(element)
                afterLbOrComma = false
                afterComma = -1
            }
        }
        for (e in elements) {
            pn.addElement(e)
        }
        pn.length = end - pos
        pn.setLineColumnNumber(lineno, column)
        return pn
    }

    /**
     * Parses a JavaScript 1.7 array comprehension.
     *
     * @param result the first expression after the opening left bracket
     * @param pos start of the LB token that begins the comprehension
     */
    private fun arrayComprehension(result: AstNode, pos: Int): AstNode {
        val loops = mutableListOf<ArrayComprehensionLoop>()
        while (peekToken() == Token.FOR) {
            loops.add(arrayComprehensionLoop())
        }
        var ifPos = -1
        var data: ConditionData? = null
        if (peekToken() == Token.IF) {
            consumeToken()
            ifPos = ts.tokenBeg - pos
            data = condition()
        }
        mustMatchToken(Token.RB, "msg.no.bracket.arg", true)
        val pn = ArrayComprehension(pos, ts.tokenEnd - pos)
        pn.result = result
        pn.setLoops(loops)
        if (data != null) {
            pn.ifPosition = ifPos
            pn.filter = data.condition
            pn.filterLp = data.lp - pos
            pn.filterRp = data.rp - pos
        }
        return pn
    }

    private fun arrayComprehensionLoop(): ArrayComprehensionLoop {
        if (nextToken() != Token.FOR) codeBug()
        val pos = ts.tokenBeg
        var eachPos = -1
        var lp = -1
        var rp = -1
        var inPos = -1
        var isForOf = false
        val pn = ArrayComprehensionLoop(pos)

        pushScope(pn)
        try {
            if (matchToken(Token.NAME, true)) {
                if ("each" == ts.string) {
                    eachPos = ts.tokenBeg - pos
                } else {
                    reportError("msg.no.paren.for")
                }
            }
            if (mustMatchToken(Token.LP, "msg.no.paren.for", true)) {
                lp = ts.tokenBeg - pos
            }

            var iter: AstNode? = null
            when (peekToken()) {
                Token.LB, Token.LC -> {
                    // Handle a destructuring assignment.
                    iter = destructuringPrimaryExpr()
                    markDestructuring(iter)
                }
                Token.NAME -> {
                    consumeToken()
                    iter = createNameNode()
                }
                else -> reportError("msg.bad.var")
            }

            // Define it as a let, so the variable scope stays inside the comprehension.
            if (iter!!.type == Token.NAME) {
                defineSymbol(Token.LET, ts.string, true)
            }

            when (nextToken()) {
                Token.IN -> inPos = ts.tokenBeg - pos
                Token.NAME -> {
                    if ("of" == ts.string) {
                        if (eachPos != -1) {
                            reportError("msg.invalid.for.each")
                        }
                        inPos = ts.tokenBeg - pos
                        isForOf = true
                    } else {
                        reportError("msg.in.after.for.name")
                    }
                }
                else -> reportError("msg.in.after.for.name")
            }
            val obj = expr(false)
            if (mustMatchToken(Token.RP, "msg.no.paren.for.ctrl", true)) rp = ts.tokenBeg - pos

            pn.length = ts.tokenEnd - pos
            pn.iterator = iter
            pn.iteratedObject = obj
            pn.inPosition = inPos
            pn.eachPosition = eachPos
            pn.isForEach = eachPos != -1
            pn.setParens(lp, rp)
            pn.isForOf = isForOf
            return pn
        } finally {
            popScope()
        }
    }

    private fun generatorExpression(result: AstNode, pos: Int): AstNode =
        generatorExpression(result, pos, false)

    private fun generatorExpression(
        result: AstNode,
        pos: Int,
        inFunctionParams: Boolean,
    ): AstNode {
        val loops = mutableListOf<GeneratorExpressionLoop>()
        while (peekToken() == Token.FOR) {
            loops.add(generatorExpressionLoop())
        }
        var ifPos = -1
        var data: ConditionData? = null
        if (peekToken() == Token.IF) {
            consumeToken()
            ifPos = ts.tokenBeg - pos
            data = condition()
        }
        if (!inFunctionParams) {
            mustMatchToken(Token.RP, "msg.no.paren.let", true)
        }
        val pn = GeneratorExpression(pos, ts.tokenEnd - pos)
        pn.result = result
        pn.setLoops(loops)
        if (data != null) {
            pn.ifPosition = ifPos
            pn.filter = data.condition
            pn.filterLp = data.lp - pos
            pn.filterRp = data.rp - pos
        }
        return pn
    }

    private fun generatorExpressionLoop(): GeneratorExpressionLoop {
        if (nextToken() != Token.FOR) codeBug()
        val pos = ts.tokenBeg
        var lp = -1
        var rp = -1
        var inPos = -1
        val pn = GeneratorExpressionLoop(pos)

        pushScope(pn)
        try {
            if (mustMatchToken(Token.LP, "msg.no.paren.for", true)) {
                lp = ts.tokenBeg - pos
            }

            var iter: AstNode? = null
            when (peekToken()) {
                Token.LB, Token.LC -> {
                    // Handle a destructuring assignment.
                    iter = destructuringPrimaryExpr()
                    markDestructuring(iter)
                }
                Token.NAME -> {
                    consumeToken()
                    iter = createNameNode()
                }
                else -> reportError("msg.bad.var")
            }

            // Define it as a let, so the variable scope stays inside the comprehension.
            if (iter!!.type == Token.NAME) {
                defineSymbol(Token.LET, ts.string, true)
            }

            if (mustMatchToken(Token.IN, "msg.in.after.for.name", true)) inPos = ts.tokenBeg - pos
            val obj = expr(false)
            if (mustMatchToken(Token.RP, "msg.no.paren.for.ctrl", true)) rp = ts.tokenBeg - pos

            pn.length = ts.tokenEnd - pos
            pn.iterator = iter
            pn.iteratedObject = obj
            pn.inPosition = inPos
            pn.setParens(lp, rp)
            return pn
        } finally {
            popScope()
        }
    }

    private fun objectLiteral(): ObjectLiteral {
        val pos = ts.tokenBeg
        val lineno = lineNumber()
        val column = columnNumber()
        var afterComma = -1
        val elems = mutableListOf<AbstractObjectProperty>()
        var getterNames: MutableSet<String>? = null
        var setterNames: MutableSet<String>? = null
        if (this.inUseStrictDirective) {
            getterNames = HashSet()
            setterNames = HashSet()
        }
        val objJsdocNode = getAndResetJsDoc()
        var objectLiteralDestructuringDefault = false
        commaLoop@ while (true) {
            var propertyName: String? = null
            var entryKind = PROP_ENTRY
            var tt = peekToken()
            val jsdocNode = getAndResetJsDoc()
            if (tt == Token.COMMENT) {
                consumeToken()
                tt = peekUntilNonComment(tt)
            }
            if (tt == Token.RC) {
                if (afterComma != -1) warnTrailingComma(pos, elems, afterComma)
                break@commaLoop
            }
            var pname = objliteralProperty()
            val firstName = pname
            if (firstName == null) {
                reportError("msg.bad.prop")
            } else if (firstName is Spread) {
                val spreadExpr = firstName.expression
                if (spreadExpr is Name || spreadExpr is StringLiteral) {
                    // For complicated reasons, parsing a name does not advance the token.
                    spreadExpr.setLineColumnNumber(lineNumber(), columnNumber())
                }

                elems.add(SpreadObjectProperty(firstName))
            } else {
                propertyName = ts.string
                val ppos = ts.tokenBeg
                consumeToken()
                if (firstName is Name || firstName is StringLiteral) {
                    // For complicated reasons, parsing a name does not advance the token.
                    firstName.setLineColumnNumber(lineNumber(), columnNumber())
                } else if (firstName is GeneratorMethodDefinition) {
                    // Same as above.
                    firstName.methodName!!.setLineColumnNumber(lineNumber(), columnNumber())
                }

                // This path handles both a destructuring object literal such as
                // var {get, b} = {get: 1, b: 2};
                // and a getter such as
                // var x = {get 1() { return 2; };
                // so a whitelist of tokens tells the first case apart. Because of keywords the
                // second case can run to many tokens.
                val peeked = peekToken()
                if (peeked != Token.COMMA && peeked != Token.COLON && peeked != Token.RC) {
                    if (peeked == Token.ASSIGN) {
                        // An object literal with a destructuring assignment and a default value.
                        objectLiteralDestructuringDefault = true
                        if (compilerEnv.languageVersion >= Context.VERSION_ES6) {
                            elems.add(plainProperty(firstName, tt))
                            if (matchToken(Token.COMMA, true)) {
                                continue@commaLoop
                            } else {
                                break@commaLoop
                            }
                        } else {
                            reportError("msg.default.args")
                        }
                    } else if (peeked == Token.LP) {
                        entryKind = METHOD_ENTRY
                    } else if (firstName.type == Token.NAME) {
                        if ("get" == propertyName) {
                            entryKind = GET_ENTRY
                        } else if ("set" == propertyName) {
                            entryKind = SET_ENTRY
                        }
                    }
                    if (entryKind == GET_ENTRY || entryKind == SET_ENTRY) {
                        pname = objliteralProperty()
                        if (pname == null) {
                            reportError("msg.bad.prop")
                        }
                        consumeToken()
                    }
                    val methodName = pname
                    if (methodName == null) {
                        propertyName = null
                    } else {
                        propertyName = ts.string
                        // A shorthand method definition.
                        val objectProp =
                            methodDefinition(
                                ppos,
                                methodName,
                                entryKind,
                                methodName is GeneratorMethodDefinition,
                                true,
                            )
                        methodName.jsDocNode = jsdocNode
                        elems.add(objectProp)
                    }
                } else {
                    firstName.jsDocNode = jsdocNode
                    elems.add(plainProperty(firstName, tt))
                }
                if (pname is GeneratorMethodDefinition && entryKind != METHOD_ENTRY) {
                    reportError("msg.bad.prop")
                }
            }

            if (this.inUseStrictDirective &&
                propertyName != null &&
                pname !is ComputedPropertyKey &&
                compilerEnv.languageVersion < Context.VERSION_ES6
            ) {
                when (entryKind) {
                    PROP_ENTRY, METHOD_ENTRY -> {
                        if (getterNames!!.contains(propertyName) ||
                            setterNames!!.contains(propertyName)
                        ) {
                            addError("msg.dup.obj.lit.prop.strict", propertyName)
                        }
                        getterNames.add(propertyName)
                        setterNames!!.add(propertyName)
                    }
                    GET_ENTRY -> {
                        if (getterNames!!.contains(propertyName)) {
                            addError("msg.dup.obj.lit.prop.strict", propertyName)
                        }
                        getterNames.add(propertyName)
                    }
                    SET_ENTRY -> {
                        if (setterNames!!.contains(propertyName)) {
                            addError("msg.dup.obj.lit.prop.strict", propertyName)
                        }
                        setterNames.add(propertyName)
                    }
                }
            }

            // Eat any dangling jsdoc in the property.
            getAndResetJsDoc()

            if (matchToken(Token.COMMA, true)) {
                afterComma = ts.tokenEnd
            } else {
                break@commaLoop
            }
        }

        mustMatchToken(Token.RC, "msg.no.brace.prop", true)
        val pn = ObjectLiteral(pos, ts.tokenEnd - pos)
        if (objectLiteralDestructuringDefault) {
            pn.putIntProp(Node.OBJECT_LITERAL_DESTRUCTURING, 1)
        }
        if (objJsdocNode != null) {
            pn.jsDocNode = objJsdocNode
        }
        pn.setElements(elems)
        pn.setLineColumnNumber(lineno, column)
        return pn
    }

    private fun objliteralProperty(): AstNode? {
        val pname: AstNode
        when (val tt = peekToken()) {
            Token.NAME -> pname = createNameNode()

            Token.STRING -> pname = createStringLiteral()

            Token.NUMBER, Token.BIGINT -> pname = createNumericLiteral(tt, true)

            Token.DOTDOTDOT -> {
                if (compilerEnv.languageVersion >= Context.VERSION_ES6) {
                    val pos = ts.tokenBeg
                    nextToken()
                    val lineno = lineNumber()
                    val column = columnNumber()

                    val exprNode = assignExpr()
                    val spread = Spread(pos, ts.tokenEnd - pos)
                    spread.setLineColumnNumber(lineno, column)
                    spread.expression = exprNode
                    pname = spread
                } else {
                    reportError("msg.bad.prop")
                    return null
                }
            }

            Token.LB -> {
                if (compilerEnv.languageVersion >= Context.VERSION_ES6) {
                    val pos = ts.tokenBeg
                    nextToken()
                    val lineno = lineNumber()
                    val column = columnNumber()
                    val expr = assignExpr()
                    if (peekToken() != Token.RB) {
                        reportError("msg.bad.prop")
                    }
                    nextToken()

                    val key = ComputedPropertyKey(pos, ts.tokenEnd - pos)
                    key.setLineColumnNumber(lineno, column)
                    key.expression = expr
                    pname = key
                } else {
                    reportError("msg.bad.prop")
                    return null
                }
            }

            Token.MUL -> {
                if (compilerEnv.languageVersion >= Context.VERSION_ES6) {
                    val pos = ts.tokenBeg
                    nextToken()
                    val lineno = lineNumber()
                    val column = columnNumber()
                    val inner = objliteralProperty()

                    val def = GeneratorMethodDefinition(pos, ts.tokenEnd - pos, inner!!)
                    def.setLineColumnNumber(lineno, column)
                    pname = def
                } else {
                    reportError("msg.bad.prop")
                    return null
                }
            }

            else -> {
                if (compilerEnv.reservedKeywordAsIdentifier &&
                    TokenStream.isKeyword(
                        ts.string!!,
                        compilerEnv.languageVersion,
                        inUseStrictDirective,
                    )
                ) {
                    // Convert the keyword to a property name, e.g. ({if: 1})
                    pname = createNameNode()
                } else {
                    return null
                }
            }
        }

        return pname
    }

    private fun plainProperty(property: AstNode, ptt: Int): ObjectProperty {
        // Support |var {x, y} = o| as destructuring shorthand for |var {x: x, y: y} = o|, as
        // SpiderMonkey does since JS 1.8.
        val tt = peekToken()
        if ((tt == Token.COMMA || tt == Token.RC) &&
            ptt == Token.NAME &&
            compilerEnv.languageVersion >= Context.VERSION_1_8
        ) {
            if (!inDestructuringAssignment &&
                compilerEnv.languageVersion < Context.VERSION_ES6
            ) {
                reportError("msg.bad.object.init")
            }
            val nn = Name(property.position, property.string!!)
            val pn = ObjectProperty()
            pn.setKeyAndValue(property, nn)
            return pn
        } else if (tt == Token.ASSIGN) {
            // Destructuring with defaults in an object literal: treat defaults as values.
            val pn = ObjectProperty()
            consumeToken() // consume the '='
            val defaultValue = Assignment(property, assignExpr())
            defaultValue.type = Token.ASSIGN
            pn.setKeyAndValue(property, defaultValue)
            return pn
        }
        mustMatchToken(Token.COLON, "msg.no.colon.prop", true)
        val pn = ObjectProperty()
        pn.setKeyAndValue(property, assignExpr())
        return pn
    }

    private fun methodDefinition(
        pos: Int,
        propName: AstNode,
        entryKind: Int,
        isGenerator: Boolean,
        isShorthand: Boolean,
    ): ObjectProperty {
        val fn = function(FunctionNode.FUNCTION_EXPRESSION, true)
        // The function name was already parsed, so fn should be anonymous.
        val name = fn.functionName
        if (name != null && name.length() != 0) {
            reportError("msg.bad.prop")
        }
        val pn = ObjectProperty(pos)
        when (entryKind) {
            GET_ENTRY -> {
                pn.setIsGetterMethod()
                fn.setFunctionIsGetterMethod()
            }
            SET_ENTRY -> {
                pn.setIsSetterMethod()
                fn.setFunctionIsSetterMethod()
            }
            METHOD_ENTRY -> {
                pn.setIsNormalMethod()
                fn.setFunctionIsNormalMethod()
                if (isGenerator) {
                    fn.isES6Generator = true
                }
                if (isShorthand) {
                    fn.isShorthand = true
                }
            }
        }
        val end = getNodeEnd(fn)
        pn.setKeyAndValue(propName, fn)
        pn.length = end - pos
        return pn
    }

    private fun createNameNode(): Name = createNameNode(false, Token.NAME)

    /**
     * Creates a [Name] node from the token info of the last scanned name. Sometimes a name node
     * has to be synthesized, or the name token info was lost while peeking. When [token] is not
     * [Token.NAME] the saved instance vars are used instead.
     */
    private fun createNameNode(checkActivation: Boolean, token: Int): Name {
        var beg = ts.tokenBeg
        var s = ts.string
        var lineno = lineNumber()
        var column = columnNumber()
        if ("" != prevNameTokenString) {
            beg = prevNameTokenStart
            s = prevNameTokenString
            lineno = prevNameTokenLineno
            column = prevNameTokenColumn
            prevNameTokenStart = 0
            prevNameTokenString = ""
            prevNameTokenLineno = 0
            prevNameTokenColumn = 0
        }
        if (s == null) {
            if (compilerEnv.ideMode) {
                s = ""
            } else {
                codeBug()
            }
        }
        val name = Name(beg, s!!)
        name.setLineColumnNumber(lineno, column)
        if (checkActivation) {
            checkActivationName(s, token)
        }
        return name
    }

    private fun createStringLiteral(): StringLiteral {
        val pos = ts.tokenBeg
        val end = ts.tokenEnd
        val s = StringLiteral(pos, end - pos)
        s.setLineColumnNumber(lineNumber(), columnNumber())
        s.value = ts.string
        s.quoteCharacter = ts.quoteChar
        return s
    }

    private fun templateLiteral(isTaggedLiteral: Boolean): AstNode {
        if (currentToken != Token.TEMPLATE_LITERAL) codeBug()
        val pos = ts.tokenBeg
        var end = ts.tokenEnd
        val lineno = lineNumber()
        val column = columnNumber()
        val elements = mutableListOf<AstNode>()
        val pn = TemplateLiteral(pos)

        var posChars = ts.tokenBeg + 1
        var tt = ts.readTemplateLiteral(isTaggedLiteral)
        while (tt == Token.TEMPLATE_LITERAL_SUBST) {
            elements.add(createTemplateLiteralCharacters(posChars))
            elements.add(expr(false))
            mustMatchToken(Token.RC, "msg.syntax", true)
            posChars = ts.tokenBeg + 1
            tt = ts.readTemplateLiteral(isTaggedLiteral)
        }
        if (tt == Token.ERROR) {
            return makeErrorNode()
        }
        elements.add(createTemplateLiteralCharacters(posChars))
        end = ts.tokenEnd
        pn.setElements(elements)
        pn.length = end - pos
        pn.setLineColumnNumber(lineno, column)

        return pn
    }

    private fun createTemplateLiteralCharacters(pos: Int): TemplateCharacters {
        val chars = TemplateCharacters(pos, ts.tokenEnd - pos - 1)
        chars.value = ts.string
        chars.rawValue = ts.rawString
        return chars
    }

    private fun createNumericLiteral(tt: Int, isProperty: Boolean): AstNode {
        var s = ts.string!!
        if (this.inUseStrictDirective && ts.isNumericOldOctal) {
            if (compilerEnv.languageVersion >= Context.VERSION_ES6 || !isProperty) {
                if (tt == Token.BIGINT) {
                    reportError("msg.no.old.octal.bigint")
                } else {
                    reportError("msg.no.old.octal.strict")
                }
            }
        }
        if (compilerEnv.languageVersion >= Context.VERSION_ES6 || !isProperty) {
            if (ts.isNumericBinary) {
                s = "0b$s"
            } else if (ts.isNumericOldOctal) {
                s = "0$s"
            } else if (ts.isNumericOctal) {
                s = "0o$s"
            } else if (ts.isNumericHex) {
                s = "0x$s"
            }
        }

        val result: AstNode =
            if (tt == Token.BIGINT) {
                BigIntLiteral(ts.tokenBeg, s + "n", ts.bigInt)
            } else {
                NumberLiteral(ts.tokenBeg, s, ts.number)
            }
        result.setLineColumnNumber(lineNumber(), columnNumber())
        return result
    }

    protected fun checkActivationName(name: String, token: Int) {
        if ("arguments" == name && currentScriptOrFn is FunctionNode) {
            // A usage of "arguments" means it has to be initialized. This check comes before the
            // insideFunctionBody one, because the usage may sit in a function's default arguments.
            (currentScriptOrFn as FunctionNode).requiresArgumentObject = true
        }

        if (!insideFunctionBody()) {
            return
        }
        var activation = false
        if ("arguments" == name &&
            // An arrow function does not generate arguments, so it needs no activation.
            (currentScriptOrFn as FunctionNode).functionType != FunctionNode.ARROW_FUNCTION
        ) {
            activation = true
        } else if (compilerEnv.activationNames?.contains(name) == true) {
            activation = true
        } else if ("length" == name) {
            if (token == Token.GETPROP &&
                compilerEnv.languageVersion == Context.VERSION_1_2
            ) {
                // Use of "length" in 1.2 requires an activation object.
                activation = true
            }
        }
        if (activation) {
            setRequiresActivation()
        }
    }

    protected fun setRequiresActivation() {
        if (insideFunctionBody()) {
            (currentScriptOrFn as FunctionNode).requiresActivation = true
        }
    }

    private fun checkCallRequiresActivation(pn: AstNode) {
        if ((pn.type == Token.NAME && "eval" == (pn as Name).identifier) ||
            (pn.type == Token.GETPROP && "eval" == (pn as PropertyGet).property?.identifier)
        ) {
            setRequiresActivation()
            setRequiresArgumentObject()
        }
    }

    protected fun setIsGenerator() {
        if (insideFunctionBody()) {
            (currentScriptOrFn as FunctionNode).isGenerator = true
        }
    }

    private fun setRequiresArgumentObject() {
        if (insideFunctionBody()) {
            (currentScriptOrFn as FunctionNode).requiresArgumentObject = true
        }
    }

    private fun checkBadIncDec(expr: UpdateExpression) {
        val op = removeParens(expr.operand!!)
        val tt = op.type
        if (!(tt == Token.NAME ||
                tt == Token.GETPROP ||
                tt == Token.GETELEM ||
                tt == Token.GET_REF ||
                tt == Token.CALL)
        ) {
            reportError(if (expr.type == Token.INC) "msg.bad.incr" else "msg.bad.decr")
        }
    }

    private fun makeErrorNode(): ErrorNode {
        val pn = ErrorNode(ts.tokenBeg, ts.tokenEnd - ts.tokenBeg)
        pn.setLineColumnNumber(lineNumber(), columnNumber())
        return pn
    }

    private fun saveNameTokenData(pos: Int, name: String?, lineno: Int, column: Int) {
        prevNameTokenStart = pos
        prevNameTokenString = name ?: ""
        prevNameTokenLineno = lineno
        prevNameTokenColumn = column
    }

    /**
     * The file offset of the start of the source line containing [pos]. A negative offset becomes
     * 0 and an offset past the end of the buffer uses the last source position. Returns -1 when
     * the environment is not in IDE mode, since only IDE mode keeps the source characters.
     */
    private fun lineBeginningFor(pos: Int): Int {
        val buf = sourceChars ?: return -1
        if (pos <= 0) {
            return 0
        }
        var p = pos
        if (p >= buf.size) {
            p = buf.size - 1
        }
        while (--p >= 0) {
            if (ScriptRuntime.isJSLineTerminator(buf[p].code)) {
                return p + 1 // the position after the newline
            }
        }
        return 0
    }

    private fun warnMissingSemi(pos: Int, end: Int) {
        // Could become a CompilerEnvirons setting with Never, Always and Permissive, where
        // Permissive would not warn for one-line functions like function (s) {return x+2}.
        if (compilerEnv.strictMode) {
            val linep = IntArray(2)
            val line = ts.getLine(end, linep)
            // This originally called lineBeginningFor, so IDE mode is special-cased here to
            // preserve its different line-offset handling.
            val beg = if (compilerEnv.ideMode) maxOf(pos, end - linep[1]) else pos
            if (line != null) {
                addStrictWarning("msg.missing.semi", "", beg, end - beg, linep[0], line, linep[1])
            } else {
                // No line information available, so report the warning at the current line.
                addStrictWarning("msg.missing.semi", "", beg, end - beg)
            }
        }
    }

    private fun warnTrailingComma(pos: Int, elems: List<*>, commaPos: Int) {
        if (compilerEnv.warnTrailingComma) {
            // Back up from the comma to the beginning of the line or the literal.
            var p = pos
            if (elems.isNotEmpty()) {
                p = (elems[0] as AstNode).position
            }
            p = maxOf(p, lineBeginningFor(commaPos))
            addWarning("msg.extra.trailing.comma", p, commaPos - p)
        }
    }

    /** Keeps the already-large [function] method from growing further. */
    internal inner class PerFunctionVariables(fnNode: FunctionNode) {
        private val savedCurrentScriptOrFn: ScriptNode?
        private val savedCurrentScope: Scope?
        private val savedEndFlags: Int
        private val savedInForInit: Boolean
        private val savedLabelSet: MutableMap<String, LabeledStatement>?
        private val savedLoopSet: MutableList<Loop>?
        private val savedLoopAndSwitchSet: MutableList<Jump>?
        private val savedHasUndefinedBeenRedefined: Boolean

        init {
            savedCurrentScriptOrFn = this@Parser.currentScriptOrFn
            this@Parser.currentScriptOrFn = fnNode

            savedCurrentScope = this@Parser.currentScope
            this@Parser.currentScope = fnNode

            savedLabelSet = this@Parser.labelSet
            this@Parser.labelSet = null

            savedLoopSet = this@Parser.loopSet
            this@Parser.loopSet = null

            savedLoopAndSwitchSet = this@Parser.loopAndSwitchSet
            this@Parser.loopAndSwitchSet = null

            savedEndFlags = this@Parser.endFlags
            this@Parser.endFlags = 0

            savedInForInit = this@Parser.inForInit
            this@Parser.inForInit = false

            // The current value is inherited on purpose.
            savedHasUndefinedBeenRedefined = this@Parser.hasUndefinedBeenRedefined
        }

        fun restore() {
            this@Parser.currentScriptOrFn = savedCurrentScriptOrFn
            this@Parser.currentScope = savedCurrentScope
            this@Parser.labelSet = savedLabelSet
            this@Parser.loopSet = savedLoopSet
            this@Parser.loopAndSwitchSet = savedLoopAndSwitchSet
            this@Parser.endFlags = savedEndFlags
            this@Parser.inForInit = savedInForInit
            this@Parser.hasUndefinedBeenRedefined = savedHasUndefinedBeenRedefined
        }
    }

    internal fun createPerFunctionVariables(fnNode: FunctionNode): PerFunctionVariables =
        PerFunctionVariables(fnNode)

    /**
     * Rewrites a destructuring assignment, whose left side parsed as an array or object literal,
     * into a series of assignments to the variables in [left] from property accesses on [right].
     *
     * @param type declaration type: [Token.VAR], [Token.LET] or -1
     * @param left array or object literal holding NAME nodes for the variables to assign
     * @param right the expression to assign from
     */
    internal fun createDestructuringAssignment(
        type: Int,
        left: Node,
        right: Node,
        defaultValue: AstNode?,
        transformer: Transformer?,
        isFunctionParameter: Boolean,
    ): Node {
        val tempName = currentScriptOrFn!!.getNextTempName()
        val result =
            destructuringAssignmentHelper(
                type,
                left,
                right,
                tempName,
                defaultValue,
                transformer,
                isFunctionParameter,
            )
        val comma = result.lastChild!!
        comma.addChildToBack(createName(tempName))
        return result
    }

    internal fun createDestructuringAssignment(
        type: Int,
        left: Node,
        right: Node,
        defaultValue: AstNode?,
        transformer: Transformer?,
    ): Node = createDestructuringAssignment(type, left, right, defaultValue, transformer, true)

    internal fun createDestructuringAssignment(
        type: Int,
        left: Node,
        right: Node,
        transformer: Transformer?,
    ): Node = createDestructuringAssignment(type, left, right, null, transformer, false)

    internal fun createDestructuringAssignment(
        type: Int,
        left: Node,
        right: Node,
        defaultValue: AstNode?,
    ): Node = createDestructuringAssignment(type, left, right, defaultValue, null, true)

    internal fun destructuringAssignmentHelper(
        variableType: Int,
        left: Node,
        right: Node,
        tempName: String,
        defaultValue: AstNode?,
        transformer: Transformer?,
        isFunctionParameter: Boolean,
    ): Node {
        val result = createScopeNode(Token.LETEXPR, left.lineno, left.column)
        result.addChildToFront(Node(Token.LET, createName(Token.NAME, tempName, right)))
        try {
            pushScope(result)
            defineSymbol(Token.LET, tempName, true)
        } finally {
            popScope()
        }
        val comma = Node(Token.COMMA)
        result.addChildToBack(comma)
        val destructuringNames = mutableListOf<String>()
        var empty = true
        var iteratorName: String? = null
        var lastResultName: String? = null
        if (left is ArrayLiteral) {
            val arrayResult =
                destructuringArray(
                    left,
                    variableType,
                    tempName,
                    comma,
                    destructuringNames,
                    defaultValue,
                    transformer,
                    isFunctionParameter,
                )
            empty = arrayResult.empty
            iteratorName = arrayResult.iteratorName
            lastResultName = arrayResult.lastResultName
        } else if (left is ObjectLiteral) {
            empty =
                destructuringObject(
                    left,
                    variableType,
                    tempName,
                    comma,
                    destructuringNames,
                    defaultValue,
                    transformer,
                    isFunctionParameter,
                )
        } else if (left.type == Token.GETPROP || left.type == Token.GETELEM) {
            when (variableType) {
                Token.CONST, Token.LET, Token.VAR -> reportError("msg.bad.assign.left")
            }
            comma.addChildToBack(simpleAssignment(left, createName(tempName), transformer))
        } else {
            reportError("msg.bad.assign.left")
        }
        if (empty) {
            // Avoid a COMMA node with no children; add a zero instead.
            comma.addChildToBack(createNumber(0.0))
        }

        // Close the iterator in the comma sequence when one was opened. This generates
        // !lastResult.done ? ((f = iterator.return) !== undefined ? f.call(iterator) : undefined)
        //                  : undefined
        if (isFunctionParameter && iteratorName != null && lastResultName != null) {
            // Allocate a temp for the return method.
            val returnMethodName = currentScriptOrFn!!.getNextTempName()
            defineSymbol(Token.LET, returnMethodName, true)

            // Check whether the iterator is done: !lastResult.done
            val getDone = Node(Token.GETPROP, createName(lastResultName), Node.newString("done"))
            val notDone = Node(Token.NOT, getDone)

            // Get iterator.return and store it: f = iterator.return
            val getReturn =
                Node(Token.GETPROP, createName(iteratorName), Node.newString("return"))
            val assignReturn =
                Node(
                    Token.SETNAME,
                    createName(Token.BINDNAME, returnMethodName, null),
                    getReturn,
                )

            // Check that the return method exists: (f = iterator.return) !== undefined
            val notUndefined = Node(Token.NE, assignReturn, Node(Token.UNDEFINED))

            // Call the return method: f.call(iterator)
            val getCall =
                Node(Token.GETPROP, createName(returnMethodName), Node.newString("call"))
            val callReturn = Node(Token.CALL, getCall)
            callReturn.addChildToBack(createName(iteratorName)) // the 'this' argument

            val innerTernary =
                Node(Token.HOOK, notUndefined, callReturn, Node(Token.UNDEFINED))

            val outerTernary = Node(Token.HOOK, notDone, innerTernary, Node(Token.UNDEFINED))

            comma.addChildToBack(outerTernary)
        }

        result.putProp(Node.DESTRUCTURING_NAMES, destructuringNames)
        return result
    }

    private class DestructuringArrayResult(
        val empty: Boolean,
        val iteratorName: String?,
        val lastResultName: String?,
    )

    private fun destructuringArray(
        array: ArrayLiteral,
        variableType: Int,
        tempName: String,
        parent: Node,
        destructuringNames: MutableList<String>,
        defaultValue: AstNode?,
        transformer: Transformer?,
        isFunctionParameter: Boolean,
    ): DestructuringArrayResult {
        var empty = true
        val setOp = if (variableType == Token.CONST) Token.SETCONST else Token.SETNAME
        var index = 0
        var defaultValuesSetup = false
        var iteratorSetup = false
        var iteratorName: String? = null
        var lastResultName: String? = null

        for (n in array.getElements()) {
            if (n.type == Token.EMPTY) {
                index++
                continue
            }

            val rightElem: Node

            if (defaultValue != null && !defaultValuesSetup) {
                setupDefaultValues(tempName, parent, defaultValue, setOp, transformer)
                defaultValuesSetup = true
            }

            // Set up the iterator for function parameters, after the default value is applied.
            // Only ES6+ uses the iterator protocol; older versions use index-based access.
            if (isFunctionParameter &&
                !iteratorSetup &&
                compilerEnv.languageVersion >= Context.VERSION_ES6
            ) {
                // Allocate temp names for iterator tracking.
                iteratorName = currentScriptOrFn!!.getNextTempName()
                lastResultName = currentScriptOrFn!!.getNextTempName()
                // Define the iterator temps, needed for strict mode.
                defineSymbol(Token.LET, iteratorName, true)
                defineSymbol(Token.LET, lastResultName, true)

                // Generate iterator = tempName[Symbol.iterator]() as pure AST:
                // CALL(GETELEM(tempName, GETPROP(NAME("Symbol"), "iterator")))
                val symbolName = createName("Symbol")
                val getIteratorProp = Node(Token.GETPROP, symbolName, Node.newString("iterator"))
                val getIteratorMethod = Node(Token.GETELEM, createName(tempName))
                getIteratorMethod.addChildToBack(getIteratorProp)
                val callIterator = Node(Token.CALL, getIteratorMethod)
                val iteratorAssign =
                    Node(
                        Token.SETNAME,
                        createName(Token.BINDNAME, iteratorName, null),
                        callIterator,
                    )
                parent.addChildToBack(iteratorAssign)
                iteratorSetup = true
                empty = false
            }

            // Generate the code that fetches the element.
            if (isFunctionParameter && iteratorName != null) {
                // ES6+: call iterator.next() and keep the whole result to check done later.
                val getNextProp =
                    Node(Token.GETPROP, createName(iteratorName), Node.newString("next"))
                val callNext = Node(Token.CALL, getNextProp)
                val storeResult =
                    Node(
                        Token.SETNAME,
                        createName(Token.BINDNAME, lastResultName!!, null),
                        callNext,
                    )
                parent.addChildToBack(storeResult)
                // Extract .value from the result.
                val elemTempName = currentScriptOrFn!!.getNextTempName()
                // Define the element temp, needed for strict mode.
                defineSymbol(Token.LET, elemTempName, true)
                val getValue =
                    Node(Token.GETPROP, createName(lastResultName), Node.newString("value"))
                val storeElem =
                    Node(
                        Token.SETNAME,
                        createName(Token.BINDNAME, elemTempName, null),
                        getValue,
                    )
                parent.addChildToBack(storeElem)
                // Use the temp for element access.
                rightElem = createName(elemTempName)
                empty = false
            } else {
                // Regular index-based access for var, let and const.
                rightElem = Node(Token.GETELEM, createName(tempName), createNumber(index.toDouble()))
            }

            if (n.type == Token.NAME) {
                // [x] = [1]
                val name = n.string!!
                parent.addChildToBack(
                    Node(setOp, createName(Token.BINDNAME, name, null), rightElem),
                )
                if (variableType != -1) {
                    defineSymbol(variableType, name, true)
                    destructuringNames.add(name)
                }
            } else if (n.type == Token.ASSIGN) {
                // [x = 1] = [2]
                processDestructuringDefaults(
                    variableType,
                    parent,
                    destructuringNames,
                    n as Assignment,
                    rightElem,
                    setOp,
                    transformer,
                    isFunctionParameter,
                )
            } else {
                parent.addChildToBack(
                    destructuringAssignmentHelper(
                        variableType,
                        n,
                        rightElem,
                        currentScriptOrFn!!.getNextTempName(),
                        null,
                        transformer,
                        isFunctionParameter,
                    ),
                )
            }
            index++
            empty = false
        }

        return DestructuringArrayResult(empty, iteratorName, lastResultName)
    }

    private fun processDestructuringDefaults(
        variableType: Int,
        parent: Node,
        destructuringNames: MutableList<String>,
        n: Assignment,
        rightElem: Node,
        setOp: Int,
        transformer: Transformer?,
        isFunctionParameter: Boolean,
    ) {
        val left: Node = n.left!!
        val right: Node
        if (left.type == Token.NAME) {
            val name = left.string!!
            // x = (x == undefined)
            //         ? (($1[0] == undefined) ? 1 : $1[0])
            //         : x
            right = transformer?.transform(n.right!!) ?: n.right!!

            val condInner =
                Node(
                    Token.HOOK,
                    Node(
                        Token.SHEQ,
                        KeywordLiteral().apply { type = Token.UNDEFINED },
                        rightElem,
                    ),
                    right,
                    rightElem,
                )

            val cond =
                Node(
                    Token.HOOK,
                    Node(
                        Token.SHEQ,
                        KeywordLiteral().apply { type = Token.UNDEFINED },
                        createName(name),
                    ),
                    condInner,
                    left,
                )

            // Store it so it can be transformed later.
            if (transformer == null) {
                currentScriptOrFn!!.putDestructuringRvalues(condInner, right)
            }

            parent.addChildToBack(Node(setOp, createName(Token.BINDNAME, name, null), cond))
            if (variableType != -1) {
                defineSymbol(variableType, name, true)
                destructuringNames.add(name)
            }
        } else {
            // Nested destructuring patterns with defaults, such as [[x, y, z] = [4, 5, 6]].
            if (left is ArrayLiteral || left is ObjectLiteral) {
                right = transformer?.transform(n.right!!) ?: n.right!!

                val condDefault =
                    Node(
                        Token.HOOK,
                        Node(
                            Token.SHEQ,
                            KeywordLiteral().apply { type = Token.UNDEFINED },
                            rightElem,
                        ),
                        right,
                        rightElem,
                    )

                if (transformer == null) {
                    currentScriptOrFn!!.putDestructuringRvalues(condDefault, right)
                }

                parent.addChildToBack(
                    destructuringAssignmentHelper(
                        variableType,
                        left,
                        condDefault,
                        currentScriptOrFn!!.getNextTempName(),
                        null,
                        transformer,
                        isFunctionParameter,
                    ),
                )
            } else {
                reportError("msg.bad.assign.left")
            }
        }
    }

    private fun setupDefaultValues(
        tempName: String,
        parent: Node,
        defaultValue: AstNode?,
        setOp: Int,
        transformer: Transformer?,
    ) {
        if (defaultValue != null) {
            // A default value can stand in for tempName when that is undefined, i.e.
            // $1 = ($1 == undefined) ? defaultValue : $1
            val defaultRvalue: Node = transformer?.transform(defaultValue) ?: defaultValue

            val undefined = KeywordLiteral().apply { type = Token.UNDEFINED }

            val condDefault =
                Node(
                    Token.HOOK,
                    Node(Token.SHEQ, createName(tempName), undefined),
                    defaultRvalue,
                    createName(tempName),
                )

            if (transformer == null) {
                currentScriptOrFn!!.putDestructuringRvalues(condDefault, defaultRvalue)
            }

            val setDefault =
                Node(setOp, createName(Token.BINDNAME, tempName, null), condDefault)
            parent.addChildToBack(setDefault)
        }
    }

    internal fun destructuringObject(
        node: ObjectLiteral,
        variableType: Int,
        tempName: String,
        parent: Node,
        destructuringNames: MutableList<String>,
        defaultValue: AstNode?, // default value used in function parameter declarations
        transformer: Transformer?,
        isFunctionParameter: Boolean,
    ): Boolean {
        var empty = true
        val setOp = if (variableType == Token.CONST) Token.SETCONST else Token.SETNAME
        var defaultValuesSetup = false

        for (abstractProp in node.getElements()) {
            if (abstractProp is SpreadObjectProperty) {
                reportError("msg.no.object.rest")
                return false
            }
            val prop = abstractProp as ObjectProperty

            // This is sometimes called from IRFactory while running regression tests, where the
            // token stream is not set. Deal with it.
            val lineno = lineNumber()
            val column = columnNumber()
            val id = prop.key

            val rightElem: Node
            if (id is Name) {
                rightElem = Node(
                    Token.GETPROP,
                    createName(tempName),
                    Node.newString(id.identifier!!),
                )
            } else if (id is StringLiteral) {
                rightElem = Node(
                    Token.GETPROP,
                    createName(tempName),
                    Node.newString(id.value!!),
                )
            } else if (id is NumberLiteral) {
                rightElem = Node(
                    Token.GETELEM,
                    createName(tempName),
                    createNumber(id.number.toInt().toDouble()),
                )
            } else if (id is ComputedPropertyKey) {
                reportError("msg.bad.computed.property.in.destruct")
                return false
            } else {
                throw codeBug()
            }

            rightElem.setLineColumnNumber(lineno, column)
            if (defaultValue != null && !defaultValuesSetup) {
                setupDefaultValues(tempName, parent, defaultValue, setOp, transformer)
                defaultValuesSetup = true
            }

            val value = prop.value!!
            if (value.type == Token.NAME) {
                val name = (value as Name).identifier!!
                parent.addChildToBack(
                    Node(setOp, createName(Token.BINDNAME, name, null), rightElem),
                )
                if (variableType != -1) {
                    defineSymbol(variableType, name, true)
                    destructuringNames.add(name)
                }
            } else if (value.type == Token.ASSIGN) {
                processDestructuringDefaults(
                    variableType,
                    parent,
                    destructuringNames,
                    value as Assignment,
                    rightElem,
                    setOp,
                    transformer,
                    isFunctionParameter,
                )
            } else {
                parent.addChildToBack(
                    destructuringAssignmentHelper(
                        variableType,
                        value,
                        rightElem,
                        currentScriptOrFn!!.getNextTempName(),
                        null,
                        transformer,
                        isFunctionParameter,
                    ),
                )
            }
            empty = false
        }
        return empty
    }

    protected fun createName(name: String): Node {
        checkActivationName(name, Token.NAME)
        return Node.newString(Token.NAME, name)
    }

    protected fun createName(type: Int, name: String, child: Node?): Node {
        val result = createName(name)
        result.type = type
        if (child != null) result.addChildToBack(child)
        return result
    }

    protected fun createNumber(number: Double): Node = Node.newNumber(number)

    /** Creates a node that can hold lexically scoped variable definitions, i.e. let declarations. */
    protected fun createScopeNode(token: Int, lineno: Int, column: Int): Scope {
        val scope = Scope()
        scope.type = token
        scope.setLineColumnNumber(lineno, column)
        return scope
    }

    // A quick tour of some interpreter bytecodes.
    //
    // GETPROP - normal foo.bar property access; the right side is a name
    // GETELEM - normal foo[bar] element access; the right side is an expression
    // SETPROP - assignment when the left side is a GETPROP
    // SETELEM - assignment when the left side is a GETELEM
    // DELPROP - delete foo.bar or foo[bar]
    //
    // GET_REF, SET_REF, DEL_REF - get, set or delete on a right-hand side expression, possibly
    // with no explicit left-hand side, that does not use the normal ScriptableObject accessors and
    // supplies its own instead. Such an object implements Ref; SpecialRef (for __proto__ and
    // friends) is the only implementation left here. The runtime notices these bytecodes and
    // delegates get, set and delete to the object.
    //
    // BINDNAME - used in assignments. The left side is evaluated first to get the object holding
    // the property, so it stays the same object regardless of side effects in the right side.
    internal fun simpleAssignment(left: Node, right: Node): Node =
        simpleAssignment(left, right, null)

    internal fun simpleAssignment(left: Node, right: Node, transformer: Transformer?): Node {
        val nodeType = left.type
        when (nodeType) {
            Token.UNDEFINED -> {
                return Node(Token.SETNAME, Node.newString(Token.BINDNAME, "undefined"), right)
            }

            Token.NAME -> {
                val name = (left as Name).identifier
                if (inUseStrictDirective && ("eval" == name || "arguments" == name)) {
                    reportError("msg.bad.id.strict", name)
                }
                left.type = Token.BINDNAME
                return Node(Token.SETNAME, left, right)
            }

            Token.GETPROP, Token.GETELEM -> {
                val obj: Node
                val id: Node
                // A PropertyGet or ElementGet means this is the parse pass. Those classes could
                // instead override getFirstChild and getLastChild, but that is just as ugly as
                // this casting.
                if (left is PropertyGet) {
                    val target = left.target!!
                    obj = transformer?.transform(target) ?: target
                    id = left.property!!
                } else if (left is ElementGet) {
                    val target = left.target!!
                    val elem = left.element!!
                    obj = transformer?.transform(target) ?: target
                    id = transformer?.transform(elem) ?: elem
                } else {
                    // This branch runs during the IRFactory transform pass.
                    obj = left.firstChild!!
                    id = left.lastChild!!
                }
                val type: Int
                if (nodeType == Token.GETPROP) {
                    type = Token.SETPROP
                    // See https://bugzilla.mozilla.org/show_bug.cgi?id=492036. The AST generates
                    // NAME tokens for GETPROP ids where the old parser generated STRING nodes.
                    // Without this the codegen fails on code like "var obj={p:3};[obj.p]=[9];".
                    id.type = Token.STRING
                } else {
                    type = Token.SETELEM
                }
                return Node(type, obj, id, right)
            }

            Token.GET_REF -> {
                val ref = left.firstChild!!
                checkMutableReference(ref)
                return Node(Token.SET_REF, ref, right)
            }
        }

        throw codeBug()
    }

    protected fun checkMutableReference(n: Node) {
        val memberTypeFlags = n.getIntProp(Node.MEMBER_TYPE_PROP, 0)
        if ((memberTypeFlags and Node.DESCENDANTS_FLAG) != 0) {
            reportError("msg.bad.assign.left")
        }
    }

    /** Removes any [ParenthesizedExpression] wrappers. */
    protected fun removeParens(node: AstNode): AstNode {
        var n = node
        while (n is ParenthesizedExpression) {
            n = n.expression!!
        }
        return n
    }

    internal fun markDestructuring(node: AstNode?) {
        if (node is DestructuringForm) {
            node.isDestructuring = true
        } else if (node is ParenthesizedExpression) {
            markDestructuring(node.expression)
        }
    }

    // Throws a failed assertion with some helpful debugging info.
    private fun codeBug(): RuntimeException =
        throw Kit.codeBug(
            "ts.cursor=" + ts.cursor + ", ts.tokenBeg=" + ts.tokenBeg +
                ", currentToken=" + currentToken,
        )

    fun inUseStrictDirective(): Boolean = inUseStrictDirective

    fun reportErrorsIfExists(baseLineno: Int) {
        if (this.syntaxErrorCount != 0) {
            val msg = lookupMessage("msg.got.syntax.errors", this.syntaxErrorCount.toString())
            if (!compilerEnv.ideMode) {
                throw errorReporter.runtimeError(msg, sourceURI, baseLineno, null, 0)
            }
        }
    }

    interface CurrentPositionReporter {
        val position: Int
        val length: Int
        val lineno: Int
        val line: String?
        val offset: Int
    }

    companion object {
        /** Maximum number of allowed function or constructor arguments, following SpiderMonkey. */
        const val ARGC_LIMIT = 1 shl 16

        // TokenInformation flags: currentFlaggedToken stores them together with the token type.
        internal const val CLEAR_TI_MASK = 0xFFFF // mask that clears the token information bits
        internal const val TI_AFTER_EOL = 1 shl 16 // first token of the source line
        internal const val TI_CHECK_LABEL = 1 shl 17 // check for a label

        private const val PROP_ENTRY = 1
        private const val GET_ENTRY = 2
        private const val SET_ENTRY = 4
        private const val METHOD_ENTRY = 8

        /**
         * Computes the absolute end offset of [n]. Use with caution: it assumes the position is
         * absolute, which only holds before the node is added to its parent.
         */
        private fun getNodeEnd(n: AstNode): Int = n.position + n.length

        /** End of the node. Assumes the node does NOT have a parent yet. */
        private fun nodeEnd(node: AstNode): Int = node.position + node.length

        private fun getDirective(n: AstNode): String? {
            if (n is ExpressionStatement) {
                val e = n.expression
                if (e is StringLiteral) {
                    return e.value
                }
            }
            return null
        }

        /** True when every bit in [mask] is set in [after] but not in [before]. */
        private fun nowAllSet(before: Int, after: Int, mask: Int): Boolean =
            ((before and mask) != mask) && ((after and mask) == mask)

        private fun isNotValidSimpleAssignmentTarget(pn: AstNode): Boolean {
            if (pn.type == Token.GETPROP) {
                return isNotValidSimpleAssignmentTarget((pn as PropertyGet).left!!)
            }
            return pn.type == Token.QUESTION_DOT
        }
    }
}
