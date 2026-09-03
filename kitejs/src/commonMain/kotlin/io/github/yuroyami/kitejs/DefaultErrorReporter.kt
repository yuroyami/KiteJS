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

    companion object {
        val instance = DefaultErrorReporter()
    }

    override fun warning(
        message: String, sourceName: String?, line: Int, lineSource: String?, lineOffset: Int,
    ) {
        // Do nothing
    }

    override fun error(
        message: String, sourceName: String?, line: Int, lineSource: String?, lineOffset: Int,
    ) {
        throw runtimeError(message, sourceName, line, lineSource, lineOffset)
    }

    override fun runtimeError(
        message: String, sourceName: String?, line: Int, lineSource: String?, lineOffset: Int,
    ): EvaluatorException =
        EvaluatorException(message, sourceName, line, lineSource, lineOffset)
}
