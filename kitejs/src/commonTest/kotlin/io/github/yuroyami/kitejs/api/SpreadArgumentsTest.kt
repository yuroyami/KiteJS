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
     * A value with no iterator is a TypeError, in a call and in an array literal alike: each
     * spread asks GetIterator for its value (ECMAScript 2015, 12.3.6.1 and 12.2.5.2).
     */
    @Test
    fun spreading_something_that_is_not_iterable_throws_a_type_error() {
        val f = "function f() { return arguments.length } "
        for (value in listOf("5", "true", "null", "undefined", "{ a: 1 }", "Symbol()")) {
            assertEquals("TypeError", eval("${f}try { f(...$value); 'none' } catch (e) { e.name }"), "f(...$value)")
            assertEquals("TypeError", eval("try { [...$value]; 'none' } catch (e) { e.name }"), "[...$value]")
            assertEquals("TypeError", eval("try { Math.max(...$value); 'none' } catch (e) { e.name }"), "Math.max(...$value)")
        }
        assertEquals("TypeError: 5 is not iterable", eval("try { [...5] } catch (e) { String(e) }"))
        assertEquals("TypeError: null is not iterable", eval("${f}try { f(...null) } catch (e) { String(e) }"))
    }

    /** An array whose iterator was taken away still spreads, by its length. */
    @Test
    fun an_array_spreads_by_its_length_when_its_iterator_is_gone() {
        assertEquals(
            "1,,3|3",
            eval(
                "var saved = Array.prototype[Symbol.iterator]; delete Array.prototype[Symbol.iterator];" +
                    " var a; try { a = [...[1, , 3]] } finally { Array.prototype[Symbol.iterator] = saved }" +
                    " String(a) + '|' + a.length",
            ),
        )
    }

    /** An object spread in an object literal copies own properties, so null and a number are fine. */
    @Test
    fun object_spread_still_takes_anything() {
        assertEquals("0", eval("Object.keys({ ...null, ...undefined, ...5 }).length + ''"))
    }
}
