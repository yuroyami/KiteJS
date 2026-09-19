/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

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
                    KiteJs().use { js ->
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
        KiteJs().use { js ->
            js.evaluate("Symbol.for('shared')")
            assertEquals("shared", js.evaluate("Symbol.keyFor(Symbol.for('shared'))").asString())
        }
        KiteJs().use { js ->
            assertEquals(
                "true",
                js.evaluate("Symbol.keyFor(Symbol.for('shared')) === 'shared'").asString(),
            )
        }
    }

    /** A second engine on the same thread still fails, and says so. */
    @Test
    fun a_second_engine_on_one_thread_still_fails() {
        KiteJs().use {
            val failure = runCatching { KiteJs() }.exceptionOrNull()
            assertTrue(failure is JsEngineError, "expected a JsEngineError, got $failure")
            assertTrue(
                failure.message!!.contains("this thread already has an open engine"),
                "unexpected message: ${failure.message}",
            )
        }
    }
}
