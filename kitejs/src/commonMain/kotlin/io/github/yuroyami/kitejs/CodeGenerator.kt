/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.Icode.Companion.Icode_CALLSPECIAL
import io.github.yuroyami.kitejs.Icode.Companion.Icode_CALL_ON_SUPER
import io.github.yuroyami.kitejs.Icode.Companion.Icode_CLOSURE_EXPR
import io.github.yuroyami.kitejs.Icode.Companion.Icode_CLOSURE_STMT
import io.github.yuroyami.kitejs.Icode.Companion.Icode_DEBUGGER
import io.github.yuroyami.kitejs.Icode.Companion.Icode_DELNAME
import io.github.yuroyami.kitejs.Icode.Companion.Icode_DELPROP_SUPER
import io.github.yuroyami.kitejs.Icode.Companion.Icode_DUP
import io.github.yuroyami.kitejs.Icode.Companion.Icode_DUP2
import io.github.yuroyami.kitejs.Icode.Companion.Icode_ELEM_AND_THIS
import io.github.yuroyami.kitejs.Icode.Companion.Icode_ELEM_AND_THIS_OPTIONAL
import io.github.yuroyami.kitejs.Icode.Companion.Icode_ELEM_INC_DEC
import io.github.yuroyami.kitejs.Icode.Companion.Icode_ENTERDQ
import io.github.yuroyami.kitejs.Icode.Companion.Icode_GENERATOR
import io.github.yuroyami.kitejs.Icode.Companion.Icode_GENERATOR_END
import io.github.yuroyami.kitejs.Icode.Companion.Icode_GENERATOR_RETURN
import io.github.yuroyami.kitejs.Icode.Companion.Icode_GETVAR1
import io.github.yuroyami.kitejs.Icode.Companion.Icode_GOSUB
import io.github.yuroyami.kitejs.Icode.Companion.Icode_IFEQ_POP
import io.github.yuroyami.kitejs.Icode.Companion.Icode_IF_NOT_NULL_UNDEF
import io.github.yuroyami.kitejs.Icode.Companion.Icode_IF_NULL_UNDEF
import io.github.yuroyami.kitejs.Icode.Companion.Icode_INTNUMBER
import io.github.yuroyami.kitejs.Icode.Companion.Icode_LEAVEDQ
import io.github.yuroyami.kitejs.Icode.Companion.Icode_LINE
import io.github.yuroyami.kitejs.Icode.Companion.Icode_LITERAL_GETTER
import io.github.yuroyami.kitejs.Icode.Companion.Icode_LITERAL_KEY_SET
import io.github.yuroyami.kitejs.Icode.Companion.Icode_LITERAL_NEW_ARRAY
import io.github.yuroyami.kitejs.Icode.Companion.Icode_LITERAL_NEW_OBJECT
import io.github.yuroyami.kitejs.Icode.Companion.Icode_LITERAL_SET
import io.github.yuroyami.kitejs.Icode.Companion.Icode_LITERAL_SETTER
import io.github.yuroyami.kitejs.Icode.Companion.Icode_LOCAL_CLEAR
import io.github.yuroyami.kitejs.Icode.Companion.Icode_METHOD_EXPR
import io.github.yuroyami.kitejs.Icode.Companion.Icode_NAME_AND_THIS
import io.github.yuroyami.kitejs.Icode.Companion.Icode_NAME_AND_THIS_OPTIONAL
import io.github.yuroyami.kitejs.Icode.Companion.Icode_NAME_INC_DEC
import io.github.yuroyami.kitejs.Icode.Companion.Icode_ONE
import io.github.yuroyami.kitejs.Icode.Companion.Icode_POP
import io.github.yuroyami.kitejs.Icode.Companion.Icode_POP_RESULT
import io.github.yuroyami.kitejs.Icode.Companion.Icode_PROP_AND_THIS
import io.github.yuroyami.kitejs.Icode.Companion.Icode_PROP_AND_THIS_OPTIONAL
import io.github.yuroyami.kitejs.Icode.Companion.Icode_PROP_INC_DEC
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REF_INC_DEC
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_BIGINT1
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_BIGINT2
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_BIGINT4
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_BIGINT_C0
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_IND1
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_IND2
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_IND4
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_IND_C0
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_STR1
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_STR2
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_STR4
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_STR_C0
import io.github.yuroyami.kitejs.Icode.Companion.Icode_RETSUB
import io.github.yuroyami.kitejs.Icode.Companion.Icode_RETUNDEF
import io.github.yuroyami.kitejs.Icode.Companion.Icode_SCOPE_SAVE
import io.github.yuroyami.kitejs.Icode.Companion.Icode_SETCONST
import io.github.yuroyami.kitejs.Icode.Companion.Icode_SETCONSTVAR
import io.github.yuroyami.kitejs.Icode.Companion.Icode_SETCONSTVAR1
import io.github.yuroyami.kitejs.Icode.Companion.Icode_SETVAR1
import io.github.yuroyami.kitejs.Icode.Companion.Icode_SHORTNUMBER
import io.github.yuroyami.kitejs.Icode.Companion.Icode_SPARE_ARRAYLIT
import io.github.yuroyami.kitejs.Icode.Companion.Icode_SPREAD
import io.github.yuroyami.kitejs.Icode.Companion.Icode_STARTSUB
import io.github.yuroyami.kitejs.Icode.Companion.Icode_SWAP
import io.github.yuroyami.kitejs.Icode.Companion.Icode_TAIL_CALL
import io.github.yuroyami.kitejs.Icode.Companion.Icode_TEMPLATE_LITERAL_CALLSITE
import io.github.yuroyami.kitejs.Icode.Companion.Icode_TYPEOFNAME
import io.github.yuroyami.kitejs.Icode.Companion.Icode_UNDEF
import io.github.yuroyami.kitejs.Icode.Companion.Icode_VALUE_AND_THIS
import io.github.yuroyami.kitejs.Icode.Companion.Icode_VALUE_AND_THIS_OPTIONAL
import io.github.yuroyami.kitejs.Icode.Companion.Icode_VAR_INC_DEC
import io.github.yuroyami.kitejs.Icode.Companion.Icode_YIELD_STAR
import io.github.yuroyami.kitejs.Icode.Companion.Icode_ZERO
import io.github.yuroyami.kitejs.ast.FunctionNode
import io.github.yuroyami.kitejs.ast.Jump
import io.github.yuroyami.kitejs.ast.ScriptNode

/**
 * Turns an IR tree into icode, the byte array the interpreter runs. One instance handles one script
 * or function; nested functions get their own.
 */
internal class CodeGenerator<T : ScriptOrFn<T>> {

    private lateinit var compilerEnv: CompilerEnvirons
    private var itsInFunctionFlag = false
    private var itsInTryFlag = false
    private lateinit var itsData: InterpreterData.Builder<T>
    private lateinit var builder: JSDescriptor.Builder<T>
    private lateinit var scriptOrFn: ScriptNode
    private var iCodeTop = 0
    private var stackDepth = 0
    private var lineNumber = -1
    private var doubleTableTop = 0
    private val strings = HashMap<String, Int>()
    private val bigInts = HashMap<KBigInt, Int>()
    private var localTop = 0
    private var labelTable: IntArray? = null
    private var labelTableTop = 0
    private var fixupTable: LongArray? = null
    private var fixupTableTop = 0
    private val literalIds = ArrayList<Any?>()
    private var exceptionTableTop = 0

    fun compile(compilerEnv: CompilerEnvirons, tree: ScriptNode, rawSource: String?, returnFunction: Boolean): JSDescriptor<T> {
        this.compilerEnv = compilerEnv
        NodeTransformer().transform(tree, compilerEnv)
        scriptOrFn = if (returnFunction) tree.getFunctionNode(0) else tree
        builder = JSDescriptor.Builder()
        itsData = InterpreterData.Builder()
        builder.code = itsData
        if (returnFunction) {
            CodeGenUtils.fillInForTopLevelFunction(builder, scriptOrFn as FunctionNode, rawSource, compilerEnv)
            generateFunctionICode()
        } else {
            CodeGenUtils.fillInForScript(builder, scriptOrFn, rawSource, compilerEnv)
            CodeGenUtils.setConstructor(builder, scriptOrFn)
            generateICodeFromTree(scriptOrFn)
        }
        return builder.build {}
    }

    private fun generateFunctionICode() {
        itsInFunctionFlag = true
        val theFunction = scriptOrFn as FunctionNode
        CodeGenUtils.setConstructor(builder, theFunction)
        if (theFunction.isGenerator) {
            val paramInitBlock = theFunction.generatorParamInitBlock
            if (paramInitBlock != null) {
                var paramInit = paramInitBlock.firstChild
                while (paramInit != null) {
                    visitStatement(paramInit, 0)
                    paramInit = paramInit.next
                }
            }
            val functionCount = theFunction.functionCount
            for (i in 0 until functionCount) {
                val fn = theFunction.getFunctionNode(i)
                if (fn.functionType == FunctionNode.FUNCTION_STATEMENT) addIndexOp(Icode_CLOSURE_STMT, i)
            }
            addIcode(Icode_GENERATOR)
            addUint16(theFunction.baseLineno and 0xFFFF)
        }
        generateICodeFromTree(theFunction.lastChild!!)
    }

    private fun generateICodeFromTree(tree: Node) {
        generateNestedFunctions()
        generateRegExpLiterals()
        generateTemplateLiterals()
        visitStatement(tree, 0)
        fixLabelGotos()
        // A script leaves its last expression's value behind.
        if (builder.functionType == 0) addToken(Token.RETURN_RESULT)

        if (itsData.itsICode.size != iCodeTop) itsData.itsICode = itsData.itsICode.copyOf(iCodeTop)

        if (strings.isEmpty()) {
            itsData.itsStringTable = emptyArray()
        } else {
            val table = arrayOfNulls<String>(strings.size)
            for ((str, index) in strings) {
                if (table[index] != null) throw Kit.codeBug()
                table[index] = str
            }
            itsData.itsStringTable = table
        }

        if (doubleTableTop == 0) {
            itsData.itsDoubleTable = null
        } else if (itsData.itsDoubleTable!!.size != doubleTableTop) {
            itsData.itsDoubleTable = itsData.itsDoubleTable!!.copyOf(doubleTableTop)
        }

        if (bigInts.isEmpty()) {
            itsData.itsBigIntTable = emptyArray()
        } else {
            val table = arrayOfNulls<KBigInt>(bigInts.size)
            for ((bigInt, index) in bigInts) {
                if (table[index] != null) throw Kit.codeBug()
                table[index] = bigInt
            }
            itsData.itsBigIntTable = table
        }

        val exceptionTable = itsData.itsExceptionTable
        if (exceptionTableTop != 0 && exceptionTable!!.size != exceptionTableTop) {
            itsData.itsExceptionTable = exceptionTable.copyOf(exceptionTableTop)
        }

        itsData.itsMaxVars = scriptOrFn.paramAndVarCount
        itsData.itsMaxFrameArray = itsData.itsMaxVars + itsData.itsMaxLocals + itsData.itsMaxStack
        if (literalIds.isNotEmpty()) itsData.literalIds = literalIds.toTypedArray()
    }

    private fun generateNestedFunctions() {
        val functionCount = scriptOrFn.functionCount
        if (functionCount == 0) return
        // The descriptor tree carries the nested functions; the data holds nothing of its own.
        for (i in 0 until functionCount) {
            val fn = scriptOrFn.getFunctionNode(i)
            val gen = CodeGenerator<JSFunction>()
            gen.compilerEnv = compilerEnv
            gen.scriptOrFn = fn
            gen.builder = builder.createChildBuilder()
            gen.itsData = InterpreterData.Builder()
            gen.builder.code = gen.itsData
            CodeGenUtils.fillInForNestedFunction(gen.builder, builder, fn)
            gen.generateFunctionICode()
        }
    }

    private fun generateRegExpLiterals() {
        val n = scriptOrFn.regexpCount
        if (n == 0) return
        val cx = Context.getContext()
        val rep = ScriptRuntime.checkRegExpProxy(cx)
        val array = arrayOfNulls<Any?>(n)
        for (i in 0 until n) {
            array[i] = rep.compileRegExp(cx, scriptOrFn.getRegexpString(i)!!, scriptOrFn.getRegexpFlags(i))
        }
        itsData.itsRegExpLiterals = array
    }

    private fun generateTemplateLiterals() {
        val n = scriptOrFn.templateLiteralCount
        if (n == 0) return
        val array = arrayOfNulls<Any?>(n)
        for (i in 0 until n) {
            val strings = scriptOrFn.getTemplateLiteralStrings(i)
            var j = 0
            val values = arrayOfNulls<String>(strings.size * 2)
            for (s in strings) {
                values[j++] = s.value
                values[j++] = s.rawValue
            }
            array[i] = values
        }
        itsData.itsTemplateLiterals = array
    }

    private fun updateLineNumber(node: Node) {
        val lineno = node.lineno
        if (lineno != lineNumber && lineno >= 0) {
            if (itsData.firstLinePC < 0) itsData.firstLinePC = lineno
            lineNumber = lineno
            addIcode(Icode_LINE)
            addUint16(lineno and 0xFFFF)
        }
    }

    private fun badTree(node: Node): RuntimeException = RuntimeException(node.toString())

    private fun visitStatement(node: Node, initialStackDepth: Int) {
        val type = node.type
        var child = node.firstChild
        when (type) {
            Token.FUNCTION -> {
                val fnIndex = node.getExistingIntProp(Node.FUNCTION_PROP)
                val fnType = scriptOrFn.getFunctionNode(fnIndex).functionType
                if (fnType == FunctionNode.FUNCTION_EXPRESSION_STATEMENT) {
                    addIndexOp(Icode_CLOSURE_STMT, fnIndex)
                } else if (fnType != FunctionNode.FUNCTION_STATEMENT) {
                    throw Kit.codeBug()
                }
                if (compilerEnv.languageVersion < Context.VERSION_ES6 && !itsInFunctionFlag) {
                    addIndexOp(Icode_CLOSURE_EXPR, fnIndex)
                    stackChange(1)
                    addIcode(Icode_POP_RESULT)
                    stackChange(-1)
                }
            }
            Token.LABEL, Token.LOOP, Token.BLOCK, Token.EMPTY, Token.WITH, Token.SCRIPT -> {
                if (type != Token.SCRIPT) updateLineNumber(node)
                while (child != null) {
                    visitStatement(child, initialStackDepth)
                    child = child.next
                }
            }
            Token.ENTERWITH -> {
                visitExpression(child!!, 0)
                addToken(Token.ENTERWITH)
                stackChange(-1)
            }
            Token.LEAVEWITH -> addToken(Token.LEAVEWITH)
            Token.LOCAL_BLOCK -> {
                val local = allocLocal()
                node.putIntProp(Node.LOCAL_PROP, local)
                updateLineNumber(node)
                while (child != null) {
                    visitStatement(child, initialStackDepth)
                    child = child.next
                }
                addIndexOp(Icode_LOCAL_CLEAR, local)
                releaseLocal(local)
            }
            Token.DEBUGGER -> addIcode(Icode_DEBUGGER)
            Token.SWITCH -> {
                updateLineNumber(node)
                visitExpression(child!!, 0)
                var caseNode = child.next as Jump?
                while (caseNode != null) {
                    if (caseNode.type != Token.CASE) throw badTree(caseNode)
                    val test = caseNode.firstChild!!
                    addIcode(Icode_DUP)
                    stackChange(1)
                    visitExpression(test, 0)
                    addToken(Token.SHEQ)
                    stackChange(-1)
                    addGoto(caseNode.target!!, Icode_IFEQ_POP)
                    stackChange(-1)
                    caseNode = caseNode.next as Jump?
                }
                addIcode(Icode_POP)
                stackChange(-1)
            }
            Token.TARGET -> markTargetLabel(node)
            Token.IFEQ, Token.IFNE -> {
                val target = (node as Jump).target!!
                visitExpression(child!!, 0)
                addGoto(target, type)
                stackChange(-1)
            }
            Token.GOTO -> addGoto((node as Jump).target!!, type)
            Token.JSR -> addGoto((node as Jump).target!!, Icode_GOSUB)
            Token.FINALLY -> {
                stackChange(1)
                val finallyRegister = getLocalBlockRef(node)
                addIndexOp(Icode_STARTSUB, finallyRegister)
                stackChange(-1)
                while (child != null) {
                    visitStatement(child, initialStackDepth)
                    child = child.next
                }
                addIndexOp(Icode_RETSUB, finallyRegister)
            }
            Token.EXPR_VOID, Token.EXPR_RESULT -> {
                updateLineNumber(node)
                visitExpression(child!!, 0)
                addIcode(if (type == Token.EXPR_VOID) Icode_POP else Icode_POP_RESULT)
                stackChange(-1)
            }
            Token.TRY -> {
                val tryNode = node as Jump
                val exceptionObjectLocal = getLocalBlockRef(tryNode)
                val scopeLocal = allocLocal()
                addIndexOp(Icode_SCOPE_SAVE, scopeLocal)
                val tryStart = iCodeTop
                val savedFlag = itsInTryFlag
                itsInTryFlag = true
                while (child != null) {
                    visitStatement(child, initialStackDepth)
                    child = child.next
                }
                itsInTryFlag = savedFlag
                val catchTarget = tryNode.target
                if (catchTarget != null) {
                    val catchStartPC = labelTable!![getTargetLabel(catchTarget)]
                    addExceptionHandler(tryStart, catchStartPC, catchStartPC, false, exceptionObjectLocal, scopeLocal)
                }
                val finallyTarget = tryNode.finallyTarget
                if (finallyTarget != null) {
                    val finallyStartPC = labelTable!![getTargetLabel(finallyTarget)]
                    addExceptionHandler(tryStart, finallyStartPC, finallyStartPC, true, exceptionObjectLocal, scopeLocal)
                }
                addIndexOp(Icode_LOCAL_CLEAR, scopeLocal)
                releaseLocal(scopeLocal)
            }
            Token.CATCH_SCOPE -> {
                val localIndex = getLocalBlockRef(node)
                val scopeIndex = node.getExistingIntProp(Node.CATCH_SCOPE_PROP)
                val name = if (child!!.type == Token.NAME) child.string!! else ""
                child = child.next
                visitExpression(child!!, 0)
                addStringPrefix(name)
                addIndexPrefix(localIndex)
                addToken(Token.CATCH_SCOPE)
                addUint8(if (scopeIndex != 0) 1 else 0)
                stackChange(-1)
            }
            Token.THROW -> {
                updateLineNumber(node)
                visitExpression(child!!, 0)
                addToken(Token.THROW)
                addUint16(lineNumber and 0xFFFF)
                stackChange(-1)
            }
            Token.RETHROW -> {
                updateLineNumber(node)
                addIndexOp(Token.RETHROW, getLocalBlockRef(node))
            }
            Token.RETURN -> {
                updateLineNumber(node)
                if (node.getIntProp(Node.GENERATOR_END_PROP, 0) != 0) {
                    if (child == null || compilerEnv.languageVersion < Context.VERSION_ES6) {
                        addIcode(Icode_GENERATOR_END)
                        addUint16(lineNumber and 0xFFFF)
                    } else {
                        visitExpression(child, ECF_TAIL)
                        addIcode(Icode_GENERATOR_RETURN)
                        addUint16(lineNumber and 0xFFFF)
                        stackChange(-1)
                    }
                } else if (child == null) {
                    addIcode(Icode_RETUNDEF)
                } else {
                    visitExpression(child, ECF_TAIL)
                    addToken(Token.RETURN)
                    stackChange(-1)
                }
            }
            Token.RETURN_RESULT -> {
                updateLineNumber(node)
                addToken(Token.RETURN_RESULT)
            }
            Token.ENUM_INIT_KEYS, Token.ENUM_INIT_VALUES, Token.ENUM_INIT_ARRAY, Token.ENUM_INIT_VALUES_IN_ORDER -> {
                visitExpression(child!!, 0)
                addIndexOp(type, getLocalBlockRef(node))
                stackChange(-1)
            }
            Icode_GENERATOR -> {}
            else -> throw badTree(node)
        }
        if (stackDepth != initialStackDepth) throw Kit.codeBug()
    }

    private fun visitExpression(node: Node, contextFlags: Int) {
        val type = node.type
        var child = node.firstChild
        val savedStackDepth = stackDepth
        when (type) {
            Token.FUNCTION -> {
                val fnIndex = node.getExistingIntProp(Node.FUNCTION_PROP)
                val fn = scriptOrFn.getFunctionNode(fnIndex)
                if (fn.functionType != FunctionNode.FUNCTION_EXPRESSION && fn.functionType != FunctionNode.ARROW_FUNCTION) {
                    throw Kit.codeBug()
                }
                if (fn.isMethodDefinition) addIndexOp(Icode_METHOD_EXPR, fnIndex) else addIndexOp(Icode_CLOSURE_EXPR, fnIndex)
                stackChange(1)
            }
            Token.LOCAL_LOAD -> {
                addIndexOp(Token.LOCAL_LOAD, getLocalBlockRef(node))
                stackChange(1)
            }
            Token.COMMA -> {
                val lastChild = node.lastChild
                while (child !== lastChild) {
                    visitExpression(child!!, 0)
                    addIcode(Icode_POP)
                    stackChange(-1)
                    child = child.next
                }
                visitExpression(child!!, contextFlags and ECF_TAIL)
            }
            Token.USE_STACK -> stackChange(1)
            Token.REF_CALL, Token.CALL, Token.NEW -> {
                val isOptionalChainingCall = node.getIntProp(Node.OPTIONAL_CHAINING, 0) == 1
                var completeOptionalCallJump: CompleteOptionalCallJump? = null
                if (type == Token.NEW) {
                    visitExpression(child!!, 0)
                } else {
                    completeOptionalCallJump = generateCallFunAndThis(child!!, isOptionalChainingCall)
                    if (completeOptionalCallJump != null) resolveForwardGoto(completeOptionalCallJump.putArgsAndDoCallLabel)
                }
                var argCount = 0
                child = child.next
                while (child != null) {
                    visitExpression(child, 0)
                    ++argCount
                    child = child.next
                }
                val callType = node.getIntProp(Node.SPECIALCALL_PROP, Node.NON_SPECIALCALL)
                var opType = type
                if (type != Token.REF_CALL && callType != Node.NON_SPECIALCALL) {
                    addIndexOp(Icode_CALLSPECIAL, argCount)
                    addUint8(callType)
                    addUint8(if (type == Token.NEW) 1 else 0)
                    addUint16(lineNumber and 0xFFFF)
                } else if (node.getIntProp(Node.SUPER_PROPERTY_ACCESS, 0) == 1) {
                    addIndexOp(Icode_CALL_ON_SUPER, argCount)
                } else {
                    if (type == Token.CALL && (contextFlags and ECF_TAIL) != 0 && !compilerEnv.generateDebugInfo && !itsInTryFlag) {
                        opType = Icode_TAIL_CALL
                    }
                    addIndexOp(opType, argCount)
                }
                if (type == Token.NEW) stackChange(-argCount) else stackChange(-1 - argCount)
                if (argCount > itsData.itsMaxCalleeArgs) itsData.itsMaxCalleeArgs = argCount
                if (completeOptionalCallJump != null) resolveForwardGoto(completeOptionalCallJump.afterLabel)
            }
            Token.AND, Token.OR -> {
                visitExpression(child!!, 0)
                addIcode(Icode_DUP)
                stackChange(1)
                val afterSecondJumpStart = iCodeTop
                val jump = if (type == Token.AND) Token.IFNE else Token.IFEQ
                addGotoOp(jump)
                stackChange(-1)
                addIcode(Icode_POP)
                stackChange(-1)
                child = child.next
                visitExpression(child!!, contextFlags and ECF_TAIL)
                resolveForwardGoto(afterSecondJumpStart)
            }
            Token.HOOK -> {
                val ifThen = child!!.next!!
                val ifElse = ifThen.next!!
                visitExpression(child, 0)
                val elseJumpStart = iCodeTop
                addGotoOp(Token.IFNE)
                stackChange(-1)
                visitExpression(ifThen, contextFlags and ECF_TAIL)
                val afterElseJumpStart = iCodeTop
                addGotoOp(Token.GOTO)
                resolveForwardGoto(elseJumpStart)
                stackDepth = savedStackDepth
                visitExpression(ifElse, contextFlags and ECF_TAIL)
                resolveForwardGoto(afterElseJumpStart)
            }
            Token.GETPROP, Token.GETPROPNOWARN -> {
                visitExpression(child!!, 0)
                child = child.next!!
                if (node.getIntProp(Node.OPTIONAL_CHAINING, 0) == 1) {
                    addIcode(Icode_DUP)
                    stackChange(1)
                    val putUndefinedLabel = iCodeTop
                    addGotoOp(Icode_IF_NULL_UNDEF)
                    stackChange(-1)
                    addStringOp(type, child.string!!)
                    val afterLabel = iCodeTop
                    addGotoOp(Token.GOTO)
                    resolveForwardGoto(putUndefinedLabel)
                    addIcode(Icode_POP)
                    addIcode(Icode_UNDEF)
                    resolveForwardGoto(afterLabel)
                } else if (node.getIntProp(Node.SUPER_PROPERTY_ACCESS, 0) == 1) {
                    addStringOp(if (type == Token.GETPROP) Token.GETPROP_SUPER else Token.GETPROPNOWARN_SUPER, child.string!!)
                } else {
                    addStringOp(type, child.string!!)
                }
            }
            Token.DELPROP -> {
                val isName = child!!.type == Token.BINDNAME
                visitExpression(child, 0)
                child = child.next!!
                visitExpression(child, 0)
                when {
                    node.getIntProp(Node.SUPER_PROPERTY_ACCESS, 0) == 1 -> addIcode(Icode_DELPROP_SUPER)
                    isName -> addIcode(Icode_DELNAME)
                    else -> addToken(Token.DELPROP)
                }
                stackChange(-1)
            }
            Token.GETELEM -> {
                visitExpression(child!!, 0)
                child = child.next!!
                if (node.getIntProp(Node.OPTIONAL_CHAINING, 0) == 1) {
                    addIcode(Icode_DUP)
                    stackChange(1)
                    val putUndefinedLabel = iCodeTop
                    addGotoOp(Icode_IF_NULL_UNDEF)
                    stackChange(-1)
                    finishGetElemGeneration(child)
                    val afterLabel = iCodeTop
                    addGotoOp(Token.GOTO)
                    resolveForwardGoto(putUndefinedLabel)
                    addIcode(Icode_POP)
                    addIcode(Icode_UNDEF)
                    resolveForwardGoto(afterLabel)
                } else if (node.getIntProp(Node.SUPER_PROPERTY_ACCESS, 0) == 1) {
                    visitExpression(child, 0)
                    addToken(Token.GETELEM_SUPER)
                    stackChange(-1)
                } else {
                    finishGetElemGeneration(child)
                }
            }
            Token.BITAND, Token.BITOR, Token.BITXOR, Token.LSH, Token.RSH, Token.URSH, Token.ADD, Token.SUB,
            Token.MOD, Token.DIV, Token.MUL, Token.EXP, Token.EQ, Token.NE, Token.SHEQ, Token.SHNE, Token.IN,
            Token.INSTANCEOF, Token.LE, Token.LT, Token.GE, Token.GT, Token.STRING_CONCAT -> {
                visitExpression(child!!, 0)
                child = child.next!!
                visitExpression(child, 0)
                addToken(type)
                stackChange(-1)
            }
            Token.POS, Token.NEG, Token.NOT, Token.BITNOT, Token.TYPEOF, Token.VOID -> {
                visitExpression(child!!, 0)
                if (type == Token.VOID) {
                    addIcode(Icode_POP)
                    addIcode(Icode_UNDEF)
                } else {
                    addToken(type)
                }
            }
            Token.GET_REF, Token.DEL_REF -> {
                visitExpression(child!!, 0)
                if (node.getIntProp(Node.OPTIONAL_CHAINING, 0) == 1) {
                    addIcode(Icode_DUP)
                    stackChange(1)
                    val afterLabel = iCodeTop
                    addGotoOp(Icode_IF_NULL_UNDEF)
                    stackChange(-1)
                    addToken(type)
                    resolveForwardGoto(afterLabel)
                } else {
                    addToken(type)
                }
            }
            Token.SETPROP, Token.SETPROP_OP -> {
                visitExpression(child!!, 0)
                child = child.next!!
                val property = child.string!!
                child = child.next!!
                if (type == Token.SETPROP_OP) {
                    addIcode(Icode_DUP)
                    stackChange(1)
                    addStringOp(Token.GETPROP, property)
                    stackChange(-1)
                }
                visitExpression(child, 0)
                addStringOp(if (node.getIntProp(Node.SUPER_PROPERTY_ACCESS, 0) == 1) Token.SETPROP_SUPER else Token.SETPROP, property)
                stackChange(-1)
            }
            Token.SETELEM, Token.SETELEM_OP -> {
                visitExpression(child!!, 0)
                child = child.next!!
                visitExpression(child, 0)
                child = child.next!!
                if (type == Token.SETELEM_OP) {
                    addIcode(Icode_DUP2)
                    stackChange(2)
                    addToken(Token.GETELEM)
                    stackChange(-1)
                    stackChange(-1)
                }
                visitExpression(child, 0)
                addToken(if (node.getIntProp(Node.SUPER_PROPERTY_ACCESS, 0) == 1) Token.SETELEM_SUPER else Token.SETELEM)
                stackChange(-2)
            }
            Token.SET_REF, Token.SET_REF_OP -> {
                visitExpression(child!!, 0)
                child = child.next!!
                if (type == Token.SET_REF_OP) {
                    addIcode(Icode_DUP)
                    stackChange(1)
                    addToken(Token.GET_REF)
                    stackChange(-1)
                }
                visitExpression(child, 0)
                addToken(Token.SET_REF)
                stackChange(-1)
            }
            Token.STRICT_SETNAME, Token.SETNAME -> {
                val name = child!!.string!!
                visitExpression(child, 0)
                child = child.next!!
                visitExpression(child, 0)
                addStringOp(type, name)
                stackChange(-1)
            }
            Token.SETCONST -> {
                val name = child!!.string!!
                visitExpression(child, 0)
                child = child.next!!
                visitExpression(child, 0)
                addStringOp(Icode_SETCONST, name)
                stackChange(-1)
            }
            Token.TYPEOFNAME -> {
                var index = -1
                if (itsInFunctionFlag && !builder.requiresActivationFrame) index = scriptOrFn.getIndexForNameNode(node)
                if (index == -1) {
                    addStringOp(Icode_TYPEOFNAME, node.string!!)
                    stackChange(1)
                } else {
                    addVarOp(Token.GETVAR, index)
                    stackChange(1)
                    addToken(Token.TYPEOF)
                }
            }
            Token.BINDNAME, Token.NAME, Token.STRING -> {
                addStringOp(type, node.string!!)
                stackChange(1)
            }
            Token.INC, Token.DEC -> visitIncDec(node, child!!)
            Token.NUMBER -> addNumber(node.double)
            Token.GETVAR -> {
                if (builder.requiresActivationFrame) throw Kit.codeBug()
                addVarOp(Token.GETVAR, scriptOrFn.getIndexForNameNode(node))
                stackChange(1)
            }
            Token.SETVAR -> {
                if (builder.requiresActivationFrame) throw Kit.codeBug()
                val index = scriptOrFn.getIndexForNameNode(child!!)
                child = child.next!!
                visitExpression(child, 0)
                addVarOp(Token.SETVAR, index)
            }
            Token.SETCONSTVAR -> {
                if (builder.requiresActivationFrame) throw Kit.codeBug()
                val index = scriptOrFn.getIndexForNameNode(child!!)
                child = child.next!!
                visitExpression(child, 0)
                addVarOp(Token.SETCONSTVAR, index)
            }
            Token.NULL, Token.THIS, Token.SUPER, Token.THISFN, Token.FALSE, Token.TRUE -> {
                addToken(type)
                stackChange(1)
            }
            Token.UNDEFINED -> {
                addIcode(Icode_UNDEF)
                stackChange(1)
            }
            Token.ENUM_NEXT, Token.ENUM_ID -> {
                addIndexOp(type, getLocalBlockRef(node))
                stackChange(1)
            }
            Token.BIGINT -> {
                addBigInt(node.bigInt!!)
                stackChange(1)
            }
            Token.REGEXP -> {
                addIndexOp(Token.REGEXP, node.getExistingIntProp(Node.REGEXP_PROP))
                stackChange(1)
            }
            Token.ARRAYLIT, Token.OBJECTLIT -> visitLiteral(node, child)
            Token.ARRAYCOMP -> visitArrayComprehension(node, child!!, child.next!!)
            Token.REF_SPECIAL -> {
                visitExpression(child!!, 0)
                if (node.getIntProp(Node.OPTIONAL_CHAINING, 0) == 1) {
                    addIcode(Icode_DUP)
                    stackChange(1)
                    val putUndefinedLabel = iCodeTop
                    addGotoOp(Icode_IF_NULL_UNDEF)
                    stackChange(-1)
                    addStringOp(type, node.getProp(Node.NAME_PROP) as String)
                    val afterLabel = iCodeTop
                    addGotoOp(Token.GOTO)
                    resolveForwardGoto(putUndefinedLabel)
                    addIcode(Icode_POP)
                    addIcode(Icode_UNDEF)
                    resolveForwardGoto(afterLabel)
                } else {
                    addStringOp(type, node.getProp(Node.NAME_PROP) as String)
                }
            }
            Token.REF_MEMBER, Token.REF_NS_MEMBER, Token.REF_NAME, Token.REF_NS_NAME -> {
                val memberTypeFlags = node.getIntProp(Node.MEMBER_TYPE_PROP, 0)
                var childCount = 0
                do {
                    visitExpression(child!!, 0)
                    ++childCount
                    child = child.next
                } while (child != null)
                addIndexOp(type, memberTypeFlags)
                stackChange(1 - childCount)
            }
            Token.DOTQUERY -> {
                updateLineNumber(node)
                visitExpression(child!!, 0)
                addIcode(Icode_ENTERDQ)
                stackChange(-1)
                val queryPC = iCodeTop
                visitExpression(child.next!!, 0)
                addBackwardGoto(Icode_LEAVEDQ, queryPC)
            }
            Token.DEFAULTNAMESPACE, Token.ESCXMLATTR, Token.ESCXMLTEXT -> {
                visitExpression(child!!, 0)
                addToken(type)
            }
            Token.YIELD, Token.YIELD_STAR -> {
                if (child != null) {
                    visitExpression(child, 0)
                } else {
                    addIcode(Icode_UNDEF)
                    stackChange(1)
                }
                if (type == Token.YIELD) addToken(Token.YIELD) else addIcode(Icode_YIELD_STAR)
                addUint16(node.lineno and 0xFFFF)
            }
            Token.WITHEXPR -> {
                val enterWith = node.firstChild!!
                val with = enterWith.next!!
                visitExpression(enterWith.firstChild!!, 0)
                addToken(Token.ENTERWITH)
                stackChange(-1)
                visitExpression(with.firstChild!!, 0)
                addToken(Token.LEAVEWITH)
            }
            Token.TEMPLATE_LITERAL -> visitTemplateLiteral(node)
            Token.NULLISH_COALESCING -> {
                visitExpression(child!!, 0)
                child = child.next!!
                addIcode(Icode_DUP)
                stackChange(1)
                val end = iCodeTop
                addGotoOp(Icode_IF_NOT_NULL_UNDEF)
                stackChange(-1)
                addIcode(Icode_POP)
                visitExpression(child, 0)
                stackChange(-1)
                resolveForwardGoto(end)
            }
            else -> throw badTree(node)
        }
        if (savedStackDepth + 1 != stackDepth) throw Kit.codeBug()
    }

    private fun addNumber(num: Double) {
        val inum = num.toInt()
        if (inum.toDouble() == num) {
            when {
                inum == 0 -> {
                    addIcode(Icode_ZERO)
                    // Negative zero is zero followed by a negation.
                    if (1.0 / num < 0.0) addToken(Token.NEG)
                }
                inum == 1 -> addIcode(Icode_ONE)
                inum.toShort().toInt() == inum -> {
                    addIcode(Icode_SHORTNUMBER)
                    addUint16(inum and 0xFFFF)
                }
                else -> {
                    addIcode(Icode_INTNUMBER)
                    addInt(inum)
                }
            }
        } else {
            addIndexOp(Token.NUMBER, getDoubleIndex(num))
        }
        stackChange(1)
    }

    private fun finishGetElemGeneration(child: Node) {
        visitExpression(child, 0)
        addToken(Token.GETELEM)
        stackChange(-1)
    }

    private fun generateCallFunAndThis(left: Node, isOptionalChainingCall: Boolean): CompleteOptionalCallJump? {
        when (val type = left.type) {
            Token.NAME -> {
                val name = left.string!!
                if (isOptionalChainingCall) {
                    addStringOp(Icode_NAME_AND_THIS_OPTIONAL, name)
                    stackChange(2)
                    return completeOptionalCallJump()
                }
                addStringOp(Icode_NAME_AND_THIS, name)
                stackChange(2)
            }
            Token.GETPROP, Token.GETELEM -> {
                val target = left.firstChild!!
                visitExpression(target, 0)
                val id = target.next!!
                if (type == Token.GETPROP) {
                    val property = id.string!!
                    if (isOptionalChainingCall) {
                        addStringOp(Icode_PROP_AND_THIS_OPTIONAL, property)
                        stackChange(1)
                        return completeOptionalCallJump()
                    }
                    addStringOp(Icode_PROP_AND_THIS, property)
                    stackChange(1)
                } else {
                    visitExpression(id, 0)
                    if (isOptionalChainingCall) {
                        addIcode(Icode_ELEM_AND_THIS_OPTIONAL)
                        return completeOptionalCallJump()
                    }
                    addIcode(Icode_ELEM_AND_THIS)
                }
            }
            else -> {
                visitExpression(left, 0)
                if (isOptionalChainingCall) {
                    addIcode(Icode_VALUE_AND_THIS_OPTIONAL)
                    stackChange(1)
                    return completeOptionalCallJump()
                }
                addIcode(Icode_VALUE_AND_THIS)
                stackChange(1)
            }
        }
        return null
    }

    private fun completeOptionalCallJump(): CompleteOptionalCallJump {
        addIcode(Icode_DUP)
        stackChange(1)
        val putArgsAndDoCallLabel = iCodeTop
        addGotoOp(Icode_IF_NOT_NULL_UNDEF)
        stackChange(-1)
        addIcode(Icode_POP)
        addIcode(Icode_UNDEF)
        val afterLabel = iCodeTop
        addGotoOp(Token.GOTO)
        return CompleteOptionalCallJump(putArgsAndDoCallLabel, afterLabel)
    }

    private fun visitIncDec(node: Node, child: Node) {
        val incrDecrMask = node.getExistingIntProp(Node.INCRDECR_PROP)
        val childType = child.type
        if (child.getIntProp(Node.SUPER_PROPERTY_ACCESS, 0) == 1) {
            visitSuperIncDec(node, child, childType, incrDecrMask)
            return
        }
        when (childType) {
            Token.GETVAR -> {
                if (builder.requiresActivationFrame) throw Kit.codeBug()
                addVarOp(Icode_VAR_INC_DEC, scriptOrFn.getIndexForNameNode(child))
                addUint8(incrDecrMask)
                stackChange(1)
            }
            Token.NAME -> {
                addStringOp(Icode_NAME_INC_DEC, child.string!!)
                addUint8(incrDecrMask)
                stackChange(1)
            }
            Token.GETPROP -> {
                val obj = child.firstChild!!
                visitExpression(obj, 0)
                addStringOp(Icode_PROP_INC_DEC, obj.next!!.string!!)
                addUint8(incrDecrMask)
            }
            Token.GETELEM -> {
                val obj = child.firstChild!!
                visitExpression(obj, 0)
                visitExpression(obj.next!!, 0)
                addIcode(Icode_ELEM_INC_DEC)
                addUint8(incrDecrMask)
                stackChange(-1)
            }
            Token.GET_REF -> {
                visitExpression(child.firstChild!!, 0)
                addIcode(Icode_REF_INC_DEC)
                addUint8(incrDecrMask)
            }
            else -> throw badTree(node)
        }
    }

    private fun visitSuperIncDec(node: Node, child: Node, childType: Int, incrDecrMask: Int) {
        val obj = child.firstChild!!
        visitExpression(obj, 0)
        when (childType) {
            Token.GETPROP -> addStringOp(Token.GETPROP_SUPER, obj.next!!.string!!)
            Token.GETELEM -> {
                visitExpression(obj.next!!, 0)
                addToken(Token.GETELEM_SUPER)
                stackChange(-1)
            }
            else -> throw badTree(node)
        }
        if ((incrDecrMask and Node.POST_FLAG) != 0) {
            addIcode(Icode_DUP)
            stackChange(1)
        }
        addToken(Token.SUPER)
        stackChange(1)
        addIcode(Icode_SWAP)
        addIcode(Icode_ONE)
        stackChange(1)
        addToken(if ((incrDecrMask and Node.DECR_FLAG) == 0) Token.ADD else Token.SUB)
        stackChange(-1)
        when (childType) {
            Token.GETPROP -> {
                addStringOp(Token.SETPROP_SUPER, obj.next!!.string!!)
                stackChange(-1)
            }
            Token.GETELEM -> {
                visitExpression(obj.next!!, 0)
                addToken(Token.SETELEM_SUPER)
                stackChange(-2)
            }
        }
        if ((incrDecrMask and Node.POST_FLAG) != 0) {
            addIcode(Icode_POP)
            stackChange(-1)
        }
    }

    private fun visitLiteral(node: Node, child: Node?) {
        when (node.type) {
            Token.ARRAYLIT -> visitArrayLiteral(node, child)
            Token.OBJECTLIT -> visitObjectLiteral(node, child)
            else -> throw badTree(node)
        }
    }

    private fun visitObjectLiteralWithSpread(node: Node, first: Node?, propertyIds: Array<Any?>?, count: Int) {
        addIcode(Icode_REG_IND4)
        addInt(-count - 1)
        addIcode(Icode_LITERAL_NEW_OBJECT)
        addUint8(0)
        stackChange(2)
        var child = first
        var i = 0
        while (child != null) {
            val propertyId = propertyIds?.get(i)
            if (propertyId is Node) {
                visitExpression(propertyId.firstChild!!, 0)
                if (propertyId.type == Token.DOTDOTDOT) {
                    addIcode(Icode_SPREAD)
                    stackChange(-1)
                    child = child.next
                    i++
                    continue
                }
            } else if (propertyId is String) {
                addStringOp(Token.STRING, propertyId)
                stackChange(1)
            } else if (propertyId is Int) {
                addNumber(propertyId.toDouble())
            } else {
                throw badTree(node)
            }
            addIcode(Icode_LITERAL_KEY_SET)
            stackChange(-1)
            visitLiteralValue(child)
            child = child.next
            i++
        }
        addToken(Token.OBJECTLIT)
        stackChange(-1)
    }

    private fun visitObjectLiteral(node: Node, first: Node?) {
        @Suppress("UNCHECKED_CAST")
        val propertyIds = node.getProp(Node.OBJECT_IDS_PROP) as Array<Any?>?
        val count = propertyIds?.size ?: 0
        val numberOfSpread = node.getIntProp(Node.NUMBER_OF_SPREAD, 0)
        if (numberOfSpread > 0) {
            visitObjectLiteralWithSpread(node, first, propertyIds, count - numberOfSpread)
            return
        }
        val hasAnyComputedProperty = propertyIds != null && propertyIds.any { it is Node }
        val nextLiteralIndex = literalIds.size
        literalIds.add(propertyIds)
        addIndexOp(Icode_LITERAL_NEW_OBJECT, nextLiteralIndex)
        addUint8(if (hasAnyComputedProperty) 1 else 0)
        stackChange(2)
        var child = first
        var i = 0
        while (child != null) {
            val propertyId = propertyIds?.get(i)
            if (propertyId is Node) {
                visitExpression(propertyId.firstChild!!, 0)
                addIcode(Icode_LITERAL_KEY_SET)
                stackChange(-1)
            }
            visitLiteralValue(child)
            child = child.next
            i++
        }
        addToken(Token.OBJECTLIT)
        stackChange(-1)
    }

    private fun visitArrayLiteral(node: Node, first: Node?) {
        var count = 0
        var n = first
        while (n != null) {
            ++count
            n = n.next
        }
        val numberOfSpread = node.getIntProp(Node.NUMBER_OF_SPREAD, 0)
        val skipIndexes = node.getProp(Node.SKIP_INDEXES_PROP) as IntArray?
        var sourcePositions: IntArray? = null
        if (skipIndexes != null) {
            // Each element's position in the source, once the holes are counted in.
            sourcePositions = IntArray(count)
            var sourcePos = 0
            var skipIdx = 0
            for (i in 0 until count) {
                while (skipIdx < skipIndexes.size && skipIndexes[skipIdx] == sourcePos) {
                    sourcePos++
                    skipIdx++
                }
                sourcePositions[i] = sourcePos
                sourcePos++
            }
        }
        var skipIndexesId = -1
        if (skipIndexes != null) {
            skipIndexesId = literalIds.size
            literalIds.add(skipIndexes)
        }
        addIndexOp(Icode_LITERAL_NEW_ARRAY, count - numberOfSpread)
        addUint8(skipIndexesId + 1)
        stackChange(1)
        var child = first
        var childIdx = 0
        while (child != null) {
            if (child.type == Token.DOTDOTDOT) {
                visitExpression(child.firstChild!!, 0)
                addIcode(Icode_SPREAD)
                if (skipIndexes != null) addUint8(sourcePositions!![childIdx])
                stackChange(-1)
            } else {
                visitLiteralValue(child)
            }
            child = child.next
            childIdx++
        }
        if (skipIndexes == null) addToken(Token.ARRAYLIT) else addIndexOp(Icode_SPARE_ARRAYLIT, skipIndexesId)
    }

    private fun visitLiteralValue(child: Node) {
        when (child.type) {
            Token.GET -> {
                visitExpression(child.firstChild!!, 0)
                addIcode(Icode_LITERAL_GETTER)
            }
            Token.SET -> {
                visitExpression(child.firstChild!!, 0)
                addIcode(Icode_LITERAL_SETTER)
            }
            Token.METHOD -> {
                visitExpression(child.firstChild!!, 0)
                addIcode(Icode_LITERAL_SET)
            }
            else -> {
                visitExpression(child, 0)
                addIcode(Icode_LITERAL_SET)
            }
        }
        stackChange(-1)
    }

    private fun visitTemplateLiteral(node: Node) {
        addIndexOp(Icode_TEMPLATE_LITERAL_CALLSITE, node.getExistingIntProp(Node.TEMPLATE_LITERAL_PROP))
        stackChange(1)
    }

    private fun visitArrayComprehension(node: Node, initStmt: Node, expr: Node) {
        visitStatement(initStmt, stackDepth)
        visitExpression(expr, 0)
    }

    // ---- Labels and jumps ------------------------------------------------------------------------

    private fun getTargetLabel(target: Node): Int {
        var label = target.labelId()
        if (label != -1) return label
        label = labelTableTop
        var table = labelTable
        if (table == null) {
            table = IntArray(MIN_LABEL_TABLE_SIZE)
            labelTable = table
        } else if (label == table.size) {
            table = table.copyOf(table.size * 2)
            labelTable = table
        }
        labelTableTop = label + 1
        table[label] = -1
        target.labelId(label)
        return label
    }

    private fun markTargetLabel(target: Node) {
        val label = getTargetLabel(target)
        if (labelTable!![label] != -1) throw Kit.codeBug()
        labelTable!![label] = iCodeTop
    }

    private fun addGoto(target: Node, gotoOp: Int) {
        val label = getTargetLabel(target)
        if (label >= labelTableTop) throw Kit.codeBug()
        val targetPC = labelTable!![label]
        if (targetPC != -1) {
            addBackwardGoto(gotoOp, targetPC)
        } else {
            val gotoPC = iCodeTop
            addGotoOp(gotoOp)
            val top = fixupTableTop
            var table = fixupTable
            if (table == null) {
                table = LongArray(MIN_FIXUP_TABLE_SIZE)
                fixupTable = table
            } else if (top == table.size) {
                table = table.copyOf(table.size * 2)
                fixupTable = table
            }
            fixupTableTop = top + 1
            table[top] = (label.toLong() shl 32) or gotoPC.toLong()
        }
    }

    private fun fixLabelGotos() {
        for (i in 0 until fixupTableTop) {
            val fixup = fixupTable!![i]
            val label = (fixup shr 32).toInt()
            val jumpSource = fixup.toInt()
            val pc = labelTable!![label]
            if (pc == -1) throw Kit.codeBug()
            resolveGoto(jumpSource, pc)
        }
        fixupTableTop = 0
    }

    private fun addBackwardGoto(gotoOp: Int, jumpPC: Int) {
        val fromPC = iCodeTop
        if (fromPC <= jumpPC) throw Kit.codeBug()
        addGotoOp(gotoOp)
        resolveGoto(fromPC, jumpPC)
    }

    private fun resolveForwardGoto(fromPC: Int) {
        if (iCodeTop < fromPC + 3) throw Kit.codeBug()
        resolveGoto(fromPC, iCodeTop)
    }

    private fun resolveGoto(fromPC: Int, jumpPC: Int) {
        var offset = jumpPC - fromPC
        if (offset in 0..2) throw Kit.codeBug()
        val offsetSite = fromPC + 1
        if (offset != offset.toShort().toInt()) {
            // Too far for two bytes: the real target goes in a side table.
            val jumps = itsData.longJumps ?: HashMap<Int, Int>().also { itsData.longJumps = it }
            jumps[offsetSite] = jumpPC
            offset = 0
        }
        val array = itsData.itsICode
        array[offsetSite] = (offset shr 8).toByte()
        array[offsetSite + 1] = offset.toByte()
    }

    // ---- Emitting bytes ------------------------------------------------------------------------

    private fun addToken(token: Int) {
        if (!Icode.validTokenCode(token)) throw Kit.codeBug()
        addUint8(token)
    }

    private fun addIcode(icode: Int) {
        if (!Icode.validIcode(icode)) throw Kit.codeBug()
        addUint8(icode and 0xFF)
    }

    private fun addUint8(value: Int) {
        if ((value and 0xFF.inv()) != 0) throw Kit.codeBug()
        var array = itsData.itsICode
        val top = iCodeTop
        if (top == array.size) array = increaseICodeCapacity(1)
        array[top] = value.toByte()
        iCodeTop = top + 1
    }

    private fun addUint16(value: Int) {
        if ((value and 0xFFFF.inv()) != 0) throw Kit.codeBug()
        var array = itsData.itsICode
        val top = iCodeTop
        if (top + 2 > array.size) array = increaseICodeCapacity(2)
        array[top] = (value ushr 8).toByte()
        array[top + 1] = value.toByte()
        iCodeTop = top + 2
    }

    private fun addInt(i: Int) {
        var array = itsData.itsICode
        val top = iCodeTop
        if (top + 4 > array.size) array = increaseICodeCapacity(4)
        array[top] = (i ushr 24).toByte()
        array[top + 1] = (i ushr 16).toByte()
        array[top + 2] = (i ushr 8).toByte()
        array[top + 3] = i.toByte()
        iCodeTop = top + 4
    }

    private fun getDoubleIndex(num: Double): Int {
        val index = doubleTableTop
        var table = itsData.itsDoubleTable
        if (index == 0) {
            table = DoubleArray(64)
            itsData.itsDoubleTable = table
        } else if (table!!.size == index) {
            table = table.copyOf(index * 2)
            itsData.itsDoubleTable = table
        }
        table[index] = num
        doubleTableTop = index + 1
        return index
    }

    private fun addGotoOp(gotoOp: Int) {
        var array = itsData.itsICode
        val top = iCodeTop
        if (top + 3 > array.size) array = increaseICodeCapacity(3)
        array[top] = gotoOp.toByte()
        iCodeTop = top + 1 + 2
    }

    private fun addVarOp(op: Int, varIndex: Int) {
        when (op) {
            Token.SETCONSTVAR -> {
                if (varIndex < 128) {
                    addIcode(Icode_SETCONSTVAR1)
                    addUint8(varIndex)
                    return
                }
                addIndexOp(Icode_SETCONSTVAR, varIndex)
                return
            }
            Token.GETVAR, Token.SETVAR -> {
                if (varIndex < 128) {
                    addIcode(if (op == Token.GETVAR) Icode_GETVAR1 else Icode_SETVAR1)
                    addUint8(varIndex)
                    return
                }
                addIndexOp(op, varIndex)
                return
            }
            Icode_VAR_INC_DEC -> {
                addIndexOp(op, varIndex)
                return
            }
        }
        throw Kit.codeBug()
    }

    private fun addStringOp(op: Int, str: String) {
        addStringPrefix(str)
        if (Icode.validIcode(op)) addIcode(op) else addToken(op)
    }

    private fun addIndexOp(op: Int, index: Int) {
        addIndexPrefix(index)
        if (Icode.validIcode(op)) addIcode(op) else addToken(op)
    }

    private fun addStringPrefix(str: String) {
        var index = strings[str] ?: -1
        if (index == -1) {
            index = strings.size
            strings[str] = index
        }
        when {
            index < 4 -> addIcode(Icode_REG_STR_C0 - index)
            index <= 0xFF -> {
                addIcode(Icode_REG_STR1)
                addUint8(index)
            }
            index <= 0xFFFF -> {
                addIcode(Icode_REG_STR2)
                addUint16(index)
            }
            else -> {
                addIcode(Icode_REG_STR4)
                addInt(index)
            }
        }
    }

    private fun addBigInt(n: KBigInt) {
        var index = bigInts[n] ?: -1
        if (index == -1) {
            index = bigInts.size
            bigInts[n] = index
        }
        when {
            index < 4 -> addIcode(Icode_REG_BIGINT_C0 - index)
            index <= 0xFF -> {
                addIcode(Icode_REG_BIGINT1)
                addUint8(index)
            }
            index <= 0xFFFF -> {
                addIcode(Icode_REG_BIGINT2)
                addUint16(index)
            }
            else -> {
                addIcode(Icode_REG_BIGINT4)
                addInt(index)
            }
        }
        addToken(Token.BIGINT)
    }

    private fun addIndexPrefix(index: Int) {
        if (index < 0) throw Kit.codeBug()
        when {
            index < 6 -> addIcode(Icode_REG_IND_C0 - index)
            index <= 0xFF -> {
                addIcode(Icode_REG_IND1)
                addUint8(index)
            }
            index <= 0xFFFF -> {
                addIcode(Icode_REG_IND2)
                addUint16(index)
            }
            else -> {
                addIcode(Icode_REG_IND4)
                addInt(index)
            }
        }
    }

    private fun addExceptionHandler(icodeStart: Int, icodeEnd: Int, handlerStart: Int, isFinally: Boolean, exceptionObjectLocal: Int, scopeLocal: Int) {
        val top = exceptionTableTop
        var table = itsData.itsExceptionTable
        if (table == null) {
            if (top != 0) throw Kit.codeBug()
            table = IntArray(Interpreter.EXCEPTION_SLOT_SIZE * 2)
            itsData.itsExceptionTable = table
        } else if (table.size == top) {
            table = table.copyOf(table.size * 2)
            itsData.itsExceptionTable = table
        }
        table[top + Interpreter.EXCEPTION_TRY_START_SLOT] = icodeStart
        table[top + Interpreter.EXCEPTION_TRY_END_SLOT] = icodeEnd
        table[top + Interpreter.EXCEPTION_HANDLER_SLOT] = handlerStart
        table[top + Interpreter.EXCEPTION_TYPE_SLOT] = if (isFinally) 1 else 0
        table[top + Interpreter.EXCEPTION_LOCAL_SLOT] = exceptionObjectLocal
        table[top + Interpreter.EXCEPTION_SCOPE_SLOT] = scopeLocal
        exceptionTableTop = top + Interpreter.EXCEPTION_SLOT_SIZE
    }

    private fun increaseICodeCapacity(extraSize: Int): ByteArray {
        var capacity = itsData.itsICode.size
        val top = iCodeTop
        if (top + extraSize <= capacity) throw Kit.codeBug()
        capacity *= 2
        if (top + extraSize > capacity) capacity = top + extraSize
        val array = itsData.itsICode.copyOf(capacity)
        itsData.itsICode = array
        return array
    }

    private fun stackChange(change: Int) {
        if (change <= 0) {
            stackDepth += change
        } else {
            val newDepth = stackDepth + change
            if (newDepth > itsData.itsMaxStack) itsData.itsMaxStack = newDepth
            stackDepth = newDepth
        }
    }

    private fun allocLocal(): Int {
        val localSlot = localTop
        ++localTop
        if (localTop > itsData.itsMaxLocals) itsData.itsMaxLocals = localTop
        return localSlot
    }

    private fun releaseLocal(localSlot: Int) {
        --localTop
        if (localSlot != localTop) throw Kit.codeBug()
    }

    private class CompleteOptionalCallJump(val putArgsAndDoCallLabel: Int, val afterLabel: Int)

    private companion object {
        const val MIN_LABEL_TABLE_SIZE = 32
        const val MIN_FIXUP_TABLE_SIZE = 40
        const val ECF_TAIL = 1 shl 0

        fun getLocalBlockRef(node: Node): Int {
            val localBlock = node.getProp(Node.LOCAL_BLOCK_PROP) as Node
            return localBlock.getExistingIntProp(Node.LOCAL_PROP)
        }
    }
}
