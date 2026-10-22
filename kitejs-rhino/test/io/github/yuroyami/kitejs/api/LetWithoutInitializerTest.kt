/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A `let` without an initializer binds `undefined` each time its declaration runs (ECMAScript
 * 2015, 13.3.1.4), so in a loop body it does not keep what the previous pass left in it.
 *
 * Each case runs at the top level and inside a function, because a function without closures
 * keeps its variables in a frame and everything else keeps them in scope objects.
 */
class LetWithoutInitializerTest {

    private fun eval(source: String): String = KiteJs(Rhino).use { js -> js.evaluate(source).asString() }

    /** [body] ends in an expression; it runs as a script and as the return of a function. */
    private fun bothWays(expected: String, body: String, last: String) {
        assertEquals(expected, eval("$body; $last"), "script: $body")
        assertEquals(expected, eval("(function () { $body; return $last })()"), "function: $body")
    }

    @Test
    fun aLetInALoopBodyStartsEachPassUndefined() {
        bothWays(",", "var out = []; for (let i = 0; i < 2; ++i) { let x; out.push(x); x = 1 }", "out.join()")
        bothWays(",", "var out = []; for (var i = 0; i < 2; ++i) { let y; out.push(y); y = 2 }", "out.join()")
        bothWays(",", "var out = [], j = 0; while (j++ < 2) { let z; out.push(z); z = 3 }", "out.join()")
        bothWays(",", "var out = [], j = 0; do { let z; out.push(z); z = 3 } while (++j < 2)", "out.join()")
        bothWays(",", "var out = []; for (var k of [1, 2]) { let w; out.push(w); w = k }", "out.join()")
    }

    @Test
    fun everyNameOfTheDeclarationIsReset() {
        bothWays(
            "undefined 5 undefined|undefined 5 undefined",
            "var out = []; for (var i = 0; i < 2; ++i) { let a, b = 5, c; out.push([a, b, c].map(String).join(' ')); a = c = 1 }",
            "out.join('|')",
        )
    }

    @Test
    fun aClosureKeepsTheBindingOfItsPass() {
        bothWays(
            "0,undefined",
            "var fs = []; for (var i = 0; i < 2; ++i) { let x; fs.push(function () { return x }); if (i == 0) x = 0 }",
            "fs.map(function (f) { return String(f()) }).join()",
        )
    }

    @Test
    fun aGeneratorAndASwitchReadUndefined() {
        assertEquals(
            "undefined,undefined",
            eval("function* g() { for (var i = 0; i < 2; i++) { let x; yield String(x); x = i } } [...g()].join()"),
        )
        bothWays(
            ",",
            "var out = []; for (var i = 0; i < 2; ++i) { switch (i) { default: let s; out.push(s); s = i } }",
            "out.join()",
        )
    }

    @Test
    fun loopHeadsAndOtherDeclarationsAreUnchanged() {
        bothWays("a,b", "var out = []; for (let x of ['a', 'b']) out.push(x)", "out.join()")
        bothWays("p,q", "var out = []; for (let k in { p: 1, q: 2 }) out.push(k)", "out.join()")
        bothWays("u", "let u; var t = typeof u", "t.charAt(0)")
        bothWays("7", "var v = 7; var v", "v")
        bothWays("0,1", "var out = []; for (let i, n = 0; n < 2; n++) { i = n; out.push(i) }", "out.join()")
    }
}
