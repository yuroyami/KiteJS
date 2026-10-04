/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals

/** The old way in still opens Rhino, until 1.0 takes it away. */
@Suppress("DEPRECATION")
class LegacyTest {

    @Test
    fun theOldShortcutOpensRhino() {
        KiteJs { languageVersion = LanguageVersion.ES6 }.use { js ->
            assertEquals("Rhino", js.engine.name)
            assertEquals(3.0, js.evaluate("1 + 2").asDouble())
        }
    }
}
