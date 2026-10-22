/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An engine belongs to one thread, and two threads may hold one each at the same time. A host that
 * runs several documents gives each one its own thread, so one long script never blocks the others
 * from opening an engine.
 */
class EnginePerThreadTest {

    @Test
    fun two_threads_hold_their_own_engine_at_the_same_time() {
        val bothOpen = CountDownLatch(2)
        val results = arrayOfNulls<String>(2)
        val failures = arrayOfNulls<Throwable>(2)

        val workers = (0..1).map { index ->
            thread {
                try {
                    KiteJs(Rhino).use { js ->
                        js.evaluate("var mine = 'engine$index'")
                        bothOpen.countDown()
                        assertTrue(bothOpen.await(10, TimeUnit.SECONDS), "the other engine never opened")
                        results[index] = js.evaluate("mine").asString()
                    }
                } catch (e: Throwable) {
                    failures[index] = e
                    bothOpen.countDown()
                }
            }
        }
        workers.forEach { it.join(30_000) }

        assertNull(failures[0], "engine 0 failed: ${failures[0]?.message}")
        assertNull(failures[1], "engine 1 failed: ${failures[1]?.message}")
        assertEquals("engine0", results[0])
        assertEquals("engine1", results[1])
    }

    /** Each thread's registry is its own, so `Symbol.for` in one engine cannot answer in the other. */
    @Test
    fun the_symbol_registry_is_not_shared_between_engines() {
        KiteJs(Rhino).use { js ->
            js.evaluate("Symbol.for('shared')")
            assertEquals("shared", js.evaluate("Symbol.keyFor(Symbol.for('shared'))").asString())
        }
        KiteJs(Rhino).use { js ->
            assertEquals(
                "true",
                js.evaluate("Symbol.keyFor(Symbol.for('shared')) === 'shared'").asString(),
            )
        }
    }

    /** A second engine on the same thread still fails, and says so. */
    @Test
    fun a_second_engine_on_one_thread_still_fails() {
        KiteJs(Rhino).use {
            val failure = runCatching { KiteJs(Rhino) }.exceptionOrNull()
            assertTrue(failure is JsEngineError, "expected a JsEngineError, got $failure")
            assertTrue(
                failure.message!!.contains("this thread already has an open engine"),
                "unexpected message: ${failure.message}",
            )
        }
    }
}

/**
 * Only the thread that opened an engine can use or close it, and a refused call changes nothing:
 * the engine stays open for its own thread, and the engine the other thread holds is untouched.
 */
class EngineOwnerThreadTest {

    private fun onAnotherThread(body: () -> Unit) {
        var failure: Throwable? = null
        val worker = thread { try { body() } catch (e: Throwable) { failure = e } }
        worker.join(30_000)
        failure?.let { throw it }
    }

    @Test
    fun closing_from_another_thread_is_refused_and_leaves_both_engines_alone() {
        val first = KiteJs(Rhino)
        try {
            onAnotherThread {
                KiteJs(Rhino).use { other ->
                    assertTrue(runCatching { first.evaluate("1 + 2") }.exceptionOrNull() is JsEngineError)
                    assertTrue(runCatching { first.close() }.exceptionOrNull() is JsEngineError)
                    assertEquals("A", other.evaluate("'a'.toUpperCase()").asString())
                }
            }
            // Still open, and still this thread's.
            assertEquals(3.0, first.evaluate("1 + 2").asDouble())
        } finally {
            first.close()
        }
        // The thread is free again.
        KiteJs(Rhino).use { assertEquals(4.0, it.evaluate("2 + 2").asDouble()) }
    }

    @Test
    fun closing_from_a_thread_with_no_engine_is_refused_too() {
        val first = KiteJs(Rhino)
        try {
            onAnotherThread {
                assertTrue(runCatching { first.close() }.exceptionOrNull() is JsEngineError)
                // And that thread can still open its own.
                KiteJs(Rhino).use { assertEquals(1.0, it.evaluate("1").asDouble()) }
            }
            assertEquals(5.0, first.evaluate("2 + 3").asDouble())
        } finally {
            first.close()
        }
        first.close()
    }

    @Test
    fun handles_refuse_another_thread_before_any_script_runs() {
        KiteJs(Rhino).use { js ->
            val o = js.evaluate("var hits = 0; ({ get n() { hits++; return 1 } })").asObject()
            val f = js.evaluate("(function () { hits++; return 7 })").asFunction()
            onAnotherThread {
                assertTrue(runCatching { o["n"] }.exceptionOrNull() is JsEngineError)
                assertTrue(runCatching { f() }.exceptionOrNull() is JsEngineError)
                KiteJs(Rhino).use { other ->
                    assertTrue(runCatching { f() }.exceptionOrNull() is JsEngineError)
                    assertTrue(runCatching { other.global["x"] = o }.exceptionOrNull() is JsEngineError)
                }
                assertEquals("[object Object]", o.toString())
            }
            assertEquals(0.0, js.evaluate("hits").asDouble())
            assertEquals(1.0, o["n"].asDouble())
            assertEquals(7.0, f().asDouble())
        }
    }
}
