/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A Promise reaction that queues itself forever, under a budget and under an interrupt hook that
 * always answers stop (D-77). Each runs on a thread of its own with a watchdog, so a regression
 * fails the test instead of hanging the suite.
 */
class EndlessReactionsTest {

    private val endless = "var n = 0; function spin() { n++; Promise.resolve().then(spin) } Promise.resolve().then(spin)"

    private fun withinTenSeconds(body: () -> String): String {
        var outcome = "did not finish within ten seconds"
        val worker = Thread { outcome = runCatching(body).fold({ it }, { "${it::class.simpleName}: ${it.message}" }) }
        worker.isDaemon = true
        worker.start()
        worker.join(10_000)
        return outcome
    }

    @Test
    fun aBudgetStopsAnEndlessChain() {
        val outcome = withinTenSeconds {
            KiteJs { instructionBudget = 10_000 }.use { js ->
                val error = runCatching { js.evaluate(endless) }.exceptionOrNull()
                "${error?.let { it::class.simpleName }} after ${js.evaluate("n").asDouble().toInt()} and then ${js.evaluate("2 + 2").asDouble().toInt()}"
            }
        }
        assertTrue(outcome.startsWith("JsEngineError after "), outcome)
        assertTrue(outcome.endsWith(" and then 4"), outcome)
    }

    @Test
    fun aHookThatAlwaysAnswersStopEndsAnEndlessChain() {
        val outcome = withinTenSeconds {
            KiteJs { interruptWhen = { true } }.use { js ->
                runCatching { js.evaluate(endless) }.exceptionOrNull()?.message ?: "no error"
            }
        }
        assertEquals("script was interrupted", outcome)
    }
}
