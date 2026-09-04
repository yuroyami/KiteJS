/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * Phase 0 slice of Context: only the language-version surface exists so far.
 * Phase 3 ports the runtime Context (scopes, evaluation entry points, features).
 */
class Context internal constructor() {

    /**
     * The language version this context evaluates at.
     *
     * Phase 2 slice: the full Context, with its scopes, features and evaluation entry points,
     * arrives in phase 3.
     */
    var languageVersion: Int = VERSION_DEFAULT
        set(value) {
            checkLanguageVersion(value)
            field = value
        }


    /** Set when the script being run has a top-level "use strict". */
    internal var isTopLevelStrict: Boolean = false

    /**
     * Whether the code running right now is in strict mode.
     *
     * KMP: upstream also checks the current activation call, which is a `NativeCall`. That class
     * holds a `JSFunction` and lands with the descriptor layer, so only the top-level flag counts
     * for now.
     */
    fun isStrictMode(): Boolean = isTopLevelStrict

    /** True unless the script pinned a language version older than 1.3. */
    fun isVersionECMA1(): Boolean =
        languageVersion == VERSION_DEFAULT || languageVersion >= VERSION_1_3

    /**
     * Whether an optional engine behaviour is on. The answers are upstream's defaults, which is all
     * there is without a ContextFactory (D-6). Thread-safe objects are always off (D-3).
     */
    fun hasFeature(featureIndex: Int): Boolean = when (featureIndex) {
        // Kept only for scripts that pin an old language version.
        FEATURE_NON_ECMA_GET_YEAR ->
            languageVersion == VERSION_1_0 ||
                languageVersion == VERSION_1_1 ||
                languageVersion == VERSION_1_2
        FEATURE_MEMBER_EXPR_AS_FUNCTION_NAME -> false
        FEATURE_RESERVED_KEYWORD_AS_IDENTIFIER -> true
        FEATURE_TO_STRING_AS_SOURCE -> languageVersion == VERSION_1_2
        FEATURE_PARENT_PROTO_PROPERTIES -> true
        FEATURE_E4X -> languageVersion == VERSION_DEFAULT || languageVersion >= VERSION_1_6
        FEATURE_DYNAMIC_SCOPE -> false
        FEATURE_STRICT_VARS -> false
        FEATURE_STRICT_EVAL -> false
        FEATURE_LOCATION_INFORMATION_IN_ERROR -> false
        FEATURE_STRICT_MODE -> false
        FEATURE_WARNING_AS_ERROR -> false
        FEATURE_ENHANCED_JAVA_ACCESS -> false
        FEATURE_V8_EXTENSIONS -> true
        FEATURE_OLD_UNDEF_NULL_THIS -> languageVersion <= VERSION_1_7
        FEATURE_ENUMERATE_IDS_FIRST -> languageVersion >= VERSION_ES6
        FEATURE_THREAD_SAFE_OBJECTS -> false
        FEATURE_INTEGER_WITHOUT_DECIMAL_PLACE -> false
        FEATURE_LITTLE_ENDIAN -> false
        FEATURE_ENABLE_XML_SECURE_PARSING -> true
        FEATURE_ENABLE_JAVA_MAP_ACCESS -> false
        FEATURE_INTL_402 -> false
        else -> throw IllegalArgumentException("$featureIndex")
    }

    companion object {
        /** The unknown version. */
        const val VERSION_UNSUPPORTED = -1

        /** The default version. */
        const val VERSION_DEFAULT = 0

        const val VERSION_1_0 = 100
        const val VERSION_1_1 = 110
        const val VERSION_1_2 = 120
        const val VERSION_1_3 = 130
        const val VERSION_1_4 = 140
        const val VERSION_1_5 = 150
        const val VERSION_1_6 = 160
        const val VERSION_1_7 = 170
        const val VERSION_1_8 = 180

        /** ES6+ features, minus ones that may break backward compatibility. */
        const val VERSION_ES6 = 200

        /** The newest ECMAScript features implemented by the engine. */
        const val VERSION_ECMASCRIPT = 250

        fun isValidLanguageVersion(version: Int): Boolean = when (version) {
            VERSION_DEFAULT,
            VERSION_1_0, VERSION_1_1, VERSION_1_2, VERSION_1_3, VERSION_1_4,
            VERSION_1_5, VERSION_1_6, VERSION_1_7, VERSION_1_8,
            VERSION_ES6, VERSION_ECMASCRIPT -> true
            else -> false
        }

        /**
         * KMP: upstream keeps the current context in a `ThreadLocal`. The engine is single-thread
         * confined (D-3), so this becomes a plain slot. It stays null until the runtime Context
         * lands in phase 3, which is also what upstream falls back to when nothing entered a
         * context.
         */
        internal var currentContext: Context? = null

        fun getCurrentContext(): Context? = currentContext

        /**
         * Reports an error through the current context's error reporter, or throws when there is
         * no context.
         */
        fun reportError(
            message: String,
            sourceName: String?,
            lineno: Int,
            lineSource: String?,
            lineOffset: Int,
        ) {
            // KMP: the runtime Context arrives in phase 3, so there is never a current context to
            // route through yet and this always throws (D-18).
            throw EvaluatorException(message, sourceName, lineno, lineSource, lineOffset)
        }

        /**
         * Reports an error with no source position.
         *
         * KMP: upstream recovers a position by walking the interpreter stack. That machinery
         * arrives in phase 3, so the position is unknown until then (D-18).
         */
        fun reportError(message: String) {
            reportError(message, null, 0, null, 0)
        }

        // The feature flags. Upstream routes each one through a ContextFactory the embedder can
        // subclass; there is no factory here (D-6), so the defaults live in `hasFeature` below.
        const val FEATURE_NON_ECMA_GET_YEAR = 1
        const val FEATURE_MEMBER_EXPR_AS_FUNCTION_NAME = 2
        const val FEATURE_RESERVED_KEYWORD_AS_IDENTIFIER = 3
        const val FEATURE_TO_STRING_AS_SOURCE = 4
        const val FEATURE_PARENT_PROTO_PROPERTIES = 5
        const val FEATURE_E4X = 6
        const val FEATURE_DYNAMIC_SCOPE = 7
        const val FEATURE_STRICT_VARS = 8
        const val FEATURE_STRICT_EVAL = 9
        const val FEATURE_LOCATION_INFORMATION_IN_ERROR = 10
        const val FEATURE_STRICT_MODE = 11
        const val FEATURE_WARNING_AS_ERROR = 12
        const val FEATURE_ENHANCED_JAVA_ACCESS = 13
        const val FEATURE_V8_EXTENSIONS = 14
        const val FEATURE_OLD_UNDEF_NULL_THIS = 15
        const val FEATURE_ENUMERATE_IDS_FIRST = 16
        const val FEATURE_THREAD_SAFE_OBJECTS = 17
        const val FEATURE_INTEGER_WITHOUT_DECIMAL_PLACE = 18
        const val FEATURE_LITTLE_ENDIAN = 19
        const val FEATURE_ENABLE_XML_SECURE_PARSING = 20
        const val FEATURE_ENABLE_JAVA_MAP_ACCESS = 21
        const val FEATURE_INTL_402 = 22

        /** The current context, or a failure if nothing entered one. */
        fun getContext(): Context =
            currentContext ?: throw RuntimeException("No Context associated with current Thread")

        /** Whether the code running right now is in strict mode. False when there is no context. */
        fun isCurrentContextStrict(): Boolean = currentContext?.isStrictMode() ?: false

        /**
         * The source name and line the engine is currently at, read off the interpreter stack. The
         * line goes into `linep[0]`.
         *
         * KMP: there is no interpreter yet, so this reports nothing (D-18).
         */
        internal fun getSourcePositionFromStack(linep: IntArray): String? {
            linep[0] = 0
            return null
        }

        /** Converts any value to a string the way `String(value)` would. */
        fun toString(value: Any?): String = ScriptRuntime.toString(value)

        /**
         * Reports a runtime error. With no error reporter to route through this always throws,
         * which is also what upstream's default reporter does (D-18).
         */
        fun reportRuntimeError(
            message: String,
            sourceName: String?,
            lineno: Int,
            lineSource: String?,
            lineOffset: Int,
        ): EvaluatorException = throw EvaluatorException(message, sourceName, lineno, lineSource, lineOffset)

        fun reportRuntimeError(message: String): EvaluatorException {
            val linep = IntArray(1)
            val filename = getSourcePositionFromStack(linep)
            return reportRuntimeError(message, filename, linep[0], null, 0)
        }

        fun reportRuntimeErrorById(messageId: String, vararg args: Any?): EvaluatorException =
            reportRuntimeError(ScriptRuntime.getMessageById(messageId, *args))

        fun checkLanguageVersion(version: Int) {
            if (isValidLanguageVersion(version)) {
                return
            }
            throw IllegalArgumentException("Bad language version: $version")
        }
    }
}
