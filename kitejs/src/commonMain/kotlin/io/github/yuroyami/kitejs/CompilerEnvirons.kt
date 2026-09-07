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
public class CompilerEnvirons {

    public var errorReporter: ErrorReporter = DefaultErrorReporter.instance

    public var languageVersion: Int = Context.VERSION_ES6
        set(value) {
            Context.checkLanguageVersion(value)
            field = value
        }

    public var generateDebugInfo: Boolean = true

    public var reservedKeywordAsIdentifier: Boolean = true

    /**
     * Extension to ECMA: if 'function &lt;name&gt;' is not followed by '(', assume
     * &lt;name&gt; starts a memberExpr.
     */
    public var allowMemberExprAsFunctionName: Boolean = false

    public var xmlAvailable: Boolean = true

    public var interpretedMode: Boolean = false

    /**
     * Whether source information is generated. Without it, evaluating "toString" on
     * JavaScript functions produces only "[native code]" for the body, which is not
     * fully ECMA conformant.
     */
    public var generatingSource: Boolean = true

    public var strictMode: Boolean = false

    public var warningAsError: Boolean = false

    public var generateObserverCount: Boolean = false

    public var recordingComments: Boolean = false

    public var recordingLocalJsDocComments: Boolean = false

    /**
     * Full error recovery: parse errors do not throw, and the parser attempts to build a
     * full syntax tree from the input. Useful for IDEs and other frontends.
     */
    public var recoverFromErrors: Boolean = false

    public var warnTrailingComma: Boolean = false

    /** "IDE" mode: slightly more expensive computations, such as helpful error bounds. */
    public var ideMode: Boolean = false

    /** Mozilla sources use the C preprocessor. */
    public var allowSharpComments: Boolean = false

    /** Allows usage of "super" everywhere, simulating that we are inside a method. */
    public var allowSuper: Boolean = false

    public var inEval: Boolean = false

    public var activationNames: Set<String>? = null

    // The field name keeps upstream's typo on purpose (1:1 mapping).
    private var homeObjecgt: Scriptable? = null

    public fun setHomeObject(homeObject: Scriptable?) {
        this.homeObjecgt = homeObject
    }

    public fun homeObject(): Scriptable? = homeObjecgt

    /** Copies the settings that matter for compilation out of [cx]. */
    public fun initFromContext(cx: Context) {
        errorReporter = cx.errorReporter
        languageVersion = cx.languageVersion
        generateDebugInfo = !cx.isGeneratingDebugChanged || cx.isGeneratingDebug
        reservedKeywordAsIdentifier = cx.hasFeature(Context.FEATURE_RESERVED_KEYWORD_AS_IDENTIFIER)
        allowMemberExprAsFunctionName = cx.hasFeature(Context.FEATURE_MEMBER_EXPR_AS_FUNCTION_NAME)
        strictMode = cx.hasFeature(Context.FEATURE_STRICT_MODE)
        warningAsError = cx.hasFeature(Context.FEATURE_WARNING_AS_ERROR)
        xmlAvailable = cx.hasFeature(Context.FEATURE_E4X)
        interpretedMode = cx.isInterpretedMode
        generatingSource = cx.isGeneratingSource
        activationNames = cx.activationNames
        generateObserverCount = cx.isGenerateObserverCount
    }

    public fun reportWarningAsError(): Boolean = warningAsError

    public companion object {
        /**
         * The preset an IDE wants: error recovery on, comments recorded, strict warnings, and
         * an [ErrorCollector] gathering the problems instead of throwing.
         */
        public fun ideEnvirons(): CompilerEnvirons = CompilerEnvirons().apply {
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
