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
public abstract class RhinoException : RuntimeException {

    /** How a script stack trace is rendered. */
    public enum class StackStyle { RHINO, MOZILLA, MOZILLA_LF, V8 }

    public companion object {
        /** How many frames a trace shows by default. */
        internal const val DEFAULT_STACK_LIMIT = 10

        public var stackStyle: StackStyle = StackStyle.RHINO

        public fun usesMozillaStackStyle(): Boolean = stackStyle == StackStyle.MOZILLA

        public fun useMozillaStackStyle(flag: Boolean) {
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

    public constructor() : super() {
        detailsMessage = null
        Interpreter().captureStackInfo(this)
    }

    public constructor(details: String) : super() {
        detailsMessage = details
        Interpreter().captureStackInfo(this)
    }

    public var sourceName: String? = null
        private set

    public var lineNumber: Int = 0
        private set

    public var lineSource: String? = null
        private set

    public var columnNumber: Int = 0
        private set

    public open fun details(): String = detailsMessage ?: ""

    /** Each of these may be set once, and only to a real value. */
    public fun initSourceName(sourceName: String) {
        check(this.sourceName == null) { "the source name is already set" }
        this.sourceName = sourceName
    }

    public fun initLineNumber(lineNumber: Int) {
        require(lineNumber > 0) { "$lineNumber" }
        check(this.lineNumber <= 0) { "the line number is already set" }
        this.lineNumber = lineNumber
    }

    public fun initLineSource(lineSource: String) {
        check(this.lineSource == null) { "the line source is already set" }
        this.lineSource = lineSource
    }

    public fun initColumnNumber(columnNumber: Int) {
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
    public val scriptStack: Array<ScriptStackElement> get() = getScriptStack(-1, null)

    public fun getScriptStack(limit: Int, hideFunction: String?): Array<ScriptStackElement> {
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

    public val scriptStackTrace: String get() = getScriptStackTrace(DEFAULT_STACK_LIMIT, null)

    public fun getScriptStackTrace(limit: Int, functionName: String?): String =
        formatStackTrace(getScriptStack(limit, functionName), details())

    final override val message: String
        get() {
            val details = details()
            val name = sourceName ?: return details
            if (lineNumber <= 0) return details
            return "$details ($name#$lineNumber)"
        }
}
