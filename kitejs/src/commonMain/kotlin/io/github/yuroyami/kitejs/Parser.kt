/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.ast.IdeErrorReporter

/**
 * Phase 0 slice of the parser: construction and the error/warning plumbing the lexer
 * reports through. Phase 1 ports the actual parsing methods into this class.
 *
 * The upstream constructor trio collapses into one constructor with default arguments.
 */
class Parser(
    internal val compilerEnv: CompilerEnvirons = CompilerEnvirons(),
    private val errorReporter: ErrorReporter = compilerEnv.errorReporter,
) {

    companion object {
        /** Maximum number of allowed function or constructor arguments, to follow SpiderMonkey. */
        const val ARGC_LIMIT = 1 shl 16

        // TokenInformation flags: currentFlaggedToken stores them together with token type
        internal const val CLEAR_TI_MASK = 0xFFFF // mask to clear token information bits
        internal const val TI_AFTER_EOL = 1 shl 16 // first token of the source line
        internal const val TI_CHECK_LABEL = 1 shl 17 // indicates to check for label
    }

    private val errorCollector: IdeErrorReporter? = errorReporter as? IdeErrorReporter

    var sourceURI: String? = null

    internal var calledByCompileFunction = false // ugly - set directly by Context

    internal lateinit var currentPos: CurrentPositionReporter
    internal var currentToken: Int = 0
    private var syntaxErrorCount = 0

    internal var inUseStrictDirective = false

    /** Exception to unwind. */
    class ParserException internal constructor() : RuntimeException()

    // Add a strict warning on the last matched token.
    internal fun addStrictWarning(messageId: String, messageArg: String?) {
        addStrictWarning(messageId, messageArg, currentPos.position, currentPos.length)
    }

    internal fun addStrictWarning(messageId: String, messageArg: String?, position: Int, length: Int) {
        if (compilerEnv.strictMode) addWarning(messageId, messageArg, position, length)
    }

    internal fun addWarning(messageId: String, messageArg: String?) {
        addWarning(messageId, messageArg, currentPos.position, currentPos.length)
    }

    internal fun addWarning(messageId: String, position: Int, length: Int) {
        addWarning(messageId, null, position, length)
    }

    internal fun addWarning(messageId: String, messageArg: String?, position: Int, length: Int) {
        val message = lookupMessage(messageId, messageArg)
        if (compilerEnv.reportWarningAsError()) {
            addError(messageId, messageArg, position, length)
        } else if (errorCollector != null) {
            errorCollector.warning(message, sourceURI, position, length)
        } else {
            errorReporter.warning(
                message,
                sourceURI,
                currentPos.lineno,
                currentPos.line,
                currentPos.offset,
            )
        }
    }

    internal fun addError(messageId: String) {
        addError(messageId, currentPos.position, currentPos.length)
    }

    internal fun addError(messageId: String, position: Int, length: Int) {
        addError(messageId, null, position, length)
    }

    internal fun addError(messageId: String, messageArg: String?) {
        addError(messageId, messageArg, currentPos.position, currentPos.length)
    }

    internal fun addError(messageId: String, c: Int) {
        val messageArg = c.toChar().toString()
        addError(messageId, messageArg)
    }

    internal fun addError(messageId: String, messageArg: String?, position: Int, length: Int) {
        ++syntaxErrorCount
        val message = lookupMessage(messageId, messageArg)
        if (errorCollector != null) {
            errorCollector.error(message, sourceURI, position, length)
        } else {
            errorReporter.error(
                message,
                sourceURI,
                currentPos.lineno,
                currentPos.line,
                currentPos.offset,
            )
        }
    }

    internal fun lookupMessage(messageId: String): String = lookupMessage(messageId, null)

    internal fun lookupMessage(messageId: String, messageArg: String?): String =
        if (messageArg == null) ScriptRuntime.getMessageById(messageId)
        else ScriptRuntime.getMessageById(messageId, messageArg)

    internal fun reportError(messageId: String) {
        reportError(messageId, null)
    }

    internal fun reportError(messageId: String, messageArg: String?) {
        reportError(messageId, messageArg, currentPos.position, currentPos.length)
    }

    internal fun reportError(messageId: String, position: Int, length: Int) {
        reportError(messageId, null, position, length)
    }

    internal fun reportError(messageId: String, messageArg: String?, position: Int, length: Int) {
        addError(messageId, messageArg, position, length)

        if (!compilerEnv.recoverFromErrors) {
            throw ParserException()
        }
    }

    fun reportErrorsIfExists(baseLineno: Int) {
        if (syntaxErrorCount != 0) {
            var msg = syntaxErrorCount.toString()
            msg = lookupMessage("msg.got.syntax.errors", msg)
            if (!compilerEnv.ideMode) {
                throw errorReporter.runtimeError(msg, sourceURI, baseLineno, null, 0)
            }
        }
    }

    interface CurrentPositionReporter {
        val position: Int
        val length: Int
        val lineno: Int
        val line: String
        val offset: Int
    }
}
