/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * This interface defines a protocol for the reporting of errors during JavaScript
 * translation or execution.
 */
interface ErrorReporter {

    /**
     * Report a warning. The implementing class may choose to ignore the warning.
     *
     * @param message a String describing the warning
     * @param sourceName the JavaScript source where the warning occurred, typically a filename or URL
     * @param line the line number associated with the warning
     * @param lineSource the text of the line (may be null)
     * @param lineOffset the offset into lineSource where the problem was detected
     */
    fun warning(message: String, sourceName: String?, line: Int, lineSource: String?, lineOffset: Int)

    /**
     * Report an error. The implementing class is free to throw an exception.
     */
    fun error(message: String, sourceName: String?, line: Int, lineSource: String?, lineOffset: Int)

    /**
     * Creates an EvaluatorException that may be thrown. runtimeErrors, unlike errors,
     * will always terminate the current script.
     */
    fun runtimeError(
        message: String, sourceName: String?, line: Int, lineSource: String?, lineOffset: Int,
    ): EvaluatorException
}
