/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mozilla.javascript.Context as UContext

/**
 * The phase 3 acceptance test: scripts run on both engines, and the result of the last expression
 * has to match, as does the name and message of anything thrown.
 *
 * Until the standard objects land, the port runs each script in a bare top-level scope, so this
 * first batch only uses what the language itself provides.
 */
class EvalOracleTest {

    private lateinit var ucx: UContext
    private lateinit var uscope: org.mozilla.javascript.Scriptable
    private lateinit var kscope: Scriptable

    @BeforeTest
    fun enter() {
        ucx = UContext.enter()
        ucx.languageVersion = UContext.VERSION_ES6
        ucx.isInterpretedMode = true
        uscope = ucx.initStandardObjects()
        val kcx = Context.enter()
        kcx.languageVersion = Context.VERSION_ES6
        kscope = TopLevel()
    }

    @AfterTest
    fun exit() {
        UContext.exit()
        Context.exit()
    }

    /** A result rendered the same way from either side. Objects render by their class name. */
    private fun render(v: Any?): String = when {
        v == null -> "null"
        v === org.mozilla.javascript.Undefined.instance || v === Undefined.instance -> "undefined"
        v is Boolean -> v.toString()
        v is Int -> v.toString()
        v is Number -> ScriptRuntime.numberToString(v.toDouble(), 10)
        v is CharSequence -> "\"" + v.toString() + "\""
        v is org.mozilla.javascript.Scriptable -> "[object " + v.className + "]"
        v is Scriptable -> "[object " + v.className + "]"
        else -> v.toString()
    }

    private fun upstream(source: String): String =
        try {
            render(ucx.evaluateString(uscope, source, "test.js", 1, null))
        } catch (e: org.mozilla.javascript.RhinoException) {
            "throws " + e.details()
        }

    private fun ported(source: String): String =
        try {
            render(Context.getContext().evaluateString(kscope, source, "test.js", 1, null))
        } catch (e: RhinoException) {
            "throws " + e.details()
        }

    private fun check(sources: List<String>) {
        val failures = mutableListOf<String>()
        for (source in sources) {
            val expected = upstream(source)
            val actual = try { ported(source) } catch (e: Throwable) { "CRASH $e" }
            if (expected != actual) failures.add("$source\n  upstream: $expected\n  ported:   $actual")
        }
        assertTrue(sources.size > 10)
        assertEquals(emptyList(), failures, "evaluation differs from upstream")
    }

    @Test
    fun arithmeticAndCoercion() = check(listOf(
        "1 + 2", "1 - 2", "2 * 3", "7 / 2", "7 % 3", "2 ** 10", "-5", "+'3'", "1 / 0", "-1 / 0", "0 / 0",
        "0.1 + 0.2", "1e21 + 1", "5 | 3", "5 & 3", "5 ^ 3", "~5", "1 << 3", "-16 >> 2", "-16 >>> 2",
        "'3' * '4'", "'3' + 4", "3 + '4'", "true + 1", "null + 1", "undefined + 1", "'a' + null",
        "1 + true + 'x'", "'x' + 1 + true", "1 == '1'", "1 === '1'", "null == undefined", "null === undefined",
        "0 == false", "'' == 0", "'0' == false", "1 < 2", "'a' < 'b'", "'10' < '9'", "10 < 9",
        "'10' < 9", "2 > 1 == true", "!0", "!!'x'", "!''", "typeof 1", "typeof 'a'", "typeof true",
        "typeof undefined", "typeof null", "typeof {}", "typeof function(){}", "void 0", "1, 2, 3",
        "var x = 5; x++; x", "var x = 5; ++x", "var x = 5; x--", "var x = 5; x += 3; x", "var x = 2; x *= x; x",
        "var x = 1; x = x || 5; x", "var x = 0; x = x || 5; x", "var x = 0; x = x && 5; x", "null ?? 'd'", "0 ?? 'd'",
        "true ? 'y' : 'n'", "false ? 'y' : 'n'", "0.5 + 0.25", "1e300 * 1e10", "123456789 * 987654321",
        "2147483647 + 1", "-2147483648 - 1", "5 % -3", "-5 % 3", "5.5 % 2", "2 ** -1", "'abc' < 'abd'",
    ))

    @Test
    fun stringsAndTemplates() = check(listOf(
        "'a' + 'b' + 'c'", "'x' + 1 + 2", "1 + 2 + 'x'", "'' + 1.5", "'' + 100", "'' + -0", "'' + 1e21", "'' + 1e-7",
        "'' + 0.000001", "'' + 123456789012345680000", "'' + (1/3)", "'' + true", "'' + null", "'' + undefined",
        "`a\${1 + 1}b`", "`\${'x'}\${'y'}`", "var n = 'w'; `hi \${n}!`", "'it\\'s'", "\"q\\\"q\"", "'\\u0041'",
        "'line\\nbreak'", "'a' == 'a'", "'a' === 'a'", "'a' + 'b' === 'ab'", "'abc' > 'abd'",
    ))

    @Test
    fun functionsAndClosures() = check(listOf(
        "function f(a, b) { return a + b } f(1, 2)",
        "function f(a) { return a } f()",
        "function f(a = 5) { return a } f()",
        "function f(a = 5) { return a } f(1)",
        "var f = function (x) { return x * 2 }; f(4)",
        "var f = x => x * 3; f(4)",
        "var f = (a, b) => { return a - b }; f(9, 4)",
        "function mk() { var c = 0; return function () { return ++c } } var g = mk(); g(); g(); g()",
        "function outer() { var x = 1; function inner() { return x + 1 } return inner() } outer()",
        "var add = function (a) { return function (b) { return a + b } }; add(2)(3)",
        "function fact(n) { return n <= 1 ? 1 : n * fact(n - 1) } fact(10)",
        "function fib(n) { return n < 2 ? n : fib(n - 1) + fib(n - 2) } fib(15)",
        "var o = { v: 7, m: function () { return this.v } }; o.m()",
        "var o = { v: 7, m() { return this.v } }; o.m()",
        "var o = { v: 7, m: () => typeof this }; o.m()",
        "function f() { return this === undefined } f()",
        "function f() { 'use strict'; return this === undefined } f()",
        "var f = function named() { return typeof named }; f()",
        "function f() { return f.name } f()",
        "var f = function () {}; f.name",
        "var f = () => 1; f.name",
        "var o = { m() {} }; o.m.name",
        "(function () { return 1 })()",
        "(() => 2)()",
        "function f(a, b) {} f.length",
        "function f(a, b = 1, c) {} f.length",
        "function f(...r) { return r.length } typeof f",
        "var x = 'global'; function f() { var x = 'local'; return x } f() + x",
        "var x = 'global'; function f() { x = 'changed' } f(); x",
        "function f() { return g() } function g() { return 'hoisted' } f()",
        "var r = typeof h; function h() {} r",
        "function f(x) { return x === undefined ? 'u' : x } f(undefined)",
        "function f() { return 1 }; function f() { return 2 }; f()",
        "function f(a, a) { return a } f(1, 2)",
    ))

    @Test
    fun controlFlow() = check(listOf(
        "var s = 0; for (var i = 0; i < 10; i++) s += i; s",
        "var s = 0; for (var i = 0; i < 10; i++) { if (i % 2) continue; s += i } s",
        "var i = 0; while (i < 5) i++; i",
        "var i = 0; do { i++ } while (i < 5); i",
        "var s = 0; for (var i = 0; ; i++) { if (i > 3) break; s += i } s",
        "outer: for (var i = 0; i < 3; i++) { for (var j = 0; j < 3; j++) { if (j == 1) continue outer; if (i == 2) break outer } } i",
        "var r = ''; switch (2) { case 1: r += 'a'; case 2: r += 'b'; case 3: r += 'c'; break; default: r += 'd' } r",
        "var r = ''; switch ('x') { case 'y': r = 'y'; break; default: r = 'def' } r",
        "var r = ''; switch (1) { default: r += 'd'; case 1: r += '1' } r",
        "if (1) 'yes'; else 'no'",
        "if (0) 'yes'; else 'no'",
        "if (0) 'yes'; else if (1) 'elseif'; else 'no'",
        "var o = { a: 1, b: 2, c: 3 }; var keys = ''; for (var k in o) keys += k; keys",
        "var o = { a: 1 }; var p = { __proto__: o, b: 2 }; var keys = ''; for (var k in p) keys += k; keys",
        "var o = { a: 1, b: 2 }; var s = 0; for (var k in o) s += o[k]; s",
        "var r = 0; for (var k in null) r++; r",
        "var r = 0; for (var k in undefined) r++; r",
        "var x = 1; { let x = 2; } x",
        "let a = 1; { let a = 2; a++ } a",
        "const c = 5; c",
        "var r; try { r = 'try' } finally { r += '-finally' } r",
        "var r; try { throw 'boom' } catch (e) { r = e } r",
        "var r; try { throw 42 } catch (e) { r = e + 1 } r",
        "var r; try { throw { v: 1 } } catch (e) { r = e.v } r",
        "var r = ''; try { try { throw 'a' } finally { r += 'f' } } catch (e) { r += e } r",
        "var r = ''; try { throw 'x' } catch (e) { r += 'c' } finally { r += 'f' } r",
        "function f() { try { return 'try' } finally { } } f()",
        "function f() { try { throw 1 } catch (e) { return 'caught' } finally { } } f()",
        "function f() { try { return 'a' } finally { return 'b' } } f()",
        "var r = ''; for (var i = 0; i < 3; i++) { try { if (i == 1) continue; r += i } finally { r += 'f' } } r",
        "var o = { x: 1 }; with (o) { x = 2 } o.x",
        "var o = { x: 1 }; var r; with (o) { r = x + 1 } r",
        "var x = 'outer'; var o = { }; with (o) { x } ",
        "var r = 0; label: { r = 1; break label; r = 2 } r",
        "var s = ''; for (var i = 0; i < 3; i++) { s += i; s += ',' } s",
        "'a' in { a: 1 }",
        "'b' in { a: 1 }",
        "var o = { a: 1 }; delete o.a; 'a' in o",
        "var o = { a: 1 }; delete o.a",
        "var o = {}; o.x = 1; o.y = 2; o.x + o.y",
        "var o = {}; o['k'] = 3; o.k",
        "var o = { 1: 'one' }; o[1]",
        "var o = { a: { b: { c: 'deep' } } }; o.a.b.c",
        "var o = { a: 1 }; o.a++; o.a",
        "var o = { a: 1 }; ++o.a",
        "var o = { a: 1 }; o.a += 5; o.a",
        "var o = {}; o.f = function () { return 'called' }; o.f()",
        "var o = { get x() { return 42 } }; o.x",
        "var o = { set x(v) { this._x = v * 2 } }; o.x = 4; o._x",
        "var o = { a: 1 }; o.b",
        "var o = {}; o.missing === undefined",
        "var o = { toString: function () { return 'custom' } }; '' + o",
        "var o = { valueOf: function () { return 7 } }; o + 1",
        "var o = { valueOf: function () { return 7 }, toString: function () { return 's' } }; '' + o",
        "var o = { [1 + 2]: 'three' }; o[3]",
        "var k = 'dyn'; var o = { [k]: 1 }; o.dyn",
        "var o = { a: 1, ...{ b: 2 } }; o.b",
        "var o = null; o?.a",
        "var o = { a: { b: 1 } }; o?.a?.b",
        "var o = {}; o.f?.()",
        "var o = { f() { return 'r' } }; o.f?.()",
        "var o = {}; o?.['x']",
        "typeof undeclared",
        "var u; u === undefined",
        "var o = { __proto__: { inherited: 'p' } }; o.inherited",
        "var p = { v: 1 }; var o = { __proto__: p }; o.v = 2; p.v",
        "function F() { this.a = 1 } var o = new F(); o.a",
        "function F() { this.a = 1 } F.prototype.b = 2; var o = new F(); o.a + o.b",
        "function F() {} var o = new F(); o instanceof F",
        "function F() {} function G() {} var o = new F(); o instanceof G",
        "function F() { return { custom: true } } var o = new F(); o.custom",
        "function F() { return 1 } var o = new F(); typeof o",
        "function F(a) { this.a = a } var o = new F(9); o.a",
        "function F() {} F.prototype.m = function () { return this.x }; var o = new F(); o.x = 3; o.m()",
        "function F() {} var o = new F(); o.constructor === F",
        "var f = function () {}; f.prototype.constructor === f",
    ))

    // The scripts that need the standard objects (arguments, NaN, new Function, the __proto__
    // setter) join this test when phase 3.8 lands.

    @Test
    fun errorsThrownByTheEngineHaveTheSameText() = check(listOf(
        "undeclared",
        "null.x",
        "undefined.x",
        "var o = null; o.f()",
        "var o = {}; o.f()",
        "var o = {}; o.a.b",
        "(1)()",
        "'s'()",
        "var f = 1; f()",
        "null[0]",
        "new 5",
        "var x = 1; new x()",
        "function f() {} f.x.y",
        "'use strict'; undeclaredAssign = 1",
        "1 in 2",
        "1 instanceof 2",
        "const c = 1; c = 2",
        "throw 'plain'",
        "throw 1",
    ))
}
