/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The class of exceptions thrown by the JavaScript engine.
 *
 * Phase 0 shell: source-position plumbing and message composition only. Phase 3 ports
 * the full class (script stacks, interpreter integration).
 */
abstract class RhinoException : RuntimeException {

    /** How a script stack trace is rendered. */
    enum class StackStyle { RHINO, MOZILLA, MOZILLA_LF, V8 }

    companion object {
        /** How many frames a trace shows by default. */
        internal const val DEFAULT_STACK_LIMIT = 10

        var stackStyle: StackStyle = StackStyle.RHINO

        fun usesMozillaStackStyle(): Boolean = stackStyle == StackStyle.MOZILLA

        fun useMozillaStackStyle(flag: Boolean) {
            stackStyle = if (flag) StackStyle.MOZILLA else StackStyle.RHINO
        }

        internal fun formatStackTrace(stack: Array<ScriptStackElement>, message: String): String {
            val buffer = StringBuilder()
            if (stackStyle == StackStyle.V8 && message != "null") buffer.append(message).append('\n')
            for (elem in stack) {
                when (stackStyle) {
                    StackStyle.MOZILLA, StackStyle.MOZILLA_LF -> elem.renderMozillaStyle(buffer)
                    StackStyle.V8 -> elem.renderV8Style(buffer)
                    StackStyle.RHINO -> elem.renderJavaStyle(buffer)
                }
                buffer.append('\n')
            }
            return buffer.toString()
        }
    }

    private val detailsMessage: String?

    constructor() : super() {
        detailsMessage = null
        Interpreter().captureStackInfo(this)
    }

    constructor(details: String) : super() {
        detailsMessage = details
        Interpreter().captureStackInfo(this)
    }

    var sourceName: String? = null
        private set

    var lineNumber: Int = 0
        private set

    var lineSource: String? = null
        private set

    var columnNumber: Int = 0
        private set

    open fun details(): String = detailsMessage ?: ""

    /** Each of these may be set once, and only to a real value. */
    fun initSourceName(sourceName: String) {
        check(this.sourceName == null) { "the source name is already set" }
        this.sourceName = sourceName
    }

    fun initLineNumber(lineNumber: Int) {
        require(lineNumber > 0) { "$lineNumber" }
        check(this.lineNumber <= 0) { "the line number is already set" }
        this.lineNumber = lineNumber
    }

    fun initLineSource(lineSource: String) {
        check(this.lineSource == null) { "the line source is already set" }
        this.lineSource = lineSource
    }

    fun initColumnNumber(columnNumber: Int) {
        require(columnNumber > 0) { "$columnNumber" }
        check(this.columnNumber <= 0) { "the column number is already set" }
        this.columnNumber = columnNumber
    }

    /** Sets whichever of the four the caller actually knows. */
    internal fun recordErrorOrigin(
        sourceName: String?,
        lineNumber: Int,
        lineSource: String?,
        columnNumber: Int,
    ) {
        // Upstream keeps taking -1 to mean 0 for compatibility.
        val line = if (lineNumber == -1) 0 else lineNumber
        if (sourceName != null) initSourceName(sourceName)
        if (line != 0) initLineNumber(line)
        if (lineSource != null) initLineSource(lineSource)
        if (columnNumber != 0) initColumnNumber(columnNumber)
    }

    /** The interpreter frame that was live when this was thrown, if any. */
    internal var interpreterStackInfo: Any? = null
    internal var interpreterLineData: Int = 0

    /** The script frames at the time of the throw, innermost first. */
    val scriptStack: Array<ScriptStackElement> get() = getScriptStack(-1, null)

    fun getScriptStack(limit: Int, hideFunction: String?): Array<ScriptStackElement> {
        if (interpreterStackInfo == null) return emptyArray()
        val list = ArrayList<ScriptStackElement>()
        var count = 0
        var printStarted = hideFunction == null
        for (group in Interpreter.getScriptStackElements(this)) {
            for (elem in group) {
                if (!printStarted && hideFunction == elem.functionName) {
                    printStarted = true
                } else if (printStarted && (limit < 0 || count < limit)) {
                    list.add(elem)
                    count++
                }
            }
        }
        return list.toTypedArray()
    }

    val scriptStackTrace: String get() = getScriptStackTrace(DEFAULT_STACK_LIMIT, null)

    fun getScriptStackTrace(limit: Int, functionName: String?): String =
        formatStackTrace(getScriptStack(limit, functionName), details())

    final override val message: String
        get() {
            val details = details()
            val name = sourceName ?: return details
            if (lineNumber <= 0) return details
            return "$details ($name#$lineNumber)"
        }
}
