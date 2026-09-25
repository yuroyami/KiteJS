/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.coroutines

import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * An engine is held by the thread that opened it, so everything it does has to happen on that one
 * thread: opening, every call, and the release in `close()`. These need threads to see, so they
 * live here rather than in the common tests.
 */
class EngineThreadTest {

    @Test
    fun everyCallRunsOnTheThreadThatHoldsTheEngine() = runBlocking {
        val js = asyncKiteJs()
        try {
            // Callers on several threads at once. A pool dispatcher moves the calls between its
            // threads, and a string method is one of the calls that then fails.
            val threads = List(8) {
                async(Dispatchers.Default) {
                    List(50) {
                        js.onEngine { engine ->
                            engine.evaluate("'hi'.toUpperCase()")
                            Thread.currentThread()
                        }
                    }
                }
            }.awaitAll().flatten().toSet()
            assertEquals(1, threads.size, "the engine ran on $threads")
        } finally {
            js.close()
        }
    }

    @Test
    fun severalEnginesCanBeOpenAtOnce() = runBlocking {
        val engines = List(4) { asyncKiteJs() }
        try {
            engines.forEachIndexed { i, js -> js.evaluate("var id = $i") }
            engines.forEachIndexed { i, js -> assertEquals(i.toDouble(), js.evaluate("id").asDouble()) }
        } finally {
            engines.forEach { it.close() }
        }
    }

    @Test
    fun closingFromAnotherThreadFreesTheEnginesThread() = runBlocking {
        val thread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val first = asyncKiteJs(thread)
            first.evaluate("1")
            first.close()
            // A thread holds one engine at a time, so this opens only if the first was released there.
            val second = asyncKiteJs(thread)
            try {
                assertEquals(2.0, second.evaluate("1 + 1").asDouble())
            } finally {
                second.close()
            }
        } finally {
            thread.close()
        }
    }

    @Test
    fun closingEndsTheThreadTheEngineWasGiven() = runBlocking {
        val js = asyncKiteJs()
        val home = js.onEngine { Thread.currentThread() }
        js.close()
        home.join(10_000)
        assertFalse(home.isAlive, "the engine's thread outlived close()")
    }

    @Test
    fun anEngineThatFailsToOpenEndsItsThread() = runBlocking {
        var home: Thread? = null
        assertFailsWith<IllegalStateException> {
            asyncKiteJs {
                home = Thread.currentThread()
                error("the host gave up")
            }
        }
        val thread = assertNotNull(home, "configure never ran")
        thread.join(10_000)
        assertFalse(thread.isAlive, "the thread of an engine that never opened was left running")
    }
}
