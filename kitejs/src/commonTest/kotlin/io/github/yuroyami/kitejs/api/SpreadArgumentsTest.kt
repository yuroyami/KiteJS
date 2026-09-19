/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spread in an argument list, for example `f(...values)`. ECMAScript 2015, 12.3.6 allows it in
 * any position and any number of times, for a call and for `new`, and each one is expanded
 * through the value's iterator.
 */
class SpreadArgumentsTest {

    private fun eval(source: String): String = KiteJs().use { js -> js.evaluate(source).asString() }

    @Test
    fun a_spread_argument_is_expanded() {
        assertEquals("2", eval("Math.max(...[1, 2])"))
        assertEquals("6", eval("function f(a, b, c) { return a + b + c } f(...[1, 2, 3])"))
    }

    @Test
    fun a_spread_mixes_with_plain_arguments_in_any_position() {
        assertEquals("1,2,3,4", eval("function f() { return Array.prototype.slice.call(arguments).join(',') } f(1, ...[2, 3], 4)"))
        assertEquals("4", eval("function f() { return arguments.length } f(1, ...[2, 3], 4)"))
        assertEquals("1,2,3,4", eval("function f() { return Array.prototype.slice.call(arguments).join(',') } f(...[1, 2], ...[3, 4])"))
        assertEquals("0", eval("function f() { return arguments.length } f(...[])"))
    }

    @Test
    fun a_spread_takes_any_iterable() {
        assertEquals("a,b", eval("function f() { return Array.prototype.slice.call(arguments).join(',') } f(...'ab')"))
        assertEquals("1,2", eval("function f() { return Array.prototype.slice.call(arguments).join(',') } f(...new Set([1, 2]))"))
        assertEquals(
            "1,2",
            eval(
                "function* g() { yield 1; yield 2 }" +
                    " function f() { return Array.prototype.slice.call(arguments).join(',') } f(...g())",
            ),
        )
    }

    /** The method keeps its receiver: the spread must not turn `obj.m(...)` into a plain call. */
    @Test
    fun a_method_called_with_a_spread_keeps_its_receiver() {
        assertEquals("7", eval("var o = { n: 4, add: function (a, b) { return this.n + a + b } }; o.add(...[1, 2])"))
        assertEquals("3", eval("var a = []; a.push(...[1, 2, 3]); a.length + ''"))
    }

    @Test
    fun new_takes_a_spread_too() {
        assertEquals("2020", eval("new Date(...[2020, 0, 1]).getFullYear()"))
        assertEquals("3", eval("function C(a, b) { this.sum = a + b } new C(...[1, 2]).sum"))
    }

    /**
     * A value with no iterator adds no arguments, the same as it adds no elements to an array
     * literal. A browser throws a TypeError here; issue 9 holds that difference. What this test
     * pins is that a call and an array literal answer alike, and that neither fails internally.
     */
    @Test
    fun spreading_something_that_is_not_iterable_adds_nothing() {
        assertEquals("0", eval("function f() { return arguments.length } f(...5) + ''"))
        assertEquals("0", eval("[...5].length + ''"))
    }
}
