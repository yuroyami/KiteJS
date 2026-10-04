/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

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
     * Keys upstream's code uses but its properties file never defines (D-49), and keys for errors
     * only the port raises, such as a refused definition (D-88), the class syntax and the early
     * errors upstream never checks (D-95), or async functions (D-97). Asking upstream for one of them raises a missing-resource
     * error rather than returning text, so there is nothing to compare against and the port writes
     * its own wording, after V8's where V8 has one.
     */
    private val addedByThePort = setOf(
        "msg.missing.argument", "msg.typed.array.abstract.ctor", "msg.func.decl.not.in.block", "msg.define.refused",
        // D-95: classes, private names, new.target and super.
        "msg.class.dup.ctor", "msg.class.extends", "msg.class.extends.proto", "msg.class.field.name",
        "msg.class.init.arguments", "msg.class.name", "msg.class.not.in.block", "msg.class.not.new",
        "msg.class.private.constructor", "msg.class.private.dup", "msg.class.special.ctor",
        "msg.class.static.prototype", "msg.derived.ctor.return", "msg.new.target", "msg.new.target.name",
        "msg.no.brace.after.class", "msg.no.brace.class", "msg.no.semi.class.field", "msg.private.alone",
        "msg.private.delete", "msg.private.host", "msg.private.in", "msg.private.method.write",
        "msg.private.no.getter", "msg.private.no.setter", "msg.private.read", "msg.private.super",
        "msg.private.twice", "msg.private.undeclared", "msg.private.write", "msg.super.alone",
        "msg.super.call", "msg.super.not.ctor", "msg.super.twice", "msg.this.before.super",
        "msg.uninitialized.binding",
        // D-95: early errors of plain functions that upstream lets through.
        "msg.getter.params", "msg.setter.params", "msg.keyword.escaped", "msg.rest.default",
        "msg.use.strict.non.simple",
        // D-97: async functions.
        "msg.async.decl.not.in.block", "msg.async.generator.unsupported", "msg.async.yield", "msg.await.params",
    )

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
