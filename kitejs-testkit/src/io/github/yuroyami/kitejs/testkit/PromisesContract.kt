/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.testkit

import io.github.yuroyami.kitejs.api.JsEngine
import io.github.yuroyami.kitejs.api.JsEngineError
import io.github.yuroyami.kitejs.api.JsError
import io.github.yuroyami.kitejs.api.JsValue
import io.github.yuroyami.kitejs.api.KiteJs
import io.github.yuroyami.kitejs.api.KiteJsConfig
import io.github.yuroyami.kitejs.api.function
import io.github.yuroyami.kitejs.api.isThenable
import io.github.yuroyami.kitejs.api.newPromise
import io.github.yuroyami.kitejs.api.onSettled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestResult

/**
 * When Promise reactions run, and the host's view of promises: what counts as thenable, a promise
 * the host settles, and `onSettled`, which attaches to a value the way `await` does.
 */
public abstract class PromisesContract<C : KiteJsConfig>(engine: JsEngine<C>) : EngineContract<C>(engine) {

    // ---- When reactions run ---------------------------------------------------------------------

    @Test
    public fun promisesSettleByTheTimeEvaluateReturns(): TestResult = withEngine { js ->
        js.evaluate("var log = []; Promise.resolve(1).then(function (v) { log.push(v) }).then(function () { log.push(2) })")
        assertEquals("1,2", js.evaluate("log.join()").asString())
    }

    @Test
    public fun asyncFunctionsFinishWithinTheCall(): TestResult = withEngine { js ->
        js.evaluate("var out; (async function () { out = await Promise.resolve(5) + await 6 })()")
        assertEquals(11.0, js.evaluate("out").asDouble())
    }

    @Test
    public fun aFunctionCallFromTheHostRunsWhatItQueued(): TestResult = withEngine { js ->
        val handle = js.newPromise()
        js.global["p"] = handle.promise
        js.evaluate("var seen = 'none'; p.then(function (v) { seen = v })")
        assertEquals("none", js.evaluate("seen").asString())
        handle.resolve("done")
        assertEquals("done", js.global["seen"].asString())
    }

    @Test
    public fun aPropertyReadLeavesReactionsQueued(): TestResult = withEngine { js ->
        val o = js.evaluate("var hit = 0; ({ get x() { Promise.resolve().then(function () { hit = 1 }); return 1 } })").asObject()
        assertEquals(1.0, o["x"].asDouble())
        assertEquals(0.0, js.global["hit"].asDouble())
        js.runMicrotasks()
        assertEquals(1.0, js.global["hit"].asDouble())
    }

    @Test
    public fun workQueuedFromAHostCallbackRunsWhenTheCallReturns(): TestResult = withEngine { js ->
        val log = mutableListOf<String>()
        js.global.function("record") { args -> log += args.first().asString(); null }
        js.evaluate("Promise.resolve().then(function () { record('reaction') }); record('script')")
        assertEquals(listOf("script", "reaction"), log)
    }

    @Test
    public fun aRejectedPromiseIsNotAnErrorUntilSomeoneAsks(): TestResult = withEngine { js ->
        val p = js.evaluate("Promise.reject(new Error('later'))")
        var reason: JsValue? = null
        assertTrue(js.onSettled(p) { _, error -> reason = error })
        assertEquals("later", reason?.asObject()?.get("message")?.asString())
    }

    // ---- Thenables ------------------------------------------------------------------------------

    @Test
    public fun thenableMeansACallableThen(): TestResult = withEngine { js ->
        for (source in listOf("1", "'then'", "true", "null", "undefined", "10n", "Symbol('s')")) {
            assertFalse(js.evaluate(source).isThenable, source)
        }
        for (source in listOf("({})", "[]", "(function () {})", "({ then: 5 })", "({ then: null })")) {
            assertFalse(js.evaluate(source).isThenable, source)
        }
        for (source in listOf(
            "Promise.resolve(1)",
            "({ then: function () {} })",
            "var a = []; a.then = function (r) { r(4) }; a",
            "var f = function () {}; f.then = function () {}; f",
            "({ get then() { return function () {} } })",
            "Object.create({ then: function () {} })",
        )) {
            assertTrue(js.evaluate(source).isThenable, source)
        }
    }

    @Test
    public fun aThenGetterThatThrowsReachesTheCaller(): TestResult = withEngine { js ->
        val value = js.evaluate("({ get then() { throw new RangeError('no then') } })")
        val e = assertFailsWith<JsError> { value.isThenable }
        assertEquals("RangeError", e.name)
        assertEquals(3.0, js.evaluate("1 + 2").asDouble())
    }

    @Test
    public fun aScalarIsNotThenableAnywhereButAnObjectNeedsItsEngine(): TestResult = test {
        val number: JsValue
        val obj: JsValue
        open().use { js ->
            number = js.evaluate("42")
            obj = js.evaluate("({ then: function () {} })")
        }
        assertFalse(number.isThenable)
        assertFailsWith<JsEngineError> { obj.isThenable }
    }

    // ---- onSettled ------------------------------------------------------------------------------

    /** What `onSettled` reported, drained the way a host drains. */
    private fun settle(js: KiteJs, source: String): Pair<Boolean, String> {
        var outcome = "pending"
        var calls = 0
        val registered = js.onSettled(js.evaluate(source)) { value, error ->
            calls++
            outcome = if (error == null) "ok:$value" else "error:$error"
        }
        js.runMicrotasks()
        assertTrue(calls <= 1, "settled $calls times: $source")
        return registered to outcome
    }

    @Test
    public fun aSettledPromiseReportsBeforeOnSettledReturns(): TestResult = withEngine { js ->
        var outcome = "pending"
        js.onSettled(js.evaluate("Promise.resolve(3)")) { value, _ -> outcome = "ok:$value" }
        assertEquals("ok:3", outcome)
    }

    @Test
    public fun aThenableIsFollowedToItsEnd(): TestResult = withEngine { js ->
        assertEquals(true to "ok:7", settle(js, "({ then: function (resolve) { resolve(Promise.resolve(7)) } })"))
        assertEquals(true to "ok:8", settle(js, "({ then: function (r) { r({ then: function (r2) { r2(8) } }) } })"))
        assertEquals(
            true to "error:TypeError: inner",
            settle(js, "({ then: function (r) { r({ then: function (_, j) { j(new TypeError('inner')) } }) } })"),
        )
        // A rejection is not followed: the reason is the promise itself.
        assertEquals(true to "error:[object Promise]", settle(js, "({ then: function (_, j) { j(Promise.resolve(1)) } })"))
    }

    @Test
    public fun aThenableSettlesOnceAndAThrowRejects(): TestResult = withEngine { js ->
        assertEquals(true to "ok:1", settle(js, "({ then: function (r) { r(1); r(2) } })"))
        assertEquals(true to "ok:1", settle(js, "({ then: function (r) { r(1); throw new Error('late') } })"))
        assertEquals(true to "error:Error: early", settle(js, "({ then: function () { throw new Error('early') } })"))
        assertEquals(true to "error:boom", settle(js, "({ get then() { throw 'boom' } })"))
    }

    @Test
    public fun thenIsReadOnce(): TestResult = withEngine { js ->
        val source = "var reads = 0; ({ get then() { reads++; return function (r) { r('read') } } })"
        assertEquals(true to "ok:read", settle(js, source))
        assertEquals(1.0, js.evaluate("reads").asDouble())
    }

    @Test
    public fun aPromiseIsWatchedWithoutCallingItsThen(): TestResult = withEngine { js ->
        val source = "var p = Promise.resolve(5); p.then = function () { throw new Error('called') }; p"
        assertEquals(true to "ok:5", settle(js, source))
    }

    @Test
    public fun aPromiseResolvedWithItselfRejects(): TestResult = withEngine { js ->
        val source = "var res; var p = new Promise(function (r) { res = r }); res(p); p"
        val (registered, outcome) = settle(js, source)
        assertTrue(registered)
        assertTrue(outcome.startsWith("error:TypeError"), outcome)
    }

    @Test
    public fun aValueThatIsNotThenableRegistersNothing(): TestResult = withEngine { js ->
        for (source in listOf("5", "'x'", "({})", "({ then: 5 })", "[]")) {
            assertEquals(false to "pending", settle(js, source), source)
        }
    }

    @Test
    public fun aHostPromiseSettlesOnce(): TestResult = withEngine { js ->
        val handle = js.newPromise()
        var outcome = "pending"
        js.onSettled(handle.promise.value) { value, error -> outcome = if (error == null) "ok:$value" else "error:$error" }
        handle.reject("first")
        handle.resolve("second")
        assertEquals("error:first", outcome)
    }

    @Test
    public fun aCallbackThatThrowsComesOutOfTheCallThatDrained(): TestResult = withEngine { js ->
        val handle = js.newPromise()
        js.onSettled(handle.promise.value) { _, _ -> throw IllegalStateException("callback broke") }
        val e = assertFailsWith<JsEngineError> { handle.resolve(1) }
        assertTrue(e.cause is IllegalStateException, "${e.cause}")
    }
}
