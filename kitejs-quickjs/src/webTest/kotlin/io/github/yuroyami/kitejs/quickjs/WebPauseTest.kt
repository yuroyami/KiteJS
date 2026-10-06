/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kitejs.quickjs

import io.github.yuroyami.kitejs.api.JsEngineError
import io.github.yuroyami.kitejs.api.JsError
import io.github.yuroyami.kitejs.api.KiteJs
import io.github.yuroyami.kitejs.api.function
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

/** A promise, as far as waiting for one goes. */
private external interface Waitable : JsAny {
    fun then(onFulfilled: (JsAny?) -> JsAny?): JsAny?
}

private fun timeout(ms: Int): Waitable = js("new Promise(function (resolve) { setTimeout(resolve, ms); })")

private fun startTicking(): JsAny = js("(globalThis.kiteTicks = 0, setInterval(function () { globalThis.kiteTicks++; }, 1))")

private fun stopTicking(timer: JsAny): Unit = js("clearInterval(timer)")

private fun ticks(): Int = js("globalThis.kiteTicks")

/** Waits [ms] of real time, which the test's virtual clock would skip. */
private suspend fun realWait(ms: Int) = suspendCoroutine { continuation ->
    timeout(ms).then {
        continuation.resume(Unit)
        null
    }
}

/** A script that keeps busy for [ms] of real time, then answers how many rounds it made. */
private fun busy(ms: Int) = "(function () { var n = 0, t = Date.now(); while (Date.now() - t < $ms) n++; return n; })()"

/** A script on the web lets the event loop run while it pauses (#130). */
class WebPauseTest {

    @Test
    fun nodeHasStackSwitching() = runTest {
        QuickJs.load()
        KiteJs(QuickJs).use { js -> assertTrue(js.canPause, "the test runtime has no WebAssembly stack switching") }
    }

    @Test
    fun aLongScriptLetsTimersRunAndGivesItsAnswer() = runTest {
        QuickJs.load()
        KiteJs(QuickJs).use { js ->
            js.global.function("ticks") { ticks() }
            val timer = startTicking()
            try {
                val passed = js.evaluatePausing("var a = ticks(); ${busy(200)}; ticks() - a", slice = 5.milliseconds).asInt()
                assertTrue(passed > 3, "only $passed timer ticks ran during the script")
                val sum = js.evaluatePausing("var s = 0; for (var i = 0; i < 3000000; i++) s += i % 7; s", slice = 1.milliseconds)
                assertEquals(8999994, sum.asInt())
                val script = js.compile("var b = ticks(); ${busy(100)}; ticks() - b", "compiled.js")
                val compiledPassed = script.runPausing(slice = 5.milliseconds).asInt()
                assertTrue(compiledPassed > 1, "only $compiledPassed timer ticks ran during the compiled script")
            } finally {
                stopTicking(timer)
            }
        }
    }

    @Test
    fun aScriptDoesNotPauseInsideAHostFunction() = runTest {
        QuickJs.load()
        KiteJs(QuickJs).use { js ->
            js.global.function("ticks") { ticks() }
            js.global.function("viaHost", 1) { args -> args[0].asFunction()() }
            val timer = startTicking()
            try {
                val passed = js.evaluatePausing(
                    "viaHost(function () { var a = ticks(); ${busy(60)}; return ticks() - a; })",
                    slice = 1.milliseconds,
                ).asInt()
                assertEquals(0, passed)
            } finally {
                stopTicking(timer)
            }
        }
    }

    @Test
    fun aPausedEngineRefusesCallsAndOtherEnginesStillWork() = runTest {
        QuickJs.load()
        val js = KiteJs(QuickJs)
        try {
            val run = async { js.evaluatePausing("${busy(150)}; 'done'", slice = 2.milliseconds) }
            realWait(30)
            assertFailsWith<JsEngineError> { js.evaluate("1") }
            assertFailsWith<JsEngineError> { js.global["x"] }
            assertFailsWith<JsEngineError> { js.close() }
            KiteJs(QuickJs).use { other ->
                assertEquals(2, other.evaluate("1 + 1").asInt())
                // Only one script pauses at a time, so this one runs to the end at once.
                assertEquals(3, other.evaluatePausing("${busy(20)}; 3").asInt())
            }
            assertEquals("done", run.await().asString())
            assertEquals(2, js.evaluate("1 + 1").asInt())
        } finally {
            js.close()
        }
    }

    @Test
    fun deepRecursionInAPausingScriptIsStillARangeError() = runTest {
        QuickJs.load()
        KiteJs(QuickJs).use { js ->
            for (script in listOf("function f() { f(); } f()", "JSON.parse('['.repeat(200000))", "eval('('.repeat(100000) + '1' + ')'.repeat(100000))")) {
                val error = assertFailsWith<JsError>(script) { js.evaluatePausing(script, slice = 0.milliseconds) }
                assertEquals("RangeError", error.name, script)
                assertEquals(2, js.evaluatePausing("1 + 1").asInt())
            }
        }
    }
}
