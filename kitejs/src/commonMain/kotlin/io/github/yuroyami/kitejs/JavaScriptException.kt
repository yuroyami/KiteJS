/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * A JavaScript `throw` reaching the host. It carries whatever value the script threw.
 */
class JavaScriptException(
    /** The value the script threw. */
    val value: Any?,
    sourceName: String?,
    lineNumber: Int,
) : RhinoException() {

    private val detailsText: String

    init {
        recordErrorOrigin(sourceName, lineNumber, null, 0)
        // TODO(P3.4): when the thrown value is a NativeError, upstream also pulls the Java cause
        // out of it and fills in fileName, lineNumber and stack. NativeError lands later.

        // The details are worked out now rather than lazily, because building them calls back into
        // the runtime and that is not safe from wherever the message is finally printed.
        detailsText = buildDetails()
    }

    override fun details(): String = detailsText

    private fun buildDetails(): String {
        if (value == null) return "null"
        return try {
            ScriptRuntime.toString(value)
        } catch (e: RuntimeException) {
            // ScriptRuntime.toString can itself throw.
            if (value is Scriptable) ScriptRuntime.defaultObjectToString(value) else value.toString()
        }
    }
}
