/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.Icode.Companion.Icode_CALLSPECIAL
import io.github.yuroyami.kitejs.Icode.Companion.Icode_CALLSPECIAL_OPTIONAL
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
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_BIGINT_C1
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_BIGINT_C2
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_BIGINT_C3
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_IND1
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_IND2
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_IND4
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_IND_C0
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_IND_C1
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_IND_C2
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_IND_C3
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_IND_C4
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_IND_C5
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_STR1
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_STR2
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_STR4
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_STR_C0
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_STR_C1
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_STR_C2
import io.github.yuroyami.kitejs.Icode.Companion.Icode_REG_STR_C3
import io.github.yuroyami.kitejs.Icode.Companion.Icode_RETSUB
import io.github.yuroyami.kitejs.Icode.Companion.Icode_RETUNDEF
import io.github.yuroyami.kitejs.Icode.Companion.Icode_SCOPE_LOAD
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
import io.github.yuroyami.kitejs.Icode.Companion.MIN_ICODE
import io.github.yuroyami.kitejs.ast.FunctionNode
import io.github.yuroyami.kitejs.ast.ScriptNode
import kotlin.math.pow

/**
 * The interpreter: the one [Evaluator] this engine has. It runs the icode `CodeGenerator` emits,
 * one call frame per script function, with the frames linked so an exception or a `yield` can
 * unwind them without touching the host stack.
 *
 * Upstream keeps a table of one object per opcode; this port dispatches with a `when` over the same
 * opcodes, which is what older Rhino did. The cases are the same code (D-30).
 *
 * Continuations (`NativeContinuation`, `captureContinuation`) are not ported (D-31).
 */
class Interpreter : Evaluator {

    /** What [compile] hands back and the create methods take. */
    internal class CompilationResult<T : ScriptOrFn<T>>(val descriptor: JSDescriptor<T>, val homeObject: Scriptable?)

    override fun compile(compilerEnv: CompilerEnvirons, tree: ScriptNode, rawSource: String, returnFunction: Boolean): Any {
        val cgen = CodeGenerator<JSFunction>()
        val itsData = cgen.compile(compilerEnv, tree, rawSource, returnFunction)
        return CompilationResult(itsData, compilerEnv.homeObject())
    }

    @Suppress("UNCHECKED_CAST")
    override fun createScriptObject(bytecode: Any, staticSecurityDomain: Any?): Script {
        val r = bytecode as CompilationResult<JSScript>
        return JSFunction.createScript(r.descriptor, r.homeObject, staticSecurityDomain)
    }

    @Suppress("UNCHECKED_CAST")
    override fun createFunctionObject(cx: Context, scope: Scriptable, bytecode: Any, staticSecurityDomain: Any?): Function {
        val r = bytecode as CompilationResult<JSFunction>
        return JSFunction.createFunction(cx, scope, r.descriptor, r.homeObject, staticSecurityDomain)
    }

    override fun captureStackInfo(ex: RhinoException) {
        val cx = Context.getCurrentContext()
        val frame = cx?.lastInterpreterFrame as CallFrame?
        if (frame == null) {
            ex.interpreterStackInfo = null
        } else {
            ex.interpreterStackInfo = frame
            ex.interpreterLineData = frame.pcSourceLineStart
        }
    }

    override fun getSourcePositionFromStack(cx: Context, linep: IntArray): String? {
        val frame = cx.lastInterpreterFrame as CallFrame
        linep[0] = if (frame.pcSourceLineStart >= 0) getIndex(frame.idata.itsICode, frame.pcSourceLineStart) else 0
        return frame.fnOrScript.descriptor!!.sourceName
    }

    /** There is no host stack trace to patch off the JVM, so the trace comes back as given. */
    override fun getPatchedStack(ex: RhinoException, nativeStackTrace: String): String = nativeStackTrace

    override fun getScriptStack(ex: RhinoException): List<String> =
        getScriptStackElements(ex).map { group -> buildString { for (e in group) { e.renderJavaStyle(this); append('\n') } } }

    override fun setEvalScriptFlag(script: Script) {
        throw UnsupportedOperationException()
    }

    /** One activation of a script or function. */
    internal class CallFrame {
        val parentFrame: CallFrame?
        val frameIndex: Int
        val previousInterpreterFrame: CallFrame?
        val parentPC: Int
        var frozen = false
        val fnOrScript: ScriptOrFn<*>
        val idata: InterpreterData<*>
        val stack: Array<Any?>
        val stackAttributes: ByteArray
        val sDbl: DoubleArray
        /** Where the variables live: this frame, or the generator frame it was cloned from. */
        val varSource: CallFrame
        val emptyStackTop: Int
        val useActivation: Boolean
        var isContinuationsTopFrame = false
        val thisObj: Scriptable?
        var result: Any? = Undefined.instance
        var resultDbl = 0.0
        var pc = 0
        var pcPrevBranch = 0
        var pcSourceLineStart: Int
        var scope: Scriptable? = null
        var savedStackTop: Int
        var savedCallOp = 0
        var throwable: Any? = null

        constructor(cx: Context, thisObj: Scriptable?, fnOrScript: ScriptOrFn<*>, code: InterpreterData<*>, parentFrame: CallFrame?, previousInterpreterFrame: CallFrame?) {
            idata = code
            useActivation = fnOrScript.descriptor!!.requiresActivationFrame
            emptyStackTop = idata.itsMaxVars + idata.itsMaxLocals - 1
            val maxFrameArray = idata.itsMaxFrameArray
            if (maxFrameArray != emptyStackTop + idata.itsMaxStack + 1) throw Kit.codeBug()
            stack = arrayOfNulls(maxFrameArray)
            stackAttributes = ByteArray(maxFrameArray)
            sDbl = DoubleArray(maxFrameArray)
            this.fnOrScript = fnOrScript
            varSource = this
            this.thisObj = thisObj
            this.parentFrame = parentFrame
            this.parentPC = if (parentFrame == null) (previousInterpreterFrame?.pcSourceLineStart ?: -1) else parentFrame.pcSourceLineStart
            this.previousInterpreterFrame = previousInterpreterFrame
            frameIndex = if (parentFrame == null) 0 else parentFrame.frameIndex + 1
            if (frameIndex > cx.getMaximumInterpreterStackDepth()) throw Context.reportRuntimeError("Exceeded maximum stack depth")
            pcSourceLineStart = idata.firstLinePC
            savedStackTop = emptyStackTop
        }

        /** A copy of a frozen frame with its own stack, for resuming a generator. */
        constructor(original: CallFrame, parentFrame: CallFrame?, previousInterpreterFrame: CallFrame?) {
            if (!original.frozen) throw Kit.codeBug()
            stack = original.stack.copyOf()
            stackAttributes = original.stackAttributes.copyOf()
            sDbl = original.sDbl.copyOf()
            frozen = false
            this.parentFrame = parentFrame
            this.previousInterpreterFrame = previousInterpreterFrame
            if (parentFrame == null) {
                frameIndex = 0
                parentPC = previousInterpreterFrame?.pcSourceLineStart ?: -1
            } else {
                frameIndex = original.frameIndex
                parentPC = parentFrame.pcSourceLineStart
            }
            fnOrScript = original.fnOrScript
            idata = original.idata
            varSource = original.varSource
            emptyStackTop = original.emptyStackTop
            useActivation = original.useActivation
            isContinuationsTopFrame = original.isContinuationsTopFrame
            thisObj = original.thisObj
            result = original.result
            resultDbl = original.resultDbl
            pc = original.pc
            pcPrevBranch = original.pcPrevBranch
            pcSourceLineStart = original.pcSourceLineStart
            scope = original.scope
            savedStackTop = original.savedStackTop
            savedCallOp = original.savedCallOp
            throwable = original.throwable
        }

        /** A copy that shares the stack arrays, to keep the parent chain right for stack traces. */
        constructor(original: CallFrame, parentFrame: CallFrame?, previousInterpreterFrame: CallFrame?, keepFrozen: Boolean) {
            if (!original.frozen) throw Kit.codeBug()
            stack = original.stack
            stackAttributes = original.stackAttributes
            sDbl = original.sDbl
            frozen = keepFrozen
            this.parentFrame = parentFrame
            this.previousInterpreterFrame = previousInterpreterFrame
            if (parentFrame == null) {
                frameIndex = 0
                parentPC = previousInterpreterFrame?.pcSourceLineStart ?: -1
            } else {
                frameIndex = original.frameIndex
                parentPC = parentFrame.pcSourceLineStart
            }
            fnOrScript = original.fnOrScript
            idata = original.idata
            varSource = original.varSource
            emptyStackTop = original.emptyStackTop
            useActivation = original.useActivation
            isContinuationsTopFrame = original.isContinuationsTopFrame
            thisObj = original.thisObj
            result = original.result
            resultDbl = original.resultDbl
            pc = original.pc
            pcPrevBranch = original.pcPrevBranch
            pcSourceLineStart = original.pcSourceLineStart
            scope = original.scope
            savedStackTop = original.savedStackTop
            savedCallOp = original.savedCallOp
            throwable = original.throwable
        }

        fun initializeArgs(cx: Context, callerScope: Scriptable, argsIn: Array<Any?>, argsDblIn: DoubleArray?, boundArgsIn: Array<Any?>?, argShiftIn: Int, argCount: Int, homeObject: Scriptable?) {
            var args = argsIn
            var argsDbl = argsDblIn
            var boundArgs = boundArgsIn
            var argShift = argShiftIn
            val desc = fnOrScript.descriptor!!
            if (useActivation) {
                if (argsDbl != null || boundArgs != null) {
                    val blen = boundArgs?.size ?: 0
                    args = getArgsArray(args, argsDbl, boundArgs, blen, argShift, argCount)
                }
                argShift = 0
                argsDbl = null
                boundArgs = null
            }
            if (desc.functionType != 0) {
                scope = fnOrScript.declarationScope
                if (useActivation) {
                    scope =
                        if (desc.functionType == FunctionNode.ARROW_FUNCTION) {
                            ScriptRuntime.createArrowFunctionActivation(fnOrScript as JSFunction, cx, scope!!, args, desc.isStrict, desc.hasRestArg, desc.requiresArgumentObject)
                        } else {
                            ScriptRuntime.createFunctionActivation(fnOrScript as JSFunction, cx, scope!!, args, desc.isStrict, desc.hasRestArg, desc.requiresArgumentObject)
                        }
                }
            } else {
                scope = callerScope
                ScriptRuntime.initScript(fnOrScript, thisObj, cx, scope!!, desc.isEvalFunction)
            }
            if (desc.getFunctionCount() != 0 && !desc.isES6Generator) {
                if (desc.functionType != 0 && !desc.requiresActivationFrame) throw Kit.codeBug()
                for (i in 0 until desc.getFunctionCount()) {
                    if (desc.getFunction(i).functionType == FunctionNode.FUNCTION_STATEMENT) {
                        initFunction(cx, scope!!, desc, i)
                    }
                }
            }
            val varCount = desc.paramAndVarCount
            for (i in 0 until varCount) {
                if (desc.getParamOrVarConst(i)) stackAttributes[i] = ScriptableObject.CONST.toByte()
            }
            var definedArgs = desc.paramCount
            if (definedArgs > argCount) definedArgs = argCount
            var blen = 0
            if (boundArgs != null) {
                blen = minOf(definedArgs, boundArgs.size)
                boundArgs.copyInto(stack, 0, 0, blen)
            }
            args.copyInto(stack, blen, argShift, argShift + definedArgs - blen)
            if (argsDbl != null) argsDbl.copyInto(sDbl, blen, argShift, argShift + definedArgs - blen)
            for (i in definedArgs until idata.itsMaxVars) stack[i] = Undefined.instance
            if (desc.hasRestArg) {
                val offset = desc.paramCount - 1
                val vals: Array<Any?>
                if (argCount >= desc.paramCount) {
                    vals = arrayOfNulls(argCount - offset)
                    var shift = argShift + offset
                    for (valsIdx in vals.indices) {
                        var v = args[shift]
                        if (v === UniqueTag.DOUBLE_MARK) v = ScriptRuntime.wrapNumber(argsDbl!![shift])
                        vals[valsIdx] = v
                        shift++
                    }
                } else {
                    vals = ScriptRuntime.emptyArgs
                }
                stack[offset] = cx.newArray(scope!!, vals)
            }
        }

        fun cloneFrozen(): CallFrame = CallFrame(this, parentFrame, previousInterpreterFrame)

        fun shallowCloneFrozen(newPreviousInterpreterFrame: CallFrame?): CallFrame = CallFrame(this, parentFrame, newPreviousInterpreterFrame, true)

        fun syncStateToFrame(otherFrame: CallFrame) {
            otherFrame.frozen = frozen
            otherFrame.isContinuationsTopFrame = isContinuationsTopFrame
            otherFrame.result = result
            otherFrame.resultDbl = resultDbl
            otherFrame.pc = pc
            otherFrame.pcPrevBranch = pcPrevBranch
            otherFrame.pcSourceLineStart = pcSourceLineStart
            otherFrame.scope = scope
            otherFrame.savedStackTop = savedStackTop
            otherFrame.savedCallOp = savedCallOp
            otherFrame.throwable = throwable
        }

        /** A detached copy, for the generator object to keep. */
        fun captureForGenerator(): CallFrame = CallFrame(this, null, null)
    }

    /** The state a suspended generator resumes with. */
    internal class GeneratorState(val operation: Int, val value: Any?) {
        var returnedException: RuntimeException? = null
    }

    /** What one instruction tells the loop to do next. Null from the dispatch means "carry on". */
    private sealed class NewState {
        object BreakLoop : NewState()
        object BreakJumplessRun : NewState()
        object BreakWithoutExtension : NewState()
        class YieldResult(val yielding: Any?) : NewState()
        class StateBreakResult(val frame: CallFrame) : NewState()
        class StateContinueResult(val frame: CallFrame, val indexReg: Int) : NewState()
        class ThrowableResult(val frame: CallFrame, val throwable: Any?) : NewState()
    }

    /** The registers of the loop, which upstream keeps as locals and passes around. */
    private class InterpreterState(var stackTop: Int, var indexReg: Int, val instructionCounting: Boolean) {
        var bigIntReg: KBigInt? = null
        var stringReg: String? = null
        var generatorState: GeneratorState? = null
        var throwable: Any? = null
    }

    companion object {
        // The layout of one entry in the exception table.
        internal const val EXCEPTION_TRY_START_SLOT = 0
        internal const val EXCEPTION_TRY_END_SLOT = 1
        internal const val EXCEPTION_HANDLER_SLOT = 2
        internal const val EXCEPTION_TYPE_SLOT = 3
        internal const val EXCEPTION_LOCAL_SLOT = 4
        internal const val EXCEPTION_SCOPE_SLOT = 5
        internal const val EXCEPTION_SLOT_SIZE = 6

        private const val INVOCATION_COST = 100
        private const val EXCEPTION_COST = 100
        private val undefined: Any = Undefined.instance
        private val DBL_MRK: Any = UniqueTag.DOUBLE_MARK

        private fun getShort(iCode: ByteArray, pc: Int): Int = (iCode[pc].toInt() shl 8) or (iCode[pc + 1].toInt() and 0xFF)

        internal fun getIndex(iCode: ByteArray, pc: Int): Int = ((iCode[pc].toInt() and 0xFF) shl 8) or (iCode[pc + 1].toInt() and 0xFF)

        private fun getInt(iCode: ByteArray, pc: Int): Int =
            (iCode[pc].toInt() shl 24) or ((iCode[pc + 1].toInt() and 0xFF) shl 16) or ((iCode[pc + 2].toInt() and 0xFF) shl 8) or (iCode[pc + 3].toInt() and 0xFF)

        /** The innermost handler covering the current pc, or -1. */
        private fun getExceptionHandler(frame: CallFrame, onlyFinally: Boolean): Int {
            val exceptionTable = frame.idata.itsExceptionTable ?: return -1
            val pc = frame.pc - 1
            var best = -1
            var bestStart = 0
            var bestEnd = 0
            var i = 0
            while (i != exceptionTable.size) {
                val start = exceptionTable[i + EXCEPTION_TRY_START_SLOT]
                val end = exceptionTable[i + EXCEPTION_TRY_END_SLOT]
                if (start <= pc && pc < end && !(onlyFinally && exceptionTable[i + EXCEPTION_TYPE_SLOT] != 1)) {
                    if (best >= 0) {
                        if (bestEnd < end) {
                            i += EXCEPTION_SLOT_SIZE
                            continue
                        }
                        if (bestStart > start) throw Kit.codeBug()
                        if (bestEnd == end) throw Kit.codeBug()
                    }
                    best = i
                    bestStart = start
                    bestEnd = end
                }
                i += EXCEPTION_SLOT_SIZE
            }
            return best
        }

        /** How many bytes an instruction takes, including its operands. */
        private fun bytecodeSpan(bytecode: Int): Int {
            when (bytecode) {
                Token.THROW, Token.YIELD, Icode_YIELD_STAR, Icode_GENERATOR, Icode_GENERATOR_END, Icode_GENERATOR_RETURN -> return 1 + 2
                Icode_GOSUB, Token.GOTO, Token.IFEQ, Token.IFNE, Icode_IFEQ_POP, Icode_IF_NULL_UNDEF, Icode_IF_NOT_NULL_UNDEF, Icode_LEAVEDQ -> return 1 + 2
                Icode_CALLSPECIAL, Icode_CALLSPECIAL_OPTIONAL -> return 1 + 1 + 1 + 2
                Token.CATCH_SCOPE -> return 1 + 1
                Icode_VAR_INC_DEC, Icode_NAME_INC_DEC, Icode_PROP_INC_DEC, Icode_ELEM_INC_DEC, Icode_REF_INC_DEC -> return 1 + 1
                Icode_SHORTNUMBER -> return 1 + 2
                Icode_INTNUMBER -> return 1 + 4
                Icode_REG_IND1 -> return 1 + 1
                Icode_REG_IND2 -> return 1 + 2
                Icode_REG_IND4 -> return 1 + 4
                Icode_REG_STR1 -> return 1 + 1
                Icode_REG_STR2 -> return 1 + 2
                Icode_REG_STR4 -> return 1 + 4
                Icode_GETVAR1, Icode_SETVAR1, Icode_SETCONSTVAR1 -> return 1 + 1
                Icode_LINE -> return 1 + 2
                Icode_LITERAL_NEW_OBJECT -> return 1 + 1
                Icode_REG_BIGINT1 -> return 1 + 1
                Icode_REG_BIGINT2 -> return 1 + 2
                Icode_REG_BIGINT4 -> return 1 + 4
            }
            if (!Icode.validBytecode(bytecode)) throw Kit.codeBug()
            return 1
        }

        /** Every source line the code carries a marker for. */
        internal fun getLineNumbers(desc: JSDescriptor<*>): IntArray {
            var code: JSCode<*>? = desc.code
            val data: InterpreterData<*> =
                if (code is InterpreterData<*>) {
                    code
                } else {
                    code = desc.constructor
                    if (code is InterpreterData<*>) code else throw Kit.codeBug("Attempt to get line number data for non-interpreted code.")
                }
            val presentLines = LinkedHashSet<Int>()
            val iCode = data.itsICode
            var pc = 0
            while (pc != iCode.size) {
                val bytecode = iCode[pc].toInt()
                val span = bytecodeSpan(bytecode)
                if (bytecode == Icode_LINE) {
                    if (span != 3) throw Kit.codeBug()
                    presentLines.add(getIndex(iCode, pc + 1))
                }
                pc += span
            }
            return presentLines.toIntArray()
        }

        internal fun getScriptStackElements(ex: RhinoException): Array<Array<ScriptStackElement>> {
            var frame = ex.interpreterStackInfo as CallFrame? ?: return emptyArray()
            val list = ArrayList<Array<ScriptStackElement>>()
            var calleeFrame: CallFrame? = null
            while (true) {
                var callerFrame: CallFrame? = frame
                val group = ArrayList<ScriptStackElement>()
                while (callerFrame != null) {
                    val idata = callerFrame.idata
                    val desc = callerFrame.fnOrScript.descriptor!!
                    val pc = if (calleeFrame == null) ex.interpreterLineData else calleeFrame.parentPC
                    val lineNumber = if (pc >= 0) getIndex(idata.itsICode, pc) else -1
                    val functionName = if (desc.name.isNotEmpty()) desc.name else null
                    calleeFrame = callerFrame
                    callerFrame = callerFrame.parentFrame
                    group.add(ScriptStackElement(desc.sourceName ?: "", functionName, lineNumber))
                }
                list.add(group.toTypedArray())
                frame = calleeFrame!!.previousInterpreterFrame ?: break
            }
            return list.toTypedArray()
        }

        private fun initFunction(cx: Context, scope: Scriptable, parent: JSDescriptor<*>, index: Int) {
            val fn = JSFunction.createFunction(cx, scope, parent, index, null)
            ScriptRuntime.initFunction(cx, scope, fn, fn.descriptor.functionType, parent.isEvalFunction)
        }

        internal fun <T : ScriptOrFn<T>> interpret(ifun: T, idata: InterpreterData<T>, cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (!ScriptRuntime.hasTopCall(cx)) throw Kit.codeBug()
            val frame = initFrame(cx, scope, thisObj, ifun.homeObject, args, null, null, 0, args.size, ifun, idata, null)
            frame.isContinuationsTopFrame = cx.isContinuationsTopCall
            cx.isContinuationsTopCall = false
            return interpretLoop(cx, frame, null)
        }

        internal fun resumeGenerator(cx: Context, scope: Scriptable, operation: Int, savedState: Any?, value: Any?): Any? {
            val frame = savedState as CallFrame
            val activeFrame = frame.shallowCloneFrozen(cx.lastInterpreterFrame as CallFrame?)
            try {
                val generatorState = GeneratorState(operation, value)
                if (operation == NativeGenerator.GENERATOR_CLOSE) {
                    try {
                        return interpretLoop(cx, activeFrame, generatorState)
                    } catch (e: NativeGenerator.GeneratorClosedException) {
                        throw e
                    } catch (e: RuntimeException) {
                        if (e !== value) throw e
                    }
                    return Undefined.instance
                }
                val result = interpretLoop(cx, activeFrame, generatorState)
                generatorState.returnedException?.let { throw it }
                return result
            } finally {
                activeFrame.syncStateToFrame(frame)
            }
        }

        private fun interpretLoop(cx: Context, frameIn: CallFrame?, throwableIn: Any?): Any? {
            val oldFrame = cx.lastInterpreterFrame
            var frame = frameIn!!
            var throwable = throwableIn
            try {
                val instructionCounting = cx.instructionThreshold != 0
                var indexReg = -1
                var generatorState: GeneratorState? = null
                if (throwable != null) {
                    if (throwable is GeneratorState) {
                        generatorState = throwable
                        enterFrame(cx, frame, ScriptRuntime.emptyArgs, true)
                        throwable = null
                    } else {
                        throw Kit.codeBug()
                    }
                }
                var interpreterResult: Any? = null
                var interpreterResultDbl = 0.0

                stateLoop@ while (true) {
                    var jsThrowable: Any? = null
                    try {
                        if (throwable != null) {
                            frame = processThrowable(cx, throwable, frame, indexReg, instructionCounting)
                            throwable = frame.throwable
                            frame.throwable = null
                        } else if (generatorState == null && frame.frozen) {
                            throw Kit.codeBug()
                        }
                        when (val result = interpretFunction(cx, frame, throwable, generatorState, indexReg, instructionCounting)) {
                            is NewState.StateContinueResult -> {
                                frame = result.frame
                                indexReg = result.indexReg
                                continue@stateLoop
                            }
                            is NewState.StateBreakResult -> {
                                frame = result.frame
                                interpreterResult = frame.result
                                interpreterResultDbl = frame.resultDbl
                                break@stateLoop
                            }
                            is NewState.YieldResult -> return result.yielding
                            is NewState.ThrowableResult -> {
                                frame = result.frame
                                jsThrowable = result.throwable
                            }
                            else -> throw Kit.codeBug()
                        }
                    } catch (ex: Throwable) {
                        if (throwable != null) throw IllegalStateException(ex)
                        jsThrowable = ex
                    }
                    throwable = jsThrowable ?: throw Kit.codeBug()

                    // What script can see of this throwable: a catch clause, only finally blocks,
                    // or nothing at all.
                    val exCatchState = 2
                    val exFinallyState = 1
                    val exNoJsState = 0
                    var exState = when {
                        generatorState != null && generatorState.operation == NativeGenerator.GENERATOR_CLOSE && throwable === generatorState.value -> exFinallyState
                        throwable is JavaScriptException || throwable is EcmaError || throwable is EvaluatorException -> exCatchState
                        throwable is RuntimeException -> exFinallyState
                        throwable is Error -> exNoJsState
                        else -> exFinallyState
                    }
                    if (instructionCounting) {
                        try {
                            addInstructionCount(cx, frame, EXCEPTION_COST)
                        } catch (ex: RuntimeException) {
                            throwable = ex
                            exState = exFinallyState
                        } catch (ex: Error) {
                            throwable = ex
                            exState = exNoJsState
                        }
                    }
                    var f: CallFrame? = frame
                    while (true) {
                        if (exState != exNoJsState) {
                            indexReg = getExceptionHandler(f!!, exState != exCatchState)
                            if (indexReg >= 0) {
                                frame = f
                                continue@stateLoop
                            }
                        }
                        exitFrame(cx, f!!, throwable)
                        f = f.parentFrame
                        if (f == null) break
                    }
                    break@stateLoop
                }

                cx.lastInterpreterFrame = if (frame.parentFrame == null) frame.previousInterpreterFrame else frame.parentFrame
                if (throwable != null) throw throwable as Throwable
                return if (interpreterResult !== DBL_MRK) interpreterResult else ScriptRuntime.wrapNumber(interpreterResultDbl)
            } finally {
                cx.lastInterpreterFrame = oldFrame
            }
        }

        private fun interpretFunction(cx: Context, frame: CallFrame, tble: Any?, genState: GeneratorState?, iReg: Int, instructionCounting: Boolean): NewState {
            val state = InterpreterState(frame.savedStackTop, iReg, instructionCounting)
            try {
                val iCode = frame.idata.itsICode
                state.generatorState = genState
                state.throwable = tble
                cx.lastInterpreterFrame = frame
                loop@ while (true) {
                    var nextState: NewState?
                    do {
                        val op = iCode[frame.pc++].toInt()
                        nextState = execute(cx, frame, state, op)
                    } while (nextState == null)
                    when (nextState) {
                        NewState.BreakLoop -> break@loop
                        NewState.BreakJumplessRun -> {
                            if (instructionCounting) addInstructionCount(cx, frame, 2)
                            val offset = getShort(iCode, frame.pc)
                            if (offset != 0) frame.pc += offset - 1 else frame.pc = frame.idata.longJumps!![frame.pc]!!
                            if (instructionCounting) frame.pcPrevBranch = frame.pc
                        }
                        NewState.BreakWithoutExtension -> return NewState.ThrowableResult(frame, state.throwable)
                        else -> return nextState
                    }
                }
                exitFrame(cx, frame, null)
                val parent = frame.parentFrame
                if (parent != null) {
                    var newFrame = parent
                    if (newFrame.frozen) newFrame = newFrame.cloneFrozen()
                    setCallResult(newFrame, frame.result, frame.resultDbl)
                    return NewState.StateContinueResult(newFrame, state.indexReg)
                }
                return NewState.StateBreakResult(frame)
            } catch (ex: Throwable) {
                if (state.throwable != null) throw IllegalStateException(ex)
                state.throwable = ex
            }
            return NewState.ThrowableResult(frame, state.throwable)
        }

        /** Runs one instruction. Null means go on to the next one. */
        private fun execute(cx: Context, frame: CallFrame, state: InterpreterState, op: Int): NewState? {
            val stack = frame.stack
            val sDbl = frame.sDbl
            when (op) {
                Icode_GENERATOR -> {
                    if (!frame.frozen) {
                        generatorCreate(cx, frame)
                        return NewState.BreakLoop
                    }
                    val obj = thawGenerator(frame, state, state.generatorState!!, op)
                    if (obj !== Scriptable.NOT_FOUND) {
                        state.throwable = obj
                        return NewState.BreakWithoutExtension
                    }
                    return null
                }
                Token.YIELD, Icode_YIELD_STAR -> {
                    if (!frame.frozen) {
                        return NewState.YieldResult(freezeGenerator(cx, frame, state, state.generatorState!!, op == Icode_YIELD_STAR))
                    }
                    val obj = thawGenerator(frame, state, state.generatorState!!, op)
                    if (obj !== Scriptable.NOT_FOUND) {
                        state.throwable = obj
                        return NewState.BreakWithoutExtension
                    }
                    return null
                }
                Icode_GENERATOR_END -> {
                    frame.frozen = true
                    val sourceLine = getIndex(frame.idata.itsICode, frame.pc)
                    state.generatorState!!.returnedException =
                        JavaScriptException(NativeIterator.getStopIterationObject(frame.scope!!), frame.fnOrScript.descriptor!!.sourceName, sourceLine)
                    return NewState.BreakLoop
                }
                Icode_GENERATOR_RETURN -> {
                    frame.frozen = true
                    frame.result = stack[state.stackTop]
                    frame.resultDbl = sDbl[state.stackTop--]
                    val si = NativeIterator.StopIteration(if (frame.result === DBL_MRK) frame.resultDbl else frame.result)
                    val sourceLine = getIndex(frame.idata.itsICode, frame.pc)
                    state.generatorState!!.returnedException = JavaScriptException(si, frame.fnOrScript.descriptor!!.sourceName, sourceLine)
                    return NewState.BreakLoop
                }
                Token.RETHROW -> {
                    state.indexReg += frame.idata.itsMaxVars
                    state.throwable = stack[state.indexReg]
                    return NewState.BreakWithoutExtension
                }
                Token.THROW -> {
                    var value = stack[state.stackTop]
                    if (value === DBL_MRK) value = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    val sourceLine = getIndex(frame.idata.itsICode, frame.pc)
                    state.throwable = JavaScriptException(value, frame.fnOrScript.descriptor!!.sourceName, sourceLine)
                    --state.stackTop
                    return NewState.BreakWithoutExtension
                }
                Token.GE, Token.LE, Token.GT, Token.LT -> {
                    val rhs = stack[state.stackTop]
                    val lhs = stack[--state.stackTop]
                    val valBln: Boolean
                    if (lhs === DBL_MRK && rhs === DBL_MRK) {
                        valBln = ScriptRuntime.compareTo(sDbl[state.stackTop], sDbl[state.stackTop + 1], op)
                    } else if (rhs === DBL_MRK) {
                        valBln = ScriptRuntime.compareNumeric(stackNumeric(frame, state.stackTop), sDbl[state.stackTop + 1], op)
                    } else if (lhs === DBL_MRK) {
                        valBln = ScriptRuntime.compareNumeric(sDbl[state.stackTop], ScriptRuntime.toNumeric(rhs), op)
                    } else {
                        valBln = ScriptRuntime.compare(lhs, rhs, op)
                    }
                    stack[state.stackTop] = valBln
                    return null
                }
                Token.IN, Token.INSTANCEOF -> {
                    var rhs = stack[state.stackTop]
                    if (rhs === DBL_MRK) rhs = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    var lhs = stack[--state.stackTop]
                    if (lhs === DBL_MRK) lhs = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    stack[state.stackTop] = if (op == Token.IN) ScriptRuntime.`in`(lhs, rhs, cx) else ScriptRuntime.instanceOf(lhs, rhs, cx)
                    return null
                }
                Token.EQ -> {
                    stack[state.stackTop - 1] = doEquals(state, stack, sDbl)
                    return null
                }
                Token.NE -> {
                    stack[state.stackTop - 1] = !doEquals(state, stack, sDbl)
                    return null
                }
                Token.SHEQ -> {
                    stack[state.stackTop - 1] = doShallowEquals(state, stack, sDbl)
                    return null
                }
                Token.SHNE -> {
                    stack[state.stackTop - 1] = !doShallowEquals(state, stack, sDbl)
                    return null
                }
                Token.IFNE -> {
                    if (stackBoolean(frame, state.stackTop--)) {
                        frame.pc += 2
                        return null
                    }
                    return NewState.BreakJumplessRun
                }
                Token.IFEQ -> {
                    if (!stackBoolean(frame, state.stackTop--)) {
                        frame.pc += 2
                        return null
                    }
                    return NewState.BreakJumplessRun
                }
                Icode_IFEQ_POP -> {
                    if (!stackBoolean(frame, state.stackTop--)) {
                        frame.pc += 2
                        return null
                    }
                    stack[state.stackTop--] = null
                    return NewState.BreakJumplessRun
                }
                Icode_IF_NULL_UNDEF -> {
                    val v = stack[state.stackTop]
                    --state.stackTop
                    if (v != null && !Undefined.isUndefined(v)) {
                        frame.pc += 2
                        return null
                    }
                    return NewState.BreakJumplessRun
                }
                Icode_IF_NOT_NULL_UNDEF -> {
                    val v = stack[state.stackTop]
                    --state.stackTop
                    if (v == null || Undefined.isUndefined(v)) {
                        frame.pc += 2
                        return null
                    }
                    return NewState.BreakJumplessRun
                }
                Token.GOTO -> return NewState.BreakJumplessRun
                Icode_GOSUB -> {
                    ++state.stackTop
                    stack[state.stackTop] = DBL_MRK
                    sDbl[state.stackTop] = (frame.pc + 2).toDouble()
                    return NewState.BreakJumplessRun
                }
                Icode_STARTSUB -> {
                    if (state.stackTop == frame.emptyStackTop + 1) {
                        // Entered from GOSUB: keep the return address in the finally's local.
                        state.indexReg += frame.idata.itsMaxVars
                        stack[state.indexReg] = stack[state.stackTop]
                        sDbl[state.indexReg] = sDbl[state.stackTop]
                        --state.stackTop
                    } else if (state.stackTop != frame.emptyStackTop) {
                        throw Kit.codeBug()
                    }
                    return null
                }
                Icode_RETSUB -> {
                    if (state.instructionCounting) addInstructionCount(cx, frame, 0)
                    state.indexReg += frame.idata.itsMaxVars
                    val value = stack[state.indexReg]
                    if (value !== DBL_MRK) {
                        // A pending throwable rather than a return address: keep unwinding.
                        state.throwable = value
                        return NewState.BreakWithoutExtension
                    }
                    frame.pc = sDbl[state.indexReg].toInt()
                    if (state.instructionCounting) frame.pcPrevBranch = frame.pc
                    return null
                }
                Icode_POP -> {
                    stack[state.stackTop] = null
                    state.stackTop--
                    return null
                }
                Icode_POP_RESULT -> {
                    frame.result = stack[state.stackTop]
                    frame.resultDbl = sDbl[state.stackTop]
                    stack[state.stackTop] = null
                    --state.stackTop
                    return null
                }
                Icode_DUP -> {
                    stack[state.stackTop + 1] = stack[state.stackTop]
                    sDbl[state.stackTop + 1] = sDbl[state.stackTop]
                    state.stackTop++
                    return null
                }
                Icode_DUP2 -> {
                    stack[state.stackTop + 1] = stack[state.stackTop - 1]
                    sDbl[state.stackTop + 1] = sDbl[state.stackTop - 1]
                    stack[state.stackTop + 2] = stack[state.stackTop]
                    sDbl[state.stackTop + 2] = sDbl[state.stackTop]
                    state.stackTop += 2
                    return null
                }
                Icode_SWAP -> {
                    val o = stack[state.stackTop]
                    stack[state.stackTop] = stack[state.stackTop - 1]
                    stack[state.stackTop - 1] = o
                    val d = sDbl[state.stackTop]
                    sDbl[state.stackTop] = sDbl[state.stackTop - 1]
                    sDbl[state.stackTop - 1] = d
                    return null
                }
                Token.RETURN -> {
                    frame.result = stack[state.stackTop]
                    frame.resultDbl = sDbl[state.stackTop]
                    --state.stackTop
                    return NewState.BreakLoop
                }
                Token.RETURN_RESULT -> return NewState.BreakLoop
                Icode_RETUNDEF -> {
                    frame.result = undefined
                    return NewState.BreakLoop
                }
                Token.BITNOT -> {
                    val result = ScriptRuntime.bitwiseNOT(stackNumeric(frame, state.stackTop))
                    putNumber(stack, sDbl, state.stackTop, result)
                    return null
                }
                Token.BITAND, Token.BITOR, Token.BITXOR, Token.LSH, Token.RSH -> {
                    if (stack[state.stackTop] === DBL_MRK && stack[state.stackTop - 1] === DBL_MRK) {
                        val lValue = sDbl[state.stackTop - 1]
                        val rValue = sDbl[state.stackTop]
                        state.stackTop--
                        val result = when (op) {
                            Token.BITAND -> ScriptRuntime.bitwiseAND(lValue, rValue)
                            Token.BITOR -> ScriptRuntime.bitwiseOR(lValue, rValue)
                            Token.BITXOR -> ScriptRuntime.bitwiseXOR(lValue, rValue)
                            Token.LSH -> ScriptRuntime.leftShift(lValue, rValue)
                            else -> ScriptRuntime.signedRightShift(lValue, rValue)
                        }
                        stack[state.stackTop] = DBL_MRK
                        sDbl[state.stackTop] = result
                        return null
                    }
                    val lValue = stackNumeric(frame, state.stackTop - 1)
                    val rValue = stackNumeric(frame, state.stackTop)
                    state.stackTop--
                    val result = when (op) {
                        Token.BITAND -> ScriptRuntime.bitwiseAND(lValue, rValue)
                        Token.BITOR -> ScriptRuntime.bitwiseOR(lValue, rValue)
                        Token.BITXOR -> ScriptRuntime.bitwiseXOR(lValue, rValue)
                        Token.LSH -> ScriptRuntime.leftShift(lValue, rValue)
                        else -> ScriptRuntime.signedRightShift(lValue, rValue)
                    }
                    putNumber(stack, sDbl, state.stackTop, result)
                    return null
                }
                Token.URSH -> {
                    val lDbl = stackDouble(frame, state.stackTop - 1)
                    val rIntValue = stackInt32(frame, state.stackTop) and 0x1F
                    stack[--state.stackTop] = DBL_MRK
                    sDbl[state.stackTop] = (ScriptRuntime.toUint32(lDbl) ushr rIntValue).toDouble()
                    return null
                }
                Token.POS -> {
                    val rDbl = stackDouble(frame, state.stackTop)
                    stack[state.stackTop] = DBL_MRK
                    sDbl[state.stackTop] = rDbl
                    return null
                }
                Token.NEG -> {
                    putNumber(stack, sDbl, state.stackTop, ScriptRuntime.negate(stackNumeric(frame, state.stackTop)))
                    return null
                }
                Token.ADD -> {
                    doAdd(cx, frame, state)
                    return null
                }
                Token.SUB, Token.MUL, Token.DIV, Token.MOD, Token.EXP -> {
                    if (stack[state.stackTop] === DBL_MRK && stack[state.stackTop - 1] === DBL_MRK) {
                        val lNum = sDbl[state.stackTop - 1]
                        val rNum = sDbl[state.stackTop]
                        state.stackTop--
                        val result = when (op) {
                            Token.SUB -> lNum - rNum
                            Token.MUL -> lNum * rNum
                            Token.DIV -> lNum / rNum
                            Token.MOD -> lNum.rem(rNum)
                            else -> lNum.pow(rNum)
                        }
                        stack[state.stackTop] = DBL_MRK
                        sDbl[state.stackTop] = result
                        return null
                    }
                    val lNum = stackNumeric(frame, state.stackTop - 1)
                    val rNum = stackNumeric(frame, state.stackTop)
                    --state.stackTop
                    val result = when (op) {
                        Token.SUB -> ScriptRuntime.subtract(lNum, rNum)
                        Token.MUL -> ScriptRuntime.multiply(lNum, rNum)
                        Token.DIV -> ScriptRuntime.divide(lNum, rNum)
                        Token.MOD -> ScriptRuntime.remainder(lNum, rNum)
                        else -> ScriptRuntime.exponentiate(lNum, rNum)
                    }
                    putNumber(stack, sDbl, state.stackTop, result)
                    return null
                }
                Token.NOT -> {
                    stack[state.stackTop] = !stackBoolean(frame, state.stackTop)
                    return null
                }
                Token.BINDNAME -> {
                    stack[++state.stackTop] = ScriptRuntime.bind(cx, frame.scope!!, state.stringReg!!)
                    return null
                }
                Token.STRICT_SETNAME, Token.SETNAME -> {
                    var rhs = stack[state.stackTop]
                    if (rhs === DBL_MRK) rhs = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    val lhs = stack[state.stackTop - 1] as Scriptable?
                    stack[state.stackTop - 1] =
                        if (op == Token.SETNAME) ScriptRuntime.setName(lhs, rhs, cx, frame.scope!!, state.stringReg!!)
                        else ScriptRuntime.strictSetName(lhs, rhs, cx, frame.scope!!, state.stringReg!!)
                    --state.stackTop
                    return null
                }
                Token.STRING_CONCAT -> {
                    var rhs = stack[state.stackTop]
                    var lhs = stack[state.stackTop - 1]
                    if (rhs === DBL_MRK) rhs = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    if (lhs === DBL_MRK) lhs = ScriptRuntime.wrapNumber(sDbl[state.stackTop - 1])
                    stack[state.stackTop - 1] = ScriptRuntime.concat(lhs, rhs)
                    --state.stackTop
                    return null
                }
                Icode_SETCONST -> {
                    var rhs = stack[state.stackTop]
                    if (rhs === DBL_MRK) rhs = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    val lhs = stack[state.stackTop - 1] as Scriptable
                    stack[state.stackTop - 1] = ScriptRuntime.setConst(lhs, rhs, cx, state.stringReg!!)
                    --state.stackTop
                    return null
                }
                Token.DELPROP, Icode_DELNAME -> {
                    var rhs = stack[state.stackTop]
                    if (rhs === DBL_MRK) rhs = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    --state.stackTop
                    var lhs = stack[state.stackTop]
                    if (lhs === DBL_MRK) lhs = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    stack[state.stackTop] = ScriptRuntime.delete(lhs, rhs, cx, frame.scope!!, op == Icode_DELNAME)
                    return null
                }
                Icode_DELPROP_SUPER -> {
                    state.stackTop -= 1
                    stack[state.stackTop] = false
                    ScriptRuntime.throwDeleteOnSuperPropertyNotAllowed()
                }
                Token.GETPROPNOWARN -> {
                    var lhs = stack[state.stackTop]
                    if (lhs === DBL_MRK) lhs = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    stack[state.stackTop] = ScriptRuntime.getObjectPropNoWarn(lhs, state.stringReg!!, cx, frame.scope!!)
                    return null
                }
                Token.GETPROP -> {
                    var lhs = stack[state.stackTop]
                    if (lhs === DBL_MRK) lhs = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    stack[state.stackTop] = ScriptRuntime.getObjectProp(lhs, state.stringReg!!, cx, frame.scope!!)
                    return null
                }
                Token.GETPROP_SUPER, Token.GETPROPNOWARN_SUPER -> {
                    val superObject = stack[state.stackTop]
                    if (superObject === DBL_MRK) throw Kit.codeBug()
                    stack[state.stackTop] = ScriptRuntime.getSuperProp(superObject, state.stringReg!!, cx, frame.scope!!, frame.thisObj, op == Token.GETPROPNOWARN_SUPER)
                    return null
                }
                Token.SETPROP -> {
                    var rhs = stack[state.stackTop]
                    if (rhs === DBL_MRK) rhs = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    var lhs = stack[state.stackTop - 1]
                    if (lhs === DBL_MRK) lhs = ScriptRuntime.wrapNumber(sDbl[state.stackTop - 1])
                    stack[--state.stackTop] = ScriptRuntime.setObjectProp(lhs, state.stringReg!!, rhs, cx, frame.scope!!)
                    return null
                }
                Token.SETPROP_SUPER -> {
                    var rhs = stack[state.stackTop]
                    if (rhs === DBL_MRK) rhs = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    val superObject = stack[state.stackTop - 1]
                    if (superObject === DBL_MRK) throw Kit.codeBug()
                    stack[--state.stackTop] = ScriptRuntime.setSuperProp(superObject, state.stringReg!!, rhs, cx, frame.scope!!, frame.thisObj)
                    return null
                }
                Icode_PROP_INC_DEC -> {
                    var lhs = stack[state.stackTop]
                    if (lhs === DBL_MRK) lhs = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    stack[state.stackTop] = ScriptRuntime.propIncrDecr(lhs, state.stringReg!!, cx, frame.scope!!, frame.idata.itsICode[frame.pc].toInt())
                    ++frame.pc
                    return null
                }
                Token.GETELEM -> {
                    var lhs = stack[--state.stackTop]
                    if (lhs === DBL_MRK) lhs = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    val id = stack[state.stackTop + 1]
                    stack[state.stackTop] =
                        if (id !== DBL_MRK) ScriptRuntime.getObjectElem(lhs, id, cx, frame.scope!!)
                        else ScriptRuntime.getObjectIndex(lhs, sDbl[state.stackTop + 1], cx, frame.scope!!)
                    return null
                }
                Token.GETELEM_SUPER -> {
                    val superObject = stack[--state.stackTop]
                    if (superObject === DBL_MRK) throw Kit.codeBug()
                    val id = stack[state.stackTop + 1]
                    stack[state.stackTop] =
                        if (id !== DBL_MRK) ScriptRuntime.getSuperElem(superObject, id, cx, frame.scope!!, frame.thisObj)
                        else ScriptRuntime.getSuperIndex(superObject, sDbl[state.stackTop + 1], cx, frame.scope!!, frame.thisObj)
                    return null
                }
                Token.SETELEM -> {
                    var rhs = stack[state.stackTop]
                    if (rhs === DBL_MRK) rhs = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    state.stackTop -= 2
                    var lhs = stack[state.stackTop]
                    if (lhs === DBL_MRK) lhs = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    val id = stack[state.stackTop + 1]
                    stack[state.stackTop] =
                        if (id !== DBL_MRK) ScriptRuntime.setObjectElem(lhs, id, rhs, cx, frame.scope!!)
                        else ScriptRuntime.setObjectIndex(lhs, sDbl[state.stackTop + 1], rhs, cx, frame.scope!!)
                    return null
                }
                Token.SETELEM_SUPER -> {
                    var rhs = stack[state.stackTop]
                    if (rhs === DBL_MRK) rhs = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    state.stackTop -= 2
                    val superObject = stack[state.stackTop]
                    if (superObject === DBL_MRK) throw Kit.codeBug()
                    val id = stack[state.stackTop + 1]
                    stack[state.stackTop] =
                        if (id !== DBL_MRK) ScriptRuntime.setSuperElem(superObject, id, rhs, cx, frame.scope!!, frame.thisObj)
                        else ScriptRuntime.setSuperIndex(superObject, sDbl[state.stackTop + 1], rhs, cx, frame.scope!!, frame.thisObj)
                    return null
                }
                Icode_ELEM_INC_DEC -> {
                    var rhs = stack[state.stackTop]
                    if (rhs === DBL_MRK) rhs = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    --state.stackTop
                    var lhs = stack[state.stackTop]
                    if (lhs === DBL_MRK) lhs = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    stack[state.stackTop] = ScriptRuntime.elemIncrDecr(lhs, rhs, cx, frame.scope!!, frame.idata.itsICode[frame.pc].toInt())
                    ++frame.pc
                    return null
                }
                Token.GET_REF -> {
                    stack[state.stackTop] = ScriptRuntime.refGet(stack[state.stackTop] as Ref, cx)
                    return null
                }
                Token.SET_REF -> {
                    var value = stack[state.stackTop]
                    if (value === DBL_MRK) value = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    val ref = stack[state.stackTop - 1] as Ref
                    stack[--state.stackTop] = ScriptRuntime.refSet(ref, value, cx, frame.scope!!)
                    return null
                }
                Token.DEL_REF -> {
                    stack[state.stackTop] = ScriptRuntime.refDel(stack[state.stackTop] as Ref, cx)
                    return null
                }
                Icode_REF_INC_DEC -> {
                    stack[state.stackTop] = ScriptRuntime.refIncrDecr(stack[state.stackTop] as Ref, cx, frame.scope!!, frame.idata.itsICode[frame.pc].toInt())
                    ++frame.pc
                    return null
                }
                Token.LOCAL_LOAD -> {
                    ++state.stackTop
                    state.indexReg += frame.idata.itsMaxVars
                    stack[state.stackTop] = stack[state.indexReg]
                    sDbl[state.stackTop] = sDbl[state.indexReg]
                    return null
                }
                Icode_LOCAL_CLEAR -> {
                    state.indexReg += frame.idata.itsMaxVars
                    stack[state.indexReg] = null
                    return null
                }
                Icode_NAME_AND_THIS -> {
                    stack[++state.stackTop] = ScriptRuntime.getNameAndThis(state.stringReg!!, cx, frame.scope!!)
                    return null
                }
                Icode_NAME_AND_THIS_OPTIONAL -> {
                    stack[++state.stackTop] = ScriptRuntime.getNameAndThisOptional(state.stringReg!!, cx, frame.scope!!)
                    return null
                }
                Icode_PROP_AND_THIS -> {
                    var obj = stack[state.stackTop]
                    if (obj === DBL_MRK) obj = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    stack[state.stackTop] = ScriptRuntime.getPropAndThis(obj, state.stringReg!!, cx, frame.scope!!)
                    return null
                }
                Icode_PROP_AND_THIS_OPTIONAL -> {
                    var obj = stack[state.stackTop]
                    if (obj === DBL_MRK) obj = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    stack[state.stackTop] = ScriptRuntime.getPropAndThisOptional(obj, state.stringReg!!, cx, frame.scope!!)
                    return null
                }
                Icode_ELEM_AND_THIS, Icode_ELEM_AND_THIS_OPTIONAL -> {
                    var obj = stack[state.stackTop - 1]
                    if (obj === DBL_MRK) obj = ScriptRuntime.wrapNumber(sDbl[state.stackTop - 1])
                    var id = stack[state.stackTop]
                    if (id === DBL_MRK) id = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    stack[--state.stackTop] =
                        if (op == Icode_ELEM_AND_THIS) ScriptRuntime.getElemAndThis(obj, id, cx, frame.scope!!)
                        else ScriptRuntime.getElemAndThisOptional(obj, id, cx, frame.scope!!)
                    return null
                }
                Icode_VALUE_AND_THIS, Icode_VALUE_AND_THIS_OPTIONAL -> {
                    var value = stack[state.stackTop]
                    if (value === DBL_MRK) value = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    stack[state.stackTop] =
                        if (op == Icode_VALUE_AND_THIS) ScriptRuntime.getValueAndThis(value, cx) else ScriptRuntime.getValueAndThisOptional(value, cx)
                    return null
                }
                Icode_CALLSPECIAL, Icode_CALLSPECIAL_OPTIONAL -> {
                    doCallSpecial(cx, frame, state, op)
                    return null
                }
                Token.CALL, Icode_CALL_ON_SUPER, Icode_TAIL_CALL, Token.REF_CALL -> return doCall(cx, frame, state, op)
                Token.NEW -> return doNew(cx, frame, state, op)
                Token.TYPEOF -> {
                    var lhs = stack[state.stackTop]
                    if (lhs === DBL_MRK) lhs = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    stack[state.stackTop] = ScriptRuntime.typeOf(lhs)
                    return null
                }
                Icode_TYPEOFNAME -> {
                    stack[++state.stackTop] = ScriptRuntime.typeofName(frame.scope!!, state.stringReg!!)
                    return null
                }
                Token.STRING -> {
                    stack[++state.stackTop] = state.stringReg
                    return null
                }
                Icode_SHORTNUMBER -> {
                    ++state.stackTop
                    stack[state.stackTop] = DBL_MRK
                    sDbl[state.stackTop] = getShort(frame.idata.itsICode, frame.pc).toDouble()
                    frame.pc += 2
                    return null
                }
                Icode_INTNUMBER -> {
                    ++state.stackTop
                    stack[state.stackTop] = DBL_MRK
                    sDbl[state.stackTop] = getInt(frame.idata.itsICode, frame.pc).toDouble()
                    frame.pc += 4
                    return null
                }
                Token.NUMBER -> {
                    ++state.stackTop
                    stack[state.stackTop] = DBL_MRK
                    sDbl[state.stackTop] = frame.idata.itsDoubleTable!![state.indexReg]
                    return null
                }
                Token.BIGINT -> {
                    stack[++state.stackTop] = state.bigIntReg
                    return null
                }
                Token.NAME -> {
                    stack[++state.stackTop] = ScriptRuntime.name(cx, frame.scope!!, state.stringReg!!)
                    return null
                }
                Icode_NAME_INC_DEC -> {
                    stack[++state.stackTop] = ScriptRuntime.nameIncrDecr(frame.scope!!, state.stringReg!!, cx, frame.idata.itsICode[frame.pc].toInt())
                    ++frame.pc
                    return null
                }
                Icode_SETCONSTVAR1, Icode_SETCONSTVAR -> {
                    if (op == Icode_SETCONSTVAR1) state.indexReg = frame.idata.itsICode[frame.pc++].toInt()
                    val varAttributes = frame.varSource.stackAttributes
                    val vars = frame.varSource.stack
                    val varDbls = frame.varSource.sDbl
                    if ((varAttributes[state.indexReg].toInt() and ScriptableObject.READONLY) == 0) {
                        throw Context.reportRuntimeErrorById("msg.var.redecl", frame.fnOrScript.descriptor!!.getParamOrVarName(state.indexReg))
                    }
                    if ((varAttributes[state.indexReg].toInt() and ScriptableObject.UNINITIALIZED_CONST) != 0) {
                        vars[state.indexReg] = stack[state.stackTop]
                        varAttributes[state.indexReg] = (varAttributes[state.indexReg].toInt() and ScriptableObject.UNINITIALIZED_CONST.inv()).toByte()
                        varDbls[state.indexReg] = sDbl[state.stackTop]
                    }
                    return null
                }
                Icode_SETVAR1, Token.SETVAR -> {
                    if (op == Icode_SETVAR1) state.indexReg = frame.idata.itsICode[frame.pc++].toInt()
                    val varAttributes = frame.varSource.stackAttributes
                    if ((varAttributes[state.indexReg].toInt() and ScriptableObject.READONLY) == 0) {
                        frame.varSource.stack[state.indexReg] = stack[state.stackTop]
                        frame.varSource.sDbl[state.indexReg] = sDbl[state.stackTop]
                    }
                    return null
                }
                Icode_GETVAR1, Token.GETVAR -> {
                    if (op == Icode_GETVAR1) state.indexReg = frame.idata.itsICode[frame.pc++].toInt()
                    ++state.stackTop
                    stack[state.stackTop] = frame.varSource.stack[state.indexReg]
                    sDbl[state.stackTop] = frame.varSource.sDbl[state.indexReg]
                    return null
                }
                Icode_VAR_INC_DEC -> {
                    doVarIncDec(frame, state)
                    return null
                }
                Icode_ZERO -> {
                    stack[++state.stackTop] = 0
                    return null
                }
                Icode_ONE -> {
                    stack[++state.stackTop] = 1
                    return null
                }
                Token.NULL -> {
                    stack[++state.stackTop] = null
                    return null
                }
                Token.THIS -> {
                    stack[++state.stackTop] = frame.thisObj
                    return null
                }
                Token.SUPER -> {
                    val homeObject = frame.fnOrScript.homeObject
                    stack[++state.stackTop] = if (homeObject == null) Undefined.instance else homeObject.prototype
                    return null
                }
                Token.THISFN -> {
                    stack[++state.stackTop] = frame.fnOrScript
                    return null
                }
                Token.FALSE -> {
                    stack[++state.stackTop] = false
                    return null
                }
                Token.TRUE -> {
                    stack[++state.stackTop] = true
                    return null
                }
                Icode_UNDEF -> {
                    stack[++state.stackTop] = undefined
                    return null
                }
                else -> return executeCold(cx, frame, state, op)
            }
        }

        /**
         * The second half of the dispatch. The two exist because HotSpot refuses to JIT-compile a
         * method over 8000 bytecodes, and one `when` over every opcode lands well past that, which
         * leaves the whole interpreter running interpreted (D-42).
         */
        private fun executeCold(cx: Context, frame: CallFrame, state: InterpreterState, op: Int): NewState? {
            val stack = frame.stack
            val sDbl = frame.sDbl
            when (op) {
                Token.ENTERWITH -> {
                    var lhs = stack[state.stackTop]
                    if (lhs === DBL_MRK) lhs = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    frame.scope = ScriptRuntime.enterWith(lhs, cx, frame.scope!!)
                    state.stackTop--
                    return null
                }
                Token.LEAVEWITH -> {
                    frame.scope = ScriptRuntime.leaveWith(frame.scope!!)
                    return null
                }
                Token.CATCH_SCOPE -> {
                    --state.stackTop
                    state.indexReg += frame.idata.itsMaxVars
                    val afterFirstScope = frame.idata.itsICode[frame.pc].toInt() != 0
                    val caughtException = stack[state.stackTop + 1] as Throwable
                    val lastCatchScope = if (!afterFirstScope) null else stack[state.indexReg] as Scriptable?
                    stack[state.indexReg] = ScriptRuntime.newCatchScope(caughtException, lastCatchScope, state.stringReg, cx, frame.scope!!)
                    ++frame.pc
                    return null
                }
                Token.ENUM_INIT_KEYS, Token.ENUM_INIT_VALUES, Token.ENUM_INIT_ARRAY, Token.ENUM_INIT_VALUES_IN_ORDER -> {
                    var lhs = stack[state.stackTop]
                    if (lhs === DBL_MRK) lhs = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    state.indexReg += frame.idata.itsMaxVars
                    val enumType = when (op) {
                        Token.ENUM_INIT_KEYS -> ScriptRuntime.ENUMERATE_KEYS
                        Token.ENUM_INIT_VALUES -> ScriptRuntime.ENUMERATE_VALUES
                        Token.ENUM_INIT_VALUES_IN_ORDER -> ScriptRuntime.ENUMERATE_VALUES_IN_ORDER
                        else -> ScriptRuntime.ENUMERATE_ARRAY
                    }
                    stack[state.indexReg] = ScriptRuntime.enumInit(lhs, cx, frame.scope!!, enumType)
                    --state.stackTop
                    return null
                }
                Token.ENUM_NEXT, Token.ENUM_ID -> {
                    state.indexReg += frame.idata.itsMaxVars
                    val v = stack[state.indexReg]
                    stack[++state.stackTop] = if (op == Token.ENUM_NEXT) ScriptRuntime.enumNext(v, cx) else ScriptRuntime.enumId(v, cx)
                    return null
                }
                Token.REF_SPECIAL -> {
                    var obj = stack[state.stackTop]
                    if (obj === DBL_MRK) obj = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    stack[state.stackTop] = ScriptRuntime.specialRef(obj, state.stringReg!!, cx, frame.scope!!)
                    return null
                }
                Token.REF_MEMBER, Token.REF_NS_MEMBER, Token.REF_NAME, Token.REF_NS_NAME,
                Icode_ENTERDQ, Icode_LEAVEDQ, Token.DEFAULTNAMESPACE, Token.ESCXMLATTR, Token.ESCXMLTEXT ->
                    throw ScriptRuntime.typeError("XML is not available")
                Icode_SCOPE_LOAD -> {
                    state.indexReg += frame.idata.itsMaxVars
                    frame.scope = stack[state.indexReg] as Scriptable
                    return null
                }
                Icode_SCOPE_SAVE -> {
                    state.indexReg += frame.idata.itsMaxVars
                    stack[state.indexReg] = frame.scope
                    return null
                }
                Icode_SPREAD -> {
                    val source = stack[state.stackTop]
                    --state.stackTop
                    val store = stack[state.stackTop] as NewLiteralStorage
                    if (store.hasSkipIndexes()) {
                        val sourcePos = 0xFF and frame.idata.itsICode[frame.pc].toInt()
                        ++frame.pc
                        store.spread(cx, frame.scope!!, source, sourcePos)
                    } else {
                        store.spread(cx, frame.scope!!, source, 0)
                    }
                    return null
                }
                Icode_CLOSURE_EXPR -> {
                    stack[++state.stackTop] = createClosure(cx, frame, state.indexReg)
                    return null
                }
                Icode_METHOD_EXPR -> {
                    val homeObject = stack[state.stackTop - 1] as Scriptable
                    stack[++state.stackTop] = createMethod(cx, frame, state.indexReg, homeObject)
                    return null
                }
                Icode_CLOSURE_STMT -> {
                    initFunction(cx, frame.scope!!, frame.fnOrScript.descriptor!!, state.indexReg)
                    return null
                }
                Token.REGEXP -> {
                    stack[++state.stackTop] = ScriptRuntime.wrapRegExp(cx, frame.scope!!, frame.idata.itsRegExpLiterals!![state.indexReg]!!)
                    return null
                }
                Icode_TEMPLATE_LITERAL_CALLSITE -> {
                    stack[++state.stackTop] = ScriptRuntime.getTemplateLiteralCallSite(cx, frame.scope!!, frame.idata.itsTemplateLiterals!!, state.indexReg)
                    return null
                }
                Icode_LITERAL_NEW_OBJECT -> {
                    ++frame.pc
                    ++state.stackTop
                    stack[state.stackTop] = cx.newObject(frame.scope!!)
                    ++state.stackTop
                    if (state.indexReg < 0) {
                        stack[state.stackTop] = NewLiteralStorage.create(cx, -state.indexReg - 1, true)
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        val ids = frame.idata.literalIds!![state.indexReg] as Array<Any?>?
                        val copyArray = frame.idata.itsICode[frame.pc].toInt() != 0
                        stack[state.stackTop] = NewLiteralStorage.create(cx, if (copyArray) ids!!.copyOf() else ids)
                    }
                    return null
                }
                Icode_LITERAL_NEW_ARRAY -> {
                    val storage = NewLiteralStorage.create(cx, state.indexReg, false)
                    val skipIdx = 0xFF and frame.idata.itsICode[frame.pc].toInt()
                    ++frame.pc
                    if (skipIdx > 0) storage.setSkipIndexes(frame.idata.literalIds!![skipIdx - 1] as IntArray)
                    stack[++state.stackTop] = storage
                    return null
                }
                Icode_LITERAL_SET -> {
                    var value = stack[state.stackTop]
                    if (value === DBL_MRK) value = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    --state.stackTop
                    (stack[state.stackTop] as NewLiteralStorage).pushValue(value)
                    return null
                }
                Icode_LITERAL_GETTER -> {
                    val value = stack[state.stackTop]
                    --state.stackTop
                    (stack[state.stackTop] as NewLiteralStorage).pushGetter(value)
                    return null
                }
                Icode_LITERAL_SETTER -> {
                    val value = stack[state.stackTop]
                    --state.stackTop
                    (stack[state.stackTop] as NewLiteralStorage).pushSetter(value)
                    return null
                }
                Icode_LITERAL_KEY_SET -> {
                    var key = stack[state.stackTop]
                    if (key === DBL_MRK) key = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                    --state.stackTop
                    (stack[state.stackTop] as NewLiteralStorage).pushKey(key)
                    return null
                }
                Token.OBJECTLIT -> {
                    val store = stack[state.stackTop] as NewLiteralStorage
                    --state.stackTop
                    val obj = stack[state.stackTop] as Scriptable
                    ScriptRuntime.fillObjectLiteral(obj, store.getKeys(), store.getValues(), store.getGetterSetters(), cx, frame.scope!!)
                    return null
                }
                Token.ARRAYLIT, Icode_SPARE_ARRAYLIT -> {
                    val store = stack[state.stackTop] as NewLiteralStorage
                    var skipIndexes: IntArray? = null
                    if (op == Icode_SPARE_ARRAYLIT) {
                        skipIndexes = store.getAdjustedSkipIndexes() ?: frame.idata.literalIds!![state.indexReg] as IntArray
                    }
                    stack[state.stackTop] = ScriptRuntime.newArrayLiteral(store.getValues(), skipIndexes, cx, frame.scope!!)
                    return null
                }
                Icode_DEBUGGER -> return null
                Icode_LINE -> {
                    frame.pcSourceLineStart = frame.pc
                    frame.pc += 2
                    return null
                }
                Icode_REG_IND_C0, Icode_REG_IND_C1, Icode_REG_IND_C2, Icode_REG_IND_C3, Icode_REG_IND_C4, Icode_REG_IND_C5 -> {
                    state.indexReg = Icode_REG_IND_C0 - op
                    return null
                }
                Icode_REG_IND1 -> {
                    state.indexReg = 0xFF and frame.idata.itsICode[frame.pc].toInt()
                    ++frame.pc
                    return null
                }
                Icode_REG_IND2 -> {
                    state.indexReg = getIndex(frame.idata.itsICode, frame.pc)
                    frame.pc += 2
                    return null
                }
                Icode_REG_IND4 -> {
                    state.indexReg = getInt(frame.idata.itsICode, frame.pc)
                    frame.pc += 4
                    return null
                }
                Icode_REG_STR_C0, Icode_REG_STR_C1, Icode_REG_STR_C2, Icode_REG_STR_C3 -> {
                    state.stringReg = frame.idata.itsStringTable[Icode_REG_STR_C0 - op]
                    return null
                }
                Icode_REG_STR1 -> {
                    state.stringReg = frame.idata.itsStringTable[0xFF and frame.idata.itsICode[frame.pc].toInt()]
                    ++frame.pc
                    return null
                }
                Icode_REG_STR2 -> {
                    state.stringReg = frame.idata.itsStringTable[getIndex(frame.idata.itsICode, frame.pc)]
                    frame.pc += 2
                    return null
                }
                Icode_REG_STR4 -> {
                    state.stringReg = frame.idata.itsStringTable[getInt(frame.idata.itsICode, frame.pc)]
                    frame.pc += 4
                    return null
                }
                Icode_REG_BIGINT_C0, Icode_REG_BIGINT_C1, Icode_REG_BIGINT_C2, Icode_REG_BIGINT_C3 -> {
                    state.bigIntReg = frame.idata.itsBigIntTable[Icode_REG_BIGINT_C0 - op]
                    return null
                }
                Icode_REG_BIGINT1 -> {
                    state.bigIntReg = frame.idata.itsBigIntTable[0xFF and frame.idata.itsICode[frame.pc].toInt()]
                    ++frame.pc
                    return null
                }
                Icode_REG_BIGINT2 -> {
                    state.bigIntReg = frame.idata.itsBigIntTable[getIndex(frame.idata.itsICode, frame.pc)]
                    frame.pc += 2
                    return null
                }
                Icode_REG_BIGINT4 -> {
                    state.bigIntReg = frame.idata.itsBigIntTable[getInt(frame.idata.itsICode, frame.pc)]
                    frame.pc += 4
                    return null
                }
                else -> throw Kit.codeBug("Unknown icode $op")
            }
        }

        private fun putNumber(stack: Array<Any?>, sDbl: DoubleArray, top: Int, result: Any) {
            if (result is KBigInt) {
                stack[top] = result
            } else {
                stack[top] = DBL_MRK
                sDbl[top] = ScriptRuntime.numericToDouble(result)
            }
        }

        private fun doEquals(state: InterpreterState, stack: Array<Any?>, sDbl: DoubleArray): Boolean {
            val rhs = stack[state.stackTop--]
            val lhs = stack[state.stackTop]
            return if (rhs === DBL_MRK) {
                if (lhs === DBL_MRK) sDbl[state.stackTop] == sDbl[state.stackTop + 1]
                else ScriptRuntime.eqNumber(sDbl[state.stackTop + 1], lhs)
            } else if (lhs === DBL_MRK) {
                ScriptRuntime.eqNumber(sDbl[state.stackTop], rhs)
            } else {
                ScriptRuntime.eq(lhs, rhs)
            }
        }

        private fun doShallowEquals(state: InterpreterState, stack: Array<Any?>, sDbl: DoubleArray): Boolean {
            val rhs = stack[state.stackTop--]
            val lhs = stack[state.stackTop]
            return if (rhs === DBL_MRK) {
                val rDbl = sDbl[state.stackTop + 1]
                if (lhs === DBL_MRK) rDbl == sDbl[state.stackTop]
                else if (lhs is Number && lhs !is KBigInt) rDbl == lhs.toDouble()
                else false
            } else if (lhs === DBL_MRK) {
                val ldbl = sDbl[state.stackTop]
                if (rhs is Number && rhs !is KBigInt) ldbl == rhs.toDouble() else false
            } else {
                ScriptRuntime.shallowEq(lhs, rhs)
            }
        }

        private fun doAdd(cx: Context, frame: CallFrame, state: InterpreterState) {
            val stack = frame.stack
            val sDbl = frame.sDbl
            var rhs = stack[state.stackTop]
            var lhs = stack[--state.stackTop]
            val d: Double
            val leftRightOrder: Boolean
            if (rhs === DBL_MRK) {
                d = sDbl[state.stackTop + 1]
                if (lhs === DBL_MRK) {
                    sDbl[state.stackTop] += d
                    return
                }
                leftRightOrder = true
            } else if (lhs === DBL_MRK) {
                d = sDbl[state.stackTop]
                lhs = rhs
                leftRightOrder = false
            } else {
                if (lhs is Scriptable || rhs is Scriptable) {
                    stack[state.stackTop] = ScriptRuntime.add(lhs, rhs, cx)
                } else if (lhs is CharSequence) {
                    stack[state.stackTop] =
                        if (rhs is CharSequence) ConsString(lhs, rhs) else ConsString(lhs, ScriptRuntime.toCharSequence(rhs))
                } else if (rhs is CharSequence) {
                    stack[state.stackTop] = ConsString(ScriptRuntime.toCharSequence(lhs), rhs)
                } else {
                    val lNum = if (lhs is Number || lhs is KBigInt) lhs else ScriptRuntime.toNumeric(lhs)
                    val rNum = if (rhs is Number || rhs is KBigInt) rhs else ScriptRuntime.toNumeric(rhs)
                    if (lNum is KBigInt && rNum is KBigInt) {
                        stack[state.stackTop] = lNum.add(rNum)
                    } else if (lNum is KBigInt || rNum is KBigInt) {
                        throw ScriptRuntime.typeErrorById("msg.cant.convert.to.number", "BigInt")
                    } else {
                        stack[state.stackTop] = DBL_MRK
                        sDbl[state.stackTop] = ScriptRuntime.numericToDouble(lNum) + ScriptRuntime.numericToDouble(rNum)
                    }
                }
                return
            }
            if (lhs is Scriptable) {
                rhs = ScriptRuntime.wrapNumber(d)
                if (!leftRightOrder) {
                    val tmp = lhs
                    lhs = rhs
                    rhs = tmp
                }
                stack[state.stackTop] = ScriptRuntime.add(lhs, rhs, cx)
            } else if (lhs is CharSequence) {
                val rstr: CharSequence = ScriptRuntime.numberToString(d, 10)
                stack[state.stackTop] = if (leftRightOrder) ConsString(lhs, rstr) else ConsString(rstr, lhs)
            } else {
                val lNum = if (lhs is Number || lhs is KBigInt) lhs else ScriptRuntime.toNumeric(lhs)
                if (lNum is KBigInt) throw ScriptRuntime.typeErrorById("msg.cant.convert.to.number", "BigInt")
                stack[state.stackTop] = DBL_MRK
                sDbl[state.stackTop] = ScriptRuntime.numericToDouble(lNum) + d
            }
        }

        private fun doVarIncDec(frame: CallFrame, state: InterpreterState) {
            val varAttributes = frame.varSource.stackAttributes
            val vars = frame.varSource.stack
            val varDbls = frame.varSource.sDbl
            ++state.stackTop
            val incrDecrMask = frame.idata.itsICode[frame.pc].toInt()
            val varValue = vars[state.indexReg]
            var d = 0.0
            var bi: KBigInt? = null
            if (varValue === DBL_MRK) {
                d = varDbls[state.indexReg]
            } else {
                val num = ScriptRuntime.toNumeric(varValue)
                if (num is KBigInt) bi = num else d = ScriptRuntime.numericToDouble(num)
            }
            val post = (incrDecrMask and Node.POST_FLAG) != 0
            val readonly = (varAttributes[state.indexReg].toInt() and ScriptableObject.READONLY) != 0
            if (bi == null) {
                val d2 = if ((incrDecrMask and Node.DECR_FLAG) == 0) d + 1.0 else d - 1.0
                if (!readonly) {
                    if (varValue !== DBL_MRK) vars[state.indexReg] = DBL_MRK
                    varDbls[state.indexReg] = d2
                    frame.stack[state.stackTop] = DBL_MRK
                    frame.sDbl[state.stackTop] = if (post) d else d2
                } else if (post && varValue !== DBL_MRK) {
                    frame.stack[state.stackTop] = varValue
                } else {
                    frame.stack[state.stackTop] = DBL_MRK
                    frame.sDbl[state.stackTop] = if (post) d else d2
                }
            } else {
                val result = if ((incrDecrMask and Node.DECR_FLAG) == 0) bi.add(KBigInt.ONE) else bi.subtract(KBigInt.ONE)
                if (!readonly) {
                    vars[state.indexReg] = result
                    frame.stack[state.stackTop] = if (post) bi else result
                } else if (post && varValue !== DBL_MRK) {
                    frame.stack[state.stackTop] = varValue
                } else {
                    frame.stack[state.stackTop] = if (post) bi else result
                }
            }
            ++frame.pc
        }

        private fun doCallSpecial(cx: Context, frame: CallFrame, state: InterpreterState, op: Int) {
            val stack = frame.stack
            val sDbl = frame.sDbl
            val iCode = frame.idata.itsICode
            val isOptionalChainingCall = op == Icode_CALLSPECIAL_OPTIONAL
            if (state.instructionCounting) cx.instructionCount += INVOCATION_COST
            val callType = iCode[frame.pc].toInt() and 0xFF
            val isNew = iCode[frame.pc + 1].toInt() != 0
            val sourceLine = getIndex(iCode, frame.pc + 2)
            if (isNew) {
                state.stackTop -= state.indexReg
                var function = stack[state.stackTop]
                if (function === DBL_MRK) function = ScriptRuntime.wrapNumber(sDbl[state.stackTop])
                val outArgs = getArgsArray(stack, sDbl, state.stackTop + 1, state.indexReg)
                stack[state.stackTop] = ScriptRuntime.newSpecial(cx, function, outArgs, frame.scope!!, callType)
            } else {
                state.stackTop -= state.indexReg
                val result = stack[state.stackTop] as ScriptRuntime.LookupResult?
                val outArgs = getArgsArray(stack, sDbl, state.stackTop + 1, state.indexReg)
                val function = result?.getCallable()
                stack[state.stackTop] = ScriptRuntime.callSpecial(
                    cx, function, result?.getThis(), outArgs, frame.scope!!, frame.thisObj, callType,
                    frame.fnOrScript.descriptor!!.sourceName, sourceLine, isOptionalChainingCall,
                )
            }
            frame.pc += 4
        }

        private fun doCall(cx: Context, frame: CallFrame, state: InterpreterState, op: Int): NewState? {
            val stack = frame.stack
            val sDbl = frame.sDbl
            var boundArgs: Array<Any?>? = null
            var blen = 0
            if (state.instructionCounting) cx.instructionCount += INVOCATION_COST
            state.stackTop -= state.indexReg
            val result = stack[state.stackTop] as ScriptRuntime.LookupResult
            var fun_: Callable? = result.getCallable()
            var funThisObj: Scriptable? = result.getThis()
            val funHomeObj = if (fun_ is BaseFunction) fun_.homeObject else null
            if (op == Icode_CALL_ON_SUPER) funThisObj = frame.thisObj
            if (op == Token.REF_CALL) {
                val outArgs = getArgsArray(stack, sDbl, state.stackTop + 1, state.indexReg)
                stack[state.stackTop] = ScriptRuntime.callRef(fun_!!, funThisObj, outArgs, cx)
                return null
            }
            var calleeScope = frame.scope!!
            if (frame.useActivation) calleeScope = ScriptableObject.getTopLevelScope(frame.scope!!)
            // Unwrap apply, call and bound functions before deciding how to make the call.
            while (true) {
                if (fun_ is KnownBuiltInFunction) {
                    val kfun = fun_
                    if (BaseFunction.isApplyOrCall(kfun)) {
                        fun_ = ScriptRuntime.getCallable(funThisObj)
                        funThisObj = getApplyThis(cx, stack, sDbl, boundArgs, state.stackTop + 1, state.indexReg, fun_ as Function)
                        if (BaseFunction.isApply(kfun)) {
                            val callArgs = when {
                                blen > 1 -> ScriptRuntime.getApplyArguments(cx, boundArgs!![1])
                                state.indexReg < 2 -> ScriptRuntime.emptyArgs
                                else -> ScriptRuntime.getApplyArguments(cx, stack[state.stackTop - blen + 2])
                            }
                            boundArgs = callArgs
                            blen = callArgs.size
                            state.indexReg = callArgs.size
                        } else if (state.indexReg > 0) {
                            if (state.indexReg > 1 && blen == 0) {
                                stack.copyInto(stack, state.stackTop + 1, state.stackTop + 2, state.stackTop + 1 + state.indexReg)
                                sDbl.copyInto(sDbl, state.stackTop + 1, state.stackTop + 2, state.stackTop + 1 + state.indexReg)
                            } else if (state.indexReg > 1) {
                                val newBArgs = boundArgs!!.copyOfRange(1, boundArgs.size)
                                boundArgs = newBArgs
                                blen = newBArgs.size
                            } else {
                                boundArgs = arrayOfNulls(0)
                                blen = 0
                            }
                            state.indexReg--
                        }
                    } else {
                        break
                    }
                } else if (fun_ is LambdaConstructor || fun_ is LambdaFunction) {
                    break
                } else if (fun_ is BoundFunction) {
                    val bfun = fun_
                    fun_ = bfun.getTargetFunction()
                    funThisObj = bfun.getCallThis(cx, calleeScope)
                    val bArgs = bfun.getBoundArgs()
                    boundArgs = addBoundArgs(boundArgs, bArgs)
                    blen += bArgs.size
                    state.indexReg += bArgs.size
                } else if (fun_ is ScriptRuntime.NoSuchMethodShim) {
                    val nsmfun = fun_
                    val elements = getArgsArray(stack, sDbl, boundArgs, blen, state.stackTop + 1, state.indexReg)
                    fun_ = nsmfun.noSuchMethodMethod
                    boundArgs = arrayOf(nsmfun.methodName, cx.newArray(calleeScope, elements))
                    blen = 2
                    state.indexReg = 2
                } else if (fun_ == null) {
                    throw ScriptRuntime.notFunctionError(null, null)
                } else {
                    break
                }
            }
            if (fun_ is JSFunction && fun_.descriptor.code is InterpreterData<*>) {
                val ifun = fun_
                @Suppress("UNCHECKED_CAST")
                val idata = ifun.descriptor.code as InterpreterData<JSFunction>
                var callParentFrame: CallFrame? = frame
                if (op == Icode_TAIL_CALL) {
                    callParentFrame = frame.parentFrame
                    exitFrame(cx, frame, null)
                }
                val calleeFrame = initFrame(
                    cx, calleeScope, ifun.getFunctionThis(funThisObj), funHomeObj, stack, sDbl, boundArgs,
                    state.stackTop + 1, state.indexReg, ifun, idata, callParentFrame,
                )
                if (op != Icode_TAIL_CALL) {
                    frame.savedStackTop = state.stackTop
                    frame.savedCallOp = op
                }
                return NewState.StateContinueResult(calleeFrame, state.indexReg)
            }
            frame.savedCallOp = op
            frame.savedStackTop = state.stackTop
            stack[state.stackTop] = fun_!!.call(cx, calleeScope, funThisObj, getArgsArray(stack, sDbl, boundArgs, blen, state.stackTop + 1, state.indexReg))
            return null
        }

        private fun doNew(cx: Context, frame: CallFrame, state: InterpreterState, op: Int): NewState? {
            if (state.instructionCounting) cx.instructionCount += INVOCATION_COST
            state.stackTop -= state.indexReg
            var lhs = frame.stack[state.stackTop]
            if (lhs is JSFunction && lhs.constructorCode is InterpreterData<*>) {
                val f = lhs
                @Suppress("UNCHECKED_CAST")
                val idata = f.constructorCode as InterpreterData<JSFunction>
                if (cx.languageVersion >= Context.VERSION_ES6 && f.homeObject != null) {
                    throw ScriptRuntime.typeErrorById("msg.not.ctor", f.getFunctionName())
                }
                val newInstance = if (f.homeObject == null) f.createObject(cx, frame.scope!!) else null
                val calleeFrame = initFrame(
                    cx, frame.scope!!, newInstance, newInstance, frame.stack, frame.sDbl, null,
                    state.stackTop + 1, state.indexReg, f, idata, frame,
                )
                frame.stack[state.stackTop] = newInstance
                frame.savedStackTop = state.stackTop
                frame.savedCallOp = op
                return NewState.StateContinueResult(calleeFrame, state.indexReg)
            }
            if (lhs !is Constructable) {
                if (lhs === DBL_MRK) lhs = ScriptRuntime.wrapNumber(frame.sDbl[state.stackTop])
                throw ScriptRuntime.notFunctionError(lhs)
            }
            val outArgs = getArgsArray(frame.stack, frame.sDbl, state.stackTop + 1, state.indexReg)
            frame.stack[state.stackTop] = lhs.construct(cx, frame.scope!!, outArgs)
            return null
        }

        // ---- Generators --------------------------------------------------------------------

        private fun generatorCreate(cx: Context, frame: CallFrame) {
            // Back up over the GENERATOR opcode so the first resume lands on it again.
            frame.pc--
            val generatorFrame = captureFrameForGenerator(frame)
            generatorFrame.frozen = true
            val fn = generatorFrame.fnOrScript as JSFunction
            frame.result = if (cx.languageVersion >= Context.VERSION_ES6) {
                ES6Generator(frame.scope!!, fn, generatorFrame)
            } else {
                NativeGenerator(frame.scope!!, fn, generatorFrame)
            }
        }

        private fun captureFrameForGenerator(frame: CallFrame): CallFrame {
            frame.frozen = true
            val result = frame.captureForGenerator()
            frame.frozen = false
            return result
        }

        private fun freezeGenerator(cx: Context, frame: CallFrame, state: InterpreterState, generatorState: GeneratorState, yieldStar: Boolean): Any? {
            if (generatorState.operation == NativeGenerator.GENERATOR_CLOSE) throw ScriptRuntime.typeErrorById("msg.yield.closing")
            frame.frozen = true
            frame.result = frame.stack[state.stackTop]
            frame.resultDbl = frame.sDbl[state.stackTop]
            frame.savedStackTop = state.stackTop
            // Back up so the resume lands on the yield again.
            frame.pc--
            ScriptRuntime.exitActivationFunction(cx)
            val result = if (frame.result !== DBL_MRK) frame.result else ScriptRuntime.wrapNumber(frame.resultDbl)
            return if (yieldStar) ES6Generator.YieldStarResult(result) else result
        }

        private fun thawGenerator(frame: CallFrame, state: InterpreterState, generatorState: GeneratorState, op: Int): Any? {
            frame.frozen = false
            val sourceLine = getIndex(frame.idata.itsICode, frame.pc)
            frame.pc += 2
            if (generatorState.operation == NativeGenerator.GENERATOR_THROW) {
                return JavaScriptException(generatorState.value, frame.fnOrScript.descriptor!!.sourceName, sourceLine)
            }
            if (generatorState.operation == NativeGenerator.GENERATOR_CLOSE) return generatorState.value
            if (generatorState.operation != NativeGenerator.GENERATOR_SEND) throw Kit.codeBug()
            if (op == Token.YIELD || op == Icode_YIELD_STAR) frame.stack[state.stackTop] = generatorState.value
            return Scriptable.NOT_FOUND
        }

        // ---- Frames --------------------------------------------------------------------------

        private fun addBoundArgs(boundArgs: Array<Any?>?, newArgs: Array<Any?>): Array<Any?>? {
            if (newArgs.isEmpty()) return boundArgs
            if (boundArgs == null) return newArgs
            val result = newArgs.copyOf(boundArgs.size + newArgs.size)
            boundArgs.copyInto(result, newArgs.size)
            return result
        }

        /** Moves the frame to the handler at [indexReg] in its exception table. */
        private fun processThrowable(cx: Context, throwable: Any?, frameIn: CallFrame, indexReg: Int, instructionCounting: Boolean): CallFrame {
            var frame = frameIn
            if (indexReg < 0) throw Kit.codeBug()
            if (frame.frozen) frame = frame.cloneFrozen()
            val table = frame.idata.itsExceptionTable!!
            frame.pc = table[indexReg + EXCEPTION_HANDLER_SLOT]
            if (instructionCounting) frame.pcPrevBranch = frame.pc
            frame.savedStackTop = frame.emptyStackTop
            val localShift = frame.idata.itsMaxVars
            val scopeLocal = localShift + table[indexReg + EXCEPTION_SCOPE_SLOT]
            val exLocal = localShift + table[indexReg + EXCEPTION_LOCAL_SLOT]
            frame.scope = frame.stack[scopeLocal] as Scriptable
            frame.stack[exLocal] = throwable
            frame.throwable = null
            return frame
        }

        private fun getApplyThis(cx: Context, stack: Array<Any?>, sDbl: DoubleArray, boundArgs: Array<Any?>?, thisIdx: Int, indexReg: Int, target: Function): Scriptable? {
            val obj: Any?
            if (indexReg != 0) {
                if (boundArgs != null && boundArgs.isNotEmpty()) {
                    obj = boundArgs[0]
                } else {
                    var o = stack[thisIdx]
                    if (o === DBL_MRK) o = ScriptRuntime.wrapNumber(sDbl[thisIdx])
                    obj = o
                }
            } else {
                obj = null
            }
            return ScriptRuntime.getApplyOrCallThis(cx, target.declarationScope!!, obj, indexReg, target)
        }

        private fun <T : ScriptOrFn<T>> initFrame(
            cx: Context, callerScope: Scriptable, thisObj: Scriptable?, homeObj: Scriptable?, args: Array<Any?>, argsDbl: DoubleArray?,
            boundArgs: Array<Any?>?, argShift: Int, argCount: Int, fnOrScript: T, code: InterpreterData<T>, parentFrame: CallFrame?,
        ): CallFrame {
            val frame = CallFrame(cx, thisObj, fnOrScript, code, parentFrame, if (parentFrame == null) cx.lastInterpreterFrame as CallFrame? else parentFrame.previousInterpreterFrame)
            frame.initializeArgs(cx, callerScope, args, argsDbl, boundArgs, argShift, argCount, homeObj)
            enterFrame(cx, frame, args, false)
            return frame
        }

        private fun enterFrame(cx: Context, frame: CallFrame, args: Array<Any?>, continuationRestart: Boolean) {
            if (frame.fnOrScript.descriptor!!.requiresActivationFrame) {
                val scope = frame.scope ?: throw Kit.codeBug()
                ScriptRuntime.enterActivationFunction(cx, scope)
            }
        }

        private fun exitFrame(cx: Context, frame: CallFrame, throwable: Any?) {
            if (frame.fnOrScript.descriptor!!.requiresActivationFrame) ScriptRuntime.exitActivationFunction(cx)
        }

        private fun setCallResult(frame: CallFrame, callResult: Any?, callResultDbl: Double) {
            if (frame.savedCallOp == Token.CALL || frame.savedCallOp == Icode_CALL_ON_SUPER) {
                frame.stack[frame.savedStackTop] = callResult
                frame.sDbl[frame.savedStackTop] = callResultDbl
            } else if (frame.savedCallOp == Token.NEW) {
                if (callResult is Scriptable) frame.stack[frame.savedStackTop] = callResult
            } else {
                throw Kit.codeBug()
            }
            frame.savedCallOp = 0
        }

        private fun stackInt32(frame: CallFrame, i: Int): Int {
            val x = frame.stack[i]
            return if (x === DBL_MRK) ScriptRuntime.toInt32(frame.sDbl[i]) else ScriptRuntime.toInt32(x)
        }

        private fun stackDouble(frame: CallFrame, i: Int): Double {
            val x = frame.stack[i]
            return if (x !== DBL_MRK) ScriptRuntime.toNumber(x) else frame.sDbl[i]
        }

        private fun stackNumeric(frame: CallFrame, i: Int): Any {
            val x = frame.stack[i]
            return if (x !== DBL_MRK) ScriptRuntime.toNumeric(x) else frame.sDbl[i]
        }

        private fun stackBoolean(frame: CallFrame, i: Int): Boolean {
            val x = frame.stack[i]
            return when {
                x == true -> true
                x == false -> false
                x === DBL_MRK -> {
                    val d = frame.sDbl[i]
                    !d.isNaN() && d != 0.0
                }
                x == null || x === Undefined.instance -> false
                x is KBigInt -> !x.isZero()
                x is Number -> {
                    val d = x.toDouble()
                    !d.isNaN() && d != 0.0
                }
                else -> ScriptRuntime.toBoolean(x)
            }
        }

        private fun getArgsArray(stack: Array<Any?>, sDbl: DoubleArray?, shift: Int, count: Int): Array<Any?> =
            getArgsArray(stack, sDbl, null, 0, shift, count)

        private fun getArgsArray(stack: Array<Any?>, sDbl: DoubleArray?, bound: Array<Any?>?, bCount: Int, shiftIn: Int, count: Int): Array<Any?> {
            if (count == 0) return ScriptRuntime.emptyArgs
            val args = arrayOfNulls<Any?>(count)
            for (i in 0 until bCount) args[i] = bound!![i]
            var shift = shiftIn
            for (i in bCount until count) {
                var v = stack[shift]
                if (v === DBL_MRK) v = ScriptRuntime.wrapNumber(sDbl!![shift])
                args[i] = v
                ++shift
            }
            return args
        }

        private fun addInstructionCount(cx: Context, frame: CallFrame, extra: Int) {
            cx.instructionCount += frame.pc - frame.pcPrevBranch + extra
            if (cx.instructionCount > cx.instructionThreshold) {
                cx.observeInstructionCountInternal(cx.instructionCount)
                cx.instructionCount = 0
            }
        }

        private fun createClosure(cx: Context, frame: CallFrame, index: Int): JSFunction {
            val desc = frame.fnOrScript.descriptor!!.getFunction(index)
            val isArrow = desc.functionType == FunctionNode.ARROW_FUNCTION
            val homeObject = if (isArrow) frame.fnOrScript.homeObject else null
            return JSFunction(cx, frame.scope!!, desc, frame.thisObj, homeObject)
        }

        private fun createMethod(cx: Context, frame: CallFrame, index: Int, homeObject: Scriptable): JSFunction {
            val desc = frame.fnOrScript.descriptor!!.getFunction(index)
            return JSFunction(cx, frame.scope!!, desc, frame.thisObj, homeObject)
        }
    }
}
