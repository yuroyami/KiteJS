/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The instruction budget and the interrupt hook reach Promise reactions and callbacks, which call
 * and return without ever branching (D-77). Every chain here is finite, if long, so a regression
 * makes the assertions on its length fail rather than hanging the suite; the JVM tests run the
 * endless chain under a watchdog.
 */
class PromiseBudgetTest {

    /** Each reaction queues the next until [limit]; `n` counts the ones that ran. */
    private fun chain(limit: Int) =
        "var n = 0; function spin() { n++; if (n < $limit) Promise.resolve().then(spin) } Promise.resolve().then(spin); 'queued'"

    private fun KiteJs.number(source: String): Int = evaluate(source).asDouble().toInt()

    @Test
    fun a_budget_stops_a_chain_of_reactions_early() {
        KiteJs { instructionBudget = 10_000 }.use { js ->
            assertFailsWith<JsEngineError> { js.evaluate(chain(1_000_000)) }
            val ran = js.number("n")
            assertTrue(ran in 1 until 1_000, "the chain ran $ran reactions under a budget of 10,000 instructions")
        }
    }

    @Test
    fun the_interrupt_hook_is_asked_while_reactions_run() {
        var asked = 0
        KiteJs { interruptWhen = { ++asked > 2 } }.use { js ->
            assertFailsWith<JsEngineError> { js.evaluate(chain(10_000_000)) }
            assertTrue(asked > 2, "the hook was asked $asked times")
            val ran = js.number("n")
            assertTrue(ran < 100_000, "the chain ran $ran reactions before the hook was heard")
        }
    }

    @Test
    fun a_stop_drops_the_reactions_still_queued() {
        KiteJs { instructionBudget = 10_000 }.use { js ->
            assertFailsWith<JsEngineError> { js.evaluate(chain(1_000_000)) }
            val ran = js.number("n")
            // The next call starts clean: the abandoned chain does not pick up again.
            assertEquals(ran, js.number("n"))
            js.runMicrotasks()
            assertEquals(ran, js.number("n"))
            // And new reactions run as usual.
            js.evaluate("var m = 0; Promise.resolve().then(function () { m = 1 })")
            assertEquals(1, js.number("m"))
            assertEquals(4, js.number("2 + 2"))
        }
    }

    @Test
    fun a_callback_without_branches_is_counted() {
        KiteJs { instructionBudget = 100_000 }.use { js ->
            assertFailsWith<JsEngineError> {
                js.evaluate("var c = 0; var a = new Array(1000000).fill(0); a.forEach(function () { c++ })")
            }
            val ran = js.number("c")
            assertTrue(ran < 10_000, "the callback ran $ran times under a budget of 100,000 instructions")
        }
    }

    @Test
    fun a_host_call_and_a_drain_are_each_one_call_to_the_budget() {
        KiteJs { instructionBudget = 10_000 }.use { js ->
            js.evaluate("var k = 0; function step() { k++; if (k < 1000000) Promise.resolve().then(step) }")
            val step = js.global["step"].asFunction()
            step()
            // The host call ran one step and queued the next; draining runs the rest.
            assertEquals(1.0, js.global["k"].asDouble())
            assertFailsWith<JsEngineError> { js.runMicrotasks() }
            val ran = js.number("k")
            assertTrue(ran in 2 until 1_000, "the drain ran $ran reactions under a budget of 10,000")
        }
    }

    @Test
    fun a_chain_within_its_budget_runs_to_the_end() {
        KiteJs { instructionBudget = 50_000_000 }.use { js ->
            js.evaluate(chain(10_000))
            assertEquals(10_000, js.number("n"))
        }
        KiteJs().use { js ->
            js.evaluate(chain(10_000))
            assertEquals(10_000, js.number("n"))
        }
    }
}
