/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * This is the default error reporter for JavaScript.
 *
 * KMP note: the forEval chaining variant needs ScriptRuntime.constructError and arrives
 * with the runtime in Phase 3.
 */
internal class DefaultErrorReporter private constructor() : ErrorReporter {

    private var forEval = false
    private var chainedReporter: ErrorReporter? = null

    companion object {
        val instance = DefaultErrorReporter()

        /** A reporter for `eval` code: a parse error becomes a script `SyntaxError`. */
        fun forEval(reporter: ErrorReporter?): ErrorReporter {
            val r = DefaultErrorReporter()
            r.forEval = true
            r.chainedReporter = reporter
            return r
        }
    }

    override fun warning(
        message: String, sourceName: String?, line: Int, lineSource: String?, lineOffset: Int,
    ) {
        chainedReporter?.warning(message, sourceName, line, lineSource, lineOffset)
    }

    override fun error(
        message: String, sourceName: String?, line: Int, lineSource: String?, lineOffset: Int,
    ) {
        if (forEval) {
            throw ScriptRuntime.constructError("SyntaxError", message, sourceName, line, lineSource, lineOffset)
        }
        val chained = chainedReporter
        if (chained != null) chained.error(message, sourceName, line, lineSource, lineOffset)
        else throw runtimeError(message, sourceName, line, lineSource, lineOffset)
    }

    override fun runtimeError(
        message: String, sourceName: String?, line: Int, lineSource: String?, lineOffset: Int,
    ): EvaluatorException =
        chainedReporter?.runtimeError(message, sourceName, line, lineSource, lineOffset)
            ?: EvaluatorException(message, sourceName, line, lineSource, lineOffset)
}
