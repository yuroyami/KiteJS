/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.coroutines

import io.github.yuroyami.kitejs.api.function
import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cancelling a coroutine while a script is running. This needs the canceller and the engine to be
 * on different threads, so it lives here rather than in the common tests: Kotlin/JS has one thread,
 * and an endless script there leaves nothing else able to run. Real time, not the test scheduler.
 */
class CancellationTest {

    @Test
    fun cancellingTheCallerStopsARunawayScript() = runBlocking {
        val js = asyncKiteJs(Rhino)
        try {
            val runner = async(Dispatchers.Default) { js.evaluate("for (;;) {}") }
            // Let it get going, on the real clock.
            delay(300)
            assertTrue(runner.isActive, "the script finished on its own, so nothing was cancelled")

            runner.cancel()
            withTimeout(10_000) {
                runCatching { runner.await() }
            }
            assertTrue(runner.isCompleted, "the script never stopped")

            // The engine is still usable.
            assertEquals(3.0, js.evaluate("1 + 2").asDouble())
        } finally {
            js.close()
        }
    }

    @Test
    fun aTimeoutAroundTheCallStopsTheScriptToo() = runBlocking {
        val js = asyncKiteJs(Rhino)
        try {
            val answer = withTimeoutOrNull(1_000) {
                withContextDefault { js.evaluate("for (;;) {}") }
            }
            assertEquals(null, answer, "the timeout did not fire")
            assertEquals(7.0, js.evaluate("3 + 4").asDouble())
        } finally {
            js.close()
        }
    }

    @Test
    fun aCancelledCallerGetsCancellation() = runBlocking {
        val js = asyncKiteJs(Rhino)
        try {
            val runner = async(Dispatchers.Default) { js.evaluate("for (;;) {}") }
            delay(300)
            runner.cancel()
            val outcome = runCatching { withTimeout(10_000) { runner.await() } }
            assertTrue(
                outcome.exceptionOrNull() is CancellationException,
                "expected cancellation, got " + outcome.exceptionOrNull(),
            )
        } finally {
            js.close()
        }
    }

    /** A chain of reactions that queue one another never branches, and still hears the cancel. */
    @Test
    fun cancellingTheCallerStopsAnEndlessChainOfReactions() = runBlocking {
        val js = asyncKiteJs(Rhino)
        try {
            val runner = async(Dispatchers.Default) {
                js.evaluate("var n = 0; function spin() { n++; Promise.resolve().then(spin) } Promise.resolve().then(spin)")
            }
            delay(300)
            assertTrue(runner.isActive, "the chain finished on its own, so nothing was cancelled")

            runner.cancel()
            val outcome = runCatching { withTimeout(10_000) { runner.await() } }
            assertTrue(
                outcome.exceptionOrNull() is CancellationException,
                "expected cancellation, got " + outcome.exceptionOrNull(),
            )

            // The reactions still queued went with the script, so the chain does not resume.
            val ran = js.evaluate("n").asDouble()
            js.onEngine { it.runMicrotasks() }
            assertEquals(ran, js.evaluate("n").asDouble())
            assertEquals(3.0, js.evaluate("1 + 2").asDouble())
        } finally {
            js.close()
        }
    }

    /**
     * The wait used to outlive the engine: nothing settles the promise once the engine is gone,
     * and nothing ended the wait either (issue 18). Here the engine is on its own thread, which
     * close ends, and the waiter is on another.
     */
    @Test
    fun closingTheEngineEndsAWaitOnAnotherThread() = runBlocking {
        val js = asyncKiteJs(Rhino)
        val never = js.evaluate("new Promise(function () {})")
        val registered = CompletableDeferred<Unit>()
        // Reading `then` happens as the wait is registered, so this says when it is.
        val thenable = js.onEngine { engine ->
            engine.global.function("registered") { registered.complete(Unit) }
            engine.evaluate("({ get then() { registered(); return function () {} } })")
        }
        val onPromise = async(Dispatchers.Default) { runCatching { js.await(never) } }
        val onThenable = async(Dispatchers.Default) { runCatching { js.await(thenable) } }
        registered.await()
        // The promise's wait registers on the same thread; one more call queued behind it ensures it has.
        js.evaluate("0")
        js.close()
        for (waiter in listOf(onPromise, onThenable)) {
            val outcome = withTimeout(10_000) { waiter.await() }
            assertTrue(outcome.exceptionOrNull() is IllegalStateException, "expected the closed engine, got $outcome")
        }
    }

    private suspend fun <T> withContextDefault(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(Dispatchers.Default) { block() }
}
