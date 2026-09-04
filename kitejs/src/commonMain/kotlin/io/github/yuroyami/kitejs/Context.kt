/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * Phase 0 slice of Context: only the language-version surface exists so far.
 * Phase 3 ports the runtime Context (scopes, evaluation entry points, features).
 */
class Context internal constructor() {

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

        fun checkLanguageVersion(version: Int) {
            if (isValidLanguageVersion(version)) {
                return
            }
            throw IllegalArgumentException("Bad language version: $version")
        }
    }
}
