/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.coroutines

import io.github.yuroyami.kitejs.api.KiteJs
import io.github.yuroyami.kitejs.rhino.Rhino
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * An engine is held by the thread that opened it, so everything it does has to happen on that one
 * thread: opening, every call, and the release in `close()`. These need threads to see, so they
 * live here rather than in the common tests.
 */
class EngineThreadTest {

    @Test
    fun everyCallRunsOnTheThreadThatHoldsTheEngine() = runBlocking {
        val js = asyncKiteJs(Rhino)
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
        val engines = List(4) { asyncKiteJs(Rhino) }
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
            val first = asyncKiteJs(Rhino, thread)
            first.evaluate("1")
            first.close()
            // A thread holds one engine at a time, so this opens only if the first was released there.
            val second = asyncKiteJs(Rhino, thread)
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
        val js = asyncKiteJs(Rhino)
        val home = js.onEngine { Thread.currentThread() }
        js.close()
        home.join(10_000)
        assertFalse(home.isAlive, "the engine's thread outlived close()")
    }

    @Test
    fun anEngineThatFailsToOpenEndsItsThread() = runBlocking {
        var home: Thread? = null
        assertFailsWith<IllegalStateException> {
            asyncKiteJs(Rhino) {
                home = Thread.currentThread()
                error("the host gave up")
            }
        }
        val thread = assertNotNull(home, "configure never ran")
        thread.join(10_000)
        assertFalse(thread.isAlive, "the thread of an engine that never opened was left running")
    }

    /**
     * A cancellation that lands after the engine was built, as withContext hands it back, used to
     * lose the engine with its context still entered on the thread, so a dispatcher of the
     * caller's own could never open another (issue 20). Cancelling from inside `configure` puts the
     * cancellation exactly there.
     */
    @Test
    fun aCreationCancelledAfterTheEngineWasBuiltReleasesIt() = runBlocking {
        val thread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            lateinit var creation: Deferred<AsyncKiteJs>
            creation = CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.LAZY) {
                asyncKiteJs(Rhino, thread) { creation.cancel() }
            }
            creation.start()
            assertFailsWith<CancellationException> { creation.await() }
            // The thread holds one engine at a time, so this opens only if the first was released.
            assertEquals(2.0, withContext(thread) { KiteJs(Rhino).use { it.evaluate("1 + 1").asDouble() } })
        } finally {
            thread.close()
        }
    }

    @Test
    fun aCreationCancelledBeforeItRanLeavesTheDispatcherFree() = runBlocking {
        val thread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val creation = CoroutineScope(Dispatchers.Default).async {
                coroutineContext.cancel()
                asyncKiteJs(Rhino, thread)
            }
            assertFailsWith<CancellationException> { creation.await() }
            val js = asyncKiteJs(Rhino, thread)
            try {
                assertEquals(2.0, js.evaluate("1 + 1").asDouble())
            } finally {
                js.close()
            }
        } finally {
            thread.close()
        }
    }

    @Test
    fun aCreationCancelledAfterTheEngineWasBuiltEndsTheThreadItWasGiven() = runBlocking {
        var home: Thread? = null
        lateinit var creation: Deferred<AsyncKiteJs>
        creation = CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.LAZY) {
            asyncKiteJs(Rhino) {
                home = Thread.currentThread()
                creation.cancel()
            }
        }
        creation.start()
        assertFailsWith<CancellationException> { creation.await() }
        val thread = assertNotNull(home, "configure never ran")
        thread.join(10_000)
        assertFalse(thread.isAlive, "the thread of an engine nobody received was left running")
    }
}
