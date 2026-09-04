/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** The interpreter's compiled form of a script or function: the icode plus its constant pools. */
internal class InterpreterData<T : ScriptOrFn<T>>(
    val itsStringTable: Array<String?>,
    val itsDoubleTable: DoubleArray?,
    val itsBigIntTable: Array<KBigInt?>,
    val itsNestedFunctions: Array<InterpreterData<JSFunction>>?,
    val itsRegExpLiterals: Array<Any?>?,
    val itsTemplateLiterals: Array<Any?>?,
    val itsICode: ByteArray,
    val itsExceptionTable: IntArray?,
    val itsMaxVars: Int,
    val itsMaxLocals: Int,
    val itsMaxStack: Int,
    val itsMaxFrameArray: Int,
    val itsMaxCalleeArgs: Int,
    val literalIds: Array<Any?>?,
    val longJumps: Map<Int, Int>?,
    val firstLinePC: Int,
) : JSCode<T>() {

    private var icodeHashCode = 0

    fun icodeHashCode(): Int {
        var h = icodeHashCode
        if (h == 0) {
            h = itsICode.contentHashCode()
            icodeHashCode = h
        }
        return h
    }

    override fun toString(): String = "An idata thing."

    override fun execute(cx: Context, executableObject: T, newTarget: Any?, scope: Scriptable, thisObj: Any?, args: Array<Any?>): Any? =
        Interpreter.interpret(executableObject, this, cx, scope, thisObj as Scriptable?, args)

    override fun resume(cx: Context, executableObject: T, state: Any?, scope: Scriptable, operation: Int, value: Any?): Any? =
        Interpreter.resumeGenerator(cx, scope, operation, state, value)

    class Builder<T : ScriptOrFn<T>> : JSCode.Builder<T>() {
        var itsStringTable: Array<String?> = arrayOfNulls(INITIAL_STRINGTABLE_SIZE)
        var itsDoubleTable: DoubleArray? = null
        var itsBigIntTable: Array<KBigInt?> = arrayOfNulls(INITIAL_BIGINTTABLE_SIZE)
        var itsNestedFunctions: Array<InterpreterData<JSFunction>>? = null
        var itsRegExpLiterals: Array<Any?>? = null
        var itsTemplateLiterals: Array<Any?>? = null
        var itsICode: ByteArray = ByteArray(INITIAL_MAX_ICODE_LENGTH)
        var itsExceptionTable: IntArray? = null
        var itsMaxVars = 0
        var itsMaxLocals = 0
        var itsMaxStack = 0
        var itsMaxFrameArray = 0
        var itsMaxCalleeArgs = 0
        var literalIds: Array<Any?>? = null
        var longJumps: MutableMap<Int, Int>? = null
        var built: InterpreterData<T>? = null
        var firstLinePC = -1

        override fun build(): JSCode<T> {
            var b = built
            if (b == null) {
                b = InterpreterData(
                    itsStringTable, itsDoubleTable, itsBigIntTable, itsNestedFunctions, itsRegExpLiterals,
                    itsTemplateLiterals, itsICode, itsExceptionTable, itsMaxVars, itsMaxLocals, itsMaxStack,
                    itsMaxFrameArray, itsMaxCalleeArgs, literalIds, longJumps?.toMap(), firstLinePC,
                )
                built = b
            }
            return b
        }
    }

    companion object {
        const val INITIAL_MAX_ICODE_LENGTH = 1024
        const val INITIAL_STRINGTABLE_SIZE = 64
        const val INITIAL_NUMBERTABLE_SIZE = 64
        const val INITIAL_BIGINTTABLE_SIZE = 64
    }
}
