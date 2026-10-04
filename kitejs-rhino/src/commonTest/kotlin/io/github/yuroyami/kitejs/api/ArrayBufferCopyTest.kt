/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `ArrayBuffer.prototype.slice`, `transfer` and `transferToFixedLength` (issues 24 and 25, D-87).
 * Slice checks both buffers again after every piece of user code it runs, and transfer copies into
 * a plain ArrayBuffer of its own realm without asking species. Every expected value here is what
 * V8 answers.
 */
class ArrayBufferCopyTest {

    private fun eval(source: String): String = KiteJs().use { js -> js.evaluate(source).asString() }

    /** What [call] did, as the name of the error it threw or `returned`. */
    private fun outcome(setup: String, call: String): String =
        eval("$setup; try { $call; 'returned' } catch (e) { e.name }")

    /** A four byte buffer holding 1 to 4 whose species constructor runs [body]. */
    private fun withSpecies(body: String): String =
        "var a = new ArrayBuffer(4); new Uint8Array(a).set([1, 2, 3, 4]);" +
            " a.constructor = {}; a.constructor[Symbol.species] = function (n) { $body }"

    @Test
    fun a_source_detached_while_slicing_is_a_type_error() {
        val detach = "a.transfer()"
        for ((setup, call) in listOf(
            "var a = new ArrayBuffer(2)" to "a.slice({ valueOf: function () { $detach; return 0 } })",
            "var a = new ArrayBuffer(2)" to "a.slice(0, { valueOf: function () { $detach; return 2 } })",
            "var a = new ArrayBuffer(0)" to "a.slice({ valueOf: function () { $detach; return 0 } })",
            withSpecies("var b = new ArrayBuffer(n); $detach; return b") to "a.slice(0, 2)",
            "var a = new ArrayBuffer(4); Object.defineProperty(a, 'constructor', { get: function () { $detach; return undefined } })"
                to "a.slice(0, 2)",
            "var a = new ArrayBuffer(4); var C = {}; Object.defineProperty(C, Symbol.species, { get: function () { $detach; return undefined } }); a.constructor = C"
                to "a.slice(0, 2)",
        )) {
            assertEquals("TypeError", outcome(setup, call), setup)
        }
        // The engine is still usable after each of them.
        assertEquals("2", eval("var a = new ArrayBuffer(2); try { a.slice({ valueOf: function () { a.transfer(); return 0 } }) } catch (e) {} String(new ArrayBuffer(2).slice().byteLength)"))
    }

    @Test
    fun the_species_result_must_be_a_fresh_attached_buffer_that_is_long_enough() {
        for (body in listOf(
            "var b = new ArrayBuffer(n); b.transfer(); return b",
            "return new ArrayBuffer(1)",
            "return a",
            "return new Uint8Array(n)",
            "return {}",
        )) {
            assertEquals("TypeError", outcome(withSpecies(body), "a.slice(0, 2)"), body)
        }
        // A longer result is fine, and only the sliced bytes are written into it.
        assertEquals(
            "2,3,0,0|4",
            eval(withSpecies("return new ArrayBuffer(n + 2)") + "; Array.prototype.join.call(new Uint8Array(a.slice(1, 3))) + '|' + a.byteLength"),
        )
    }

    @Test
    fun slice_clamps_its_indices_and_defaults_to_the_realms_own_constructor() {
        assertEquals(
            "0,4,0,7",
            eval(
                "var a = new ArrayBuffer(8); [a.slice(-Infinity, NaN).byteLength, a.slice(2, -2).byteLength," +
                    " a.slice(6, 2).byteLength, a.slice('1', undefined).byteLength].join()",
            ),
        )
        assertEquals(
            "2,3|true",
            eval(
                "var a = new ArrayBuffer(4); new Uint8Array(a).set([1, 2, 3, 4]); var AB = ArrayBuffer;" +
                    " ArrayBuffer = function () { throw new Error('global') }; var b = a.slice(1, -1);" +
                    " Array.prototype.join.call(new Uint8Array(b)) + '|' + (b instanceof AB)",
            ),
        )
    }

    @Test
    fun transfer_never_asks_species() {
        assertEquals(
            "true:false:1,2,3,4",
            eval(withSpecies("return a") + "; var b = a.transfer(); a.detached + ':' + b.detached + ':' + Array.prototype.join.call(new Uint8Array(b))"),
        )
        assertEquals(
            "true:2",
            eval("var a = new ArrayBuffer(2); Object.defineProperty(a, 'constructor', { get: function () { throw new Error('read') } }); var b = a.transfer(); a.detached + ':' + b.byteLength"),
        )
        assertEquals(
            "0",
            eval(
                "var log = []; var C = {}; Object.defineProperty(C, Symbol.species, { get: function () { log.push('species') } });" +
                    " var a = new ArrayBuffer(2); a.constructor = C; a.transfer();" +
                    " a = new ArrayBuffer(2); a.constructor = C; a.transferToFixedLength(); String(log.length)",
            ),
        )
        assertEquals(
            "true|true",
            eval(
                "var AB = ArrayBuffer; var a = new AB(2); ArrayBuffer = function () { throw new Error('global') };" +
                    " var b = a.transfer(); (b instanceof AB) + '|' + (Object.getPrototypeOf(b) === AB.prototype)",
            ),
        )
    }

    @Test
    fun transfer_keeps_the_bytes_and_zero_fills_or_truncates() {
        assertEquals(
            "7,8,0,0:true",
            eval("var a = new ArrayBuffer(2); new Uint8Array(a).set([7, 8]); var b = a.transferToFixedLength(4); Array.prototype.join.call(new Uint8Array(b)) + ':' + a.detached"),
        )
        assertEquals(
            "1,2",
            eval("var a = new ArrayBuffer(4); new Uint8Array(a).set([1, 2, 3, 4]); Array.prototype.join.call(new Uint8Array(a.transfer(2)))"),
        )
        assertEquals(
            "0:true:0",
            eval("var a = new ArrayBuffer(4); var v = new Uint8Array(a); var b = a.transfer(0); b.byteLength + ':' + a.detached + ':' + v.length"),
        )
    }

    @Test
    fun transfer_converts_the_length_before_checking_the_source() {
        // ToIndex runs first, so its error wins over the detached check.
        assertEquals(
            "len",
            eval("var a = new ArrayBuffer(2); a.transfer(); try { a.transfer({ valueOf: function () { throw new Error('len') } }) } catch (e) { e.message }"),
        )
        for (call in listOf("a.transfer()", "a.transferToFixedLength(1)")) {
            assertEquals("TypeError", outcome("var a = new ArrayBuffer(2); a.transfer()", call), call)
        }
        // ToIndex truncates toward zero, so a small negative fraction is a length of zero.
        assertEquals("ok:true", eval("var a = new ArrayBuffer(2); try { a.transfer(-0.5); 'ok:' + a.detached } catch (e) { e.name }"))
        for (length in listOf("-1", "Infinity")) {
            assertEquals(
                "RangeError:false",
                eval("var a = new ArrayBuffer(2); try { a.transfer($length) } catch (e) { e.name + ':' + a.detached }"),
                length,
            )
        }
    }
}
