/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The class of exceptions thrown by the JavaScript engine for translation or
 * evaluation problems.
 */
open class EvaluatorException : RhinoException {

    constructor(detail: String) : super(detail)

    constructor(detail: String, sourceName: String?, lineNumber: Int) :
        this(detail, sourceName, lineNumber, null, 0)

    constructor(
        detail: String,
        sourceName: String?,
        lineNumber: Int,
        lineSource: String?,
        columnNumber: Int,
    ) : super(detail) {
        if (sourceName != null) initSourceName(sourceName)
        if (lineSource != null) initLineSource(lineSource)
        if (lineNumber > 0) initLineNumber(lineNumber)
        if (columnNumber > 0) initColumnNumber(columnNumber)
    }
}
