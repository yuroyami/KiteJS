/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ES2023 typed array methods `at`, `toReversed`, `toSorted` and `with` (issues 23 and 44, D-83).
 * Each validates its receiver before anything else, makes its copy with the realm's own constructor,
 * and converts a replacement the way the view's element type asks. Every expected value here is
 * what V8 answers.
 */
class TypedArrayCopyTest {

    private fun eval(source: String): String = KiteJs().use { js -> js.evaluate(source).asString() }

    /** What [call] did on a view of [ctor] built from [values] whose buffer was then detached. */
    private fun onDetached(ctor: String, values: String, call: String): String = eval(
        "(function () { var a = new $ctor($values); a.buffer.transfer();" +
            " try { a.$call; return 'returned' } catch (e) { return e.name } })()",
    )

    @Test
    fun a_detached_view_is_a_type_error_for_every_copying_method() {
        for ((ctor, values, value) in listOf(
            Triple("Uint8Array", "[1, 2]", "5"),
            Triple("Uint8Array", "[]", "5"),
            Triple("Float64Array", "[1, 2]", "5"),
            Triple("BigInt64Array", "[1n, 2n]", "5n"),
            Triple("BigUint64Array", "[]", "5n"),
        )) {
            for (call in listOf("at(0)", "toReversed()", "toSorted()", "toSorted(function (x, y) { return 0 })", "with(0, $value)")) {
                assertEquals("TypeError", onDetached(ctor, values, call), "$ctor($values).$call")
            }
        }
    }

    @Test
    fun a_buffer_detached_by_a_conversion_is_seen_afterwards() {
        val detachOnRead = "{ valueOf: function () { a.buffer.transfer(); return 0 } }"
        // at reads the element after converting the index, and a detached element reads undefined.
        assertEquals("undefined", eval("var a = new Uint8Array([1, 2]); String(a.at($detachOnRead))"))
        // with checks the index against the view as it is after both conversions.
        for (call in listOf("a.with($detachOnRead, 1)", "a.with(0, $detachOnRead)")) {
            assertEquals("RangeError", eval("var a = new Uint8Array([1, 2]); try { $call; 'returned' } catch (e) { e.name }"), call)
        }
        // toSorted reads every element before the comparator runs, so detaching changes nothing.
        assertEquals(
            "1,2,3|0",
            eval(
                "var a = new Uint8Array([3, 1, 2]);" +
                    " var r = a.toSorted(function (x, y) { if (!a.buffer.detached) a.buffer.transfer(); return x - y });" +
                    " r.join() + '|' + a.length",
            ),
        )
        assertEquals(
            "0|0",
            eval(
                "var a = new Uint8Array([3, 1, 2]);" +
                    " var r = a.sort(function (x, y) { if (!a.buffer.detached) a.buffer.transfer(); return x - y });" +
                    " r.length + '|' + a.byteLength",
            ),
        )
    }

    @Test
    fun the_copy_is_made_by_the_realms_own_constructors() {
        assertEquals(
            "2,1|1,2|9,2|true|true",
            eval(
                "var U = Uint8Array; var a = new U([1, 2]);" +
                    " Uint8Array = function () { throw new Error('the global was used') };" +
                    " ArrayBuffer = function () { throw new Error('the global ArrayBuffer was used') };" +
                    " var r = a.toReversed();" +
                    " [r.join(), a.toSorted().join(), a.with(0, 9).join(), Object.getPrototypeOf(r) === U.prototype," +
                    " a.map(function (x) { return x }) instanceof U].join('|')",
            ),
        )
        // None of them consults species, which only map, filter, slice and subarray do.
        assertEquals(
            "2,1|1,2|9,2",
            eval(
                "var a = new Uint8Array([1, 2]); a.constructor = function () { throw new Error('species') };" +
                    " [a.toReversed().join(), a.toSorted().join(), a.with(0, 9).join()].join('|')",
            ),
        )
    }

    @Test
    fun with_converts_the_replacement_for_the_element_type() {
        assertEquals("3,2", eval("new BigInt64Array([1n, 2n]).with(0, 3n).join()"))
        assertEquals("1,18446744073709551615", eval("new BigUint64Array([1n, 2n]).with(-1, -1n).join()"))
        assertEquals("NaN,2", eval("new Float64Array([1, 2]).with(0).join()"))
        assertEquals("0,2", eval("new Uint8Array([1, 2]).with(0).join()"))
        assertEquals("0,2", eval("new Float32Array([1, 2]).with(0, null).join()"))
        assertEquals("1,44", eval("new Uint8Array([1, 2]).with(-1, 300).join()"))
        assertEquals("1,255", eval("new Uint8ClampedArray([1, 2]).with(1, 300).join()"))
        for (call in listOf("with(0, 1)", "with(0)", "with(0, undefined)", "with(5, 1)")) {
            assertEquals(
                "TypeError",
                eval("try { new BigInt64Array([1n, 2n]).$call; 'returned' } catch (e) { e.name }"),
                call,
            )
        }
        assertEquals("3", eval("new BigInt64Array([1n]).with(0, '3').join()"))
        assertEquals("SyntaxError", eval("try { new BigInt64Array([1n]).with(0, '1n') } catch (e) { e.name }"))
    }

    @Test
    fun with_converts_index_then_value_then_checks_the_range() {
        val log = "var log = []; function v(tag, x) { return { valueOf: function () { log.push(tag); return x } } }"
        assertEquals("i,v|7,2|1,2", eval("$log; var a = new Uint8Array([1, 2]); var r = a.with(v('i', 0), v('v', 7)); [log, r.join(), a.join()].join('|')"))
        assertEquals("RangeError|i,v", eval("$log; try { new Uint8Array([1, 2]).with(v('i', 2), v('v', 7)) } catch (e) { log.unshift(e.name) } log[0] + '|' + log.slice(1)"))
        assertEquals("RangeError", eval("try { new Uint8Array([1, 2]).with(-3, 1) } catch (e) { e.name }"))
        assertEquals("RangeError", eval("try { new Uint8Array([1, 2]).with(Infinity, 1) } catch (e) { e.name }"))
        assertEquals("9,2", eval("new Uint8Array([1, 2]).with(-0, 9).join()"))
    }

    @Test
    fun at_and_toReversed_still_work_on_a_live_view() {
        assertEquals("2|1|undefined|undefined", eval("var a = new Int16Array([1, 2]); [a.at(-1), a.at(0.9), a.at(2), a.at(-Infinity)].map(String).join('|')"))
        assertEquals("3,2,1|1,2,3", eval("var a = new Int8Array([1, 2, 3]); a.toReversed().join() + '|' + a.join()"))
        assertEquals(
            "-Infinity,-0,0,1,3,NaN",
            eval("Array.prototype.map.call(new Float64Array([3, NaN, 0, -0, 1, -Infinity]).toSorted(), function (x) { return Object.is(x, -0) ? '-0' : x }).join()"),
        )
        assertEquals("TypeError", eval("try { new Uint8Array(1).toSorted(5) } catch (e) { e.name }"))
    }

    @Test
    fun a_comparator_answering_nan_means_equal() {
        assertEquals("1,2,3", eval("new Uint8Array([1, 2, 3]).toSorted(function () { return NaN }).join()"))
        assertEquals("1,2,3", eval("new Uint8Array([1, 2, 3]).sort(function () { return NaN }).join()"))
        // A stable sort keeps elements the comparator calls equal in their order.
        assertEquals(
            "0,3,6,9,12,15,18,21,24,27,30,33,36,39,1,4,7,10,13,16,19,22,25,28,31,34,37,2,5,8,11,14,17,20,23,26,29,32,35,38",
            eval(
                "var a = []; for (var i = 0; i < 40; i++) a.push({ k: i % 3, i: i });" +
                    " a.sort(function (x, y) { return x.k === y.k ? NaN : x.k - y.k }).map(function (o) { return o.i }).join()",
            ),
        )
    }
}
