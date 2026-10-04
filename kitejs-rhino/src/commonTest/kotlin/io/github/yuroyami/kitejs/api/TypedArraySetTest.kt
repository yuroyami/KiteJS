/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `%TypedArray%.prototype.set` (issue 45, D-86): a missing source is undefined and so a TypeError
 * the script can catch, any other source goes through ToObject, and its elements are read with Get.
 * The offset is converted first and the target checked next, as the spec orders them. Every
 * expected value here is what V8 answers.
 */
class TypedArraySetTest {

    @Test
    fun every_case_answers_what_v8_answers() {
        val cases = listOf(
            "TypeError|TypeError|TypeError" to "['set()', 'set(undefined)', 'set(null)'].map(function (c) { try { eval('new Uint8Array([1, 2]).' + c); return 'returned' } catch (e) { return e.name } }).join('|')",
            "TypeError" to "try { new BigInt64Array(2).set() } catch (e) { e.name }",
            "1,2,3" to "var a = new Uint8Array(3); a.set('123'); a.join()",
            "0,0,0,0" to "var a = new Uint8Array(4); a.set(5); a.join()",
            "5,7,0" to "var a = new Uint8Array(3); var o = Object.create({ 1: 7 }); o.length = 3; o[0] = 5; a.set(o); a.join()",
            "0,9,0" to "var a = new Uint8Array(3); a.set({ length: 2, get 0() { return 9 } }, 1); a.join()",
            "off,TypeError" to "var log = []; var a = new Uint8Array(2); a.buffer.transfer(); try { a.set({ get length() { log.push('len'); return 0 } }, { valueOf: function () { log.push('off'); return 0 } }) } catch (e) { log.push(e.name) } log.join()",
            "off,TypeError" to "var log = []; try { new Uint8Array(2).set(undefined, { valueOf: function () { log.push('off'); return 0 } }) } catch (e) { log.push(e.name) } log.join()",
            "RangeError|RangeError|RangeError" to "[[-1], [Infinity], [2]].map(function (o) { try { new Uint8Array(2).set([1], o[0]); return 'returned' } catch (e) { return e.name } }).join('|')",
            "0,0" to "var a = new Uint8Array(2); a.set([], 2); a.join()",
            "1,2,3" to "var a = new Uint8Array(3); a.set([1, 2, 3], 0.9); a.join()",
            "9,0|9,0|0,9" to "[NaN, -0, '1'].map(function (o) { var a = new Uint8Array(2); a.set([9], o); return a.join() }).join('|')",
            "1,2" to "var a = new BigInt64Array(2); a.set([1n, 2n]); a.join()",
            "TypeError" to "try { new BigInt64Array(2).set([1]) } catch (e) { e.name }",
            "2,3" to "var a = new Uint8Array(2); a.set(new Float64Array([2.5, 3.9])); a.join()",
        )
        KiteJs().use { js ->
            for ((expected, source) in cases) {
                assertEquals(expected, js.evaluate(source).asString(), source)
            }
        }
    }

    @Test
    fun the_engine_carries_on_after_a_set_with_no_source() {
        KiteJs().use { js ->
            assertEquals("TypeError", js.evaluate("try { new Float32Array(1).set() } catch (e) { e.name }").asString())
            assertEquals("1,2", js.evaluate("var a = new Int8Array(2); a.set([1, 2]); a.join()").asString())
        }
    }
}
