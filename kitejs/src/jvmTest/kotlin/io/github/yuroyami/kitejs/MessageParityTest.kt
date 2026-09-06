/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mozilla.javascript.ScriptRuntime as UpstreamScriptRuntime

/**
 * The ported message table is hand-copied from upstream's properties file (ledger D-4). This
 * checks every key we carry against the upstream bundle, with and without arguments, so a typo
 * or a mangled line continuation cannot slip through.
 */
class MessageParityTest {

    /**
     * Two keys upstream's code uses but its properties file never defines. Asking upstream for one
     * of them raises a missing-resource error rather than returning text, so there is nothing to
     * compare against and the port writes its own wording (D-49).
     */
    private val addedByThePort = setOf("msg.missing.argument", "msg.typed.array.abstract.ctor")

    private fun portedKeys(): List<String> {
        val field = Messages::class.java.getDeclaredField("en")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (field.get(Messages) as Map<String, String>).keys.filterNot { it in addedByThePort }.sorted()
    }

    @Test
    fun theAddedKeysAreStillMissingUpstream() {
        // If upstream ever defines these, the port should take their wording instead.
        for (key in addedByThePort) {
            val upstream = try { UpstreamScriptRuntime.getMessageById(key) } catch (e: Exception) { null }
            assertEquals(null, upstream, "upstream now defines $key, so the port should use its text")
        }
    }

    @Test
    fun everyPortedKeyMatchesUpstream() {
        val keys = portedKeys()
        assertTrue(keys.size > 100, "expected the parser keys to be present, got ${keys.size}")
        for (key in keys) {
            assertEquals(
                UpstreamScriptRuntime.getMessageById(key),
                ScriptRuntime.getMessageById(key),
                "message $key",
            )
        }
    }

    @Test
    fun argumentSubstitutionMatchesUpstream() {
        val keys = portedKeys()
        for (key in keys) {
            // Feed one and two arguments so any {0} or {1} slot gets exercised.
            assertEquals(
                UpstreamScriptRuntime.getMessageById(key, "ARG0"),
                ScriptRuntime.getMessageById(key, "ARG0"),
                "message $key with one argument",
            )
            assertEquals(
                UpstreamScriptRuntime.getMessageById(key, "ARG0", "ARG1"),
                ScriptRuntime.getMessageById(key, "ARG0", "ARG1"),
                "message $key with two arguments",
            )
        }
    }
}
