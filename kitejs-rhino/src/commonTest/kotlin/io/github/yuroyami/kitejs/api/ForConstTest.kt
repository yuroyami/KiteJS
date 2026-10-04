/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A loop head that declares its variable with `const`, and the binding a `let` or `const` head
 * gives each iteration. ECMAScript 2015, 13.7.5 (for-in and for-of) and 13.7.4 (for): each pass
 * gets a fresh binding, so a closure made in the body keeps the value of its own iteration, and a
 * const one cannot be assigned to.
 *
 * Each case runs at the top level and inside a function, because a function without closures
 * keeps its variables in a frame and everything else keeps them in scope objects, and the two are
 * compiled differently.
 */
class ForConstTest {

    private fun eval(source: String): String = KiteJs().use { js -> js.evaluate(source).asString() }

    /** [body] ends in an expression; it runs as a script and as the return of a function. */
    private fun bothWays(expected: String, body: String, last: String) {
        assertEquals(expected, eval("$body; $last"), "script: $body")
        assertEquals(expected, eval("(function () { $body; return $last })()"), "function: $body")
    }

    @Test
    fun the_issue_examples() {
        bothWays("6", "var s = 0; for (const x of [1, 2, 3]) s += x", "s")
        bothWays("ab", "var s = ''; for (const k in { a: 1, b: 2 }) s += k", "s")
        bothWays("14", "var s = 0; for (const [a, b] of [[1, 2], [3, 4]]) s += a * b", "s")
        bothWays("3", "var fs = []; for (const x of [1, 2]) fs.push(function () { return x })", "fs[0]() + fs[1]()")
        bothWays("TypeError", "var r = 'none'; try { for (const x of [1]) { x = 2 } } catch (e) { r = e.name }", "r")
    }

    @Test
    fun every_write_to_the_loop_const_throws() {
        for (write in listOf("x = 2", "x++", "--x", "x += 1", "[x] = [2]", "x &&= 2")) {
            bothWays("TypeError:1", "var r = 'none'; try { for (const x of [1]) { $write } } catch (e) { r = e.name + ':' }", "r + 1")
        }
        // From a closure, where the binding lives in a scope object of its own.
        bothWays(
            "TypeError1TypeError2",
            "var fs = []; for (const x of [1, 2]) fs.push(function () { try { x = 9 } catch (e) { return e.name + x } })",
            "fs[0]() + fs[1]()",
        )
    }

    @Test
    fun destructuring_heads_bind_every_name_afresh() {
        bothWays(
            "3:7",
            "var fs = []; for (const { a, b: [c] } of [{ a: 1, b: [2] }, { a: 3, b: [4] }]) fs.push(function () { return a + c })",
            "fs[0]() + ':' + fs[1]()",
        )
        bothWays("TypeError", "var r = 'none'; try { for (const [a] of [[1]]) a = 2 } catch (e) { r = e.name }", "r")
    }

    /** A let head was one binding for the whole loop; now each pass has its own, as with const. */
    @Test
    fun a_let_head_binds_each_iteration_too() {
        bothWays("3", "var fs = []; for (let x of [1, 2]) fs.push(function () { return x })", "fs[0]() + fs[1]()")
        bothWays("ab", "var fs = []; for (let k in { a: 1, b: 2 }) fs.push(function () { return k })", "fs[0]() + fs[1]()")
        bothWays("10,20", "var fs = []; for (let x of [1, 2]) { fs.push(function () { return x }); x = x * 10 }", "fs[0]() + ',' + fs[1]()")
    }

    @Test
    fun the_loop_const_is_scoped_to_the_loop() {
        assertEquals("abouter", eval("var x = 'outer'; (function () { var r = ''; for (const x of ['a', 'b']) r += x; return r + x })()"))
        assertEquals("ap", eval("(function (item) { var r = ''; for (const item of ['a']) r += item; return r + item })('p')"))
        assertEquals("ab", eval("const q = 1; (function () { var r = ''; for (const q of ['a', 'b']) r += q; return r })()"))
        assertEquals("undefined", eval("for (const x of [1]) {} typeof x"))
        assertEquals("two", eval("for (const x of [1]) {} for (const x of [2]) {} 'two'"))
    }

    @Test
    fun break_continue_and_labels_still_work() {
        bothWays("b", "var r = ''; for (const x of ['a', 'b']) { if (x == 'a') continue; r += x }", "r")
        bothWays("a", "var r = ''; for (const x of ['a', 'b', 'c']) { if (x == 'b') break; r += x }", "r")
        bothWays(
            "11,21,12,22",
            "var r = []; outer: for (const x of [1, 2]) { for (const y of [10, 20]) { r.push(function () { return x + y }); if (y == 20) continue outer } }",
            "r.map(function (f) { return f() }).join()",
        )
    }

    @Test
    fun generators_keep_each_binding() {
        assertEquals("10,20", eval("function* g() { for (const x of [1, 2]) yield x * 10 } [...g()].join()"))
        assertEquals(
            "3",
            eval("var fs = []; function* g() { for (const x of [1, 2]) { fs.push(function () { return x }); yield x } } [...g()]; fs[0]() + fs[1]()"),
        )
    }

    /** `for (const i = s; ...)` binds once per start of the loop, and `i++` is a TypeError. */
    @Test
    fun an_ordinary_for_takes_a_const_too() {
        bothWays("5,5", "var r = []; for (const i = 5; r.length < 2; ) r.push(i)", "r.join()")
        bothWays("TypeError", "var r = 'none'; try { for (const i = 0; i < 2; i++) {} } catch (e) { r = e.name }", "r")
        bothWays("0,1", "var r = []; for (var j = 0; j < 2; j++) { for (const i = j; ; ) { r.push(function () { return i }); break } }", "r[0]() + ',' + r[1]()")
        bothWays("3", "var r; for (const [a, b] = [1, 2]; ; ) { r = a + b; break }", "r")
    }

    @Test
    fun a_var_of_the_same_name_in_the_body_is_a_redeclaration() {
        assertEquals("SyntaxError", eval("try { eval('for (const x of [1]) { var x }') } catch (e) { e.name }"))
    }

    /** The head binds each value as it comes, so it cannot have an initializer of its own. */
    @Test
    fun a_for_in_or_of_const_head_takes_no_initializer() {
        assertEquals("SyntaxError", eval("try { eval('for (const x = 1 of []) {}') } catch (e) { e.name }"))
        assertEquals("SyntaxError", eval("try { eval('for (const x = 1 in {}) {}') } catch (e) { e.name }"))
    }
}
