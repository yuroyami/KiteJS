/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino.contract

import io.github.yuroyami.kitejs.api.JsEngineError
import io.github.yuroyami.kitejs.api.JsError
import io.github.yuroyami.kitejs.api.JsFunction
import io.github.yuroyami.kitejs.api.function
import io.github.yuroyami.kitejs.rhino.Context
import io.github.yuroyami.kitejs.rhino.Rhino
import io.github.yuroyami.kitejs.rhino.RhinoConfig
import io.github.yuroyami.kitejs.testkit.EngineContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RhinoRecursionTest : EngineContract<RhinoConfig>(Rhino) {

    @Test
    fun aConfiguredFrameLimitStopsBeforeAnotherBodyRuns() = withEngine({ maxCallDepth = 12 }) { js ->
        repeat(3) {
            assertEquals("RangeError:Maximum call stack size exceeded", js.evaluate(
                "var depth = 0; function grow() { depth++; grow(); return depth; } " +
                    "try { grow(); } catch (e) { e.name + ':' + e.message; }",
            ).asString())
            assertEquals(12, js.evaluate("depth").asInt())
            assertIdle()
            assertEquals(2, js.evaluate("1 + 1").asInt())
        }
    }

    @Test
    fun nativeCallbacksAndResumedGeneratorsHaveAnIndependentLimit() = withEngine({ maxHostCallDepth = 8 }) { js ->
        val scripts = listOf(
            "function mapAgain() { [0].map(mapAgain); } mapAgain();",
            "var getterAgain = { get x() { return getterAgain.x; } }; getterAgain.x;",
            "var proxyAgain = new Proxy({}, { get: function () { return proxyAgain.x; } }); proxyAgain.x;",
            "var stringAgain = { toString: function () { return String(stringAgain); } }; String(stringAgain);",
            "function* generatorAgain() { yield* generatorAgain(); } generatorAgain().next();",
        )
        for (script in scripts) {
            assertEquals("RangeError", js.evaluate("try { $script } catch (e) { e.name; }").asString(), script)
            assertIdle()
            assertEquals(2, js.evaluate("1 + 1").asInt(), script)
        }
    }

    @Test
    fun scriptFramesStayCountedAcrossHostReentry() = withEngine({ maxCallDepth = 20 }) { js ->
        var hostCalls = 0
        js.global.function("bounce") { args -> hostCalls++; args.first().asFunction()() }
        repeat(3) {
            hostCalls = 0
            val error = assertFailsWith<JsError> { js.evaluate(
                "function mixed() { function pad(n) { if (n) { pad(n - 1); return; } bounce(mixed); } pad(2); } mixed();",
            ) }
            assertEquals("RangeError", error.name)
            assertTrue(hostCalls in 1..6, "frame depth restarted after $hostCalls host calls")
            assertIdle()
            assertEquals(2, js.evaluate("1 + 1").asInt())
        }
    }

    @Test
    fun disablingTheFrameLimitLeavesNativeCallsBounded() = withEngine({ maxCallDepth = 0; maxHostCallDepth = 8 }) { js ->
        assertEquals(10_100, js.evaluate(
            "function finite(n) { if (!n) return 0; return finite(n - 1) + 1; } finite(10100);",
        ).asInt())
        val error = assertFailsWith<JsError> {
            js.global.function("bounceDisabled") { args -> args.first().asFunction()() }
            js.evaluate("function nativeAgain() { bounceDisabled(nativeAgain); } nativeAgain();")
        }
        assertEquals("RangeError", error.name)
        assertIdle()
    }

    @Test
    fun tailCallsDoNotRetainReplacedRootFrames() = withEngine({ maxCallDepth = 4 }) { js ->
        Context.getContext().setGeneratingDebug(false)
        val tail = js.evaluate(
            "function tail(n) { 'use strict'; if (n === 0) return 7; return tail(n - 1); } tail;",
        ).asFunction()
        assertEquals(7, tail(1000).asInt())
        assertEquals(7, js.evaluate("tail(1000)").asInt())
        assertIdle()
    }

    @Test
    fun boundedGeneratorDelegationStillCompletesAndUnwinds() = withEngine({ maxHostCallDepth = 8 }) { js ->
        assertEquals("1,9,true", js.evaluate(
            "function wrap(child) { return (function* () { yield* child; })(); } " +
                "var delegated = (function* () { yield 1; yield 2; })(); " +
                "for (var i = 0; i < 3; i++) delegated = wrap(delegated); " +
                "[delegated.next().value, delegated.return(9).value, delegated.next().done].join();",
        ).asString())
        assertIdle()
    }

    @Test
    fun aHostFunctionThatCallsItselfHasTheSameLimit() = withEngine({ maxHostCallDepth = 8 }) { js ->
        lateinit var again: JsFunction
        again = js.global.function("hostAgain") { _ -> again() }
        repeat(3) {
            assertEquals("RangeError", assertFailsWith<JsError> { again() }.name)
            assertIdle()
            assertEquals(2, js.evaluate("1 + 1").asInt())
        }
    }

    @Test
    fun invalidLimitsReleaseTheContextWhenOpeningFails() = test {
        assertFailsWith<JsEngineError> { open { maxCallDepth = -1 }.use { } }
        for (limit in listOf(0, -1)) assertFailsWith<JsEngineError> { open { maxHostCallDepth = limit }.use { } }
        open().use { js -> assertEquals(2, js.evaluate("1 + 1").asInt()) }
    }

    private fun assertIdle() {
        val cx = Context.getContext()
        assertEquals(0, cx.interpreterInvocationDepth)
        assertNull(cx.lastInterpreterFrame)
        assertNull(cx.currentActivationCall)
    }
}
