/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals

/** A host accessor with no getter reads as undefined and describes itself with `get: undefined`. */
class SetterOnlyAccessorTest {

    @Test
    fun a_setter_only_host_accessor_reads_as_undefined() {
        KiteJs(Rhino).use { js ->
            var written = 0.0
            js.global.setter("sink") { v -> written = v.asDouble() }
            assertEquals("undefined", js.evaluate("sink = 3; typeof sink").asString())
            assertEquals(3.0, written)
            assertEquals(
                "true,true,function",
                js.evaluate(
                    "var d = Object.getOwnPropertyDescriptor(globalThis, 'sink');" +
                        " ['get' in d, d.get === undefined, typeof d.set].join()",
                ).asString(),
            )
        }
    }
}
