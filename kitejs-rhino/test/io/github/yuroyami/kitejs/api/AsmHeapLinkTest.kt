/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import io.github.yuroyami.kitejs.rhino.asmReports
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Linking must preserve the construction errors of every declared whole-buffer view. */
class AsmHeapLinkTest {
    private val lengths = listOf(0, 1, 2, 3, 4, 7, 8, 15, 16)

    private fun check(
        length: Int, declarations: String, result: String, expected: String, linked: Boolean,
        setup: String = "", effects: String = "",
    ) {
        for (budget in listOf(0, 1_000_000)) for (enabled in listOf(false, true)) {
            KiteJs(Rhino) { asmJs = enabled; instructionBudget = budget }.use { js ->
                val source = """
                    var events = [], result = '', stdlib = {
                      Int8Array: Int8Array, Uint8Array: Uint8Array,
                      Int16Array: Int16Array, Uint16Array: Uint16Array,
                      Int32Array: Int32Array, Uint32Array: Uint32Array,
                      Float32Array: Float32Array, Float64Array: Float64Array
                    }, imports = {};
                    $setup
                    try {
                      var m = (function (stdlib, imports, heap) {
                        "use asm";
                        $declarations
                        function f() { return $result; }
                        return { f: f };
                      })(stdlib, imports, new ArrayBuffer($length));
                      result = 'ok|' + m.f();
                    } catch (e) { result = e.name; }
                    result $effects;
                """.trimIndent()
                assertEquals(expected, js.evaluate(source, "asm-heap-link.js").asString(),
                    "length=$length asmJs=$enabled budget=$budget: $declarations")
                if (enabled) {
                    val report = js.asmReports.single()
                    assertTrue(report.compiled, "$report")
                    assertEquals(linked, report.linked, "$report")
                    if (!linked) assertTrue(report.reason.isNotEmpty(), "$report")
                }
                assertEquals(2, js.evaluate("1 + 1").asInt())
            }
        }
    }

    @Test
    fun integerViewsRespectTheirWholeBufferWidths() {
        for ((name, width) in listOf(
            "Int8Array" to 1, "Uint8Array" to 1, "Int16Array" to 2,
            "Uint16Array" to 2, "Int32Array" to 4, "Uint32Array" to 4,
        )) for (length in lengths) {
            val valid = length % width == 0
            check(length, "var H = new stdlib.$name(heap);", "H[0] | 0",
                if (valid) "ok|0" else "RangeError", linked = valid)
        }
    }

    @Test
    fun floatingViewsRespectTheirWidthsIncludingAnEmptyHeap() {
        for ((name, width) in listOf("Float32Array" to 4, "Float64Array" to 8)) for (length in lengths) {
            val valid = length % width == 0
            check(length, "var H = new stdlib.$name(heap);", "+H[0]",
                if (!valid) "RangeError" else if (length == 0) "ok|NaN" else "ok|0", linked = valid)
        }
    }

    @Test
    fun allMixedViewDeclarationsAreCheckedInEitherOrder() {
        val views = listOf(
            "var B = new stdlib.Uint8Array(heap);",
            "var W = new stdlib.Int32Array(heap);",
            "var D = new stdlib.Float64Array(heap);",
        )
        for (declarations in listOf(views, views.reversed())) for (length in lengths) {
            val valid = length % 8 == 0
            check(length, declarations.joinToString("\n"), "B[0] | 0",
                if (valid) "ok|0" else "RangeError", linked = valid)
        }
    }

    @Test
    fun importsBeforeAFailingViewKeepTheirOriginalEffectsAndOrder() {
        check(3, "var n = +imports.n; var H = new stdlib.Int32Array(heap);", "H[0] | 0",
            "RangeError|number,constructor", linked = false,
            setup = """
                imports.n = { valueOf: function () { events.push('number'); return 7; } };
                Object.defineProperty(stdlib, 'Int32Array', {
                  get: function () { events.push('constructor'); return Int32Array; }
                });
            """.trimIndent(), effects = "+ '|' + events.join()")
        check(4, "var n = +imports.n; var W = new stdlib.Int32Array(heap); var D = new stdlib.Float64Array(heap);",
            "W[0] | 0", "RangeError|number", linked = false,
            setup = "imports.n = { valueOf: function () { events.push('number'); return 7; } };",
            effects = "+ '|' + events.join()")
    }
}
