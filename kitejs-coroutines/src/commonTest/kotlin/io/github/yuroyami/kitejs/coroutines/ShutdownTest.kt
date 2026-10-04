/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.coroutines

import io.github.yuroyami.kitejs.api.JsEngineError
import io.github.yuroyami.kitejs.api.JsError
import io.github.yuroyami.kitejs.api.JsValue
import io.github.yuroyami.kitejs.api.newPromise
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * What happens to the promises in flight when the engine closes or a scope is cancelled: every
 * wait ends and every promise handed to the script settles once (issues 18 and 19).
 *
 * The engine runs on the test's own scheduler here, which is one thread, so each step happens
 * exactly when `runCurrent` says it does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShutdownTest {

    private suspend fun TestScope.engine(budget: Int = 0): AsyncKiteJs =
        asyncKiteJs(StandardTestDispatcher(testScheduler)) { instructionBudget = budget }

    /**
     * Closes [js] and lets the release run, even after a failed assertion, so the engine does not
     * stay open on the test's thread for the next test.
     */
    private fun TestScope.release(js: AsyncKiteJs) {
        js.close()
        testScheduler.runCurrent()
    }

    /** What a promise from `call` did, as the script sees it. */
    private fun outcomeOf(call: String) =
        "$call.then(function (v) { return 'fulfilled:' + v }, function (e) { return 'rejected:' + e })"

    @Test
    fun closingEndsEveryAwaitStillWaiting() = runTest {
        val js = engine()
        try {
            val never = js.evaluate("new Promise(function () {})")
            val later = js.onEngine { it.newPromise() }
            val first = js.onEngine { it.newPromise() }
            // Each wait ends in a failure, which would cancel the test if it escaped its child.
            val waiters = listOf(
                async { runCatching { js.await(never) } },
                async { runCatching { js.await(never) } },
                async { runCatching { js.await(JsValue.of(later.promise)) } },
            )
            val settledFirst = async { js.await(JsValue.of(first.promise)) }
            runCurrent()
            js.onEngine { first.resolve("first"); it.runMicrotasks() }
            assertEquals("first", settledFirst.await().asString())
            assertTrue(waiters.all { it.isActive }, "an await ended before the engine closed")

            js.close()
            runCurrent()
            for (waiter in waiters) {
                val e = waiter.await().exceptionOrNull()
                assertTrue(e is IllegalStateException, "expected the closed engine, got $e")
                assertContains(e.message.orEmpty(), "closed before the promise settled")
            }
            // Nothing can settle it now, and trying says why instead of settling it late.
            assertFailsWith<JsEngineError> { later.resolve("too late") }
            assertFailsWith<IllegalStateException> { js.await(never) }
        } finally {
            release(js)
        }
    }

    @Test
    fun anAwaitThatGivesUpLeavesNothingBehind() = runTest {
        val js = engine()
        try {
            val never = js.evaluate("new Promise(function () {})")
            val waiters = (1..40).map { async { js.await(never) } }
            runCurrent()
            waiters.forEach { it.cancel() }
            runCurrent()
            assertTrue(waiters.all { it.isCancelled })
            assertEquals(2.0, js.evaluate("1 + 1").asDouble())
        } finally {
            release(js)
        }
    }

    @Test
    fun aScopeCancelledBeforeTheWorkStartsRejects() = runTest {
        val js = engine()
        try {
            val scope = CoroutineScope(Job().apply { cancel() } + StandardTestDispatcher(testScheduler))
            var ran = false
            js.suspendFunction(js.onEngine { it.global }, "work", scope = scope) { ran = true; "done" }
            assertTrue(js.evaluateAwaiting(outcomeOf("work()")).asString().startsWith("rejected:"))
            assertEquals(false, ran)

            val deferred = CompletableDeferred("value")
            val promise = js.deferredToPromise(deferred, scope)
            assertFailsWith<JsError> { js.await(JsValue.of(promise)) }
        } finally {
            release(js)
        }
    }

    @Test
    fun aScopeCancelledWhileTheWorkRunsRejects() = runTest {
        val js = engine()
        try {
            val owner = Job()
            val scope = CoroutineScope(owner + StandardTestDispatcher(testScheduler))
            val started = CompletableDeferred<Unit>()
            js.suspendFunction(js.onEngine { it.global }, "work", scope = scope) {
                started.complete(Unit)
                awaitCancellation()
            }
            val outcome = async { js.evaluateAwaiting(outcomeOf("work()")).asString() }
            started.await()
            owner.cancel()
            assertTrue(outcome.await().startsWith("rejected:"), "the script never heard of the cancellation")
        } finally {
            release(js)
        }
    }

    @Test
    fun aCancelledDeferredRejectsAndSoDoesACancelledScope() = runTest {
        val js = engine()
        try {
            val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
            val cancelled = CompletableDeferred<String>()
            val first = js.deferredToPromise(cancelled, scope)
            cancelled.cancel()
            assertFailsWith<JsError> { js.await(JsValue.of(first)) }

            val owner = Job()
            val other = CoroutineScope(owner + StandardTestDispatcher(testScheduler))
            val pending = CompletableDeferred<String>()
            val second = js.deferredToPromise(pending, other)
            val waiter = async { runCatching { js.await(JsValue.of(second)) } }
            runCurrent()
            owner.cancel()
            assertTrue(waiter.await().exceptionOrNull() is JsError)
            assertTrue(pending.isActive, "the Deferred belongs to the caller and is left alone")
        } finally {
            release(js)
        }
    }

    @Test
    fun nothingIsSettledOnceTheEngineIsClosed() = runTest {
        val js = engine()
        try {
            val failures = mutableListOf<Throwable>()
            val scope = CoroutineScope(
                Job() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> failures += e },
            )
            val gate = CompletableDeferred<String>()
            var finished = false
            js.suspendFunction(js.onEngine { it.global }, "work", scope = scope) {
                gate.await().also { finished = true }
            }
            js.evaluate("work().then(function () {})")
            runCurrent()
            js.close()
            runCurrent()
            gate.complete("after close")
            runCurrent()
            assertTrue(finished, "the work belongs to its scope and carries on")
            assertEquals(emptyList(), failures)
        } finally {
            release(js)
        }
    }

    @Test
    fun aFailureWhileTheReactionsRunGoesToTheScopesHandler() = runTest {
        val js = engine(budget = 50_000)
        try {
            val failures = mutableListOf<Throwable>()
            val scope = CoroutineScope(
                Job() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> failures += e },
            )
            js.suspendFunction(js.onEngine { it.global }, "work", scope = scope) { "done" }
            js.evaluate("work().then(function () { for (;;) {} })")
            runCurrent()
            assertEquals(1, failures.size)
            assertTrue(failures.single() is JsEngineError, "${failures.single()}")
            assertEquals(3.0, js.evaluate("1 + 2").asDouble())
        } finally {
            release(js)
        }
    }
}
