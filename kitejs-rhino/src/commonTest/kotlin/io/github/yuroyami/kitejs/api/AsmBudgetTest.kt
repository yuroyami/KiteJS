/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Context
import io.github.yuroyami.kitejs.rhino.Rhino
import io.github.yuroyami.kitejs.rhino.asmReports
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Finite workloads expose missed accounting without leaving an unbounded test running. */
class AsmBudgetTest {

    private fun load(js: KiteJs, source: String = trees): JsObject {
        js.evaluate(source, "asm-budget.js")
        val report = js.asmReports.single()
        assertTrue(report.compiled, "module must compile: $report")
        assertTrue(report.linked, "module must link: $report")
        return js.global["m"].asObject()
    }

    private fun stopped(budget: Int, body: () -> Unit) {
        val error = assertFailsWith<JsEngineError>(block = body)
        assertEquals("script used more than $budget instructions and was stopped", error.message)
    }

    @Test
    fun directAndTableCallTreesReachTheBudget() {
        for (name in listOf("tree", "through")) {
            KiteJs(Rhino) { instructionBudget = 10_000 }.use { js ->
                val function = load(js)[name].asFunction()
                stopped(10_000) { function(18) }
                // A later top-level call has a fresh budget, including after unwinding recursion.
                repeat(100) { assertEquals(16, function(4).asInt(), name) }
                assertEquals(2, js.evaluate("1 + 1").asInt())
            }
        }
    }

    @Test
    fun longStraightLineCodeIsBoundedAndKeepsItsEffects() {
        val increments = "value = (value + 1) | 0;\n".repeat(10_000)
        KiteJs(Rhino) { instructionBudget = 1_000 }.use { js ->
            val exports = load(js, """
                var m = (function () {
                  "use asm";
                  var value = 0;
                  function work() { $increments return value | 0; }
                  function count() { return value | 0; }
                  return { work: work, count: count };
                })();
            """.trimIndent())
            stopped(1_000) { exports["work"].asFunction()() }
            val count = exports["count"].asFunction()().asInt()
            assertTrue(count in 1 until 10_000, "the stopped call changed value $count times")
            assertEquals(count, exports["count"].asFunction()().asInt())
            assertEquals(2, js.evaluate("1 + 1").asInt())
        }
    }

    @Test
    fun recursiveWorkReachesAnInterruptWithoutTakingABackwardJump() {
        var interrupt = false
        var asked = 0
        KiteJs(Rhino) { interruptWhen = { asked++; interrupt } }.use { js ->
            val function = load(js)["tree"].asFunction()
            interrupt = true
            val error = assertFailsWith<JsEngineError> { function(18) }
            assertEquals("script was interrupted", error.message)
            assertTrue(asked > 0)
            interrupt = false
            assertEquals(16, function(4).asInt())
            assertEquals(2, js.evaluate("1 + 1").asInt())
        }
    }

    @Test
    fun loopsAreBoundedAndObserverFailuresReleaseTheRunner() {
        var interrupt = false
        var throwFromHook = false
        var asked = 0
        KiteJs(Rhino) {
            interruptWhen = {
                asked++
                if (throwFromHook) throw IllegalStateException("observer failed")
                interrupt
            }
        }.use { js ->
            val function = load(js)["loop"].asFunction()
            interrupt = true
            assertFailsWith<JsEngineError> { function(110_000) }
            interrupt = false
            throwFromHook = true
            val error = assertFailsWith<JsEngineError> { function(110_000) }
            assertTrue(error.message.orEmpty().contains("observer failed"), error.message)
            assertTrue(asked >= 2)
            throwFromHook = false
            assertEquals(10, function(10).asInt())
            assertEquals(2, js.evaluate("1 + 1").asInt())
        }
        KiteJs(Rhino) { instructionBudget = 10_000 }.use { js ->
            val function = load(js)["loop"].asFunction()
            stopped(10_000) { function(110_000) }
            assertEquals(10, function(10).asInt())
        }
    }

    @Test
    fun foreignCallbacksAndReentrantExportsShareTheOuterBudget() {
        KiteJs(Rhino) { instructionBudget = 30_000 }.use { js ->
            var nested: JsFunction? = null
            var calls = 0
            js.global["callback"] = js.newFunction("callback", 0) { _, _ ->
                calls++
                nested!!(8).asInt()
            }
            val exports = load(js, """
                var m = (function (s, f) {
                  "use asm";
                  var cb = f.cb;
                  function tree(n) {
                    n = n | 0;
                    if ((n | 0) <= 0) return 1 | 0;
                    return ((tree((n - 1) | 0) | 0) + (tree((n - 1) | 0) | 0)) | 0;
                  }
                  function work(n) {
                    n = n | 0;
                    var i = 0, result = 0;
                    for (i = 0; (i | 0) < (n | 0); i = (i + 1) | 0) {
                      result = (result + (cb() | 0)) | 0;
                    }
                    return result | 0;
                  }
                  return { work: work, tree: tree };
                })({}, { cb: callback });
            """.trimIndent())
            nested = exports["tree"].asFunction()
            stopped(30_000) { exports["work"].asFunction()(100) }
            assertTrue(calls in 2 until 100, "the outer call made $calls callbacks")
            repeat(100) { assertEquals(256, nested!!(8).asInt()) }
            assertEquals(256, exports["work"].asFunction()(1).asInt())
            assertEquals(2, js.evaluate("1 + 1").asInt())
        }
    }

    @Test
    fun observerReentryKeepsTheInstructionsItAddsToTheContext() {
        var nested: JsFunction? = null
        var countedByNested = 0
        var countOnEntry = -1
        KiteJs(Rhino) {
            interruptWhen = {
                val cx = Context.getContext()
                countOnEntry = cx.instructionCount
                nested!!(0)
                countedByNested = cx.instructionCount
                false
            }
        }.use { js ->
            nested = load(js)["tree"].asFunction()
            val cx = Context.getContext()
            js.global["probe"] = js.newFunction("probe", 0) { _, _ ->
                cx.addInstructionCount(cx.instructionThreshold + 1)
                assertEquals(0, countOnEntry, "charged work must be cleared before observer reentry")
                assertTrue(countedByNested > 0, "the nested typed call must contribute instructions")
                assertEquals(countedByNested, cx.instructionCount, "observer work must remain counted")
                7
            }
            assertEquals(7, js.evaluate("probe()").asInt())
            assertEquals(2, js.evaluate("1 + 1").asInt())
        }
    }

    @Test
    fun instructionAccountingDoesNotWrapAtTheLargestBudget() {
        KiteJs(Rhino) { instructionBudget = Int.MAX_VALUE }.use { js ->
            val function = load(js)["tree"].asFunction()
            js.global["probe"] = js.newFunction("probe", 0) { _, _ ->
                Context.getContext().instructionCount = Int.MAX_VALUE - 2
                function(0)
            }
            stopped(Int.MAX_VALUE) { js.evaluate("probe()") }
            assertEquals(0, Context.getContext().interpreterInvocationDepth)
            assertEquals(1, function(0).asInt())
            assertEquals(2, js.evaluate("1 + 1").asInt())
        }
    }

    @Test
    fun changingTheThresholdDoesNotReplaceTheActiveBytecode() {
        var asked = 0
        KiteJs(Rhino) {
            interruptWhen = {
                asked++
                Context.getContext().setInstructionObserverThreshold(0)
                false
            }
        }.use { js ->
            val function = load(js, """
                var m = (function () {
                  "use asm";
                  function f(n, x) {
                    n = n | 0; x = +x;
                    var i = 0, result = 0.0;
                    result = x;
                    for (i = 0; (i | 0) < (n | 0); i = (i + 1) | 0) {
                      result = result + 1.0;
                    }
                    return +result;
                  }
                  return { f: f };
                })();
            """.trimIndent())["f"].asFunction()
            assertEquals(110_001.5, function(110_000, 1.5).asDouble())
            assertEquals(1, asked)
            assertEquals(11.5, function(10, 1.5).asDouble())
            Context.getContext().setInstructionObserverThreshold(100_000)
            assertEquals(110_001.5, function(110_000, 1.5).asDouble())
            assertEquals(2, asked)
            assertEquals(2, js.evaluate("1 + 1").asInt())
        }
    }

    @Test
    fun typedRecursionChecksTheNativeDepthLimitAndUnwinds() {
        KiteJs(Rhino) { maxHostCallDepth = 8; maxCallDepth = 0 }.use { js ->
            val exports = load(js, """
                var m = (function () {
                  "use asm";
                  function f(n) {
                    n = n | 0;
                    if ((n | 0) <= 0) return 1 | 0;
                    return f((n - 1) | 0) | 0;
                  }
                  return { f: f };
                })();
            """.trimIndent())
            val function = exports["f"].asFunction()
            val error = assertFailsWith<JsError> { function(100) }
            assertEquals("RangeError", error.name)
            assertEquals("Maximum call stack size exceeded", error.errorMessage)
            assertEquals(0, Context.getCurrentContext()!!.interpreterInvocationDepth)
            assertEquals(1, function(3).asInt())
            assertEquals("RangeError", js.evaluate("try { m.f(100) } catch (e) { e.name }").asString())
            assertEquals(0, Context.getCurrentContext()!!.interpreterInvocationDepth)
            assertEquals(2, js.evaluate("1 + 1").asInt())
        }
    }

    @Test
    fun meteredBranchesSwitchesAndMixedCallsKeepTheirAnswers() {
        val source = """
            var m = (function () {
              "use asm";
              function a(x) { x = +x; return +(x + 0.25); }
              function b(x) { x = +x; return +(x * 2.0); }
              function main(n) {
                n = n | 0;
                var i = 0, result = 0.0;
                for (i = 0; (i | 0) < (n | 0); i = (i + 1) | 0) {
                  if ((i | 0) == 3) continue;
                  switch (i & 3) {
                    case 0: result = result + +TBL[i & 1](+(i | 0)); break;
                    case 1: result = result + +a(+(i | 0)); break;
                    default: result = result + +b(+(i | 0));
                  }
                  if ((i | 0) == 7) break;
                }
                return +result;
              }
              var TBL = [a, b];
              return { main: main };
            })();
        """.trimIndent()
        for (enabled in listOf(false, true)) {
            KiteJs(Rhino) { asmJs = enabled; instructionBudget = 1_000_000 }.use { js ->
                if (enabled) load(js, source) else js.evaluate(source)
                val function = js.global["m"].asObject()["main"].asFunction()
                assertEquals(5.5, function(3).asDouble())
                assertEquals(41.0, function(100).asDouble())
            }
        }
    }

    companion object {
        private val trees = """
            var m = (function () {
              "use asm";
              function tree(n) {
                n = n | 0;
                if ((n | 0) <= 0) return 1 | 0;
                return ((tree((n - 1) | 0) | 0) + (tree((n - 1) | 0) | 0)) | 0;
              }
              function through(n) {
                n = n | 0;
                if ((n | 0) <= 0) return 1 | 0;
                return ((TBL[0 & 1]((n - 1) | 0) | 0) + (TBL[1 & 1]((n - 1) | 0) | 0)) | 0;
              }
              function loop(n) {
                n = n | 0;
                var i = 0;
                for (i = 0; (i | 0) < (n | 0); i = (i + 1) | 0) {}
                return i | 0;
              }
              var TBL = [through, through];
              return { tree: tree, through: through, loop: loop };
            })();
        """.trimIndent()
    }
}
