/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.EvaluatorException

/**
 * An error reporter that records problems instead of throwing, for IDE-mode parsing. Only the
 * offset-and-length overloads are supported; the line-based ones throw.
 */
public class ErrorCollector : IdeErrorReporter {

    /** Every error and warning recorded so far, in the order they arrived. */
    public val errors: MutableList<ParseProblem> = mutableListOf()

    override fun warning(
        message: String,
        sourceName: String?,
        line: Int,
        lineSource: String?,
        lineOffset: Int,
    ): Unit = throw UnsupportedOperationException()

    override fun warning(message: String, sourceName: String?, offset: Int, length: Int) {
        errors.add(ParseProblem(ParseProblem.Type.Warning, message, sourceName, offset, length))
    }

    override fun error(
        message: String,
        sourceName: String?,
        line: Int,
        lineSource: String?,
        lineOffset: Int,
    ): Unit = throw UnsupportedOperationException()

    override fun error(message: String, sourceName: String?, offset: Int, length: Int) {
        errors.add(ParseProblem(ParseProblem.Type.Error, message, sourceName, offset, length))
    }

    override fun runtimeError(
        message: String,
        sourceName: String?,
        line: Int,
        lineSource: String?,
        lineOffset: Int,
    ): EvaluatorException = throw UnsupportedOperationException()

    override fun toString(): String {
        val sb = StringBuilder(errors.size * 100)
        for (pp in errors) {
            sb.append(pp.toString()).append("\n")
        }
        return sb.toString()
    }
}
