/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** One frame of a script stack trace. */
public class ScriptStackElement(public val fileName: String, public val functionName: String?, public val lineNumber: Int) {

    override fun toString(): String = buildString { renderMozillaStyle(this) }

    public fun renderJavaStyle(sb: StringBuilder) {
        sb.append("\tat ").append(fileName)
        if (lineNumber > -1) sb.append(':').append(lineNumber)
        if (functionName != null) sb.append(" (").append(functionName).append(')')
    }

    public fun renderMozillaStyle(sb: StringBuilder) {
        if (functionName != null) sb.append(functionName).append("()")
        sb.append('@').append(fileName)
        if (lineNumber > -1) sb.append(':').append(lineNumber)
    }

    public fun renderV8Style(sb: StringBuilder) {
        sb.append("    at ")
        if (functionName == null || functionName == "anonymous" || functionName == "undefined") {
            appendV8Location(sb)
        } else {
            sb.append(functionName).append(" (")
            appendV8Location(sb)
            sb.append(')')
        }
    }

    private fun appendV8Location(sb: StringBuilder) {
        sb.append(fileName).append(':')
        sb.append(if (lineNumber > -1) lineNumber else 0).append(":0")
    }
}
