/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A generator function inherits from %GeneratorFunction.prototype%, whose own prototype is
 * Function.prototype (ECMAScript 2015, 14.4.13 and 25.2.3), in any scope the facade makes: an
 * expression, a declaration and a method alike have call, apply and bind, and are functions.
 */
class GeneratorFunctionPrototypeTest {

    private fun eval(source: String): String = KiteJs().use { js -> js.evaluate(source).asString() }

    private val generators = listOf(
        "(function* () { yield 1; })",
        "(function () { function* d() { yield 1; } return d; })()",
        "({ *m() { yield 1; } }).m",
    )

    @Test
    fun a_generator_function_inherits_from_the_generator_function_prototype() {
        for (g in generators) {
            assertEquals(
                "[object GeneratorFunction],true,true,function,function,function,true",
                eval(
                    "var g = $g; var G = Object.getPrototypeOf(g);" +
                        " [Object.prototype.toString.call(G), G === Object.getPrototypeOf(function* () {}), Object.getPrototypeOf(G) === Function.prototype," +
                        " typeof g.call, typeof g.apply, typeof g.bind, g instanceof Function].join()",
                ),
                g,
            )
        }
    }

    @Test
    fun a_generator_called_through_call_yields() {
        for (g in generators) {
            assertEquals("1", eval("var g = $g; Array.from(g.call(null)).join()"), g)
        }
    }

    @Test
    fun a_custom_iterator_that_is_a_generator_drives_the_iterable_protocol() {
        assertEquals(
            "a,b",
            eval("var o = {}; o[Symbol.iterator] = function* () { yield 'a'; yield 'b'; }; Array.from(o).join()"),
        )
    }

    @Test
    fun the_generator_function_constructor_is_reached_through_the_prototype() {
        assertEquals(
            "function,3,false",
            eval(
                "var GF = Object.getPrototypeOf(function* () {}).constructor;" +
                    " var g = new GF('a', 'yield a; yield a + 1; yield a + 2;');" +
                    " [typeof GF, Array.from(g(1)).length, Object.keys(this).some(function (k) { return k.indexOf('Generator') >= 0; })].join()",
            ),
        )
    }
}
