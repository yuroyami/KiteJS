/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Many globals are built the first time something reads them. Their property descriptors have to
 * build them too, or the descriptor's value is the engine's placeholder, which no script can touch
 * (D-75, issue 66). Each engine here is fresh, so nothing has read the globals before.
 */
class LazyGlobalDescriptorTest {

    private fun eval(source: String): String = KiteJs().use { js -> js.evaluate(source).asString() }

    @Test
    fun the_descriptor_of_a_lazy_global_holds_the_built_in() {
        for (name in listOf("JSON", "Math", "Reflect")) {
            assertEquals("object", eval("typeof Object.getOwnPropertyDescriptor(globalThis, '$name').value"), name)
        }
        for (name in listOf("Map", "Set", "Promise", "Proxy", "BigInt", "WeakMap", "ArrayBuffer", "Float64Array", "DataView", "RegExp")) {
            assertEquals("true", eval("Object.getOwnPropertyDescriptor(globalThis, '$name').value === $name"), name)
        }
    }

    @Test
    fun every_global_descriptor_agrees_with_a_read() {
        val script = "var r = []; for (var k of Object.getOwnPropertyNames(globalThis)) {" +
            " var d = Object.getOwnPropertyDescriptor(globalThis, k);" +
            " if ('value' in d && d.value !== globalThis[k] && k !== 'NaN' && k !== 'r' && k !== 'd' && k !== 'k') r.push(k) } r.join()"
        assertEquals("", eval(script))
        assertEquals("true", eval("var d = Object.getOwnPropertyDescriptors(globalThis); d.Map.value === Map && d.JSON.value === JSON"))
    }

    @Test
    fun the_unscopables_descriptor_holds_its_object() {
        assertEquals("true", eval("Object.getOwnPropertyDescriptor(Array.prototype, Symbol.unscopables).value.flat"))
    }
}
