/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.quickjs.contract

import io.github.yuroyami.kitejs.api.JsEngineError
import io.github.yuroyami.kitejs.api.KiteJs
import io.github.yuroyami.kitejs.quickjs.QuickJs
import io.github.yuroyami.kitejs.quickjs.QuickJsConfig
import io.github.yuroyami.kitejs.testkit.EngineContract
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** What only QuickJS does, on top of the contract every engine keeps. */
class QuickJsOnlyTest : EngineContract<QuickJsConfig>(QuickJs) {

    @Test
    fun theEngineReportsItsVersion() = withEngine { js ->
        assertContains(js.version, "KiteJS")
        assertContains(js.version, "QuickJS-ng")
        assertEquals("QuickJS", js.engine.name)
    }

    @Test
    fun twoEnginesOnOneThreadWorkSideBySide() = test {
        KiteJs(QuickJs).use { a ->
            KiteJs(QuickJs).use { b ->
                a.evaluate("var x = 'a'")
                b.evaluate("var x = 'b'")
                assertEquals("a", a.evaluate("x").asString())
                assertEquals("b", b.evaluate("x").asString())
            }
        }
    }

    @Test
    fun aMemoryLimitStopsAScriptThatOutgrowsIt() = withEngine({ memoryLimit = 8L * 1024 * 1024 }) { js ->
        assertFailsWith<JsEngineError> {
            js.evaluate("(() => { const a = []; for (;;) a.push(new Array(1024).fill(1)) })()")
        }
        assertEquals(2, js.evaluate("1 + 1").asInt())
    }

    @Test
    fun runningOutOfMemoryEndsTheCallEvenWhenTheScriptCatchesIt() = withEngine({ memoryLimit = 8L * 1024 * 1024 }) { js ->
        assertFailsWith<JsEngineError> {
            js.evaluate("(() => { try { const a = []; for (;;) a.push(new Array(1024).fill(1)) } catch (e) {} return 'swallowed' })()")
        }
        assertEquals(2, js.evaluate("1 + 1").asInt())
    }

    @Test
    fun aFailedCollectionConversionReleasesItsTemporaryHandles() = withEngine({ memoryLimit = 4L * 1024 * 1024 }) { js ->
        val refused = listOf("x".repeat(128 * 1024), object {})
        repeat(80) {
            val error = assertFailsWith<JsEngineError> { js.valueOf(refused) }
            assertContains(error.message!!, "has no JavaScript form")
        }
        assertEquals(2, js.evaluate("1 + 1").asInt())
    }

    /**
     * A FinalizationRegistry holds its unregister token weakly (ECMA-262, 26.2.3.2: the cell's
     * [[UnregisterToken]] does not keep it alive), so a target that is its own token still goes.
     * QuickJS held the token strongly, and the common `register(o, v, o)` kept every `o`.
     */
    @Test
    fun aRegistryHoldsItsUnregisterTokenWeakly() = withEngine { js ->
        js.evaluate(
            """
            var fired = [];
            var registry = new FinalizationRegistry(function (v) { fired.push(v); });
            var keptToken = {};
            (function () {
              var a = {}; registry.register(a, 'its own token', a);
              var b = {}, t = {}; registry.register(b, 'a token that went first', t); t = null;
              var c = {}; registry.register(c, 'unregistered', c); registry.unregister(c);
              var d = {}; registry.register(d, 'a token still held', keptToken);
            })();
            """.trimIndent(),
        )
        assertEquals(
            "a token still held,a token that went first,its own token",
            js.evaluate("fired.sort().join()").asString(),
        )
        assertEquals(false, js.evaluate("registry.unregister(keptToken)").asBoolean())
    }

    @Test
    fun asyncIterationRuns() = withEngine { js ->
        js.evaluate(
            """
            var out = [];
            (async () => {
                async function* gen() { yield 1; yield 2; yield 3 }
                for await (const x of gen()) out.push(x * 2);
            })();
            """.trimIndent(),
        )
        assertEquals("2,4,6", js.evaluate("out.join()").asString())
    }
}
