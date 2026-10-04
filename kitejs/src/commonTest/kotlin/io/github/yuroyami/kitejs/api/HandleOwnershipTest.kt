/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A handle belongs to the engine that made it: it stops working when that engine closes, whatever
 * engine is open next, and it cannot be handed to another engine. Scalars are copies and keep
 * working. The cases on other threads are in the JVM tests.
 */
class HandleOwnershipTest {

    @Test
    fun handles_from_a_closed_engine_refuse_to_run() {
        val first = KiteJs()
        val f = first.evaluate("(function () { return 9 })").asFunction()
        val o = first.evaluate("({ n: 1 })").asObject()
        val a = first.evaluate("[1, 2]").asArray()
        val boxed = first.evaluate("({ valueOf: function () { return 4 } })")
        first.close()

        assertFailsWith<JsEngineError> { o["n"] }
        assertFailsWith<JsEngineError> { o["n"] = 2 }
        assertFailsWith<JsEngineError> { o.has("n") }
        assertFailsWith<JsEngineError> { o.keys }
        assertFailsWith<JsEngineError> { o.toMap() }
        assertFailsWith<JsEngineError> { f() }
        assertFailsWith<JsEngineError> { f.arity }
        assertFailsWith<JsEngineError> { a.size }
        assertFailsWith<JsEngineError> { boxed.asDouble() }
        assertFailsWith<JsEngineError> { o.function("late") { 0 } }

        // A second engine does not bring them back.
        KiteJs().use {
            assertFailsWith<JsEngineError> { f() }
            assertFailsWith<JsEngineError> { o["n"] = 2 }
        }
    }

    @Test
    fun a_refused_handle_runs_none_of_its_script() {
        val first = KiteJs()
        val o = first.evaluate("var hits = 0; ({ get n() { hits++; return 1 }, toString: function () { hits++; return 'x' } })").asObject()
        first.close()
        assertFailsWith<JsEngineError> { o["n"] }
        // toString never throws, and where the engine is gone it prints the class without calling anything.
        assertEquals("[object Object]", o.toString())
        assertEquals("[object Object]", o.value.toString())
    }

    @Test
    fun a_revoked_proxy_still_prints_where_its_engine_is_gone() {
        val first = KiteJs()
        val revoked = first.evaluate("var r = Proxy.revocable({}, {}); r.revoke(); r.proxy")
        first.close()
        assertEquals("[object Object]", revoked.toString())
    }

    @Test
    fun scalars_outlive_their_engine() {
        val first = KiteJs()
        val n = first.evaluate("6 * 7")
        val s = first.evaluate("'kite'")
        val b = first.evaluate("true")
        first.close()
        assertEquals(42.0, n.asDouble())
        assertEquals("kite", s.asString())
        assertEquals(true, b.asBoolean())
        assertEquals("42", n.toString())
    }

    @Test
    fun a_handle_cannot_move_to_another_engine() {
        val first = KiteJs()
        val o = first.evaluate("({ n: 1 })").asObject()
        first.close()
        KiteJs().use { second ->
            assertFailsWith<JsEngineError> { second.global["borrowed"] = o }
            assertFailsWith<JsEngineError> { second.valueOf(listOf(o)) }
            assertFailsWith<JsEngineError> { second.newArray(o.value) }
            assertEquals("undefined", second.evaluate("typeof borrowed").asString())
        }
    }

    @Test
    fun a_closed_engine_refuses_new_values_and_closes_once() {
        val js = KiteJs()
        js.close()
        assertFailsWith<JsEngineError> { js.newObject() }
        assertFailsWith<JsEngineError> { js.newArray(1, 2) }
        assertFailsWith<JsEngineError> { js.valueOf(mapOf("a" to 1)) }
        assertFailsWith<JsEngineError> { js.evaluate("1") }
        js.close()
        // The thread is free for the next engine.
        KiteJs().use { assertEquals(2.0, it.evaluate("1 + 1").asDouble()) }
    }

    @Test
    fun handles_work_while_their_engine_is_open() {
        KiteJs().use { js ->
            val o = js.evaluate("({ n: 1 })").asObject()
            o["n"] = 2
            js.global["kept"] = o
            assertEquals(2.0, js.evaluate("kept.n").asDouble())
            assertTrue(o.has("n"))
            assertEquals(listOf("n"), o.keys)
        }
    }
}
