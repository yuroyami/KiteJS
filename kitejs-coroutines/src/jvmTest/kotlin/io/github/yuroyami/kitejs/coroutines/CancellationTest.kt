/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.coroutines

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
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
        val js = asyncKiteJs()
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
        val js = asyncKiteJs()
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
        val js = asyncKiteJs()
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

    private suspend fun <T> withContextDefault(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(Dispatchers.Default) { block() }
}
