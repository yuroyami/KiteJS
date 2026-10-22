/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An arrow function takes a rest parameter, a name or a pattern, as the last of its parameters
 * (ECMAScript 2015, 14.2). Each case's answer is what V8 gives.
 */
class ArrowRestParameterTest {

    private val evaluated = listOf(
        "((...a) => a.length)(1, 2)" to "2",
        "((x, ...a) => a.length)(1, 2, 3)" to "2",
        "(({ a }, ...b) => b.length)({}, 2, 3)" to "2",
        "((...a) => a)().length" to "0",
        "((a, ...b) => 0).length" to "1",
        "((...[a, b]) => a + b)(1, 2)" to "3",
        "((...{ length }) => length)(1, 2, 3)" to "3",
        "((a = 5, ...b) => a + b.length)(undefined, 1, 1)" to "7",
        "((...a) => Array.isArray(a))()" to "true",
        "(async (...a) => a.length)(1, 2) instanceof Promise" to "true",
        "(() => { const u = (...args) => Uint8Array.of(...args); return u(1, 2).length })()" to "2",
        "((...a) => { 'use strict'; return a.length })(1)" to "throws SyntaxError",
    )

    /** Source parsed as a function body, and whether that parses. */
    private val parsed = listOf(
        "(...a)" to "throws SyntaxError",
        "((...a, b) => 1)" to "throws SyntaxError",
        "((...a = 1) => 1)" to "throws SyntaxError",
        "((a, ...b,) => 1)" to "throws SyntaxError",
        "((...a)\n=> 1)" to "throws SyntaxError",
        "(((...a)) => 1)" to "throws SyntaxError",
        "((...a.b) => 1)" to "throws SyntaxError",
        "((...1) => 1)" to "throws SyntaxError",
        "((...a) => 1, ...b)" to "throws SyntaxError",
    )

    @Test
    fun everyCaseAnswersAsV8Does() {
        val failures = mutableListOf<String>()
        KiteJs(Rhino).use { js ->
            js.global["evaluated"] = evaluated.map { it.first }
            js.global["parsed"] = parsed.map { it.first }
            val answers = js.evaluate(
                "evaluated.map(function (c) { try { return JSON.stringify((0, eval)(c)); } " +
                    "catch (e) { return 'throws ' + e.constructor.name; } })",
            ).asArray()
            for ((i, case) in evaluated.withIndex()) {
                val actual = answers[i].asString()
                if (actual != case.second) failures += "${case.first}: expected ${case.second}, was $actual"
            }
            val parses = js.evaluate(
                "parsed.map(function (c) { try { new Function(c); return 'parses'; } " +
                    "catch (e) { return 'throws ' + e.constructor.name; } })",
            ).asArray()
            for ((i, case) in parsed.withIndex()) {
                val actual = parses[i].asString()
                if (actual != case.second) failures += "${case.first}: expected ${case.second}, was $actual"
            }
        }
        assertEquals(emptyList(), failures)
    }
}
