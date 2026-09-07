/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.EcmaError
import io.github.yuroyami.kitejs.EvaluatorException
import io.github.yuroyami.kitejs.JavaScriptException
import io.github.yuroyami.kitejs.RhinoException
import io.github.yuroyami.kitejs.ScriptRuntime
import io.github.yuroyami.kitejs.ScriptableObject

/** One frame of a script's own call stack. */
data class JsStackFrame(val functionName: String?, val fileName: String?, val lineNumber: Int) {
    override fun toString(): String =
        "at ${functionName ?: "<anonymous>"} (${fileName ?: "<unknown>"}:$lineNumber)"
}

/** Anything the facade throws. Every case is one of the three below. */
sealed class JsException(message: String, cause: Throwable?) : RuntimeException(message, cause)

/** A script threw. [value] is whatever it threw, which is usually an `Error` but need not be. */
class JsError internal constructor(
    val value: JsValue,
    val name: String,
    message: String,
    val scriptStack: List<JsStackFrame>,
    cause: Throwable?,
) : JsException(if (name.isEmpty()) message else "$name: $message", cause) {

    /** The message on its own, without the error's name in front. */
    val errorMessage: String = message

    companion object {
        /** Wraps a thrown or rejected value, reading `name` and `message` off it when it has them. */
        fun from(value: JsValue): JsError {
            val obj = value.asObjectOrNull()
            val name = obj?.get("name")?.takeIf { !it.isNullish }?.asString() ?: "Error"
            val message = obj?.get("message")?.takeIf { !it.isNullish }?.asString() ?: value.asString()
            return JsError(value, name, message, emptyList(), null)
        }
    }
}

/** The source did not parse. */
class JsSyntaxError internal constructor(
    message: String,
    val fileName: String?,
    val lineNumber: Int,
    val columnNumber: Int,
    val lineSource: String?,
    cause: Throwable?,
) : JsException(message, cause)

/** The engine itself could not go on: it is closed, misused, or out of budget. */
class JsEngineError internal constructor(message: String, cause: Throwable? = null) :
    JsException(message, cause)

/** A shorthand for the facade's own type complaints, which reach the caller as a [JsError]. */
internal fun jsTypeError(message: String): JsError =
    JsError(JsValue.undefined, "TypeError", message, emptyList(), null)

/** Turns whatever the engine threw into the facade's own shape. */
internal fun translate(e: Throwable): JsException = when (e) {
    is JsException -> e
    is JavaScriptException -> {
        val thrown = e.value
        val name = readString(thrown, "name") ?: ScriptRuntime.typeOf(thrown)
        val message = readString(thrown, "message") ?: ScriptRuntime.toString(thrown)
        JsError(JsValue(thrown), name, message, framesOf(e), e)
    }
    is EcmaError -> JsError(JsValue.undefined, e.name, e.errorMessage, framesOf(e), e)
    is EvaluatorException -> JsSyntaxError(
        e.details(), e.sourceName, e.lineNumber, e.columnNumber, e.lineSource, e,
    )
    is RhinoException -> JsEngineError(e.details(), e)
    else -> JsEngineError(e.message ?: e::class.simpleName ?: "engine failure", e)
}

private fun readString(thrown: Any?, name: String): String? {
    val obj = thrown as? ScriptableObject ?: return null
    val v = ScriptableObject.getProperty(obj, name)
    return if (v == null || v === io.github.yuroyami.kitejs.Scriptable.NOT_FOUND) null else ScriptRuntime.toString(v)
}

private fun framesOf(e: RhinoException): List<JsStackFrame> =
    e.scriptStack.map { JsStackFrame(it.functionName, it.fileName, it.lineNumber) }
