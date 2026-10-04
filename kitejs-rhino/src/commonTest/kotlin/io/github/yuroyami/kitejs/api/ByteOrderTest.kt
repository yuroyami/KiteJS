/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The byte order of typed array views. ECMAScript 2015, 24.1.1.5 GetValueFromBuffer leaves the
 * order to the implementation, and every engine a script is written for is little-endian.
 * Emscripten output depends on it: a pointer written through `Int32Array` is read back byte by
 * byte through `Uint8Array`.
 */
class ByteOrderTest {

    @Test
    fun views_are_little_endian_by_default() {
        KiteJs().use { js ->
            assertEquals(
                4.0,
                js.evaluate("var b = new ArrayBuffer(4); new Int32Array(b)[0] = 0x01020304; new Uint8Array(b)[0]").asDouble(),
            )
            assertEquals(
                -255.0,
                js.evaluate(
                    "var b = new ArrayBuffer(8); var i8 = new Int8Array(b), i32 = new Int32Array(b);" +
                        " i32[0] = -1; i8[0] = 1; i32[0]",
                ).asDouble(),
            )
        }
    }

    @Test
    fun a_host_can_ask_for_the_old_order() {
        KiteJs { littleEndian = false }.use { js ->
            assertEquals(
                1.0,
                js.evaluate("var b = new ArrayBuffer(4); new Int32Array(b)[0] = 0x01020304; new Uint8Array(b)[0]").asDouble(),
            )
        }
    }

    /** The order belongs to the engine, not to the process: the first engine must not decide for the next. */
    @Test
    fun the_order_does_not_leak_from_one_engine_to_the_next() {
        val source = "var b = new ArrayBuffer(4); new Int32Array(b)[0] = 0x01020304; new Uint8Array(b)[0]"
        KiteJs { littleEndian = false }.use { js -> assertEquals(1.0, js.evaluate(source).asDouble()) }
        KiteJs().use { js -> assertEquals(4.0, js.evaluate(source).asDouble()) }
        KiteJs { littleEndian = false }.use { js -> assertEquals(1.0, js.evaluate(source).asDouble()) }
    }

    /** A script that needs a fixed order has DataView, whatever the engine's default is. */
    @Test
    fun data_view_keeps_taking_the_order_per_call() {
        KiteJs().use { js ->
            assertEquals(
                "16909060,67305985",
                js.evaluate(
                    "var d = new DataView(new ArrayBuffer(4)); d.setInt32(0, 0x01020304, false);" +
                        " d.getInt32(0, false) + ',' + d.getInt32(0, true)",
                ).asString(),
            )
        }
    }
}
