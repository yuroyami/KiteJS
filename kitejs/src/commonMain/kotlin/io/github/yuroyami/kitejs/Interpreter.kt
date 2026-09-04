/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.ast.ScriptNode

/**
 * The interpreter: the one [Evaluator] this engine has.
 *
 * Phase 3.5 shell. The call-frame machine and `interpret` land in phase 3.7; the code generator
 * that `compile` needs lands in phase 3.6.
 */
class Interpreter : Evaluator {

    /** What [compile] hands back and the create methods take. */
    internal class CompilationResult<T : ScriptOrFn<T>>(val descriptor: JSDescriptor<T>, val homeObject: Scriptable?)

    override fun compile(compilerEnv: CompilerEnvirons, tree: ScriptNode, rawSource: String, returnFunction: Boolean): Any {
        TODO("CodeGenerator lands in phase 3.6")
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
        if (cx == null || cx.lastInterpreterFrame == null) {
            ex.interpreterStackInfo = null
        } else {
            // TODO(P3.7): the frame's source line offset comes with the call-frame machine.
            ex.interpreterStackInfo = cx.lastInterpreterFrame
        }
    }

    override fun getSourcePositionFromStack(cx: Context, linep: IntArray): String? {
        TODO("the call-frame machine lands in phase 3.7")
    }

    override fun getPatchedStack(ex: RhinoException, nativeStackTrace: String): String = nativeStackTrace

    override fun getScriptStack(ex: RhinoException): List<String> = emptyList()

    override fun setEvalScriptFlag(script: Script) {
        throw UnsupportedOperationException()
    }

    companion object {
        internal fun <T : ScriptOrFn<T>> interpret(
            fnOrScript: T,
            idata: InterpreterData<T>,
            cx: Context,
            scope: Scriptable,
            thisObj: Scriptable?,
            args: Array<Any?>,
        ): Any? = TODO("the interpreter loop lands in phase 3.7")

        internal fun resumeGenerator(cx: Context, scope: Scriptable, operation: Int, state: Any?, value: Any?): Any? =
            TODO("the interpreter loop lands in phase 3.7")

        internal fun getLineNumbers(desc: JSDescriptor<*>): IntArray = TODO("needs the icode reader from phase 3.7")

        internal fun getScriptStackElements(ex: RhinoException): Array<Array<ScriptStackElement>> = emptyArray()
    }
}
