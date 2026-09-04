/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.ast.ScriptNode

/**
 * What it takes to turn an IR tree into something runnable. Upstream has two implementations, an
 * interpreter and a bytecode compiler; this port only ever has the interpreter.
 */
interface Evaluator {

    /**
     * Compiles [tree] into a runnable form.
     *
     * The return value is opaque: pass it to [createFunctionObject] when [returnFunction] was true,
     * and to [createScriptObject] otherwise.
     */
    fun compile(
        compilerEnv: CompilerEnvirons,
        tree: ScriptNode,
        rawSource: String,
        returnFunction: Boolean,
    ): Any

    /** Wraps compiled [bytecode] as a callable function living in [scope]. */
    fun createFunctionObject(
        cx: Context,
        scope: Scriptable,
        bytecode: Any,
        staticSecurityDomain: Any?,
    ): Function

    /** Wraps compiled [bytecode] as a runnable script. */
    fun createScriptObject(bytecode: Any, staticSecurityDomain: Any?): Script

    /** Records where in the script [ex] was thrown. */
    fun captureStackInfo(ex: RhinoException)

    /**
     * Reads the current source position off the interpreter stack. The line number is written to
     * `linep[0]`; the return value is the source name.
     */
    fun getSourcePositionFromStack(cx: Context, linep: IntArray): String?

    /** Rewrites a platform stack trace so it names script sources and lines. */
    fun getPatchedStack(ex: RhinoException, nativeStackTrace: String): String

    /** The script-level stack for [ex], one entry per frame. */
    fun getScriptStack(ex: RhinoException): List<String>

    /** Marks [script] as having come from `eval` or the `Function` constructor. */
    fun setEvalScriptFlag(script: Script)
}
