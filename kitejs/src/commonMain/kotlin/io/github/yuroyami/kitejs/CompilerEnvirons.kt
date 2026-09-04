/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.ast.ErrorCollector

/**
 * Compiler configuration shared by the lexer, parser and code generator.
 *
 * Ported subset notes: initFromContext arrives with Context in Phase 3. The
 * security-controller fields and the deprecated optimizationLevel pair are out of the
 * port's scope: there is no bytecode compiler, so interpretedMode is the only switch.
 */
class CompilerEnvirons {

    var errorReporter: ErrorReporter = DefaultErrorReporter.instance

    var languageVersion: Int = Context.VERSION_ES6
        set(value) {
            Context.checkLanguageVersion(value)
            field = value
        }

    var generateDebugInfo: Boolean = true

    var reservedKeywordAsIdentifier: Boolean = true

    /**
     * Extension to ECMA: if 'function &lt;name&gt;' is not followed by '(', assume
     * &lt;name&gt; starts a memberExpr.
     */
    var allowMemberExprAsFunctionName: Boolean = false

    var xmlAvailable: Boolean = true

    var interpretedMode: Boolean = false

    /**
     * Whether source information is generated. Without it, evaluating "toString" on
     * JavaScript functions produces only "[native code]" for the body, which is not
     * fully ECMA conformant.
     */
    var generatingSource: Boolean = true

    var strictMode: Boolean = false

    var warningAsError: Boolean = false

    var generateObserverCount: Boolean = false

    var recordingComments: Boolean = false

    var recordingLocalJsDocComments: Boolean = false

    /**
     * Full error recovery: parse errors do not throw, and the parser attempts to build a
     * full syntax tree from the input. Useful for IDEs and other frontends.
     */
    var recoverFromErrors: Boolean = false

    var warnTrailingComma: Boolean = false

    /** "IDE" mode: slightly more expensive computations, such as helpful error bounds. */
    var ideMode: Boolean = false

    /** Mozilla sources use the C preprocessor. */
    var allowSharpComments: Boolean = false

    /** Allows usage of "super" everywhere, simulating that we are inside a method. */
    var allowSuper: Boolean = false

    var inEval: Boolean = false

    var activationNames: Set<String>? = null

    // KMP: typed Scriptable once the object model lands in Phase 3.
    // The field name keeps upstream's typo on purpose (1:1 mapping).
    private var homeObjecgt: Any? = null

    fun setHomeObject(homeObject: Any?) {
        this.homeObjecgt = homeObject
    }

    fun homeObject(): Any? = homeObjecgt

    fun reportWarningAsError(): Boolean = warningAsError

    companion object {
        /**
         * The preset an IDE wants: error recovery on, comments recorded, strict warnings, and
         * an [ErrorCollector] gathering the problems instead of throwing.
         */
        fun ideEnvirons(): CompilerEnvirons = CompilerEnvirons().apply {
            recoverFromErrors = true
            recordingComments = true
            strictMode = true
            warnTrailingComma = true
            languageVersion = Context.VERSION_1_7
            reservedKeywordAsIdentifier = true
            ideMode = true
            errorReporter = ErrorCollector()
        }
    }
}
