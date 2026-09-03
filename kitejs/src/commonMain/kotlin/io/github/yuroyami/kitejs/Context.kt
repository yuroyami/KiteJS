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

        fun checkLanguageVersion(version: Int) {
            if (isValidLanguageVersion(version)) {
                return
            }
            throw IllegalArgumentException("Bad language version: $version")
        }
    }
}
