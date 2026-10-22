/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.LanguageVersion
import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals

/** Modern expectations checked against Node 26.10.0 (V8). */
class YieldIdentifierTest {
    private val evaluated = listOf(
        "(function*(){yield (function yield(){return 30})()})().next().value" to "30",
        "(function*(){yield (function y\\u0069eld(){return 31})()})().next().value" to "31",
        "(function*(){yield typeof (async function yield(){})})().next().value" to "function",
        "(function(){function* yield(){yield 23};return yield().next().value})()" to "23",
        "(function* f(a=function*(){yield 24}){yield a().next().value})().next().value" to "24",
        "(function* f(a=function(yield){return yield}){yield a(25)})().next().value" to "25",
        "(function*(){yield ({m(){var yield=26;return yield}}).m()})().next().value" to "26",
        "(function(){var yield=27;return (function* f(a=()=>yield){yield a()})().next().value})()" to "27",
        "(function*(){var o={yield:28};yield o.yield})().next().value" to "28",
        "(function*(){yield* [29]})().next().value" to "29",
        "(function(){var yield=4; return yield})()" to "4",
        "(function(yield){return yield})(5)" to "5",
        "(function yield(){return 6})()" to "6",
        "(function(){function yield(){return 7};return yield()})()" to "7",
        "(function(){var yield=1; yield+=2; yield++;return yield})()" to "4",
        "(function(){var yield=8;return ({yield}).yield})()" to "8",
        "(function(){try{throw 9}catch(yield){return yield}})()" to "9",
        "(function(){var n=0;yield:while(true){n++;break yield}return n})()" to "1",
        "(function(){var [yield]=[10];return yield})()" to "10",
        "(function(){var {x:yield}={x:11};return yield})()" to "11",
        "(function(){var y\\u0069eld=12;return y\\u0069eld})()" to "12",
        "((yield)=>yield+1)(12)" to "13",
        "(function(){var yield=14;return (()=>yield)()})()" to "14",
        "(function*(){function f(yield){return yield};yield f(15)})().next().value" to "15",
        "(function*(){yield (function(){var yield=16;return yield})()})().next().value" to "16",
        "(function(){var yield=17;return (function*(){yield (()=>yield)()})().next().value})()" to "17",
        "(function* yield(){yield 18})().next().value" to "throws SyntaxError",
        "(function(){async function f(){var yield=1;return yield};return f() instanceof Promise})()" to "true",
        "(function(){return {yield(){return 19}}.yield()})()" to "19",
        "(function(){return {'yield':20}.yield})()" to "20",
        "(function(){return {get yield(){return 21}}.yield})()" to "21",
        "(function(){yield})()" to "throws ReferenceError",
        "(function(){'use strict';return {yield:22}.yield})()" to "22",
    )

    private val parsed = listOf(
        "function* g(){function yield(){}}" to "throws SyntaxError",
        "'use strict';var yield=1" to "throws SyntaxError",
        "'use strict'\nyield" to "throws SyntaxError",
        "'use strict';yield=1" to "throws SyntaxError",
        "function f(){'use strict';yield}" to "throws SyntaxError",
        "function f(yield){'use strict'}" to "throws SyntaxError",
        "function yield(){'use strict'}" to "throws SyntaxError",
        "(yield)=>{'use strict';return yield}" to "throws SyntaxError",
        "function* f(yield){}" to "throws SyntaxError",
        "function* f(){var yield}" to "throws SyntaxError",
        "function* f(){var y\\u0069eld}" to "throws SyntaxError",
        "function* f(){y\\u0069eld 1}" to "throws SyntaxError",
        "function* f(){(yield)=>0}" to "throws SyntaxError",
        "function* f(){(y\\u0069eld)=>0}" to "throws SyntaxError",
        "function f(){yield 1}" to "throws SyntaxError",
        "class C {m(){yield}}" to "throws SyntaxError",
        "function* f(){function g(){'use strict';yield}}" to "throws SyntaxError",
        "function f(){function* g(a=yield 1){}}" to "throws SyntaxError",
        "function* f(a=yield 1){}" to "throws SyntaxError",
    )

    @Test
    fun modernGrammarMatchesV8() {
        val failures = mutableListOf<String>()
        KiteJs(Rhino).use { js ->
            js.global["evaluated"] = evaluated.map { it.first }
            js.global["parsed"] = parsed.map { it.first }
            val values = js.evaluate("""
                evaluated.map(function(source) {
                    try { return String((0, eval)(source)); }
                    catch (e) { return 'throws ' + e.name; }
                });
            """).asArray()
            val parses = js.evaluate("""
                parsed.map(function(source) {
                    try { new Function(source); return 'parses'; }
                    catch (e) { return 'throws ' + e.name; }
                });
            """).asArray()
            for ((cases, answers) in listOf(evaluated to values, parsed to parses)) {
                for ((i, case) in cases.withIndex()) {
                    val actual = answers[i].asString()
                    if (actual != case.second) failures += "${case.first}: expected ${case.second}, was $actual"
                }
            }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun legacyVersionsKeepImplicitGenerators() {
        KiteJs(Rhino) { languageVersion = LanguageVersion.ES5 }.use { js ->
            assertEquals("7", js.evaluate("function f() { yield 7; } String(f().next())").asString())
        }
    }
}
