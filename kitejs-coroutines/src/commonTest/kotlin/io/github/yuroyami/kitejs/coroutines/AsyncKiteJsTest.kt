/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.coroutines

import io.github.yuroyami.kitejs.api.JsEngineError
import io.github.yuroyami.kitejs.api.JsError
import io.github.yuroyami.kitejs.api.JsValue
import io.github.yuroyami.kitejs.api.function
import io.github.yuroyami.kitejs.api.newPromise
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

/** The engine behind suspending functions. */
class AsyncKiteJsTest {

    @Test
    fun evaluateAnswersOnTheEnginesDispatcher() = runTest {
        val js = asyncKiteJs()
        try {
            assertEquals(4.0, js.evaluate("2 + 2").asDouble())
            assertEquals("HI", js.evaluate("'hi'.toUpperCase()").asString())
        } finally {
            js.close()
        }
    }

    @Test
    fun theEngineIsReachableForBindingToo() = runTest {
        val js = asyncKiteJs()
        try {
            js.onEngine { engine ->
                engine.global.function("double") { args -> args.first().asDouble() * 2 }
            }
            assertEquals(10.0, js.evaluate("double(5)").asDouble())
        } finally {
            js.close()
        }
    }

    @Test
    fun aCompiledScriptRunsOnTheDispatcher() = runTest {
        val js = asyncKiteJs()
        try {
            val script = js.compile("n = (typeof n === 'undefined' ? 0 : n) + 1")
            js.onEngine { script.run() }
            js.onEngine { script.run() }
            assertEquals(2.0, js.evaluate("n").asDouble())
        } finally {
            js.close()
        }
    }

    @Test
    fun aScriptErrorArrivesAsJsError() = runTest {
        val js = asyncKiteJs()
        try {
            val e = assertFailsWith<JsError> { js.evaluate("throw new RangeError('too far')") }
            assertEquals("RangeError", e.name)
            assertEquals("too far", e.errorMessage)
        } finally {
            js.close()
        }
    }

    // ---- Promises ------------------------------------------------------------------------------

    @Test
    fun anAlreadySettledPromiseIsAwaited() = runTest {
        val js = asyncKiteJs()
        try {
            assertEquals(7.0, js.evaluateAwaiting("Promise.resolve(7)").asDouble())
            assertEquals("done", js.evaluateAwaiting("Promise.resolve('done')").asString())
        } finally {
            js.close()
        }
    }

    @Test
    fun aChainOfThensIsAwaited() = runTest {
        val js = asyncKiteJs()
        try {
            val v = js.evaluateAwaiting(
                "Promise.resolve(1).then(function (n) { return n + 1 }).then(function (n) { return n * 10 })",
            )
            assertEquals(20.0, v.asDouble())
        } finally {
            js.close()
        }
    }

    @Test
    fun aRejectedPromiseThrowsWhatItCarried() = runTest {
        val js = asyncKiteJs()
        try {
            val e = assertFailsWith<JsError> { js.evaluateAwaiting("Promise.reject(new TypeError('nope'))") }
            assertEquals("TypeError", e.name)
            assertEquals("nope", e.errorMessage)
        } finally {
            js.close()
        }
    }

    @Test
    fun awaitingAPlainValueGivesItBack() = runTest {
        val js = asyncKiteJs()
        try {
            assertEquals(5.0, js.await(js.evaluate("5")).asDouble())
            assertTrue(js.await(js.evaluate("undefined")).isUndefined)
        } finally {
            js.close()
        }
    }

    @Test
    fun aPromiseSettledFromKotlinIsAwaited() = runTest {
        val js = asyncKiteJs()
        try {
            val handle = js.onEngine { it.newPromise() }
            js.onEngine { engine ->
                handle.resolve("late")
                engine.runMicrotasks()
            }
            assertEquals("late", js.await(JsValue.of(handle.promise)).asString())
        } finally {
            js.close()
        }
    }

    /**
     * No sleeping anywhere in these: the engine runs on its own dispatcher, so a delay measured on
     * the test's virtual clock says nothing about whether the bridge has run. Awaiting the promise
     * is the signal.
     */
    @Test
    fun aDeferredBecomesAPromiseTheScriptCanUse() = runTest {
        val js = asyncKiteJs()
        try {
            val deferred = CompletableDeferred<String>()
            val promise = js.deferredToPromise(deferred, this)
            js.onEngine { it.global["fromKotlin"] = promise }
            deferred.complete("hello")
            assertEquals("hello", js.await(JsValue.of(promise)).asString())
            assertEquals("hello", js.evaluateAwaiting("fromKotlin").asString())
        } finally {
            js.close()
        }
    }

    @Test
    fun aHostSuspendFunctionLooksLikeAPromiseToTheScript() = runTest {
        val js = asyncKiteJs()
        try {
            js.suspendFunction(js.onEngine { it.global }, "fetchThing", 1, this) { args ->
                delay(5)
                "got:" + args.first().asString()
            }
            // To the script it is an ordinary call that answers a promise.
            assertEquals("object", js.evaluate("typeof fetchThing('x')").asString())
            assertEquals("got:a", js.evaluateAwaiting("fetchThing('a')").asString())
        } finally {
            js.close()
        }
    }

    @Test
    fun aFailingHostSuspendFunctionRejects() = runTest {
        val js = asyncKiteJs()
        try {
            js.suspendFunction(js.onEngine { it.global }, "boom", 0, this) { _ ->
                throw IllegalStateException("host gave up")
            }
            val e = assertFailsWith<JsError> { js.evaluateAwaiting("boom()") }
            assertContains(e.errorMessage, "host gave up")
        } finally {
            js.close()
        }
    }

    // ---- Cancellation ---------------------------------------------------------------------------

    /**
     * The hook is asked from inside the running script, so anything it can decide for itself works
     * on every target. Cancelling from another coroutine needs a second thread, which Kotlin/JS
     * does not have; that case lives in the JVM tests.
     */
    @Test
    fun aDeadlineStopsARunawayScript() = runTest {
        var asked = 0
        val js = asyncKiteJs { interruptWhen = { ++asked > 3 } }
        try {
            assertFailsWith<JsEngineError> { js.evaluate("for (;;) {}") }
            assertTrue(asked > 3, "the hook was never asked")
            assertEquals(3.0, js.evaluate("1 + 2").asDouble())
        } finally {
            js.close()
        }
    }

    @Test
    fun aBudgetStillStopsARunawayScript() = runTest {
        val js = asyncKiteJs { instructionBudget = 200_000 }
        try {
            assertFailsWith<JsEngineError> { js.evaluate("while (true) {}") }
            assertEquals(3.0, js.evaluate("1 + 2").asDouble())
        } finally {
            js.close()
        }
    }

    @Test
    fun normalScriptsAreNotDisturbedByTheInterruptHook() = runTest {
        val js = asyncKiteJs()
        try {
            assertEquals(499500.0, js.evaluate("var t = 0; for (var i = 0; i < 1000; i++) t += i; t").asDouble())
        } finally {
            js.close()
        }
    }

    @Test
    fun aClosedAsyncEngineRefusesToRun() = runTest {
        val js = asyncKiteJs()
        js.close()
        assertFailsWith<IllegalStateException> { js.evaluate("1") }
    }
}
