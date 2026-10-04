/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A `const` in a block belongs to that block and is bound afresh each time its declaration runs
 * (ECMAScript 2015, 13.3.1). A const in a loop body used to keep the value of the first pass,
 * because it was one binding for the whole function that only its first initialization set.
 *
 * Each case runs at the top level and inside a function, because a function without closures
 * keeps its variables in a frame and everything else keeps them in scope objects, and the two are
 * compiled differently.
 */
class BlockConstTest {

    private fun eval(source: String): String = KiteJs(Rhino).use { js -> js.evaluate(source).asString() }

    /** [body] ends in an expression; it runs as a script and as the return of a function. */
    private fun bothWays(expected: String, body: String, last: String) {
        assertEquals(expected, eval("$body; $last"), "script: $body")
        assertEquals(expected, eval("(function () { $body; return $last })()"), "function: $body")
    }

    @Test
    fun a_const_in_a_loop_body_takes_the_value_of_each_pass() {
        bothWays("0,2,4", "var r = []; for (var i = 0; i < 3; i++) { const k = i * 2; r.push(k) }", "r.join()")
        bothWays("0,10,20", "var r = [], i = 0; while (i < 3) { const k = i * 10; r.push(k); i++ }", "r.join()")
        bothWays("1,2,3", "var r = [], i = 0; do { const k = i + 1; r.push(k); i++ } while (i < 3)", "r.join()")
        bothWays("12", "var s = 0; for (const x of [1, 2, 3]) { const y = x * 2; s += y }", "s")
        bothWays("0,3,6", "var r = []; for (var i = 0; i < 3; i++) { const [a, b] = [i, i * 2]; r.push(a + b) }", "r.join()")
    }

    @Test
    fun a_closure_keeps_the_binding_of_its_own_pass() {
        bothWays("1", "var fs = []; for (var i = 0; i < 2; i++) { const k = i; fs.push(function () { return k }) }", "fs[0]() + fs[1]()")
        bothWays("1", "var fs = []; for (var i = 0; i < 2; i++) { const { a } = { a: i }; fs.push(function () { return a }) }", "fs[0]() + fs[1]()")
        bothWays(
            "4",
            "var fs = []; for (var i = 0; i < 2; i++) { let a = i; const b = a + 1; fs.push(function () { return a + b }) }",
            "fs[0]() + fs[1]()",
        )
    }

    @Test
    fun a_write_to_a_block_const_still_throws() {
        bothWays(
            "TypeError0,TypeError1",
            "var r = []; for (var i = 0; i < 2; i++) { const k = i; try { k = 5 } catch (e) { r.push(e.name + k) } }",
            "r.join()",
        )
        bothWays(
            "TypeError0TypeError1",
            "var fs = []; for (var i = 0; i < 2; i++) { const k = i; fs.push(function () { try { k = 9 } catch (e) { return e.name + k } }) }",
            "fs[0]() + fs[1]()",
        )
    }

    @Test
    fun the_const_is_scoped_to_its_block() {
        bothWays("undefined", "{ const a = 1 }", "typeof a")
        bothWays("outer", "var a = 'outer'; { const a = 'inner' }", "a")
        bothWays("1", "var r; { const a = 1; { const a = 2 } r = a }", "r")
        assertEquals("2", eval("(function (x) { if (true) { const x = 2; return x } })(1) + ''"))
        assertEquals("0,3", eval("var r = []; for (var i = 0; i < 2; i++) { try { throw i } catch (e) { const z = e * 3; r.push(z) } } r.join()"))
    }

    @Test
    fun labels_generators_and_eval_see_each_binding() {
        bothWays(
            "0,1",
            "var r = []; outer: for (var i = 0; i < 2; i++) { const k = i; for (var j = 0; j < 2; j++) { if (j == 1) continue outer; r.push(k) } }",
            "r.join()",
        )
        assertEquals("0,3,6", eval("function* g() { for (var i = 0; i < 3; i++) { const k = i * 3; yield k } } [...g()].join()"))
        bothWays("0,1", "var r = []; for (var i = 0; i < 2; i++) { const k = i; r.push(eval('k')) }", "r.join()")
    }

    /** A var or function declared in the same block, before or after, is an early SyntaxError. */
    @Test
    fun a_block_const_may_not_share_its_name_with_a_var_of_the_block() {
        for (source in listOf(
            "{ var f; const f = 0; }",
            "{ const f = 0; var f; }",
            "{ function f() {} const f = 0; }",
            "{ { var f; } const f = 0; }",
            "{ const f = 0; const f = 1; }",
        )) {
            assertEquals("SyntaxError", eval("try { eval('$source'); 'accepted' } catch (e) { e.name }"), source)
        }
        for (source in listOf("var f; { const f = 0; }", "{ var f; { const f = 0; } }", "function g(a) { { const a = 1; } }")) {
            assertEquals("accepted", eval("try { eval('$source'); 'accepted' } catch (e) { e.name }"), source)
        }
    }

    /** A const directly in a function or script body is what it was: one binding for the body. */
    @Test
    fun a_const_in_a_function_body_is_unchanged() {
        assertEquals("1", eval("const c = 1; c + ''"))
        assertEquals("2", eval("(function () { const top = 1; { const inner = top + 1; return inner } })() + ''"))
        assertEquals("5", eval("(function () { switch (1) { case 1: const z = 5; return z } })() + ''"))
    }
}
