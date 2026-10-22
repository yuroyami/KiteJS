/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.quickjs

import io.github.yuroyami.kitejs.api.JsError
import io.github.yuroyami.kitejs.api.KiteJs
import io.github.yuroyami.kitejs.api.function
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The browser's own stack is about 1 MiB and QuickJS's check cannot see it, so on the web every
 * way a script can recurse must reach QuickJS's RangeError before it runs out. When it does not,
 * Node dies and a browser leaves the module broken for every engine.
 */
class WebStackTest {

    /** Each one recurses without end in one way QuickJS has. */
    private val recursions = mapOf(
        "a call" to "function f() { f(); } f()",
        "a getter" to "var o = { get x() { return this.x; } }; o.x",
        "a callback of a built-in" to "function h() { [0].map(h); } h()",
        "a sort comparator" to "function s() { [2, 1].sort(function () { s(); return 0; }); } s()",
        "toString" to "var o = { toString: function () { return String(o); } }; String(o)",
        "a proxy trap" to "var p = new Proxy({}, { get: function (t, k) { return p[k]; } }); p.x",
        "a chain of proxies" to "var p = {}; for (var i = 0; i < 100000; i++) p = new Proxy(p, {}); p.x",
        "JSON.parse" to "JSON.parse('['.repeat(200000))",
        "JSON.parse of objects" to "JSON.parse('{\"a\":'.repeat(200000))",
        "a reviver" to "JSON.parse('['.repeat(20000) + ']'.repeat(20000), function (k, v) { return v; })",
        "JSON.stringify" to "var a = []; for (var i = 0; i < 100000; i++) a = [a]; JSON.stringify(a)",
        "flat" to "var a = []; for (var i = 0; i < 100000; i++) a = [a]; a.flat(Infinity)",
        "parentheses" to "eval('('.repeat(100000) + '1' + ')'.repeat(100000))",
        "array literals" to "eval('['.repeat(100000) + ']'.repeat(100000))",
        "object literals" to "eval('({a:'.repeat(100000) + '1' + '})'.repeat(100000))",
        "unary operators" to "eval('!'.repeat(200000) + '1')",
        "assignments" to "eval('a='.repeat(200000) + '1')",
        "conditionals" to "eval('1?'.repeat(100000) + '1' + ':1'.repeat(100000))",
        "blocks" to "eval('{'.repeat(200000) + '}'.repeat(200000))",
        "if statements" to "eval('if(1)'.repeat(200000) + ';')",
        "functions" to "eval('(function(){'.repeat(50000) + '})'.repeat(50000))",
        "arrow functions" to "eval('x=>'.repeat(100000) + '1')",
        "template literals" to "eval('`\${'.repeat(100000) + '1' + '}`'.repeat(100000))",
        "classes" to "eval('(class{m(){'.repeat(30000) + '}})'.repeat(30000))",
        "calls in calls" to "eval('(function(){ ' + 'f('.repeat(100000) + ')'.repeat(100000) + ' })')",
        "a regular expression" to "new RegExp('(?:'.repeat(200000) + ')'.repeat(200000))",
    )

    @Test
    fun everyRecursionStopsAtARangeErrorAndTheEngineGoesOn() = runTest {
        QuickJs.load()
        for (limit in listOf(512L * 1024, 1L shl 30, 0L)) {
            KiteJs(QuickJs) { maxStackSize = limit }.use { js ->
                for ((way, script) in recursions) {
                    val caught = js.evaluate("try { $script; 'nothing' } catch (e) { e.name }").asString()
                    // A regular expression too deep for its parser is a SyntaxError that says so.
                    val expected = if (way == "a regular expression") "SyntaxError" else "RangeError"
                    assertEquals(expected, caught, "$way, limit $limit")
                    assertEquals(2, js.evaluate("1 + 1").asInt(), "the engine after $way, limit $limit")
                }
            }
        }
    }

    @Test
    fun aRecursionThroughTheHostStopsAtARangeError() = runTest {
        QuickJs.load()
        KiteJs(QuickJs).use { js ->
            js.global.function("viaHost", 1) { args -> args[0].asFunction()() }
            val error = assertFailsWith<JsError> {
                js.evaluate("var d = 0; function f() { d++; viaHost(f); } f()")
            }
            assertEquals("RangeError", error.name)
            val depth = js.evaluate("d").asInt()
            assertTrue(depth > 50, "the host recursion stopped after $depth calls")
            assertEquals(2, js.evaluate("1 + 1").asInt())
        }
    }

    @Test
    fun theWebKeepsAUsefulDepth() = runTest {
        QuickJs.load()
        KiteJs(QuickJs).use { js ->
            val calls = js.evaluate("var d = 0; function f() { d++; f(); } try { f(); } catch (e) {} d").asInt()
            assertTrue(calls > 700, "$calls calls")
            val nesting = js.evaluate(
                "var n = 0; for (var k = 8; k < 100000; k += 8) { try { eval('('.repeat(k) + '1' + ')'.repeat(k)); n = k; } catch (e) { break; } } n",
            ).asInt()
            assertTrue(nesting >= 200, "$nesting nested parentheses")
        }
    }
}
