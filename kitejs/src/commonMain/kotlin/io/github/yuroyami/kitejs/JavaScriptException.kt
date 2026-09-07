/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** A JavaScript `throw` that reached Kotlin: [value] is whatever the script threw. */
public class JavaScriptException(
    public val value: Any?,
    sourceName: String?,
    lineNumber: Int,
) : RhinoException() {

    private val detailsText: String

    init {
        recordErrorOrigin(sourceName, lineNumber, null, 0)
        if (value is NativeError) {
            // Upstream also chains a wrapped Java exception as the cause; that is LiveConnect
            // territory and is not ported.
            if (Context.getContext().hasFeature(Context.FEATURE_LOCATION_INFORMATION_IN_ERROR)) {
                if (!value.has("fileName", value)) {
                    value.put("fileName", value, sourceName)
                }
                if (!value.has("lineNumber", value)) {
                    value.put("lineNumber", value, lineNumber)
                }
                value.setStackProvider(this)
            }
        }
        // The details are worked out now rather than lazily, because building them calls back into
        // the runtime and that is not safe from wherever the message is finally printed.
        detailsText = buildDetails()
    }

    override fun details(): String = detailsText

    private fun buildDetails(): String {
        if (value == null) return "null"
        if (value is NativeError) return value.toString()
        return try {
            ScriptRuntime.toString(value)
        } catch (e: RuntimeException) {
            // ScriptRuntime.toString can itself throw.
            if (value is Scriptable) ScriptRuntime.defaultObjectToString(value) else value.toString()
        }
    }
}
