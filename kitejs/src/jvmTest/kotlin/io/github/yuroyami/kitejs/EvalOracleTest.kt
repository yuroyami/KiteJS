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
            "throws " + normalise(e.details().replace("org.mozilla.javascript.", ""))
        }

    private fun ported(source: String): String =
        try {
            render(Context.getContext().evaluateString(kscope, source, "test.js", 1, null))
        } catch (e: RhinoException) {
            "throws " + normalise(e.details().replace("io.github.yuroyami.kitejs.", ""))
        }

    /** A message that prints an object's identity hash can never match; the hash is dropped. */
    private fun normalise(details: String): String = details.replace(Regex("@[0-9a-f]+"), "@")

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

    // Number.prototype.toString with a radix other than 10 waits for the phase 5 BigInt. The array
    // and string scripts that look at Symbol.iterator, Symbol.unscopables and Symbol.species join
    // in phase 4, as do match, search, matchAll and the regexp forms of split and replace. Rhino
    // 1.9.1 has no class syntax at all, so nothing here uses it.

    @Test
    fun stringBuiltin() = check(listOf(
        "'abc'.length", "'abc'[1]", "'abc'[5]", "'abc'.charAt(1)", "'abc'.charAt(5)", "'abc'.charAt()", "'abc'.charCodeAt(1)",
        "'abc'.charCodeAt(9)", "'abc'.codePointAt(0)", "'\\uD83D\\uDE00'.codePointAt(0)", "'\\uD83D\\uDE00'.codePointAt(1)",
        "'\\uD83D\\uDE00'.length", "'abc'.at(-1)", "'abc'.at(5)", "'abc'.at()",
        "'abcabc'.indexOf('b')", "'abcabc'.indexOf('b', 2)", "'abc'.indexOf('z')", "'abc'.indexOf('')", "'abc'.indexOf('', 10)",
        "'abc'.indexOf('c', -5)", "'abc'.indexOf()", "'aXbXc'.lastIndexOf('X')", "'aXbXc'.lastIndexOf('X', 2)", "'abc'.lastIndexOf('')",
        "'abc'.lastIndexOf('a', -1)", "'abc'.lastIndexOf('c', NaN)", "'abc'.includes('b')", "'abc'.includes('b', 2)", "'abc'.includes('')",
        "'abc'.startsWith('ab')", "'abc'.startsWith('bc', 1)", "'abc'.startsWith('')", "'abc'.endsWith('bc')", "'abc'.endsWith('ab', 2)",
        "'abc'.endsWith('c', undefined)", "'abc'.endsWith('c', NaN)", "'abc'.endsWith('', 0)",
        "'abcdef'.slice(1, 3)", "'abcdef'.slice(-2)", "'abcdef'.slice(4, 1)", "'abcdef'.slice()", "'abcdef'.slice(1, undefined)",
        "'abcdef'.substring(4, 1)", "'abcdef'.substring(-1, 2)", "'abcdef'.substring()", "'abcdef'.substring(2, NaN)",
        "'abcdef'.substr(1, 3)", "'abcdef'.substr(-3)", "'abcdef'.substr(2)", "'abcdef'.substr(1, -1)", "'abcdef'.substr()",
        "'AbC'.toLowerCase()", "'AbC'.toUpperCase()", "'\\u00df'.toUpperCase()", "'AbC'.toLocaleLowerCase()", "'AbC'.toLocaleUpperCase()",
        "'  a b  '.trim()", "'  a  '.trimStart() + '|'", "'|' + '  a  '.trimEnd()", "'\\u00a0\\ufeffa\\n\\u2028'.trim()", "'  a  '.trimLeft() + '|'",
        "'|' + '  a  '.trimRight()", "'\\t\\n a'.trim()",
        "'a,b,c'.split(',').join('|')", "'a,b,c'.split(',', 2).join('|')", "'abc'.split('').join('|')", "'abc'.split().length",
        "'abc'.split(undefined)[0]", "''.split(',').length", "''.split('').length", "'a,b'.split(',', 0).length", "'aXbXc'.split('X', -1).length",
        "'abc'.split('', 2).join('|')", "'a b'.split(' ')[1]", "'\\uD83D\\uDE00'.split('').length", "'a,,b'.split(',').length",
        "'abc'.split('abc').length", "'abc'.split('abc')[0] === ''", "'a1b2c'.split(1).join('|')",
        "'a'.concat('b', 1, null)", "'a'.concat()", "'ab'.repeat(3)", "'ab'.repeat(0)", "'ab'.repeat(2.9)", "''.repeat(5)",
        "'5'.padStart(3, '0')", "'5'.padEnd(3, 'xy')", "'abc'.padStart(2)", "'5'.padStart(4)", "'5'.padStart(4, '')", "'5'.padEnd(6, 'abc')",
        "'aXbXc'.replace('X', '-')", "'aXbXc'.replaceAll('X', '-')", "'abc'.replace('b', '$&$&')", "'abc'.replace('b', '[$`|$\\']')",
        "'abc'.replace('b', '$$')", "'abc'.replace('b', '$1')", "'abc'.replace('b', '$<x>')", "'abc'.replace('b', '$')", "'abc'.replace('b', '$0')",
        "'abc'.replace('b', function (m, p, s) { return m + p + s.length })", "'abc'.replace('z', 'y')", "'aaa'.replaceAll('', '-')",
        "'abc'.replaceAll('b', (m, p) => p)", "'abc'.replace()", "'abc'.replaceAll()", "'abc'.replace('', 'x')", "'aXbX'.replaceAll('X', '$&$&')",
        "'abc'.toString()", "'abc'.valueOf()", "new String('abc').length", "typeof new String('x')", "new String('x') == 'x'",
        "new String('x') === 'x'", "String(123)", "String(null)", "String(undefined)", "String()", "String({})", "String([1, 2])",
        "String(true)", "new String(5) + 1", "'' + new String('q')", "new String('q').toSource()", "String.name", "String.length",
        "String.fromCharCode(72, 105)", "String.fromCharCode()", "String.fromCharCode(65.9, -1)", "String.fromCharCode(0x10041)",
        "String.fromCodePoint(128512).length", "String.fromCodePoint(0x1F600) === '\\uD83D\\uDE00'", "String.fromCodePoint()",
        "String.fromCodePoint(65, 66)", "String.raw({ raw: ['a', 'b', 'c'] }, 1, 2)", "String.raw({ raw: 'xyz' }, 1)", "String.raw({ raw: [] })",
        "'abc'.bold()", "'abc'.link('u\"rl')", "'abc'.fontsize(3)", "'abc'.anchor()", "'abc'.big()", "'abc'.fontcolor()",
        "'a'.equals('a')", "'a'.equalsIgnoreCase('A')", "'a'.localeCompare('a')", "'a'.localeCompare('b')", "'b'.localeCompare('a')",
        "'abc'.normalize()", "'abc'.normalize('NFD')", "'abc'.normalize(undefined)",
        "var r = ''; for (var c of 'abc') r += c + '-'; r", "var r = 0; for (var c of '\\uD83D\\uDE00x') r++; r", "[...'abc'].length",
        "Array.from('abc').join('|')", "Array.from('abc') === 'abc'", "Array.prototype.map.call('abc', x => x).length", "[1, 2, 3].map(String).join()",
        "Object.keys('abc').join()", "Object.keys(new String('ab')).join()", "Object.getOwnPropertyNames('ab').join()",
        "Object.getOwnPropertyDescriptor('abc', 0).value", "Object.getOwnPropertyDescriptor('abc', 1).writable",
        "Object.getOwnPropertyDescriptor('abc', 1).enumerable", "'abc'.hasOwnProperty(1)", "'abc'.hasOwnProperty('length')",
        "'abc'.propertyIsEnumerable(0)", "'abc'.propertyIsEnumerable('length')", "1 in new String('ab')", "5 in new String('ab')",
        "var s = new String('ab'); s[0] = 'z'; s[0]", "var s = new String('ab'); s.x = 1; s.x", "var s = new String('ab'); s[5] = 'z'; s[5]",
        "var s = new String('ab'); s.length = 9; s.length", "delete new String('ab')[0]", "'use strict'; delete new String('ab')[0]",
        "'abc'.isWellFormed()", "'\\uD800'.isWellFormed()", "'\\uDC00\\uD800'.isWellFormed()", "'\\uD800a'.toWellFormed().charCodeAt(0)",
        "'a\\uD83D\\uDE00b'.toWellFormed().length", "'\\uD800\\uD800'.toWellFormed().charCodeAt(1)",
        "'abc' < 'abd'", "'a' + 1 + 2", "'abc' == new String('abc')", "new String('a') == new String('a')", "'abc'.constructor === String",
        "Object.getPrototypeOf('abc') === String.prototype", "String.prototype.length", "'' + String.prototype", "typeof String.prototype",
        "Object.prototype.toString.call('s')", "Object.prototype.toString.call(new String('s'))", "String.prototype.constructor === String",
        "'x'.length = 5", "(function () { 'use strict'; 'x'.length = 5 })()", "Number.prototype.toString.call('x')", "(5).toFixed.call('x')",
        "String.charAt('abc', 1)", "String.indexOf('abc', 'c')", "String.split('a,b', ',').length", "String.toUpperCase('q')",
        "String.concat('a', 'b', 'c')", "String.slice('abcdef', 1, 3)",
    ))

    @Test
    fun jsonBuiltin() = check(listOf(
        "JSON.stringify({ a: 1, b: 'x', c: [1, 2], d: null, e: true })", "JSON.stringify([1, 'a', null, undefined, function () {}])",
        "JSON.stringify(undefined)", "JSON.stringify(function () {})", "JSON.stringify(null)", "JSON.stringify('a\"b\\\\c\\n')",
        "JSON.stringify(NaN)", "JSON.stringify(Infinity)", "JSON.stringify(-0)", "JSON.stringify(1e21)", "JSON.stringify(0.1)",
        "JSON.stringify(new Number(3))", "JSON.stringify(new String('s'))", "JSON.stringify(new Boolean(false))",
        "JSON.stringify({ a: undefined, b: function () {} })", "JSON.stringify({ toJSON: function () { return 'custom' } })",
        "JSON.stringify({ a: { toJSON: function (k) { return k + '!' } } })", "JSON.stringify({ a: 1, b: 2 }, ['b'])",
        "JSON.stringify({ 1: 'one', a: 1 }, [1])", "JSON.stringify({ a: 1, b: 2 }, function (k, v) { return typeof v == 'number' ? v * 2 : v })",
        "JSON.stringify({ a: [1, { b: 2 }] }, null, 2)", "JSON.stringify({ a: 1 }, null, '--')", "JSON.stringify([1, [2]], null, 1)",
        "JSON.stringify({ a: 1 }, null, 20).length", "JSON.stringify({ a: 1 }, null, 'abcdefghijklmnop')", "JSON.stringify([])",
        "JSON.stringify({})", "JSON.stringify([[]], null, 2)", "JSON.stringify({ a: {} }, null, 2)", "JSON.stringify('\\uD83D\\uDE00')",
        "JSON.stringify('\\uD800')", "JSON.stringify('\\u001f')", "JSON.stringify('\\u007f\\u0080')", "JSON.stringify({ [1]: 'n', a: 'b' })",
        "var o = {}; o.self = o; JSON.stringify(o)", "var a = []; a[0] = a; JSON.stringify(a)", "JSON.stringify([, 1])",
        "JSON.stringify(Object.create({ inherited: 1 }))", "JSON.stringify({ a: 1 }, null, new Number(2))",
        "JSON.stringify({ a: 1 }, null, new String('~'))", "JSON.stringify(Math)", "JSON.stringify(JSON)", "typeof JSON", "'' + JSON",
        "Object.prototype.toString.call(JSON)", "JSON.toSource", "JSON.stringify.length", "JSON.parse.length",
        "JSON.stringify({ b: 1, a: 2, 1: 3 })", "JSON.stringify(new Error('e'))", "JSON.stringify({ a: 1 }, function () { return undefined })",
        "JSON.stringify([1], function (k, v) { return k === '' ? v : 'r' })", "JSON.stringify({ a: 1 }, [])", "JSON.stringify('x', null, 2)",
        "JSON.stringify(true)", "JSON.stringify(Object(1))", "JSON.stringify([new Number(1), new String('s')])",
        "JSON.stringify({ a: 1 }, null, -1)", "JSON.stringify({ a: 1 }, null, 1.9)", "JSON.stringify({ a: 1 }, null, '')",
        "JSON.parse('{\"a\":1}').a", "JSON.parse('[1,2,3]').length", "JSON.parse('\"s\"')", "JSON.parse('1e3')", "1 / JSON.parse('-0')",
        "JSON.parse('true')", "JSON.parse('null')", "JSON.parse(' [ ] ').length", "JSON.parse('{}')", "JSON.parse('\"\\\\u0041\\\\n\"')",
        "JSON.parse('{\"1\":\"a\",\"b\":2}')[1]", "Object.keys(JSON.parse('{\"b\":1,\"a\":2,\"1\":3}')).join()",
        "JSON.parse('[1,2]', function (k, v) { return typeof v == 'number' ? v + 1 : v }).join()",
        "JSON.parse('{\"a\":{\"b\":1}}', function (k, v) { return k == 'b' ? undefined : v }).a.b",
        "JSON.parse('{\"a\":1}', function (k, v) { return k === '' ? 'root' : v })", "JSON.parse('[1,[2,3]]', function (k, v) { return v }).length",
        "JSON.parse(1)", "JSON.parse(true)", "JSON.parse('\"\\\\/\"')", "JSON.parse('\"\\\\b\\\\f\\\\r\\\\t\"').length",
        "JSON.parse('[]').constructor === Array", "JSON.parse('{}').constructor === Object", "JSON.parse('{\"__proto__\":1}').__proto__",
        "JSON.parse('\"\\\\uD83D\\\\uDE00\"').length", "JSON.parse('12345678901234567890')", "JSON.parse('2147483648')", "JSON.parse('[1.5,2]')[0]",
        "JSON.parse('{\"a\":[{\"b\":[]}]}').a[0].b.length", "JSON.stringify(JSON.parse('{\"a\":[1,{\"b\":null}]}'))", "JSON.parse('-1.5e-2')",
        "JSON.parse('  1  ')", "JSON.parse('\"\\u00e9\"')", "JSON.parse('[1,2,3]').join('|')", "JSON.parse('{\"a\":{\"b\":{\"c\":1}}}').a.b.c",
        "JSON.parse('[]', 5).length", "JSON.parse(JSON.stringify({ a: [1, 'b', null, true] })).a[3]",
    ))

    @Test
    fun arrayBuiltin() = check(listOf(
        "[1, 2, 3].length", "[].length", "[1, , 3].length", "[1, , 3][1]", "1 in [1, , 3]", "0 in [1, , 3]",
        "'' + [1, 2, 3]", "'' + []", "'' + [null, undefined, 1]", "[1, [2, [3]]].toString()", "[1, 2].toLocaleString()",
        "[1, 'a', null].toSource()", "[1, , 3].toSource()", "[, ].toSource()", "[1, , ].toSource()",
        "[1, 2, 3].join()", "[1, 2, 3].join('-')", "[1, 2, 3].join('')", "[null, undefined].join('x')", "[].join()",
        "new Array(3).length", "new Array(3).join('x')", "new Array(1, 2).length", "new Array('3').length",
        "Array(3).length", "Array(1, 2, 3)[2]", "new Array(-1)", "new Array(4294967296)", "new Array(2.5)",
        "Array.isArray([])", "Array.isArray({})", "Array.isArray(Array.prototype)", "Array.isArray()",
        "Array.of(1, 2, 3).length", "Array.of(7)[0]", "Array.of().length",
        "Array.from({ length: 3, 0: 'a', 1: 'b', 2: 'c' }).join()", "Array.from([1, 2, 3], x => x * 2).join()",
        "Array.from({ length: 2 }).join()", "Array.from([1, 2], function (x) { return x + this.k }, { k: 10 }).join()",
        "var a = [1, 2, 3]; a.push(4); a.length", "var a = [1, 2, 3]; a.push(4, 5)", "var a = []; a.push()",
        "var a = [1, 2, 3]; a.pop()", "var a = [1, 2, 3]; a.pop(); a.length", "[].pop()",
        "var a = [1, 2, 3]; a.shift()", "var a = [1, 2, 3]; a.shift(); '' + a", "[].shift()",
        "var a = [1, 2, 3]; a.unshift(0)", "var a = [1, 2, 3]; a.unshift(-1, 0); '' + a",
        "[1, 2, 3].reverse().join()", "[1, 2, 3, 4].reverse().join()", "var a = [1, 2]; a.reverse() === a",
        "[3, 1, 2].sort().join()", "[10, 9, 1].sort().join()", "[3, 1, 2].sort((a, b) => b - a).join()",
        "[3, undefined, 1, , 2].sort().length", "[3, undefined, 1, , 2].sort().join()", "['b', 'a', 'c'].sort().join()",
        "var a = [3, 1, 2]; a.sort() === a", "[1, 2, 3].sort(() => 0).join()",
        "[1, 2, 3, 4, 5].slice(1, 3).join()", "[1, 2, 3, 4, 5].slice(-2).join()", "[1, 2, 3].slice().join()",
        "[1, 2, 3].slice(5).length", "[1, 2, 3].slice(1, -1).join()", "[1, 2, 3].slice(undefined, undefined).join()",
        "var a = [1, 2, 3, 4, 5]; a.splice(1, 2).join()", "var a = [1, 2, 3, 4, 5]; a.splice(1, 2); a.join()",
        "var a = [1, 2, 3]; a.splice(1, 0, 'x', 'y'); a.join()", "var a = [1, 2, 3]; a.splice(1); a.join()",
        "var a = [1, 2, 3]; a.splice(); a.length", "var a = [1, 2, 3]; a.splice(-1, 1).join()",
        "var a = [1, 2, 3]; a.splice(1, 1, 'a').join()", "var a = [1, 2, 3]; a.splice(1, 1, 'a'); a.join()",
        "[1, 2].concat([3, 4]).join()", "[1].concat(2, [3, [4]]).length", "[1].concat().length", "[].concat([1], [2, 3]).join()",
        "var a = [1]; a.concat(a) !== a", "[1, 2].concat({ length: 1, 0: 'x' }).length",
        "[1, 2, 3, 2].indexOf(2)", "[1, 2, 3].indexOf(4)", "[1, 2, 3].indexOf(2, 2)", "[1, 2, 3].indexOf(3, -1)", "[NaN].indexOf(NaN)",
        "[1, 2, 3, 2].lastIndexOf(2)", "[1, 2, 3].lastIndexOf(9)", "[1, 2, 3, 2].lastIndexOf(2, 2)", "[1, 2].lastIndexOf(1, -5)",
        "[1, 2, 3].includes(2)", "[1, 2, 3].includes(4)", "[NaN].includes(NaN)", "[1, , 3].includes(undefined)", "[0].includes(-0)",
        "[1, 2, 3].includes(1, 1)", "[1, 2, 3].includes(3, -1)",
        "[1, 2, 3].every(x => x > 0)", "[1, 2, 3].every(x => x > 1)", "[].every(x => false)",
        "[1, 2, 3].some(x => x > 2)", "[1, 2, 3].some(x => x > 3)", "[].some(x => true)",
        "[1, 2, 3, 4].filter(x => x % 2 == 0).join()", "[1, 2, 3].map(x => x * x).join()", "[1, , 3].map(x => x * 2).length",
        "var s = 0; [1, 2, 3].forEach(x => s += x); s", "[1, 2, 3].forEach(x => x)", "var r = []; [1, 2].forEach(function (x, i, a) { r.push(x + i + a.length) }); r.join()",
        "[1, 2, 3].find(x => x > 1)", "[1, 2, 3].find(x => x > 5)", "[1, 2, 3].findIndex(x => x > 1)", "[1, 2, 3].findIndex(x => x > 5)",
        "[1, 2, 3].findLast(x => x < 3)", "[1, 2, 3].findLastIndex(x => x < 3)", "[1, 2, 3].findLast(x => x > 5)",
        "[1, 2, 3].reduce((a, b) => a + b)", "[1, 2, 3].reduce((a, b) => a + b, 10)", "[[1], [2]].reduce((a, b) => a.concat(b)).join()",
        "['a', 'b', 'c'].reduceRight((a, b) => a + b)", "[].reduce((a, b) => a + b, 'init')", "[, 1].reduce((a, b) => a + b)",
        "[1, 2, 3].map(function (x) { return this.m * x }, { m: 3 }).join()",
        "[1, 2, 3].fill(0).join()", "[1, 2, 3].fill(0, 1).join()", "[1, 2, 3].fill(0, 1, 2).join()", "[1, 2, 3].fill(9, -1).join()",
        "[1, 2, 3, 4, 5].copyWithin(0, 3).join()", "[1, 2, 3, 4, 5].copyWithin(1, 3, 4).join()", "[1, 2, 3, 4, 5].copyWithin(-2).join()",
        "[1, 2, 3].at(0)", "[1, 2, 3].at(-1)", "[1, 2, 3].at(5)", "[1, 2, 3].at()",
        "[1, [2, [3, [4]]]].flat().length", "[1, [2, [3, [4]]]].flat(2).length", "[1, [2, [3, [4]]]].flat(Infinity).join()", "[1, , 3].flat().length",
        "[1, 2].flatMap(x => [x, x * 2]).join()", "[1, 2].flatMap(x => x).join()", "[[1], [2]].flatMap(x => x).join()",
        "[1, 2, 3].keys().next().value", "[1, 2, 3].entries().next().value.join()", "[1, 2, 3].values().next().value",
        "var it = [1, 2].values(); it.next(); it.next(); it.next().done", 
        "Object.prototype.toString.call([].values())", "'' + [].values()",
        "var r = ''; for (var x of [1, 2, 3]) r += x; r", "var r = ''; for (var [k, v] of [[1, 'a'], [2, 'b']]) r += k + v; r",
        "var [a, b] = [1, 2]; a + b", "var [a, , c] = [1, 2, 3]; c", "var [a = 5] = []; a", "var [a, ...rest] = [1, 2, 3]; rest.join()",
        "var { x, y } = { x: 1, y: 2 }; x + y", "[...[1, 2], ...[3]].join()", "function f(...a) { return a.length } f(1, 2, 3)",
        "function f(a, b) { return a + b } f(...[1, 2])", "Math.max(...[1, 5, 3])",
        "[3, 1, 2].toSorted().join()", "var a = [3, 1, 2]; a.toSorted(); a.join()", "[1, 2, 3].toReversed().join()",
        "[1, 2, 3].toSpliced(1, 1).join()", "[1, 2, 3].toSpliced(1, 1, 'x', 'y').join()", "[1, 2, 3].toSpliced().join()",
        "[1, 2, 3].with(1, 'x').join()", "[1, 2, 3].with(-1, 'x').join()",
        "var a = [1, 2, 3]; a.length = 1; a.join()", "var a = [1, 2, 3]; a.length = 5; a.length", "var a = []; a[5] = 1; a.length",
        "var a = [1, 2, 3]; a.length = 0; a[0]", "var a = []; a['2'] = 'x'; a.length", "var a = []; a[-1] = 'x'; a.length",
        "var a = []; a[4294967295] = 'x'; a.length", "var a = []; a[4294967294] = 'x'; a.length", "var a = [1, 2]; delete a[0]; a.length",
        "var a = [1, 2]; delete a[0]; 0 in a", "var a = [1, 2, 3]; a.length = 'x'", "var a = [1, 2, 3]; a.length = -1",
        "var a = [1, 2, 3]; a.length = 2.5", "Object.keys([1, 2, 3]).join()", "Object.keys([1, , 3]).join()",
        "Object.getOwnPropertyNames([1, 2]).join()", "Object.entries({ a: 1, b: 2 }).join(';')", "Object.values({ a: 1, b: 2 }).join()",
        "Object.fromEntries([['a', 1], ['b', 2]]).b", "Object.getOwnPropertySymbols({}).length",
        "var a = [1, 2, 3]; Object.freeze(a); a.push(4)", "var a = [1, 2, 3]; Object.freeze(a); a[0] = 9; a[0]",
        "var a = Object.freeze([1, 2]); a.length = 0; a.length", "Object.isFrozen(Object.freeze([1]))",
        "var a = [1, 2, 3]; Object.defineProperty(a, 'length', { writable: false }); a.push(4)",
        "var a = [1, 2, 3]; Object.defineProperty(a, 'length', { value: 1 }); a.join()",
        "var a = [1, 2, 3]; Object.defineProperty(a, 1, { get: function () { return 'g' } }); a.join()",
        "var a = [1]; Object.defineProperty(a, 0, { value: 2, writable: false }); a[0] = 3; a[0]",
        "Object.getOwnPropertyDescriptor([1], 0).writable", "Object.getOwnPropertyDescriptor([1], 'length').enumerable",
        "Object.getOwnPropertyDescriptor([1], 'length').configurable", "Object.getOwnPropertyDescriptor([1], 'length').writable",
        "[].propertyIsEnumerable('length')", "[1].propertyIsEnumerable(0)", "[1].hasOwnProperty(0)", "[1].hasOwnProperty('length')",
        "var a = [1, 2]; a.x = 'y'; Object.keys(a).join()", "var a = [1, 2]; var r = ''; for (var k in a) r += k; r",
        "var a = [1, , 3]; var r = ''; for (var k in a) r += k; r", "Array.prototype.length", "Array.length", "Array.name",
        "Array.prototype.constructor === Array", "[].constructor === Array", "Object.getPrototypeOf([]) === Array.prototype",
        "Array.prototype.toString.call({ join: function () { return 'J' } })", "Array.prototype.toString.call({})",
        "Array.prototype.join.call({ length: 2, 0: 'a', 1: 'b' }, '+')", "Array.prototype.slice.call({ length: 2, 0: 'a', 1: 'b' }).join()",
        "Array.prototype.push.call({ length: 1 }, 'x')", 
        "Array.prototype.indexOf.call({ length: 2, 0: 'a', 1: 'b' }, 'b')", "function f() { return Array.prototype.slice.call(arguments).join() } f(1, 2)",
        "Array.join([1, 2], '+')", "Array.push([1], 2)", "Array.isArray(Array.slice([1, 2], 1))",
        "[] instanceof Array", "[] instanceof Object", "Object.prototype.toString.call([])", "typeof []",
        "[1, 2] == '1,2'", "[] == false", "[0] == false", "[1] == 1", "[1, 2] + [3]", "[] + {}", "+[]", "+[5]",
        "var a = [1, 2, 3]; a[1]", "var a = [1, 2, 3]; a['1']", "var a = [1, 2, 3]; a[1.0]", "var a = [1, 2, 3]; a[1.5]",
        "var a = [1, 2, 3]; a[' 1']", "var a = [1, 2, 3]; a[-0]", "var a = []; a[1e3] = 1; a.length", "var a = new Array(10001); a[5] = 1; a.length",
        "var a = new Array(5); a.push(1); a.length", "var a = new Array(5); a.join('')", "new Array(20000).map(x => 1).length",
        "var a = [1, 2, 3]; a.__proto__ = { x: 1 }; a.x", "var a = [1, 2, 3]; a.__proto__ = {}; a.length",
        "var a = [1, 2, 3]; Object.setPrototypeOf(a, null); a.length", "var a = [1, 2, 3]; a.__defineGetter__('x', function () { return 1 }); a.join()",
        "['1', '2', '3'].map(Number).join()", "[1, 2, 3].map((x, i) => i).join()",
        "var a = []; a.length = 4294967295; a.length", "var a = [1, 2, 3]; a.length = 4294967296",
        
        "Object.getOwnPropertyNames(Array.prototype).indexOf('') >= 0",
        
                        "var a = [1, 2, 3]; a.forEach(function (x) { if (x == 1) a.push(4) }); a.length",
        "var a = [1, 2, 3]; var r = 0; a.forEach(function (x) { r++; if (x == 1) a.length = 1 }); r",
        "var a = [1, 2, 3]; a.sort(function (x, y) { return x < y ? 1 : -1 }).join()",
        "[1, 2, 3].sort(function () { throw 'boom' })", "var a = [1, 2]; a.map(function () { throw 'boom' })",
        "[1, 2, 3].reduce(function () { throw 'boom' })", "[].reduce(function () {})", "[1].map()", "[1].forEach(1)",
        "[1].sort(1)", "Array.from()", "Array.from(null)", "new Array(1, 2).indexOf()", "[].at.call(null)",
        "Array.prototype.join.call(null)", "Array.from([1], 5)",
    ))

    @Test
    fun scriptObject() = check(listOf(
        "typeof Script", "new Script('1 + 2')()", "'' + new Script('1 + 2')", "new Script('var q = 8')(); q",
        "typeof new Script('1')", "Object.prototype.toString.call(new Script(''))", "new Script('').compile('3')()",
        "typeof Script.prototype.exec", "Script.length", "Script.name", "new Script('7').toString()",
        "Object.getPrototypeOf(Script.prototype) === Function.prototype", "typeof Script.prototype.compile",
        "new Script('1')() + new Script('2')()", "Script('9')()", "new Script('').length", "'' + Script.prototype",
    ))

    /**
     * The corpus: whole programs under `src/jvmTest/resources/eval`, each ending in the expression
     * that is its result. Both engines run every file and the results have to match.
     */
    @Test
    fun corpusMatchesUpstream() {
        val dir = java.io.File("src/jvmTest/resources/eval")
        val files = dir.listFiles { f -> f.name.endsWith(".js") }?.sortedBy { it.name } ?: emptyList()
        assertTrue(files.size >= 40, "corpus not found at ${dir.absolutePath}")
        val failures = mutableListOf<String>()
        for (file in files) {
            val source = file.readText()
            val expected = upstream(source)
            val actual = try { ported(source) } catch (e: Throwable) { "CRASH $e" }
            if (expected != actual) failures.add("${file.name}\n  upstream: $expected\n  ported:   $actual")
        }
        assertEquals(emptyList(), failures, "corpus evaluation differs from upstream")
    }

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
        "'abc'.charAt.call(null)", "String.prototype.trim.call(undefined)", "String.prototype.toString.call(1)",
        "String.prototype.valueOf.call({})", "'abc'.repeat(-1)", "'abc'.repeat(Infinity)", "'abc'.normalize('bad')",
        "String.fromCodePoint(-1)", "String.fromCodePoint(1.5)", "String.fromCodePoint(0x110000)", "String.fromCodePoint('x')",
        "String.raw()", "String.raw({})", "'x'.padStart.call(null, 5)", "'a'.at.call(undefined)", "String.prototype.split.call(null)",
        "String.prototype.indexOf.call(undefined, 'x')", "String.charAt()", "new String('a').codePointAt.call(null)",
        "JSON.parse()", "JSON.parse('')", "JSON.parse('{')", "JSON.parse('[1,]')", "JSON.parse('{\"a\":1,}')", "JSON.parse('01')",
        "JSON.parse('1.')", "JSON.parse('\"\\t\"')", "JSON.parse('\\'a\\'')", "JSON.parse('tru')", "JSON.parse('nul')", "JSON.parse('{\"a\" 1}')",
        "JSON.parse('\"\\\\x41\"')", "JSON.parse('\"\\\\u00zz\"')", "JSON.parse('[1] x')", "JSON.parse('1e')", "JSON.parse('-')",
        "JSON.parse('NaN')", "JSON.parse('undefined')", "JSON.parse('{1:2}')", "JSON.parse('[,1]')", "JSON.parse('{,}')", "JSON.parse('\"abc')",
        "JSON.parse('\"\\\\u12\"')", "JSON.parse('+1')", "JSON.parse('.5')", "JSON.parse('1.5.5')", "JSON.parse('{\"a\":}')",
        "JSON.parse('[1 2]')", "JSON.parse('{\"a\":1 \"b\":2}')", "JSON.parse('fals')", "JSON.parse('\"\\\\\"')",
        "JSON.stringify({ toJSON: 1 }, 5, 5)", "JSON.stringify({ get a() { throw 'boom' } })",
    ))
}
