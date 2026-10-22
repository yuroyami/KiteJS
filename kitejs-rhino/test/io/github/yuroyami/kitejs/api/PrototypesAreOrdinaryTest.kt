/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Date.prototype and RegExp.prototype are ordinary objects, not a Date and a RegExp (ECMAScript
 * 2015, 20.3.4 and 21.2.5), and source, flags and the flag booleans are accessors on
 * RegExp.prototype. Each case's answer is what V8 gives.
 */
class PrototypesAreOrdinaryTest {

    private val cases = listOf(
        "Object.prototype.toString.call(Date.prototype)" to "\"[object Object]\"",
        "Date.prototype.getTime()" to "throws TypeError",
        "String(Date.prototype)" to "throws TypeError",
        "new Date(Date.prototype)" to "throws TypeError",
        "new Date(5).getTime()" to "5",
        "Date.prototype.toJSON === Date.prototype.toJSON" to "true",
        "typeof Date.prototype[Symbol.toPrimitive]" to "\"function\"",
        "'abc'.replace(RegExp.prototype, 'x')" to "throws TypeError",
        "RegExp.prototype[Symbol.replace].call(RegExp.prototype, 'abc', 'x')" to "throws TypeError",
        "RegExp.prototype.flags" to "\"\"",
        "RegExp.prototype.toString()" to "\"/(?:)/\"",
        "RegExp.prototype.source" to "\"(?:)\"",
        "String(RegExp.prototype.global)" to "\"undefined\"",
        "Object.getOwnPropertyNames(/a/g).join()" to "\"lastIndex\"",
        "JSON.stringify(Object.getOwnPropertyDescriptor(RegExp.prototype, 'source'), (k, v) => typeof v === 'function' ? 'fn' : v)" to "\"{\\\"get\\\":\\\"fn\\\",\\\"enumerable\\\":false,\\\"configurable\\\":true}\"",
        "Reflect.get(RegExp.prototype, 'source', /xy/)" to "\"xy\"",
        "/a/yu.flags" to "\"uy\"",
        "String(/a/yu)" to "\"/a/uy\"",
        "Object.prototype.toString.call(RegExp.prototype)" to "\"[object Object]\"",
        "RegExp.prototype.exec.call(RegExp.prototype, '')" to "throws TypeError",
        "RegExp.prototype.test.call(RegExp.prototype, '')" to "throws TypeError",
        "Object.getOwnPropertyDescriptor(RegExp.prototype, 'global').get.call(/a/g)" to "true",
        "Object.getOwnPropertyDescriptor(RegExp.prototype, 'global').get.call({})" to "throws TypeError",
        "new RegExp('\\n').source" to "\"\\\\n\"",
        "new RegExp('').source" to "\"(?:)\"",
        "String(new RegExp(''))" to "\"/(?:)/\"",
        "RegExp(RegExp.prototype) === RegExp.prototype" to "true",
        "new RegExp(RegExp.prototype).source" to "\"(?:)\"",
        "String(new RegExp({ [Symbol.match]: true, source: 'b+', flags: 'g' }))" to "\"/b+/g\"",
        "'a'.startsWith(RegExp.prototype)" to "throws TypeError",
        "'a'.includes({ [Symbol.match]: true })" to "throws TypeError",
        "'lastIndex' in RegExp.prototype" to "false",
        "(() => { class R extends RegExp {} ; var r = new R('a', 'g'); return r.flags + r.global + (r instanceof R) })()" to "\"gtruetrue\"",
        "Object.getOwnPropertyDescriptor(RegExp.prototype, 'flags').get.call({ global: 1, sticky: 'x', hasIndices: true })" to "\"dgy\"",
        "new RegExp('/').source" to "\"\\\\/\"",
        "String(new RegExp('a/b', 'g'))" to "\"/a\\\\/b/g\"",
        "/[/]/.source" to "\"[/]\"",
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
