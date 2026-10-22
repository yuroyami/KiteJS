/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Float16Array and DataView's getFloat16 and setFloat16 (ECMAScript 2025). Each answer is what V8
 * gives, except the sort, where Node 22's experimental Float16Array is wrong and the spec's
 * numeric order is pinned instead.
 */
class Float16ArrayTest {

    private val cases = listOf(
        "typeof Float16Array" to "\"function\"",
        "Float16Array.BYTES_PER_ELEMENT" to "2",
        "Float16Array.prototype.BYTES_PER_ELEMENT" to "2",
        "Float16Array.name" to "\"Float16Array\"",
        "Float16Array.length" to "3",
        "Object.getPrototypeOf(Float16Array) === Object.getPrototypeOf(Int8Array)" to "true",
        "Object.prototype.toString.call(new Float16Array(1))" to "\"[object Float16Array]\"",
        "new Float16Array(4).byteLength" to "8",
        "Array.from(new Float16Array([1.337, -2.5, 65520, 1e-8, NaN, -0]), String).join()" to "\"1.3369140625,-2.5,Infinity,0,NaN,0\"",
        "new Float16Array([1, 2, 3]).map(x => x / 3).join()" to "\"0.333251953125,0.66650390625,1\"",
        "Float16Array.of(0.1, 0.2).join()" to "\"0.0999755859375,0.199951171875\"",
        "Float16Array.from([1.0009765625, 1.00048828125]).join()" to "\"1.0009765625,1\"",
        "(() => { var f = new Float16Array(2); f[0] = 70000; f[1] = -1e-10; return Object.is(f[1], -0) + ',' + f[0] })()" to "\"true,Infinity\"",
        "new Uint16Array(new Float16Array([NaN, 1, -2, 0.5]).buffer).join()" to "\"32256,15360,49152,14336\"",
        "new Float16Array(new Uint16Array([0x3c00, 0x7bff, 0x0001, 0xfc00]).buffer).join()" to "\"1,65504,5.960464477539063e-8,-Infinity\"",
        "(() => { var dv = new DataView(new ArrayBuffer(4)); dv.setFloat16(0, 1.5); dv.setFloat16(2, -0.333, true); return [dv.getUint8(0), dv.getUint8(1), dv.getFloat16(0), dv.getFloat16(2, true), dv.getFloat16(2)].join() })()" to "\"62,0,1.5,-0.3330078125,75.3125\"",
        "DataView.prototype.getFloat16.length" to "1",
        "DataView.prototype.setFloat16.length" to "2",
        "typeof DataView.prototype.getFloat16" to "\"function\"",
        "(() => { try { new DataView(new ArrayBuffer(1)).getFloat16(0) } catch (e) { return e.name } })()" to "\"RangeError\"",
        "new Float16Array(new Float64Array([1.0009765625, 3.14159])).join()" to "\"1.0009765625,3.140625\"",
        "new Float64Array(new Float16Array([3.14159])).join()" to "\"3.140625\"",
        "(() => { try { new Float16Array(new BigInt64Array(1)) } catch (e) { return e.name } })()" to "\"TypeError\"",
        "new Float16Array([3, 1, 2, -0, 0, NaN, -Infinity]).sort().join()" to "\"-Infinity,0,0,1,2,3,NaN\"",
        "new Float16Array([1, 2, 3, 4]).subarray(1, 3).join()" to "\"2,3\"",
        "Math.f16round(1.337)" to "1.3369140625",
        "Math.f16round(65520)" to "null",
        "Math.f16round(5.960464477539063e-8)" to "5.960464477539063e-8",
        "Math.f16round(2.9802322387695312e-8)" to "0",
        "Object.is(Math.f16round(-1e-10), -0)" to "true",
        "new Float16Array([1, 2]).includes(2)" to "true",
        "new Float16Array([0.1]).indexOf(0.1)" to "-1",
        "JSON.stringify(new Float16Array([0.5, 1.5]))" to "\"{\\\"0\\\":0.5,\\\"1\\\":1.5}\"",
    )

    @Test
    fun everyCaseAnswersAsV8Does() {
        val failures = mutableListOf<String>()
        KiteJs(Rhino).use { js ->
            js.global["cases"] = cases.map { it.first }
            val answers = js.evaluate(
                "cases.map(function (c) { try { return JSON.stringify((0, eval)(c)); } " +
                    "catch (e) { return 'throws ' + e.constructor.name; } })",
            ).asArray()
            for ((i, case) in cases.withIndex()) {
                val actual = answers[i].asString()
                if (actual != case.second) failures += "${case.first}: expected ${case.second}, was $actual"
            }
        }
        assertEquals(emptyList(), failures)
    }
}
