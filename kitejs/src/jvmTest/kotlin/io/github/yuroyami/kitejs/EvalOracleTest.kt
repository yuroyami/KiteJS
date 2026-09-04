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
 * Both engines run with their standard objects installed. Scripts that need a builtin the port
 * does not have yet (Array, String, JSON, RegExp, Date and the phase 4 set) join as those land.
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
        kscope = kcx.initStandardObjects()
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
            // The port names classes without the package (D-23).
            "throws " + e.details().replace("org.mozilla.javascript.", "")
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

    @Test
    fun objectBuiltin() = check(listOf(
        "typeof Object", "Object.name", "Object.length", "Object.prototype.constructor === Object",
        "var o = new Object(); o.constructor === Object", "Object(null) instanceof Object", "Object(5) === 5",
        "new Object(1) instanceof Number", "Object(true) instanceof Boolean", "typeof Object(1)",
        "var o = {}; Object.getPrototypeOf(o) === Object.prototype", "Object.getPrototypeOf(Object.prototype)",
        "var p = {}; var o = Object.create(p); Object.getPrototypeOf(o) === p",
        "var o = Object.create(null, { a: { value: 1, enumerable: true } }); o.a",
        "var o = Object.create(null); o.toString",
        "var o = {}; Object.defineProperty(o, 'x', { value: 5 }); o.x",
        "var o = {}; Object.defineProperty(o, 'x', { value: 5 }); o.x = 6; o.x",
        "var o = {}; Object.defineProperty(o, 'x', { value: 5, writable: true }); o.x = 6; o.x",
        "var o = {}; Object.defineProperty(o, 'x', { get: function () { return 'g' } }); o.x",
        "var o = {}; Object.defineProperty(o, 'x', { set: function (v) { this.y = v } }); o.x = 2; o.y",
        "var o = {}; Object.defineProperties(o, { a: { value: 1 }, b: { value: 2 } }); o.a + o.b",
        "var o = { a: 1 }; Object.getOwnPropertyDescriptor(o, 'a').writable",
        "var o = { a: 1 }; var d = Object.getOwnPropertyDescriptor(o, 'a'); d.value + '' + d.enumerable + d.configurable",
        "var o = { get g() { return 1 } }; typeof Object.getOwnPropertyDescriptor(o, 'g').get",
        "Object.getOwnPropertyDescriptor({}, 'nope')",
        "var o = { a: 1 }; Object.getOwnPropertyDescriptors(o).a.value",
        "var o = Object.freeze({ a: 1 }); o.a = 2; o.a",
        "var o = Object.freeze({ a: 1 }); delete o.a; o.a",
        "Object.isFrozen(Object.freeze({}))", "Object.isFrozen({})", "Object.isFrozen(1)",
        "Object.isSealed(Object.seal({ a: 1 }))", "Object.isSealed({})",
        "var o = Object.seal({ a: 1 }); o.a = 2; o.a", "var o = Object.seal({ a: 1 }); o.b = 2; o.b",
        "Object.isExtensible({})", "Object.isExtensible(Object.preventExtensions({}))", "Object.isExtensible(1)",
        "var o = Object.preventExtensions({}); o.x = 1; o.x",
        "Object.assign({}, { a: 1 }, { b: 2 }).b", "Object.assign({ a: 1 }, null, undefined, { a: 2 }).a",
        "var t = {}; Object.assign(t, { a: 1 }) === t",
        "Object.is(NaN, NaN)", "Object.is(0, -0)", "Object.is(1, 1)", "Object.is('a', 'a')", "Object.is({}, {})",
        "var o = { a: 1 }; o.hasOwnProperty('a')", "var o = { a: 1 }; o.hasOwnProperty('toString')",
        "Object.hasOwn({ a: 1 }, 'a')", "Object.hasOwn({ a: 1 }, 'b')",
        "({}).propertyIsEnumerable('toString')", "({ a: 1 }).propertyIsEnumerable('a')",
        "Object.prototype.isPrototypeOf({})", "var p = {}; p.isPrototypeOf(Object.create(p))", "({}).isPrototypeOf({})",
        "var o = {}; o.__proto__ === Object.prototype", "var o = {}; o.__proto__ = { z: 9 }; o.z",
        "var o = {}; o.__proto__ = 5; o.__proto__ === Object.prototype",
        "var o = {}; Object.setPrototypeOf(o, { q: 1 }); o.q", "Object.setPrototypeOf(1, null)",
        "var o = Object.setPrototypeOf({}, null); Object.getPrototypeOf(o)",
        "({}).toString()", "({}).toLocaleString()", "({}).valueOf() instanceof Object",
        "Object.prototype.toString.call(1)", "Object.prototype.toString.call(true)", "Object.prototype.toString.call(null)",
        "Object.prototype.toString.call(undefined)", "Object.prototype.toString.call(function () {})",
        "var o = {}; o.__defineGetter__('g', function () { return 'got' }); o.g",
        "var o = {}; o.__defineSetter__('s', function (v) { this.t = v }); o.s = 3; o.t",
        "var o = { get g() { return 1 } }; typeof o.__lookupGetter__('g')", "var o = {}; o.__lookupGetter__('none')",
        "var o = { set s(v) {} }; typeof o.__lookupSetter__('s')",
        "typeof Object.groupBy", "typeof Object.fromEntries", "typeof Object.keys",
        "var o = {}; o.__proto__ = o",
        "function F() {} F.prototype = { m() { return 'proto' } }; new F().m()",
    ))

    @Test
    fun functionBuiltin() = check(listOf(
        "new Function('a', 'b', 'return a + b')(2, 3)", "Function('return 7')()", "new Function()()",
        "(function () {}).constructor === Function", "typeof Function.prototype", "Function.prototype()",
        "Function.prototype.call.call(function () { return this.v }, { v: 4 })",
        "function f() { return arguments.length } f(1, 2, 3)", "function f() { return arguments[1] } f('a', 'b')",
        "function f(a) { arguments[0] = 'changed'; return a } f('orig')",
        "function f(a) { 'use strict'; arguments[0] = 'changed'; return a } f('orig')",
        "function f() { return typeof arguments } f()",
        "function f() { return Object.prototype.toString.call(arguments) } f()",
        "function f(a, b) { return f.length + arguments.length } f(1)",
        "(function f(a) { return a }).toString()", "(function (a, b) { return a + b }).toString()",
        "var b = (function () { return this.v }).bind({ v: 'bound' }); b()",
        "var b = (function (a, b) { return a + b }).bind(null, 1); b(2)",
        "function F() {} var B = F.bind({}); new B() instanceof F",
        "(function () {}).hasOwnProperty('prototype')", "(() => 1).hasOwnProperty('prototype')",
        "var f = function (a, b) { return this.x + a + b }; f.call({ x: 1 }, 2, 3)",
        "var f = function () { return this }; f.call(null) === globalThis",
        "var f = function () { 'use strict'; return this }; f.call(null)",
        "(function () {}).length", "Function.length", "Function.name", "Function.prototype.name",
        "(function () {}).bind().name", "(function named() {}).bind().name",
    ))

    @Test
    fun errorBuiltin() = check(listOf(
        "new Error('m').message", "new Error('m').name", "'' + new Error('m')", "new Error('m').toString()",
        "new Error().message", "new Error(undefined).message", "Error('call').message",
        "new TypeError('t') instanceof Error", "new TypeError('t').name", "'' + new RangeError('r')",
        "'' + new SyntaxError('s')", "'' + new ReferenceError('e')", "'' + new EvalError('v')", "'' + new URIError('u')",
        "'' + new InternalError('i')", "'' + new JavaException('j')",
        "try { null.x } catch (e) { e.name }", "try { null.x } catch (e) { e instanceof TypeError }",
        "try { undeclared } catch (e) { e.constructor === ReferenceError }",
        "try { (1)() } catch (e) { e instanceof TypeError }",
        "try { throw new Error('x') } catch (e) { e.message }",
        "try { throw new TypeError('x') } catch (e) { '' + e }",
        "var e = new Error('m', { cause: 'c' }); e.cause", "var e = new Error('m', { cause: 'c' }); e.propertyIsEnumerable('cause')",
        "var e = new Error('m', 'file.js', 7); e.fileName + ':' + e.lineNumber",
        "Error.prototype.name", "Error.prototype.message", "TypeError.prototype.name", "TypeError.prototype.message",
        "Object.getPrototypeOf(TypeError.prototype) === Error.prototype", "TypeError.prototype instanceof Error",
        "Object.getPrototypeOf(TypeError) === Error", "TypeError.name", "TypeError.length", "AggregateError.length",
        "typeof Error.captureStackTrace", "Error.stackTraceLimit", "typeof new Error('x').stack",
        "Error.prototype.toString.call({ name: 'N', message: 'M' })", "Error.prototype.toString.call({})",
        "Error.prototype.toString.call({ name: '', message: 'only' })", "Error.prototype.toString.call({ name: 'only' })",
        "Error.isError(new Error())", "Error.isError({})", "Error.isError(new TypeError('t'))",
        "new Error('e').toSource()", "new Error('e', 'f.js', 3).toSource()", "new Error('e').propertyIsEnumerable('message')",
        "try { throw new Error('t') } catch (e) { e.lineNumber }", "try { throw new Error('t') } catch (e) { e.fileName }",
        "var e = new Error('x'); e.name = 'Custom'; '' + e",
        "var o = { __proto__: Error.prototype, message: 'inherited' }; '' + o",
        "new Error('with stack').stack",
    ))

    @Test
    fun globalFunctions() = check(listOf(
        "parseInt('42')", "parseInt('  42abc')", "parseInt('0x1f')", "parseInt('1f', 16)", "parseInt('101', 2)",
        "parseInt('')", "parseInt('-7')", "parseInt('08')", "parseInt('z', 36)", "parseInt('12', 1)", "parseInt(15.9)",
        "parseInt('  -0x10')", "parseInt('0x')", "parseInt('123', 0)", "parseInt('99999999999999999999')",
        "parseFloat('3.14abc')", "parseFloat('.5')", "parseFloat('-.5e2')", "parseFloat('1e')", "parseFloat('1e+')",
        "parseFloat('Infinity')", "parseFloat('-Infinityx')", "parseFloat('abc')", "parseFloat('')", "parseFloat('5.')",
        "parseFloat('  12  ')", "parseFloat('1.2.3')", "parseFloat('+')", "parseFloat('1e5x')", "parseFloat()",
        "isNaN('x')", "isNaN('1')", "isNaN()", "isFinite(1 / 0)", "isFinite('5')", "isFinite()",
        "NaN == NaN", "NaN !== NaN", "Infinity > 1e308", "-Infinity", "undefined === void 0", "typeof globalThis",
        "globalThis === this", "typeof NaN", "delete NaN", "NaN = 1; NaN",
        "escape('a b+c/@*_-.')", "escape('\u00fc\u1234')", "unescape('%41%u0042%')", "unescape('%zz%4')",
        "encodeURI('http://x.y/a b?q=1&r=\u00fc#f')", "encodeURIComponent('a b&c=d/\u00e9')",
        "decodeURI('%41%20%C3%BC%3F%26')", "decodeURIComponent('%41%20%C3%BC%3F%26')",
        "encodeURI('\uD83D\uDE00')", "decodeURIComponent('%F0%9F%98%80') === '\uD83D\uDE00'",
        "eval('1 + 1')", "var x = 5; eval('x * 2')", "eval('var y = 3'); y",
        "function f() { var l = 1; return eval('l + 1') } f()", "eval('(function () { return 9 })')()",
        "typeof eval", "eval()", "eval(5)", "(0, eval)('1')", "eval('')",
        "uneval({ a: 1, b: 'x' })", "uneval('s')", "uneval(-0)", "uneval(null)", "uneval(undefined)", "uneval(true)",
        "uneval({ a: { b: 1 } })", "({}).toSource()", "({ 'a b': 1 }).toSource()", "({ 1: 2 }).toSource()",
        "(function f(a) { return a }).toSource()", "uneval(1.5)",
    ))

    @Test
    fun booleanAndNumberBuiltins() = check(listOf(
        "new Boolean(false) ? 'y' : 'n'", "Boolean(0)", "Boolean('')", "Boolean('x')", "Boolean()",
        "new Boolean(1).valueOf()", "'' + new Boolean(true)", "new Boolean(false).toString()", "typeof new Boolean(1)",
        "true.toString()", "false.valueOf()", "new Boolean(true) == true", "new Boolean(true) === true",
        "new Boolean(true).toSource()", "Boolean.name", "Boolean.length", "Boolean.prototype.constructor === Boolean",
        "Object.getPrototypeOf(Boolean.prototype) === Object.prototype", "'' + Boolean.prototype",
        "Number('42')", "Number('')", "Number('  0x10 ')", "Number('abc')", "Number(true)", "Number(null)",
        "Number(undefined)", "Number()", "new Number(5).valueOf()", "typeof new Number(5)", "new Number(5) + 1",
        "'' + new Number(5)", "(255).toString(10)", "(1e21).toString()", "(123.456).toFixed(2)", "(1.005).toFixed(2)", "(2.5).toFixed(0)",
        "(1e21).toFixed(2)", "(0).toFixed(2)", "(-1.5).toFixed(0)", "(0.000001).toFixed(7)", "(123.456).toFixed()",
        "(123.456).toExponential(2)", "(0).toExponential()", "(123456).toExponential()", "(0.00015).toExponential(1)",
        "(123.456).toPrecision(4)", "(0.000123).toPrecision(2)", "(123456).toPrecision(2)", "(123.456).toPrecision()",
        "(1).toPrecision(1)", "(NaN).toFixed(2)", "(Infinity).toPrecision(2)", "(-Infinity).toExponential(1)",
        "Number.MAX_SAFE_INTEGER", "Number.MIN_SAFE_INTEGER", "Number.EPSILON", "Number.MIN_VALUE", "Number.MAX_VALUE",
        "Number.POSITIVE_INFINITY", "Number.NEGATIVE_INFINITY", "Number.NaN",
        "Number.isInteger(5)", "Number.isInteger(5.5)", "Number.isInteger('5')", "Number.isInteger()",
        "Number.isSafeInteger(2 ** 53)", "Number.isSafeInteger(2 ** 53 - 1)", "Number.isNaN('x')", "Number.isNaN(NaN)",
        "Number.isFinite('1')", "Number.isFinite(1)", "Number.isFinite(1 / 0)", "Number.parseInt === parseInt",
        "Number.parseFloat === parseFloat", "Number.parseFloat('1.5')", "(5).toLocaleString()", "(5).toSource()",
        "Number.length", "Number.name", "Number.prototype.valueOf()", "'' + Number.prototype",
        "Number.prototype.constructor === Number", "(5).constructor === Number", "(5).hasOwnProperty('x')",
        "var n = 5; n.prop = 1; n.prop",
    ))

    @Test
    fun mathBuiltin() = check(listOf(
        "Math.PI", "Math.E", "Math.SQRT2", "Math.LN2", "Math.LN10", "Math.LOG2E", "Math.LOG10E", "Math.SQRT1_2",
        "Math.abs(-3)", "Math.abs('-2')", "1 / Math.abs(-0)", "Math.floor(-1.5)", "Math.ceil(-1.5)", "1 / Math.ceil(-0.5)",
        "Math.round(2.5)", "Math.round(-2.5)", "Math.round(0.49999999999999994)", "1 / Math.round(-0.4)", "Math.round(1e16)",
        "Math.round(4503599627370497)", "Math.round(NaN)", "Math.round(-Infinity)",
        "Math.max()", "Math.min()", "Math.max(1, 'x')", "Math.max(1, 2, 3)", "1 / Math.min(-0, 0)", "1 / Math.max(-0, 0)",
        "Math.pow(2, 10)", "Math.pow(-8, 1 / 3)", "Math.pow(NaN, 0)", "Math.pow(1, Infinity)", "Math.pow(-1, Infinity)",
        "Math.pow(0, -1)", "1 / Math.pow(-0, 3)", "Math.pow(-0, -3)", "Math.pow(-Infinity, 3)", "Math.pow(2, -1074)",
        "Math.sqrt(16)", "Math.sqrt(-1)", "Math.cbrt(27)", "Math.cbrt(-8)", "Math.hypot(3, 4)", "Math.hypot()",
        "Math.hypot(NaN, Infinity)", "Math.sign(-5)", "1 / Math.sign(-0)", "Math.sign('x')", "Math.trunc(-4.7)",
        "Math.log(Math.E)", "Math.log(-1)", "Math.log2(8)", "Math.log10(1000)", "Math.log1p(0)", "Math.expm1(0)",
        "Math.exp(1) === Math.E", "Math.exp(-Infinity)", "Math.sin(0)", "Math.sin(Infinity)", "Math.cos(0)", "Math.tan(0)",
        "Math.atan2(1, 1)", "Math.atan(1)", "Math.asin(2)", "Math.asin(1)", "Math.acos(1)", "Math.sinh(0)", "Math.cosh(0)",
        "Math.tanh(Infinity)", "1 / Math.asinh(-0)", "Math.asinh(1)", "Math.acosh(1)", "Math.acosh(0)", "Math.atanh(0)",
        "Math.atanh(0.5)", "Math.clz32(1)", "Math.clz32(0)", "Math.clz32(-1)", "Math.clz32(0x10000)",
        "Math.imul(0xffffffff, 5)", "Math.imul(2, 4)", "Math.imul(0x7fffffff, 2)", "Math.fround(5.5)", "Math.fround(5.05)",
        "Math.f16round(1.337)", "Math.f16round(65520)", "Math.f16round(5.960464477539063e-8)", "Math.f16round(0.1)",
        "Math.f16round()", "Math.f16round(-1e-9)", "typeof Math.random()", "Math.random() < 1", "Math.toSource",
        "'' + Math", "Object.prototype.toString.call(Math)", "typeof Math", "Math.abs()", "Math.max('1', '2')",
    ))

    // Number.prototype.toString with a radix other than 10 waits for the phase 5 BigInt.

    @Test
    fun scriptObject() = check(listOf(
        "typeof Script", "new Script('1 + 2')()", "'' + new Script('1 + 2')", "new Script('var q = 8')(); q",
        "typeof new Script('1')", "Object.prototype.toString.call(new Script(''))", "new Script('').compile('3')()",
        "typeof Script.prototype.exec", "Script.length", "Script.name", "new Script('7').toString()",
        "Object.getPrototypeOf(Script.prototype) === Function.prototype", "typeof Script.prototype.compile",
        "new Script('1')() + new Script('2')()", "Script('9')()", "new Script('').length", "'' + Script.prototype",
    ))

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
        "Object.defineProperty(1, 'x', {})", "Object.create(1)", "Object.setPrototypeOf({})",
        "Object.defineProperty({}, 'x', 1)", "Object.defineProperty({}, 'x', { get: 1 })",
        "Object.defineProperty({}, 'x', { get: function () {}, value: 1 })",
        "var o = {}; Object.defineProperty(o, 'x', { value: 1 }); Object.defineProperty(o, 'x', { value: 2 })",
        "'use strict'; var o = {}; Object.defineProperty(o, 'x', { value: 5 }); o.x = 6",
        "'use strict'; var o = Object.freeze({ a: 1 }); o.a = 2",
        "'use strict'; var o = Object.freeze({ a: 1 }); delete o.a",
        "'use strict'; var o = Object.preventExtensions({}); o.x = 1",
        "Object.freeze(Object.prototype); Object.prototype.x = 1; 'use strict'; Object.prototype.y = 2",
        "(1).toString(1)", "(1).toFixed(-1)", "(1).toFixed(101)", "(1).toExponential(101)", "(1).toPrecision(0)",
        "(1).toPrecision(101)", "Boolean.prototype.valueOf.call(1)",
        "Boolean.prototype.toString.call({})", "decodeURI('%')", "decodeURIComponent('%C3')", "encodeURIComponent('\\uD800')",
        "encodeURI('\\uDC00')", "decodeURI('%ZZ')", "escape('x', 9)", "new Script()()", "Script.prototype.exec()",
        "new AggregateError()", "eval('throw 1')", "eval('syntax error here')",
        "eval('var')", "new Function('return')", "new Function('a b', '')", "Function.prototype.call.call(1)",
        "Function.prototype.bind.call(1)", "(function () {}).bind.call(undefined)", "new (function () {}).bind()",
        "var o = {}; o.__defineGetter__('x', 1)", "Object.prototype.__lookupGetter__.call(null, 'x')",
        "Object.assign(null)", "Object.assign()", "Object.getPrototypeOf(null)", "Object.getOwnPropertyDescriptor(null, 'x')",
        "Object.isFrozen()", "Object.preventExtensions(1)", "({}).hasOwnProperty.call(null, 'x')",
        "({}).propertyIsEnumerable.call(undefined, 'x')", "({}).isPrototypeOf.call(null, {})",
        "Object.prototype.toString.call()", "Object.prototype.toLocaleString.call(null)", "Object.prototype.valueOf.call(null)",
        "var o = {}; o.__proto__ = 5; o.__proto__ = o", "Object.setPrototypeOf(null, {})", "Object.setPrototypeOf({}, 1)",
        "var a = {}; var b = Object.create(a); Object.setPrototypeOf(a, b)", "Object.setPrototypeOf(Object.preventExtensions({}), {})",
        "Math.max.call()", "new Math.max()", "new parseInt()", "new Error.prototype.toString()", "Number.isInteger.call()",
        "new Number.prototype.constructor.prototype.valueOf()",
    ))
}
