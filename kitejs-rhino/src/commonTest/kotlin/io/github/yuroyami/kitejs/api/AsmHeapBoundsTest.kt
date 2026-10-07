/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import io.github.yuroyami.kitejs.rhino.asmReports
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A script-controlled byte address must stay inside the heap, including near Int.MAX_VALUE. */
class AsmHeapBoundsTest {
    private data class View(val name: String, val shift: Int, val floating: Boolean = false)

    private val views = listOf(
        View("Int8Array", 0), View("Uint8Array", 0),
        View("Int16Array", 1), View("Uint16Array", 1),
        View("Int32Array", 2), View("Uint32Array", 2),
        View("Float32Array", 2, true), View("Float64Array", 3, true),
    )

    private fun withHeap(view: View, size: Int = 64, check: (KiteJs) -> Unit) {
        for (budget in listOf(0, 1_000_000)) for (enabled in listOf(false, true)) {
            KiteJs(Rhino) { asmJs = enabled; instructionBudget = budget }.use { js ->
                val read = if (view.floating) "+H[i >> ${view.shift}]" else "H[i >> ${view.shift}] | 0"
                val coerce = if (view.floating) "+v" else "v | 0"
                js.evaluate("""
                    var heap = new ArrayBuffer($size);
                    var bytes = new Uint8Array(heap);
                    var m = (function (s, f, h) {
                      "use asm";
                      var H = new s.${view.name}(h);
                      function read(i) { i = i | 0; return $read; }
                      function write(i, v) { i = i | 0; v = $coerce; H[i >> ${view.shift}] = v; }
                      return { read: read, write: write };
                    })({ ${view.name}: ${view.name} }, {}, heap);
                """.trimIndent(), "asm-heap-bounds.js")
                if (enabled) {
                    val report = js.asmReports.single()
                    assertTrue(report.compiled && report.linked, "${view.name}: $report")
                }
                check(js)
                assertEquals(2, js.evaluate("1 + 1").asInt())
            }
        }
    }

    private fun invalidAddresses(view: View, size: Int): List<Int> = listOf(
        Int.MIN_VALUE, -8, -1, size, size + 8,
        Int.MAX_VALUE and ((1 shl view.shift) - 1).inv(), Int.MAX_VALUE,
    ).distinct()

    @Test
    fun integerReadsOutsideTheHeapStayZero() {
        for (view in views.filter { !it.floating }) withHeap(view) { js ->
            for (address in invalidAddresses(view, 64)) {
                assertEquals(0, js.evaluate("m.read($address)").asInt(), "${view.name} at $address")
            }
        }
    }

    @Test
    fun floatingReadsOutsideTheHeapStayNaN() {
        for (view in views.filter { it.floating }) withHeap(view) { js ->
            for (address in invalidAddresses(view, 64)) {
                val answer = js.evaluate("m.read($address)").asDouble()
                assertTrue(answer.isNaN(), "${view.name} at $address: $answer")
            }
        }
    }

    @Test
    fun invalidStoresLeaveEveryHeapByteUnchanged() {
        for (view in views) withHeap(view) { js ->
            js.evaluate("for (var i = 0; i < bytes.length; i++) bytes[i] = (i * 37 + 11) & 255;")
            val before = js.evaluate("Array.prototype.join.call(bytes, ',')").asString()
            for (address in invalidAddresses(view, 64)) {
                js.evaluate("m.write($address, ${if (view.floating) "123.75" else "305419896"})")
                assertEquals(before, js.evaluate("Array.prototype.join.call(bytes, ',')").asString(),
                    "${view.name} store at $address")
            }
        }
    }

    @Test
    fun theFinalValidElementStillReadsAndWritesCorrectly() {
        for (view in views) withHeap(view) { js ->
            val address = 64 - (1 shl view.shift)
            val values = if (view.floating) "[-0, 1.1, -123.75, Infinity, NaN]"
                else "[123, -123, 2147483647, -2147483648]"
            assertTrue(js.evaluate("""
                (function () {
                  var values = $values;
                  var reference = new ${view.name}(1);
                  for (var i = 0; i < values.length; i++) {
                    reference[0] = values[i];
                    m.write($address, values[i]);
                    var expected = ${if (view.floating) "+reference[0]" else "reference[0] | 0"};
                    if (!Object.is(expected, m.read($address))) return false;
                    for (var b = 0; b < $address; b++) if (bytes[b] !== 0) return false;
                  }
                  return true;
                })()
            """.trimIndent()).asBoolean(), view.name)
        }
    }

    @Test
    fun emptyAndOneElementHeapsKeepTheSameBounds() {
        for (view in views) {
            withHeap(view, size = 0) { js ->
                for (address in invalidAddresses(view, 0)) {
                    val answer = js.evaluate("m.read($address)")
                    when {
                        !view.floating -> assertEquals(0, answer.asInt())
                        else -> assertTrue(answer.asDouble().isNaN())
                    }
                    js.evaluate("m.write($address, 17)")
                    assertEquals(0, js.evaluate("bytes.length").asInt())
                }
            }
            withHeap(view, size = 1 shl view.shift) { js ->
                js.evaluate("m.write(0, 17)")
                assertEquals(17.0, js.evaluate("m.read(0)").asDouble())
                js.evaluate("m.write(${1 shl view.shift}, 99)")
                assertEquals(17.0, js.evaluate("m.read(0)").asDouble())
            }
        }
    }

    @Test
    fun floatingReadCoercionsAndLocalCallsKeepNaN() {
        for (budget in listOf(0, 1_000_000)) for (enabled in listOf(false, true)) {
            KiteJs(Rhino) { asmJs = enabled; instructionBudget = budget }.use { js ->
                js.evaluate("""
                    var m = (function (s, f, h) {
                      "use asm";
                      var F = new s.Float32Array(h);
                      var round = s.Math.fround;
                      function double(i) { i = i | 0; return +F[i >> 2]; }
                      function float(i) { i = i | 0; return round(F[i >> 2]); }
                      function direct(i) { i = i | 0; return +double(i | 0); }
                      function indirect(i) { i = i | 0; return +D[i & 1](i | 0); }
                      var D = [double, double];
                      return { double: double, float: float, direct: direct, indirect: indirect };
                    })({ Float32Array: Float32Array, Math: Math }, {}, new ArrayBuffer(64));
                """.trimIndent())
                if (enabled) {
                    val report = js.asmReports.single()
                    assertTrue(report.compiled && report.linked, "$report")
                }
                for (address in listOf(-1, 64, 68, Int.MAX_VALUE)) {
                    for (name in listOf("double", "float", "direct", "indirect")) {
                        assertTrue(js.evaluate("m.$name($address)").asDouble().isNaN(), "$name at $address")
                    }
                }
            }
        }
    }

    @Test
    fun validFloat32BitsKeepTheirValuesAndZeroSign() {
        withHeap(views.first { it.name == "Float32Array" }) { js ->
            assertTrue(js.evaluate("""
                (function () {
                  var bits = [0x80000000, 0, 0x3dcccccd, 1, 0x007fffff, 0x00800000,
                              0x7f7fffff, 0x7f800000, 0xff800000, 0x7fc00001, 0x7fa00001];
                  var words = new Uint32Array(heap);
                  var reference = new Float32Array(heap);
                  for (var i = 0; i < bits.length; i++) {
                    words[i] = bits[i];
                    if (!Object.is(reference[i], m.read(i * 4))) return false;
                  }
                  return Object.is(m.read(0), -0) && 1 / m.read(0) === -Infinity;
                })()
            """.trimIndent()).asBoolean())
        }
    }
}
