/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

/** One frame of a script's own call stack. */
public data class JsStackFrame(val functionName: String?, val fileName: String?, val lineNumber: Int) {
    override fun toString(): String =
        "at ${functionName ?: "<anonymous>"} (${fileName ?: "<unknown>"}:$lineNumber)"
}

/** Anything the API throws. Every case is one of the three below. */
public sealed class JsException(message: String, cause: Throwable?) : RuntimeException(message, cause)

/** A script threw. [value] is whatever it threw, which is usually an `Error` but need not be. */
public class JsError(
    public val value: JsValue,
    public val name: String,
    message: String,
    public val scriptStack: List<JsStackFrame>,
    cause: Throwable?,
) : JsException(if (name.isEmpty()) message else "$name: $message", cause) {

    /** The message on its own, without the error's name in front. */
    public val errorMessage: String = message

    public companion object {
        /**
         * Wraps a thrown or rejected value, reading `name` and `message` off it when it has them.
         * Any value can be thrown, so reading them can fail, from a getter that throws or a
         * revoked proxy; then the name is `Error` and the message is what the value is, without
         * running more script. [value] is kept as it was either way.
         */
        public fun from(value: JsValue): JsError {
            val obj = value.asObjectOrNull()
            val name = readOrNull { obj?.get("name")?.takeIf { !it.isNullish }?.asString() } ?: "Error"
            val message = readOrNull { obj?.get("message")?.takeIf { !it.isNullish }?.asString() ?: value.asString() }
                ?: inertDescription(value)
            return JsError(value, name, message, emptyList(), null)
        }

        /** What a script failure while reading turns into: nothing, so the caller falls back. */
        private inline fun readOrNull(read: () -> String?): String? = try {
            read()
        } catch (e: JsError) {
            null
        }

        private fun inertDescription(value: JsValue): String = when (val r = value.raw) {
            is JsSymbol -> r.toString()
            is JsObject -> r.inertText()
            else -> value.typeOf
        }
    }
}

/** The source did not parse. */
public class JsSyntaxError(
    message: String,
    public val fileName: String?,
    public val lineNumber: Int,
    public val columnNumber: Int,
    public val lineSource: String?,
    cause: Throwable?,
) : JsException(message, cause)

/** The engine itself could not go on: it is closed, misused, out of budget or out of memory. */
public class JsEngineError(message: String, cause: Throwable? = null) : JsException(message, cause)
