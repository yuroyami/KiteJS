/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.testkit

import io.github.yuroyami.kitejs.api.ConsoleLevel
import io.github.yuroyami.kitejs.api.ConsoleMessage
import io.github.yuroyami.kitejs.api.ConsolePrinters
import io.github.yuroyami.kitejs.api.JsEngine
import io.github.yuroyami.kitejs.api.JsEngineError
import io.github.yuroyami.kitejs.api.KiteJsConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestResult
import kotlinx.datetime.TimeZone

/**
 * What every engine's configuration does: the clock and the time zone, the console, sealed
 * built-ins, and the limits that stop a script. Budgets here are loose on purpose, since what one
 * unit of budget is belongs to the engine.
 */
public abstract class ConfigContract<C : KiteJsConfig>(engine: JsEngine<C>) : EngineContract<C>(engine) {

    @Test
    public fun theEngineKnowsWhatItIs(): TestResult = withEngine { js ->
        assertSame(engine, js.engine)
        assertTrue(js.version.isNotBlank())
    }

    // ---- Time -------------------------------------------------------------------------------------

    @Test
    public fun aFixedClockMakesDateStable(): TestResult = withEngine({ clock = { 1_700_000_000_000.0 }; timeZone = TimeZone.UTC }) { js ->
        assertEquals(1_700_000_000_000.0, js.evaluate("Date.now()").asDouble())
        assertEquals("2023-11-14T22:13:20.000Z", js.evaluate("new Date().toISOString()").asString())
    }

    @Test
    public fun theTimeZoneIsTheOneConfigured(): TestResult = withEngine({ clock = { 0.0 }; timeZone = TimeZone.of("Europe/Berlin") }) { js ->
        assertEquals(1.0, js.evaluate("new Date(0).getHours()").asDouble())
        // Summer time is the zone's rule, not a fixed offset.
        assertEquals(2.0, js.evaluate("new Date(Date.UTC(2024, 6, 1)).getHours()").asDouble())
        assertEquals(-60.0, js.evaluate("new Date(0).getTimezoneOffset()").asDouble())
        assertEquals(0.0, js.evaluate("new Date(1970, 0, 1, 1).getTime()").asDouble())
    }

    @Test
    public fun anotherZoneOnTheOtherSide(): TestResult = withEngine({ timeZone = TimeZone.of("America/New_York") }) { js ->
        assertEquals(19.0, js.evaluate("new Date(Date.UTC(2024, 0, 2, 0)).getHours()").asDouble())
        assertEquals(240.0, js.evaluate("new Date(Date.UTC(2024, 6, 2)).getTimezoneOffset()").asDouble())
    }

    // ---- Console ----------------------------------------------------------------------------------

    @Test
    public fun consoleGoesWhereTheEmbedderSaid(): TestResult = test {
        val lines = mutableListOf<ConsoleMessage>()
        open { console = ConsolePrinters.collecting(lines) }.use { js ->
            js.evaluate("console.log('hello', 1, true)")
            js.evaluate("console.warn('careful')")
            js.evaluate("console.error('%s went wrong at %d', 'thing', 5)")
            js.evaluate("console.info('i'); console.debug('d')")
        }
        assertEquals(5, lines.size)
        assertEquals(ConsoleLevel.INFO, lines[0].level)
        assertEquals("hello 1 true", lines[0].text)
        assertEquals(ConsoleLevel.WARN, lines[1].level)
        assertEquals("careful", lines[1].text)
        assertEquals(ConsoleLevel.ERROR, lines[2].level)
        assertEquals("thing went wrong at 5", lines[2].text)
        assertEquals(ConsoleMessage(ConsoleLevel.INFO, "i"), lines[3])
        assertEquals(ConsoleMessage(ConsoleLevel.DEBUG, "d"), lines[4])
    }

    @Test
    public fun consoleFormatsTheWayABrowserDoes(): TestResult = test {
        val lines = mutableListOf<String>()
        open { console = ConsolePrinters.of { _, text -> lines += text } }.use { js ->
            js.evaluate("console.log('%i|%f|%%|%c', 4.7, 1.5, 'color: red')")
            js.evaluate("console.log({ a: 1, b: [1, 'x'] })")
            js.evaluate("console.log('%o', { n: null })")
            js.evaluate("console.log(undefined, null, 10n)")
            js.evaluate("console.log()")
        }
        assertEquals("4|1.5|%|", lines[0])
        assertEquals("{\"a\":1,\"b\":[1,\"x\"]}", lines[1])
        assertEquals("{\"n\":null}", lines[2])
        assertEquals("undefined null 10n", lines[3])
        assertEquals("", lines[4])
    }

    @Test
    public fun consoleCountsAndTimes(): TestResult = test {
        val lines = mutableListOf<ConsoleMessage>()
        open { console = ConsolePrinters.collecting(lines) }.use { js ->
            js.evaluate("console.count('a'); console.count('a'); console.count()")
            js.evaluate("console.time('t'); console.timeEnd('t')")
            js.evaluate("console.assert(false, 'nope'); console.assert(true, 'quiet')")
        }
        assertEquals("a: 1", lines[0].text)
        assertEquals("a: 2", lines[1].text)
        assertEquals("default: 1", lines[2].text)
        assertTrue(lines[3].text.startsWith("t: "), lines[3].text)
        assertEquals("Assertion failed: nope", lines[4].text)
        assertEquals(5, lines.size)
    }

    @Test
    public fun withNoPrinterThereIsNoConsole(): TestResult = withEngine { js ->
        assertEquals("undefined", js.evaluate("typeof console").asString())
    }

    // ---- Sealed built-ins -------------------------------------------------------------------------

    @Test
    public fun sealedBuiltinsCannotBeRedefined(): TestResult = withEngine({ sealBuiltins = true }) { js ->
        js.evaluate("try { Array.prototype.push = null } catch (e) {}")
        assertEquals("function", js.evaluate("typeof Array.prototype.push").asString())
        js.evaluate("try { Math.max = null } catch (e) {}")
        assertEquals("function", js.evaluate("typeof Math.max").asString())
        // The script's own objects are its own.
        assertEquals(2.0, js.evaluate("var mine = {}; mine.x = 2; mine.x").asDouble())
    }

    @Test
    public fun unsealedBuiltinsCanBeRedefined(): TestResult = withEngine { js ->
        js.evaluate("Array.prototype.extra = function () { return 'mine' }")
        assertEquals("mine", js.evaluate("[].extra()").asString())
    }

    // ---- Limits -----------------------------------------------------------------------------------

    @Test
    public fun aRunawayScriptIsStopped(): TestResult = withEngine({ instructionBudget = 100_000 }) { js ->
        assertFailsWith<JsEngineError> { js.evaluate("while (true) {}") }
        assertFailsWith<JsEngineError> { js.evaluate("(function f() { for (;;) { [].map(function () {}) } })()") }
        // The engine is still usable afterwards.
        assertEquals(2.0, js.evaluate("1 + 1").asDouble())
    }

    @Test
    public fun theBudgetLeavesNormalScriptsAlone(): TestResult = withEngine({ instructionBudget = 5_000_000 }) { js ->
        assertEquals(499500.0, js.evaluate("var t = 0; for (var i = 0; i < 1000; i++) t += i; t").asDouble())
    }

    @Test
    public fun eachCallGetsTheBudgetAgain(): TestResult = withEngine({ instructionBudget = 500_000 }) { js ->
        repeat(20) {
            assertEquals(4950.0, js.evaluate("var t = 0; for (var i = 0; i < 100; i++) t += i; t").asDouble())
        }
    }

    @Test
    public fun theBudgetReachesPromiseReactions(): TestResult = withEngine({ instructionBudget = 100_000 }) { js ->
        val chain = "var n = 0; function spin() { n++; Promise.resolve().then(spin) } spin()"
        assertFailsWith<JsEngineError> { js.evaluate(chain) }
        val ran = js.evaluate("n").asDouble()
        // The abandoned chain is dropped, so nothing more runs.
        js.runMicrotasks()
        assertEquals(ran, js.evaluate("n").asDouble())
        // And new work runs as usual.
        js.evaluate("var m = 0; Promise.resolve().then(function () { m = 1 })")
        assertEquals(1.0, js.evaluate("m").asDouble())
    }

    @Test
    public fun theInterruptHookStopsAScript(): TestResult = test {
        var asked = 0
        open { interruptWhen = { ++asked > 2 } }.use { js ->
            assertFailsWith<JsEngineError> { js.evaluate("while (true) {}") }
            assertTrue(asked > 2, "the hook was asked $asked times")
        }
    }

    @Test
    public fun anInterruptHookThatThrowsStopsTheScriptWithItsException(): TestResult = test {
        open { interruptWhen = { throw IllegalStateException("deadline") } }.use { js ->
            val e = assertFailsWith<Exception> { js.evaluate("while (true) {}") }
            val cause = if (e is JsEngineError) e.cause else e
            assertTrue(cause is IllegalStateException, "$e")
        }
    }
}
