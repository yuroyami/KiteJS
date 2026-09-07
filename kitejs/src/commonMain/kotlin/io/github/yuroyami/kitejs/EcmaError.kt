/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The errors the engine itself raises, as ECMA edition 3 section 15.11.6 describes them:
 * `EvalError`, `RangeError`, `ReferenceError`, `SyntaxError`, `TypeError` and `URIError`.
 *
 * A problem inside the engine throws a plain runtime exception instead.
 */
public class EcmaError internal constructor(
    /** Which of the standard error names this is. */
    public val name: String,
    /** The text describing what went wrong. Not specified, so it can change between releases. */
    public val errorMessage: String,
    sourceName: String?,
    lineNumber: Int,
    lineSource: String?,
    columnNumber: Int,
) : RhinoException() {

    init {
        recordErrorOrigin(sourceName, lineNumber, lineSource, columnNumber)
    }

    override fun details(): String = "$name: $errorMessage"
}
