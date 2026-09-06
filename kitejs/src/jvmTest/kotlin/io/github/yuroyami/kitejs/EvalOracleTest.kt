/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    /** A pinned "now" so Date.now() answers the same on both sides. */
    private val FIXED_NOW = 1719792000000.0

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
        v is java.math.BigInteger -> v.toString() + "n"
        v is KBigInt -> v.toString() + "n"
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

    /**
     * A message that prints an object's identity hash can never match; the hash is dropped. The
     * `regexp.` sub-package goes too, for the same reason the top-level package does (D-23).
     */
    private fun normalise(details: String): String =
        details.replace(Regex("@[0-9a-f]+"), "@").replace("regexp.", "")

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

    /** Runs the same scripts on both engines at a language version other than the default. */
    private fun checkAtVersion(version: Int, sources: List<String>) {
        val savedU = ucx.languageVersion
        val savedK = Context.getContext().languageVersion
        val uold = uscope
        val kold = kscope
        try {
            ucx.languageVersion = version
            uscope = ucx.initStandardObjects()
            Context.getContext().languageVersion = version
            kscope = Context.getContext().initStandardObjects()
            check(sources)
        } finally {
            ucx.languageVersion = savedU
            Context.getContext().languageVersion = savedK
            uscope = uold
            kscope = kold
        }
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

    // Number.prototype.toString with a radix other than 10 waits for the phase 5 BigInt. match,
    // search, matchAll and the regexp forms of split and replace wait for the phase 4 regexp
    // engine. Rhino 1.9.1 has no class syntax at all, so nothing here uses it.

    @Test
    fun symbols() = check(listOf(
        // The constructor and the primitive it makes.
        "typeof Symbol()", "typeof Symbol('a')", "typeof Symbol", "Symbol.length", "Symbol.name",
        "Symbol('a').toString()", "Symbol().toString()", "Symbol(undefined).toString()", "Symbol(null).toString()",
        "Symbol(1).toString()", "Symbol({}).toString()", "String(Symbol('a'))", "String(Symbol())",
        "Symbol('a').description", "Symbol().description", "Symbol('').description", "Symbol(0).description",
        "Symbol('a') === Symbol('a')", "Symbol() == Symbol()", "var s = Symbol('a'); s === s",
        "new Symbol()", "new Symbol('a')",
        "Symbol.prototype.toString.call(1)", "Symbol.prototype.valueOf.call('x')",
        "Object.prototype.toString.call(Symbol())", "Object.getPrototypeOf(Symbol('a')) === Symbol.prototype",
        "Object.prototype.toString.call(Symbol.prototype)",

        // The wrapper object Object() makes.
        "typeof Object(Symbol('a'))", "Object(Symbol('a')).toString()", "Object(Symbol('a')).description",
        "Object(Symbol('a')).valueOf() === Object(Symbol('a')).valueOf()",
        "var s = Symbol('a'); Object(s).valueOf() === s", "Object(Symbol('a')) == Object(Symbol('a'))",
        "var s = Symbol('a'); var o = Object(s); o.x = 1; o.x",

        // The registry.
        "Symbol.for('k') === Symbol.for('k')", "Symbol.for('k') === Symbol('k')",
        "Symbol.for('k').toString()", "Symbol.for('k').description", "Symbol.for().toString()",
        "Symbol.keyFor(Symbol.for('k'))", "Symbol.keyFor(Symbol('k'))", "Symbol.keyFor(Symbol.iterator)",
        "Symbol.keyFor(Object(Symbol.for('k2')))", "Symbol.keyFor(1)", "Symbol.keyFor()", "Symbol.keyFor('k')",
        "Symbol.for.length", "Symbol.keyFor.length",

        // Coercions, every one of which is an error except the string ones above.
        "+Symbol()", "-Symbol()", "Symbol() + 1", "1 + Symbol()", "'' + Symbol()", "`\${Symbol()}`",
        "Symbol() * 2", "Symbol() < Symbol()", "Number(Symbol())", "Symbol() | 0", "~Symbol()",
        "!Symbol()", "Symbol() ? 1 : 2", "Boolean(Symbol())", "Symbol() ?? 1",
        "var o = {}; o[Symbol()] = 1; JSON.stringify(o)",

        // Symbols as property keys.
        "var s = Symbol('k'); var o = {}; o[s] = 7; o[s]",
        "var s = Symbol('k'); var o = {}; o[s] = 7; o[Symbol('k')]",
        "var s = Symbol('k'); var o = {}; o[s] = 7; s in o",
        "var s = Symbol('k'); var o = {}; o[s] = 7; delete o[s]; o[s]",
        "var s = Symbol('k'); var o = {}; o[s] = 7; Object.keys(o).length",
        "var s = Symbol('k'); var o = {}; o[s] = 7; Object.getOwnPropertyNames(o).length",
        "var s = Symbol('k'); var o = {}; o[s] = 7; Object.getOwnPropertySymbols(o).length",
        "var s = Symbol('k'); var o = {}; o[s] = 7; Object.getOwnPropertySymbols(o)[0] === s",
        "var s = Symbol('k'); var o = {}; o[s] = 7; Object.getOwnPropertySymbols(o)[0].toString()",
        "var s = Symbol('k'); var o = {}; o[s] = 7; JSON.stringify(o)",
        "var s = Symbol('k'); var o = {}; o[s] = 7; var n = 0; for (var k in o) n++; n",
        "var s = Symbol('k'); var o = {}; Object.defineProperty(o, s, { value: 3 }); o[s]",
        "var s = Symbol('k'); var o = {}; Object.defineProperty(o, s, { value: 3 }); Object.getOwnPropertyDescriptor(o, s).writable",
        "var s = Symbol('k'); var o = {}; o[s] = 7; Object.getOwnPropertyDescriptor(o, s).value",
        "var s = Symbol('k'); var o = { get [s]() { return 9 } }; o[s]",
        "var s = Symbol('k'); var o = {}; o[s] = 7; var c = Object.assign({}, o); c[s]",
        "var s = Symbol('k'); var o = {}; o[s] = 7; o.propertyIsEnumerable(s)",
        "var s = Symbol('k'); var o = {}; o[s] = 7; o.hasOwnProperty(s)",
        "var o = {}; o[Symbol.for('r')] = 1; o[Symbol.for('r')]",

        // The well-known symbols, and where they are already read.
        "typeof Symbol.iterator", "Symbol.iterator.toString()", "Symbol.iterator === Symbol.iterator",
        "Symbol.species.toString()", "Symbol.toStringTag.toString()", "Symbol.hasInstance.toString()",
        "Symbol.isConcatSpreadable.toString()", "Symbol.toPrimitive.toString()", "Symbol.match.toString()",
        "Symbol.matchAll.toString()", "Symbol.replace.toString()", "Symbol.search.toString()",
        "Symbol.split.toString()", "Symbol.unscopables.toString()", "Symbol.isRegExp.toString()",
        "Object.getOwnPropertyDescriptor(Symbol, 'iterator').writable",
        "Object.getOwnPropertyDescriptor(Symbol, 'iterator').enumerable",
        "typeof [][Symbol.iterator]", "typeof ''[Symbol.iterator]",
        "[][Symbol.iterator].name", "Array[Symbol.species] === Array",
        "typeof Array.prototype[Symbol.unscopables]", "Array.prototype[Symbol.unscopables].flat",
        "Object.prototype.toString.call(Math)", "Object.prototype.toString.call(JSON)",
        "var o = {}; o[Symbol.toStringTag] = 'Thing'; Object.prototype.toString.call(o)",
        "var o = {}; o[Symbol.toStringTag] = 5; Object.prototype.toString.call(o)",
        "var o = { [Symbol.toPrimitive]: function () { return 42 } }; +o",
        "var o = { [Symbol.toPrimitive]: function (h) { return h } }; '' + o",
        "var o = { [Symbol.toPrimitive]: 1 }; +o",
        "var a = [1, 2]; var b = { length: 2, 0: 3, 1: 4 }; b[Symbol.isConcatSpreadable] = true; a.concat(b).length",
        "var it = [1, 2][Symbol.iterator](); it.next().value",
        "var o = {}; o[Symbol.iterator] = function () { var n = 0; return { next: function () { return { value: n, done: n++ > 2 } } } }; [...o].join()",
        "Symbol.prototype[Symbol.toStringTag]",
        "typeof Symbol.prototype[Symbol.toPrimitive]",
        "var s = Symbol('a'); s[Symbol.toPrimitive]() === s",
    ))

    @Test
    fun generators() = check(listOf(
        // The generator object itself.
        "function* g() { yield 1 } typeof g", "function* g() { yield 1 } typeof g()",
        "function* g() { yield 1 } Object.prototype.toString.call(g())",
        "function* g() { yield 1 } var it = g(); it[Symbol.iterator]() === it",
        "function* g() { yield 1 } typeof g().next", "function* g() { yield 1 } typeof g().return",
        "function* g() { yield 1 } typeof g().throw",
        "function* g() { yield 1 } var r = g().next(); r.value + ':' + r.done",
        "function* g() { yield 1 } var it = g(); it.next(); var r = it.next(); r.value + ':' + r.done",
        "function* g() {} var r = g().next(); r.value + ':' + r.done",
        "function* g() { return 5 } var r = g().next(); r.value + ':' + r.done",
        "function* g() { yield 1; return 5 } var it = g(); it.next(); var r = it.next(); r.value + ':' + r.done",
        "function* g() { yield 1 } var it = g(); it.next(); it.next(); var r = it.next(); r.value + ':' + r.done",

        // Values in and out.
        "function* g() { var x = yield 1; yield x * 2 } var it = g(); it.next(); it.next(21).value",
        "function* g() { var a = yield 1; var b = yield 2; yield a + b } var it = g(); it.next(); it.next(10); it.next(20).value",
        "function* g() { yield 1; yield 2; yield 3 } var r = []; for (var v of g()) r.push(v); r.join()",
        "function* g() { yield 1; yield 2; yield 3 } [...g()].join()",
        "function* g() { yield 1; yield 2; yield 3 } var a = [...g()]; a.length",
        "function* g() { yield 1; yield 2 } var [a, b] = g(); a + ':' + b",
        "function* g() { yield 1; yield 2; yield 3 } var [a, ...rest] = g(); a + ':' + rest.join()",
        "function* g() { var i = 0; while (true) yield i++ } var it = g(); var r = []; for (var i = 0; i < 5; i++) r.push(it.next().value); r.join()",
        "function* g() { yield 1; yield 2 } Array.from(g()).join()",
        "function* g() { yield 1; yield 2 } var m = 0; for (var v of g()) m += v; m",

        // yield* delegation.
        "function* inner() { yield 1; yield 2 } function* g() { yield 0; yield* inner(); yield 3 } [...g()].join()",
        "function* g() { yield* [1, 2, 3] } [...g()].join()",
        "function* g() { yield* 'abc' } [...g()].join()",
        "function* inner() { yield 1; return 9 } function* g() { var r = yield* inner(); yield r } [...g()].join()",
        "function* g() { yield* [] ; yield 1 } [...g()].join()",
        "function* a() { yield 1 } function* b() { yield* a(); yield 2 } function* c() { yield* b(); yield 3 } [...c()].join()",
        "function* g() { yield* [1, 2] } var it = g(); it.next(); it.return(7).value + ':' + it.next().done",
        "function* inner() { try { yield 1; yield 2 } finally { } } function* g() { yield* inner() } var it = g(); it.next(); it.return(5).value",
        "function* g() { yield* 5 }; var it = g(); try { it.next() } catch (e) { e.name }",
        "function* g() { yield* { } }; var it = g(); try { it.next() } catch (e) { e.name }",

        // return() and throw() into a suspended generator.
        "function* g() { yield 1; yield 2 } var it = g(); it.next(); var r = it.return(9); r.value + ':' + r.done",
        "function* g() { yield 1 } var it = g(); var r = it.return(9); r.value + ':' + r.done",
        "function* g() { yield 1 } var it = g(); it.next(); it.next(); var r = it.return(9); r.value + ':' + r.done",
        "function* g() { try { yield 1 } finally { globalThis.seen = 'yes' } } var it = g(); it.next(); it.return(2); globalThis.seen",
        "function* g() { try { yield 1 } finally { } } var it = g(); it.next(); it.return(2).value",
        "function* g() { try { yield 1 } catch (e) { yield 'caught ' + e } } var it = g(); it.next(); it.throw('boom').value",
        "function* g() { yield 1 } var it = g(); it.next(); try { it.throw(new Error('x')) } catch (e) { e.message }",
        "function* g() { yield 1 } var it = g(); try { it.throw('early') } catch (e) { e }",
        "function* g() { try { yield 1 } finally { yield 2 } } var it = g(); it.next(); it.return(9).value",
        "function* g() { yield 1; yield 2 } var it = g(); it.next(); it.return(); var r = it.next(); r.value + ':' + r.done",

        // Errors from inside.
        "function* g() { throw new Error('inside') } var it = g(); try { it.next() } catch (e) { e.message }",
        "function* g() { yield 1; throw new Error('later') } var it = g(); it.next(); try { it.next() } catch (e) { e.message }",
        "function* g() { throw new Error('x') } var it = g(); try { it.next() } catch (e) {} var r = it.next(); r.value + ':' + r.done",
        "function* g() { yield 1 } var it = g(); it.next.call({})",
        "function* g() { yield 1 } var it = g(); var n = it.next; try { n() } catch (e) { e.name }",

        // Where generators can be written.
        "var o = { *g() { yield 1; yield 2 } }; [...o.g()].join()",
        "var o = { *[Symbol.iterator]() { yield 1; yield 2 } }; [...o].join()",
        "var g = function* () { yield 1 }; g().next().value",
        "var g = function* named() { yield 1 }; g.name",
        "function* g() { yield this.x } var o = { x: 5, g: g }; o.g().next().value",
        "function* g() { yield arguments.length } g(1, 2, 3).next().value",
        "function* g(a, b) { yield a + b } g(1, 2).next().value",
        "function* g() { yield 1 } g.prototype.extra = 7; g().extra",
        "function* g() { yield 1 } Object.getPrototypeOf(g()) === g.prototype",
        "function* g() { yield 1 } g.length",
        "function* g() { yield (yield 1) + 1 } var it = g(); it.next(); it.next(4).value",
        "function* g() { for (var i = 0; i < 3; i++) yield i } [...g()].join()",
        "function* g() { var a = [1, 2]; for (var v of a) yield v * 2 } [...g()].join()",
        "function* g() { yield [1, 2] } g().next().value.join()",
        "function* g() { yield { a: 1 } } g().next().value.a",

        // Custom iterables, and closing one on break.
        "var o = {}; o[Symbol.iterator] = function () { var n = 0; return { next: function () { return n < 3 ? { value: n++, done: false } : { value: undefined, done: true } } } }; [...o].join()",
        "var closed = false; var o = {}; o[Symbol.iterator] = function () { return { next: function () { return { value: 1, done: false } }, return: function () { closed = true; return { done: true } } } }; for (var v of o) break; closed",
        "var o = {}; o[Symbol.iterator] = function () { return { next: function () { return { done: true } } } }; [...o].length",
        "var o = {}; o[Symbol.iterator] = 5; try { [...o] } catch (e) { e.name }",
        "var o = {}; o[Symbol.iterator] = function () { return 5 }; try { [...o] } catch (e) { e.name }",
        "function* g() { yield 1; yield 2; yield 3 } var r = []; for (var v of g()) { if (v == 2) break; r.push(v) } r.join()",
        "function* g() { try { yield 1; yield 2 } finally { globalThis.fin = 'ran' } } for (var v of g()) break; globalThis.fin",

        // The iterator built-in and its prototype.
        "typeof Iterator", "typeof StopIteration", "Object.prototype.toString.call(StopIteration)",
        "var it = new Iterator({ a: 1, b: 2 }); it.next().join()",
        "var it = new Iterator({ a: 1 }, true); it.next()",
        "var it = new Iterator({}); try { it.next() } catch (e) { e === StopIteration }",
        "new Iterator()", "new Iterator(null)", "Iterator({ a: 1 }).next().join()",
        "var it = new Iterator({ a: 1 }); it.__iterator__() === it",
        "var it = new Iterator([7, 8]); it.next().join()",
    ))

    /** JavaScript 1.7 generators: no star, and StopIteration instead of a done flag. */
    @Test
    fun legacyGenerators() = checkAtVersion(Context.VERSION_1_8, listOf(
        "function g() { yield 1 } typeof g()",
        "function g() { yield 1 } Object.prototype.toString.call(g())",
        "function g() { yield 1 } g().next()",
        "function g() { yield 1; yield 2 } var it = g(); it.next(); it.next()",
        "function g() { yield 1 } var it = g(); it.next(); try { it.next() } catch (e) { e === StopIteration }",
        "function g() { var x = yield 1; yield x * 2 } var it = g(); it.next(); it.send(21)",
        "function g() { yield 1 } var it = g(); try { it.send(5) } catch (e) { e.name }",
        "function g() { yield 1 } var it = g(); it.__iterator__() === it",
        "function g() { yield 1 } var it = g(); it.close()",
        "function g() { try { yield 1 } finally { globalThis.fin = 'ran' } } var it = g(); it.next(); it.close(); globalThis.fin",
        "function g() { try { yield 1 } catch (e) { yield 'caught ' + e } } var it = g(); it.next(); it.throw('boom')",
        "function g() { yield 1 } var it = g(); it.next(); try { it.throw('x') } catch (e) { e }",
        "function g() { yield 1; yield 2; yield 3 } var r = []; for (var v in Iterator(g())) r.push(v); r.join()",
        "function g() { yield 1; yield 2 } var s = 0; try { var it = g(); while (true) s += it.next() } catch (e) {} s",
        "function g() { for (var i = 0; i < 3; i++) yield i } var it = g(); it.next() + ',' + it.next()",
        "typeof StopIteration", "StopIteration instanceof StopIteration",
    ))

    @Test
    fun mapAndSet() = check(listOf(
        // Map basics.
        "typeof Map", "Map.length", "Map.name", "typeof new Map()", "Object.prototype.toString.call(new Map())",
        "Map()", "new Map().size", "new Map([[1, 'a'], [2, 'b']]).size",
        "var m = new Map(); m.set('a', 1); m.get('a')", "var m = new Map(); m.get('missing')",
        "var m = new Map(); m.set('a', 1).set('b', 2); m.size",
        "var m = new Map(); m.set('a', 1); m.set('a', 2); m.size + ':' + m.get('a')",
        "var m = new Map(); m.set('a', 1); m.has('a') + ':' + m.has('b')",
        "var m = new Map(); m.set('a', 1); m.delete('a') + ':' + m.size",
        "var m = new Map(); m.delete('nope')",
        "var m = new Map([['a', 1], ['b', 2]]); m.clear(); m.size",
        "var m = new Map([['a', 1]]); [...m.keys()].join()",
        "var m = new Map([['a', 1], ['b', 2]]); [...m.values()].join()",
        "var m = new Map([['a', 1]]); JSON.stringify([...m.entries()])",
        "var m = new Map([['a', 1]]); JSON.stringify([...m])",
        "var m = new Map([['a', 1], ['b', 2]]); var r = []; m.forEach(function (v, k) { r.push(k + '=' + v) }); r.join()",
        "var m = new Map([['a', 1]]); m.forEach(function (v, k, mm) { globalThis.same = mm === m }); globalThis.same",
        "var m = new Map(); m.forEach(1)",
        "new Map(1)", "new Map([1])", "new Map(['ab'])", "new Map(null).size", "new Map(undefined).size",
        "JSON.stringify(new Map([['a', 1]]))",
        "var m = new Map(); m.set('a', 1); Object.keys(m).length",
        "Map.prototype.size", "Object.getOwnPropertyDescriptor(Map.prototype, 'size').enumerable",
        "Object.getOwnPropertyDescriptor(Map.prototype, 'size').configurable",
        "typeof Object.getOwnPropertyDescriptor(Map.prototype, 'size').get",
        "Map.prototype.get.call({}, 'a')", "Map.prototype[Symbol.iterator] === Map.prototype.entries",
        "Map[Symbol.species] === Map",
        "typeof Map.groupBy", "var g = Map.groupBy([1, 2, 3, 4], function (n) { return n % 2 ? 'odd' : 'even' }); [...g.keys()].join()",
        "var g = Map.groupBy([1, 2, 3, 4], function (n) { return n % 2 ? 'odd' : 'even' }); g.get('odd').join()",
        "Map.groupBy([], function () {}).size",

        // Insertion order, and every key coercion corner.
        "var m = new Map(); m.set('b', 1); m.set('a', 2); m.set('c', 3); [...m.keys()].join()",
        "var m = new Map(); m.set('b', 1); m.set('a', 2); m.delete('b'); m.set('b', 9); [...m.keys()].join()",
        "var m = new Map(); m.set(-0, 'neg'); [...m.keys()].map(function (k) { return 1 / k }).join()",
        "var m = new Map(); m.set(0, 'a'); m.set(-0, 'b'); m.size + ':' + m.get(0)",
        "var m = new Map(); m.set(NaN, 'n'); m.get(NaN) + ':' + m.has(NaN)",
        "var m = new Map(); m.set(NaN, 'a'); m.set(NaN, 'b'); m.size",
        "var m = new Map(); m.set(1, 'int'); m.get(1.0)",
        "var m = new Map(); m.set(1, 'int'); m.get('1')",
        "var m = new Map(); m.set('1', 'str'); m.set(1, 'num'); m.size",
        "var a = {}, b = {}; var m = new Map(); m.set(a, 1); m.set(b, 2); m.size + ':' + m.get(a)",
        "var a = {}; var m = new Map(); m.set(a, 1); m.get({})",
        "var m = new Map(); m.set(null, 1); m.set(undefined, 2); m.get(null) + ':' + m.get(undefined) + ':' + m.size",
        "var m = new Map(); m.set(true, 1); m.get(true) + ':' + m.get('true')",
        "var s = Symbol('k'); var m = new Map(); m.set(s, 1); m.get(s) + ':' + m.get(Symbol('k'))",
        "var m = new Map(); m.set(Infinity, 1); m.set(-Infinity, 2); m.get(Infinity) + ':' + m.size",

        // Mutation while iterating.
        "var m = new Map([['a', 1], ['b', 2]]); var r = []; m.forEach(function (v, k) { r.push(k); if (k === 'a') m.set('c', 3) }); r.join()",
        "var m = new Map([['a', 1], ['b', 2]]); var r = []; m.forEach(function (v, k) { r.push(k); if (k === 'a') m.delete('b') }); r.join()",
        "var m = new Map([['a', 1], ['b', 2]]); var r = []; m.forEach(function (v, k) { r.push(k); m.clear() }); r.join()",
        "var m = new Map([['a', 1]]); var it = m.keys(); m.set('b', 2); [...it].join()",
        "var m = new Map([['a', 1], ['b', 2]]); var it = m.keys(); it.next(); m.delete('b'); it.next().done",
        "var m = new Map([['a', 1]]); var it = m.keys(); m.clear(); m.set('z', 1); [...it].join()",
        "var m = new Map([['a', 1], ['b', 2]]); var r = []; for (var k of m.keys()) { r.push(k); if (k === 'a') m.delete('a') } r.join()",

        // Set basics.
        "typeof Set", "Set.length", "Set.name", "Object.prototype.toString.call(new Set())", "Set()",
        "new Set().size", "new Set([1, 2, 3]).size", "new Set([1, 1, 2]).size", "new Set('hello').size",
        "[...new Set('hello')].join()",
        "var s = new Set(); s.add(1); s.has(1) + ':' + s.size",
        "var s = new Set(); s.add(1).add(2); s.size",
        "var s = new Set([1, 2]); s.delete(1) + ':' + s.size", "new Set().delete(1)",
        "var s = new Set([1, 2]); s.clear(); s.size",
        "var s = new Set([1, 2]); [...s.keys()].join()", "var s = new Set([1, 2]); [...s.values()].join()",
        "var s = new Set([1, 2]); JSON.stringify([...s.entries()])",
        "var s = new Set([1, 2]); var r = []; s.forEach(function (v, k) { r.push(v + '/' + k) }); r.join()",
        "Set.prototype.values === Set.prototype.keys", "Set.prototype[Symbol.iterator] === Set.prototype.values",
        "Set[Symbol.species] === Set", "Set.prototype.add.call({}, 1)",
        "typeof Object.getOwnPropertyDescriptor(Set.prototype, 'size').get",
        "var s = new Set(); s.add(-0); [...s].map(function (v) { return 1 / v }).join()",
        "var s = new Set(); s.add(0); s.has(-0)",
        "var s = new Set(); s.add(NaN); s.add(NaN); s.size + ':' + s.has(NaN)",
        "var s = new Set([1, 2, 3]); var r = []; for (var v of s) { r.push(v); if (v === 1) s.delete(2) } r.join()",
        "var s = new Set([1]); var it = s.values(); s.add(2); [...it].join()",
        "JSON.stringify(new Set([1, 2]))",

        // The set algebra, against real sets.
        "[...new Set([1, 2, 3]).union(new Set([3, 4]))].join()",
        "[...new Set([1, 2, 3]).intersection(new Set([2, 3, 4]))].join()",
        "[...new Set([1, 2, 3]).difference(new Set([2]))].join()",
        "[...new Set([1, 2]).symmetricDifference(new Set([2, 3]))].join()",
        "new Set([1, 2]).isSubsetOf(new Set([1, 2, 3]))",
        "new Set([1, 2, 3]).isSubsetOf(new Set([1, 2]))",
        "new Set([1, 2, 3]).isSupersetOf(new Set([1, 2]))",
        "new Set([1]).isSupersetOf(new Set([1, 2]))",
        "new Set([1, 2]).isDisjointFrom(new Set([3, 4]))",
        "new Set([1, 2]).isDisjointFrom(new Set([2, 3]))",
        "[...new Set().union(new Set([1]))].join()",
        "[...new Set([1]).intersection(new Set())].join()",
        "[...new Set([1, 2, 3, 4, 5]).difference(new Set([1]))].join()",
        "[...new Set([1]).difference(new Set([1, 2, 3, 4, 5]))].join()",
        "new Set([1]).union(new Set([2])) instanceof Set",
        "new Set([1]).union(new Set([2])) === undefined",

        // The set algebra, against set-like objects and against bad ones.
        "var like = { size: 2, has: function (v) { return v === 1 || v === 2 }, keys: function () { return [1, 2][Symbol.iterator]() } }; [...new Set([2, 3]).intersection(like)].join()",
        "var like = { size: 2, has: function (v) { return v === 1 || v === 2 }, keys: function () { return [1, 2][Symbol.iterator]() } }; [...new Set([3]).union(like)].join()",
        "var like = { size: 1, has: function (v) { return v === 1 }, keys: function () { return [1][Symbol.iterator]() } }; new Set([1]).isSubsetOf(like)",
        "var like = { size: 1, has: function (v) { return v === 1 }, keys: function () { return [1][Symbol.iterator]() } }; [...new Set([1, 2]).difference(like)].join()",
        "new Set([1]).union({})", "new Set([1]).union({ size: 1 })", "new Set([1]).union({ size: 1, has: 1, keys: 1 })",
        "new Set([1]).union({ size: 1, has: function () {}, keys: 1 })",
        "new Set([1]).union({ size: NaN, has: function () {}, keys: function () {} })",
        "new Set([1]).intersection({ size: 1, has: 5, keys: function () {} })",
        "new Set([1]).isSubsetOf({ size: 1, has: 5, keys: function () {} })",
        "new Set([1]).union()", "new Set([1]).intersection(null)", "new Set([1]).difference(5)",
        "var like = { size: Infinity, has: function () { return true }, keys: function () { return [][Symbol.iterator]() } }; new Set([1]).isSubsetOf(like)",
    ))

    /** Renders an exec result fully, so a difference in any capture or index shows up. */
    private fun execScripts(cases: List<String>): List<String> = cases.map {
        "(function () { var r = ($it); if (r === null) return 'null'; " +
            "var out = []; for (var i = 0; i < r.length; i++) out.push(r[i] === undefined ? 'u' : String(r[i])); " +
            "return out.join('~') + '|' + r.index + '|' + r.input + '|' + " +
            "(r.groups === undefined ? 'nogroups' : Object.keys(r.groups).map(function (k) { return k + '=' + r.groups[k] }).join(',')) })()"
    }

    @Test
    fun regexpBasics() = check(listOf(
        // The object itself.
        "typeof RegExp", "RegExp.length", "typeof /x/", "Object.prototype.toString.call(/x/)",
        "/abc/.source", "/abc/gimsy.flags", "new RegExp('a').source", "new RegExp('').source",
        "String(new RegExp(''))", "String(/(?:)/)", "String(/a\\/b/)", "String(new RegExp('a/b'))",
        "/a/.global", "/a/g.global", "/a/i.ignoreCase", "/a/m.multiline", "/a/s.dotAll", "/a/y.sticky", "/a/u.unicode",
        "/a/gimsy.flags", "new RegExp('a', 'gi').flags", "new RegExp(/a/g).flags", "new RegExp(/a/g, 'i').flags",
        "new RegExp('a', 'x')", "new RegExp('a', 'gg')", "new RegExp('a', 'ui')",
        "/a/ instanceof RegExp", "/a/.constructor === RegExp", "RegExp(/a/) === RegExp(/a/)",
        "var r = /a/; RegExp(r) === r", "RegExp[Symbol.species] === RegExp",
        "Object.getOwnPropertyDescriptor(/a/, 'lastIndex').writable",
        "Object.getOwnPropertyDescriptor(/a/, 'lastIndex').enumerable",
        "typeof /a/.exec", "typeof /a/.test", "typeof /a/.compile", "typeof /a/[Symbol.match]",
        "typeof /a/[Symbol.replace]", "typeof /a/[Symbol.split]", "typeof /a/[Symbol.search]", "typeof /a/[Symbol.matchAll]",
        "RegExp.prototype.toString.call({ source: 'x', flags: 'g' })",
        "RegExp.prototype.source", "RegExp.prototype.exec.call({}, 'x')",

        // Syntax errors.
        "/[/", "/(/", "/)/", "/a{2,1}/", "new RegExp('(')", "new RegExp('[a-')", "new RegExp('\\\\')",
        "new RegExp('a**')", "new RegExp('*')", "new RegExp('+')", "new RegExp('?')",
        "new RegExp('(?<>a)')", "new RegExp('(?<a>x)(?<a>y)')", "new RegExp('\\\\k<nope>', 'u')",
        "new RegExp('[z-a]')", "new RegExp('\\\\p{Nope}', 'u')", "new RegExp('\\\\p{Script=Nope}', 'u')",
        "new RegExp('(?=a)*', 'u')", "new RegExp('(?<=a)*')",

        // Capture group names written with unicode escapes, which is the one path that has to
        // rebuild the name out of source pieces.
        "/(?<\\u0061>x)/.exec('x').groups.a",
        "/(?<a\\u0062c>x)/.exec('x').groups.abc",
        "/(?<\\u0061\\u0062>x)/.exec('x').groups.ab",
        "/(?<x\\u0031>y)/.exec('y').groups.x1",
        "new RegExp('(?<\\\\u0061>x)').exec('x').groups.a",
        "new RegExp('(?<\\\\u0030>x)')",
        "new RegExp('(?<a\\\\u0020b>x)')",
        "Object.keys(/(?<\\u0061bc>x)/.exec('x').groups).join()",
    ))

    @Test
    fun regexpMatching() = check(execScripts(listOf(
        // Literals and quantifiers.
        "/a/.exec('bab')", "/a+/.exec('caaat')", "/a*/.exec('bbb')", "/a?/.exec('bbb')",
        "/a{2}/.exec('caaat')", "/a{2,}/.exec('caaat')", "/a{2,3}/.exec('caaaaat')", "/a{0}/.exec('x')",
        "/a+?/.exec('caaat')", "/a{2,3}?/.exec('caaaaat')", "/a*?b/.exec('aaab')",
        "/.+/.exec('one\ntwo')", "/.+/s.exec('one\ntwo')",
        "/x/.exec('abc')", "/(a)(b)/.exec('ab')", "/(a)|(b)/.exec('b')",
        "/(a+)(b+)/.exec('aabbb')", "/((a)(b))/.exec('ab')",
        "/(a)?b/.exec('b')", "/(a)?b/.exec('ab')",
        "/^abc$/.exec('abc')", "/^b/m.exec('a\nb')", "/a$/m.exec('a\nb')",
        "/\\bfoo\\b/.exec('a foo b')", "/\\Bfoo/.exec('afoo')",

        // Classes and escapes.
        "/[abc]+/.exec('xxabcabxx')", "/[^abc]+/.exec('abcXYZabc')", "/[a-z]+/.exec('123abc456')",
        "/[\\d]+/.exec('ab123cd')", "/\\d+/.exec('ab123cd')", "/\\D+/.exec('12ab34')",
        "/\\w+/.exec('  a_b1  ')", "/\\W+/.exec('ab  cd')", "/\\s+/.exec('a  b')", "/\\S+/.exec('  ab  ')",
        "/[\\]]/.exec('a]b')", "/[-a]/.exec('-')", "/[a-]/.exec('-')", "/[\\b]/.exec('a\\bb')",
        "/[\\w-]+/.exec('a-b')", "/[^]/.exec('a')", "/[]/.exec('a')",
        "/\\t\\n\\r\\f\\v/.exec('\\t\\n\\r\\f\\v')", "/\\0/.exec('\\0')", "/\\cA/.exec('\\u0001')",
        "/\\x41/.exec('A')", "/\\u0041/.exec('A')", "/\\u{41}/u.exec('A')",
        "/[\\u0041-\\u005A]+/.exec('abcABCdef')",

        // Groups, backreferences and lookaround.
        "/(a)\\1/.exec('aa')", "/(a)\\1/.exec('ab')", "/(\\w)\\1/.exec('abba')",
        "/(?:ab)+/.exec('ababab')", "/(?<y>a)(?<z>b)/.exec('ab')",
        "/\\k<y>(?<y>a)/.exec('a')", "/(?<y>a)\\k<y>/.exec('aa')",
        "/a(?=b)/.exec('ab')", "/a(?=b)/.exec('ac')", "/a(?!b)/.exec('ac')",
        "/(?<=a)b/.exec('ab')", "/(?<=a)b/.exec('cb')", "/(?<!a)b/.exec('cb')",
        "/(?<=(a))b/.exec('ab')",
        "/(a)(?:b)(c)/.exec('abc')",
        "/(z)?(a)/.exec('a')",
        "/(a|b)+/.exec('abab')",
        "/^(a+)+$/.exec('aaa')",

        // Unicode.
        "/./u.exec('\\uD83D\\uDE00')", "/./.exec('\\uD83D\\uDE00')",
        "/\\u{1F600}/u.exec('\\uD83D\\uDE00')",
        "/^\\p{L}+$/u.exec('abcABC')", "/\\p{Nd}+/u.exec('ab123')",
        "/\\p{Script=Greek}+/u.exec('ab\\u03B1\\u03B2')",
        "/\\P{L}+/u.exec('ab123')", "/\\p{Alphabetic}+/u.exec('123abc')",
        "/\\p{White_Space}/u.exec('a b')", "/\\p{ASCII}+/u.exec('ab')",
        "/[\\p{Lu}]+/u.exec('abABcd')",

        // Sticky and global state.
        "var r = /a/y; r.lastIndex = 1; r.exec('ba')",
        "var r = /a/y; r.lastIndex = 0; r.exec('ba')",
        "var r = /a/g; r.exec('aa'); r.exec('aa')",
        "var r = /a/g; r.lastIndex = 5; r.exec('aa')",
    )))

    @Test
    fun regexpStringMethods() = check(listOf(
        // match, matchAll and search.
        "'aXbXc'.match(/X/) === null", "'aXbXc'.match(/X/)[0]", "'aXbXc'.match(/X/).index",
        "'aXbXc'.match(/X/g).join()", "'abc'.match(/z/g)", "'abc'.match(/z/)",
        "'a1b2'.match(/\\d/g).join()", "'abc'.match('b')[0]", "'aaa'.match(/a/g).length",
        "[...'aXbXc'.matchAll(/X/g)].map(function (m) { return m[0] + '@' + m.index }).join()",
        "[...'a1b2'.matchAll(/(\\w)(\\d)/g)].map(function (m) { return m[1] + m[2] }).join()",
        "'abc'.matchAll(/b/)", "try { 'abc'.matchAll(/b/) } catch (e) { e.name }",
        "'hello'.search(/l/)", "'hello'.search(/z/)", "'hello'.search('ll')",
        "var r = /l/g; r.lastIndex = 4; 'hello'.search(r) + ':' + r.lastIndex",

        // replace and replaceAll.
        "'a1b2'.replace(/\\d/, '#')", "'a1b2'.replace(/\\d/g, '#')",
        "'abc'.replace(/b/, function (m) { return m.toUpperCase() })",
        "'a1b2'.replace(/(\\w)(\\d)/g, '$2$1')",
        "'abc'.replace(/b/, '[$&]')", "'abc'.replace(/b/, '[$`]')", "'abc'.replace(/b/, \"[$']\")",
        "'abc'.replace(/b/, '[$$]')", "'abc'.replace(/(b)/, '[$1]')", "'abc'.replace(/(b)/, '[$2]')",
        "'abc'.replace(/(?<m>b)/, '[$<m>]')", "'abc'.replace(/b/, '[$<m>]')",
        "'a1b2'.replace(/(\\d)/g, function (m, p1, off, str) { return p1 + ':' + off + ':' + str.length })",
        "'aaa'.replaceAll('a', 'b')", "'aaa'.replaceAll(/a/g, 'b')", "'aaa'.replaceAll(/a/, 'b')",
        "'abc'.replace('b', 'X')", "'abc'.replace('z', 'X')", "''.replace(/x/g, 'y')",
        "'aaa'.replace(/(?:)/g, '-')", "'abc'.replace(/(?:)/, '-')",
        "'abc'.replace(/./g, function () { return arguments.length })",
        "'abc'.replace(/(a)(b)/, function () { return arguments.length })",

        // split.
        "'a,b,c'.split(/,/).join('|')", "'a1b2c'.split(/\\d/).join('|')",
        "'a1b2c'.split(/(\\d)/).join('|')", "'abc'.split(/(?:)/).join('|')",
        "'abc'.split(/x/).join('|')", "''.split(/x/).length", "''.split(/(?:)/).length",
        "'a,b,c'.split(/,/, 2).join('|')", "'a,b,c'.split(/,/, 0).length",
        "'a1b2c'.split(/(\\d)/, 3).join('|')",
        "'aXbXc'.split(/x/i).join('|')", "'test'.split(/(?=s)/).join('|')",
        "'a,b'.split(',').join('|')", "'abc'.split('').join('|')",

        // The legacy statics.
        "/(\\d+)/.exec('a123b'); RegExp.$1", "/(\\d+)/.exec('a123b'); RegExp.lastMatch",
        "/(\\d+)/.exec('a123b'); RegExp.leftContext", "/(\\d+)/.exec('a123b'); RegExp.rightContext",
        "/(\\d+)/.exec('a123b'); RegExp.lastParen", "/(a)(b)/.exec('ab'); RegExp.$1 + RegExp.$2",
        "/x/.exec('x'); RegExp.$1", "'a1'.replace(/(\\d)/, 'x'); RegExp.$1",
        "RegExp.input = 'hello'; RegExp.input",

        // exec and test corners.
        "/a/.test('a')", "/a/.test('b')", "/a/.test()", "RegExp.prototype.test.call(/a/, 'a')",
        "var r = /a/g; r.test('aa') + ':' + r.lastIndex + ':' + r.test('aa') + ':' + r.lastIndex + ':' + r.test('aa')",
        "var r = /a/; r.test('aa'); r.lastIndex",
        "var r = /a/g; r.exec('bbb'); r.lastIndex",
        "var r = /(?:)/g; r.exec('ab'); r.lastIndex + ':' + r.exec('ab').index",
        "var r = /a/; r.compile('b'); r.source", "var r = /a/g; r.compile('b', 'i'); r.flags",
        "var r = /a/; r.lastIndex = 'x'; r.lastIndex",
    ))

    @Test
    fun regexpSymbolProtocols() = check(listOf(
        // The protocols reached through a plain object rather than a real regexp.
        "var o = {}; o[Symbol.replace] = function (s, r) { return 'custom' }; 'abc'.replace(o, 'x')",
        "var o = {}; o[Symbol.split] = function (s, l) { return ['x', 'y'] }; 'abc'.split(o).join('|')",
        "var o = {}; o[Symbol.match] = function (s) { return 'm' }; 'abc'.match(o)",
        "var o = {}; o[Symbol.search] = function (s) { return 7 }; 'abc'.search(o)",
        "RegExp.prototype[Symbol.replace].call(/b/, 'abc', 'X')",
        "RegExp.prototype[Symbol.split].call(/,/, 'a,b').join('|')",
        "RegExp.prototype[Symbol.match].call(/b/, 'abc')[0]",
        "RegExp.prototype[Symbol.search].call(/b/, 'abc')",
        "RegExp.prototype[Symbol.replace].call({}, 'abc', 'X')",
        "RegExp.prototype[Symbol.split].call({ flags: '', exec: function () { return null } }, 'abc').join('|')",
        "var calls = 0; var r = /b/; r.exec = function (s) { calls++; return null }; 'abc'.replace(r, 'X') + ':' + calls",
        "var r = /b/g; r.exec = function (s) { return null }; 'abc'.match(r)",
        "var o = { flags: 'g', exec: function () { return null } }; RegExp.prototype[Symbol.match].call(o, 'abc')",
        "typeof RegExp.prototype[Symbol.matchAll].call(/a/g, 'aa')",
        "[...RegExp.prototype[Symbol.matchAll].call(/a/g, 'aa')].length",
        "Object.prototype.toString.call(/a/g[Symbol.matchAll]('aa'))",
    ))

    /**
     * Runs the scripts with the same zone and the same fixed clock on both engines. Date is the
     * one builtin whose answers depend on something outside the script.
     */
    private fun checkInZone(zoneId: String, sources: List<String>) {
        val savedULocale = java.util.Locale.getDefault()
        val uold = uscope
        val kold = kscope
        try {
            java.util.Locale.setDefault(java.util.Locale.US)
            ucx.timeZone = java.util.TimeZone.getTimeZone(zoneId)
            uscope = ucx.initStandardObjects()
            Context.getContext().timeZone = kotlinx.datetime.TimeZone.of(zoneId)
            Context.getContext().clock = { FIXED_NOW }
            kscope = Context.getContext().initStandardObjects()
            check(sources)
        } finally {
            java.util.Locale.setDefault(savedULocale)
            ucx.timeZone = java.util.TimeZone.getDefault()
            Context.getContext().timeZone = kotlinx.datetime.TimeZone.currentSystemDefault()
            uscope = uold
            kscope = kold
        }
    }

    /** Everything that does not print a zone name, checked in four zones. */
    private fun dateScripts(): List<String> = listOf(
        // Construction.
        "typeof Date", "Date.length", "typeof new Date()", "Object.prototype.toString.call(new Date(0))",
        "new Date(0).getTime()", "new Date(0).valueOf()", "new Date(1719792000000).getTime()",
        "new Date(-1).getTime()", "new Date(NaN).getTime()", "new Date(Infinity).getTime()",
        "new Date(8.64e15).getTime()", "new Date(8.64e15 + 1).getTime()", "new Date(-8.64e15).getTime()",
        "new Date(2024, 0).getTime()", "new Date(2024, 0, 2).getTime()",
        "new Date(2024, 0, 2, 3).getTime()", "new Date(2024, 0, 2, 3, 4).getTime()",
        "new Date(2024, 0, 2, 3, 4, 5).getTime()", "new Date(2024, 0, 2, 3, 4, 5, 6).getTime()",
        "new Date(99, 0, 1).getFullYear()", "new Date(0, 0, 1).getFullYear()", "new Date(100, 0, 1).getFullYear()",
        "new Date(2024, 12, 1).getMonth()", "new Date(2024, -1, 1).getMonth()",
        "new Date(2024, 1, 30).getDate()", "new Date(2024, 1, 0).getDate()",
        "new Date(2024, 0, 1, 25).getDate()", "new Date(NaN, 0).getTime()",
        "var d = new Date(0); var e = new Date(d); e.getTime()",
        "new Date('2024-01-02T03:04:05.006Z').getTime()",
        "new Date(true).getTime()", "new Date(null).getTime()", "new Date(undefined).getTime()",
        "new Date({}).getTime()", "new Date([]).getTime()",

        // Date.UTC and Date.parse.
        "Date.UTC()", "Date.UTC(2024)", "Date.UTC(2024, 0)", "Date.UTC(2024, 0, 2)",
        "Date.UTC(2024, 0, 2, 3, 4, 5, 6)", "Date.UTC(NaN)", "Date.UTC(99, 0)",
        "Date.parse('2024-01-02T03:04:05.006Z')", "Date.parse('2024-01-02T03:04:05Z')",
        "Date.parse('2024-01-02T03:04Z')", "Date.parse('2024-01-02')", "Date.parse('2024-01')", "Date.parse('2024')",
        "Date.parse('2024-01-02T03:04:05.006+02:00')", "Date.parse('2024-01-02T03:04:05.006-05:30')",
        "Date.parse('2024-01-02T03:04:05.006+0200')", "Date.parse('+002024-01-02T00:00:00Z')",
        "Date.parse('-000001-01-01T00:00:00Z')", "Date.parse('2024-01-02T03:04:05.6Z')",
        "Date.parse('2024-01-02T03:04:05.06Z')", "Date.parse('2024-13-01')", "Date.parse('2024-02-30')",
        "Date.parse('2024-01-02T25:00:00Z')", "Date.parse('2024-01-02T24:00:00Z')",
        "Date.parse('2024-01-02T24:00:01Z')", "Date.parse('garbage')", "Date.parse('')",
        "Date.parse('Jan 2, 2024')", "Date.parse('2 Jan 2024')", "Date.parse('Tue Jan 02 2024')",
        "Date.parse('Jan 2 2024 03:04:05')", "Date.parse('Jan 2 2024 03:04:05 GMT')",
        "Date.parse('Jan 2 2024 03:04:05 GMT+0200')", "Date.parse('Jan 2 2024 03:04:05 GMT-05:30')",
        "Date.parse('Jan 2 2024 3:04 PM')", "Date.parse('Jan 2 2024 12:04 AM')", "Date.parse('Jan 2 2024 12:04 PM')",
        "Date.parse('Jan 2 2024 (a comment) 03:04')", "Date.parse('1/2/2024')", "Date.parse('2024/01/02')",
        "Date.parse('Mon Jan 02 2024 EST')", "Date.parse('Mon Jan 02 2024 PDT')", "Date.parse('Jan 2 2024 UTC')",

        // Getters, both local and UTC.
        "var d = new Date(1719792000000); [d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate(), d.getUTCDay(), d.getUTCHours(), d.getUTCMinutes(), d.getUTCSeconds(), d.getUTCMilliseconds()].join()",
        "var d = new Date(1719792000000); [d.getFullYear(), d.getMonth(), d.getDate(), d.getDay(), d.getHours(), d.getMinutes(), d.getSeconds(), d.getMilliseconds()].join()",
        "var d = new Date(0); [d.getFullYear(), d.getMonth(), d.getDate(), d.getDay(), d.getHours()].join()",
        "new Date(1719792000000).getTimezoneOffset()", "new Date(0).getTimezoneOffset()",
        "new Date(NaN).getFullYear()", "new Date(NaN).getTimezoneOffset()",
        "new Date(1719792000000).getYear()", "new Date(0).getYear()",

        // Setters.
        "var d = new Date(0); d.setTime(1719792000000); d.getTime()",
        "var d = new Date(0); d.setTime(NaN); d.getTime()",
        "var d = new Date(0); d.setUTCMilliseconds(500); d.getTime()",
        "var d = new Date(0); d.setUTCSeconds(30); d.getTime()",
        "var d = new Date(0); d.setUTCSeconds(30, 500); d.getTime()",
        "var d = new Date(0); d.setUTCMinutes(30); d.getTime()",
        "var d = new Date(0); d.setUTCHours(6); d.getTime()",
        "var d = new Date(0); d.setUTCDate(15); d.getTime()",
        "var d = new Date(0); d.setUTCMonth(6); d.getTime()",
        "var d = new Date(0); d.setUTCFullYear(2024); d.getTime()",
        "var d = new Date(0); d.setUTCFullYear(2024, 5, 15); d.getTime()",
        "var d = new Date(0); d.setMilliseconds(500); d.getUTCMilliseconds()",
        "var d = new Date(0); d.setSeconds(30); d.getUTCSeconds()",
        "var d = new Date(0); d.setMinutes(30); d.getUTCMinutes()",
        "var d = new Date(0); d.setHours(6); d.getHours()",
        "var d = new Date(0); d.setDate(15); d.getDate()",
        "var d = new Date(0); d.setMonth(6); d.getMonth()",
        "var d = new Date(0); d.setFullYear(2024); d.getFullYear()",
        "var d = new Date(0); d.setMilliseconds(); d.getTime()",
        "var d = new Date(0); d.setSeconds(NaN); d.getTime()",
        "var d = new Date(NaN); d.setFullYear(2024); d.getTime()",
        "var d = new Date(NaN); d.setMonth(1); d.getTime()",
        "var d = new Date(0); d.setYear(99); d.getFullYear()",
        "var d = new Date(0); d.setYear(2024); d.getFullYear()",
        "var d = new Date(0); d.setYear(NaN); d.getTime()",
        "var d = new Date(0); d.setUTCDate(32); d.getUTCMonth()",
        "var d = new Date(0); d.setUTCMonth(-1); d.getUTCFullYear()",

        // The formats that do not carry a zone name.
        "new Date(1719792000000).toISOString()", "new Date(0).toISOString()",
        "new Date(-62167219200000).toISOString()", "new Date(253402300799999).toISOString()",
        "new Date(NaN).toISOString()", "try { new Date(NaN).toISOString() } catch (e) { e.name }",
        "new Date(1719792000000).toUTCString()", "new Date(0).toUTCString()", "new Date(NaN).toUTCString()",
        "new Date(0).toGMTString()", "Date.prototype.toGMTString === Date.prototype.toUTCString",
        "new Date(1719792000000).toJSON()", "new Date(NaN).toJSON()",
        "JSON.stringify({ d: new Date(0) })", "JSON.stringify(new Date(0))",
        "new Date(0).toSource()", "new Date(NaN).toSource()",
        "new Date(1719792000000).toDateString()", "new Date(NaN).toDateString()",

        // Coercion and the prototype.
        "+new Date(0)", "'' + new Date(NaN)", "new Date(0) instanceof Date",
        "typeof new Date(0)[Symbol.toPrimitive]",
        "new Date(0)[Symbol.toPrimitive]('number')",
        "new Date(0)[Symbol.toPrimitive]('string') === new Date(0).toString()",
        "new Date(0)[Symbol.toPrimitive]('default') === new Date(0).toString()",
        "try { new Date(0)[Symbol.toPrimitive]('nope') } catch (e) { e.name }",
        "Date.prototype.getTime.call({})", "Date.prototype.valueOf.call(1)",
        "Object.prototype.toString.call(Date.prototype)",
        "Date.prototype.getTime.call(Date.prototype)",
        "typeof Date.now()", "Date.now() === Date.now()",
        "var a = Date.now(); var b = new Date().getTime(); a === b",
    )

    @Test
    fun dateInUtc() = checkInZone("UTC", dateScripts() + listOf(
        // These print the zone's short name, which only lines up when it equals the zone id.
        "new Date(0).toString()", "new Date(1719792000000).toString()",
        "new Date(0).toTimeString()", "new Date(1719792000000).toTimeString()",
        "new Date(-1000000000000).toString()", "new Date(NaN).toString()",
        "String(new Date(0))", "new Date(0) + ''",
        "new Date(0).toLocaleString()", "new Date(0).toLocaleDateString()", "new Date(0).toLocaleTimeString()",
        "new Date(1719792000000).toLocaleString()", "new Date(NaN).toLocaleString()",
        "new Date(1704067200000).toLocaleString()", "new Date(1704110645678).toLocaleString()",
    ))

    @Test
    fun dateInBerlin() = checkInZone("Europe/Berlin", dateScripts())

    @Test
    fun dateInNewYork() = checkInZone("America/New_York", dateScripts())

    @Test
    fun dateInFixedOffset() = checkInZone("GMT+05:30", dateScripts())

    /**
     * Below ES6 the locale formats are the long ones, and they print the zone's short name. That
     * only lines up with upstream where the name equals the zone id, so this runs in UTC.
     */
    @Test
    fun dateLocaleFormatsBeforeEs6() {
        val savedU = ucx.languageVersion
        val savedK = Context.getContext().languageVersion
        try {
            ucx.languageVersion = UContext.VERSION_1_8
            Context.getContext().languageVersion = Context.VERSION_1_8
            checkInZone("UTC", listOf(
                "new Date(0).toLocaleString()", "new Date(0).toLocaleDateString()", "new Date(0).toLocaleTimeString()",
                "new Date(1719792000000).toLocaleString()", "new Date(1719792000000).toLocaleDateString()",
                "new Date(1719792000000).toLocaleTimeString()",
                "new Date(1704110645678).toLocaleString()", "new Date(NaN).toLocaleString()",
                "new Date(Date.UTC(2024, 0, 1, 0, 0, 0)).toLocaleTimeString()",
                "new Date(Date.UTC(2024, 0, 1, 12, 0, 0)).toLocaleTimeString()",
                "new Date(Date.UTC(2024, 11, 25, 13, 5, 9)).toLocaleString()",
                "new Date(0).toString()", "new Date(0).getYear()",
                "new Date(1719792000000).getYear()",
                "new Date(0).toLocaleString('en-US')",
            ))
        } finally {
            ucx.languageVersion = savedU
            Context.getContext().languageVersion = savedK
        }
    }

    /** The ES6 locale formats print no zone name, so they can be checked in any zone. */
    @Test
    fun dateLocaleFormats() = checkInZone("Europe/Berlin", listOf(
        "new Date(0).toLocaleString()", "new Date(0).toLocaleDateString()", "new Date(0).toLocaleTimeString()",
        "new Date(1719792000000).toLocaleString()", "new Date(1719792000000).toLocaleDateString()",
        "new Date(1719792000000).toLocaleTimeString()",
        "new Date(1704110645678).toLocaleString()", "new Date(1704067200000).toLocaleString()",
        "new Date(NaN).toLocaleString()", "new Date(NaN).toLocaleDateString()", "new Date(NaN).toLocaleTimeString()",
        "new Date(Date.UTC(2024, 0, 1, 0, 0)).toLocaleTimeString()",
        "new Date(Date.UTC(2024, 0, 1, 11, 0)).toLocaleTimeString()",
        "new Date(Date.UTC(2024, 0, 1, 12, 0)).toLocaleTimeString()",
        "new Date(Date.UTC(2024, 0, 1, 23, 0)).toLocaleTimeString()",
        "new Date(Date.UTC(5, 0, 1)).toLocaleDateString()",
        "new Date(0).toLocaleString(['en-US'])", "new Date(0).toLocaleString('en-US')",
    ))

    @Test
    fun typedArrays() = check(listOf(
        "var ta = new Uint8Array(4); ta.buffer.transfer(); try { for (var v of ta) {} 'no throw' } catch(e) { e.name + ': ' + e.message }",
        "var ta = new Uint8Array(4); ta.buffer.transfer(); try { ta.keys().next() } catch(e) { e.name + ': ' + e.message }",
        "var ta = new Uint8Array([1,2,3]); var out=[]; for (var v of ta) out.push(v); out.join(',')",
        "var ta = new Uint8Array([1,2,3]); ta.buffer.transfer(); try { Array.from(ta).join(',') } catch(e) { e.name }",
        // The constructors and their shape.
        "typeof ArrayBuffer", "typeof Int8Array", "typeof DataView",
        "Int8Array.BYTES_PER_ELEMENT", "Int16Array.BYTES_PER_ELEMENT", "Int32Array.BYTES_PER_ELEMENT",
        "Float32Array.BYTES_PER_ELEMENT", "Float64Array.BYTES_PER_ELEMENT", "Uint8ClampedArray.BYTES_PER_ELEMENT",
        "Int8Array.prototype.BYTES_PER_ELEMENT", "Int8Array.name", "Int8Array.length",
        "Object.getPrototypeOf(Int8Array) === Object.getPrototypeOf(Uint8Array)",
        "Object.getPrototypeOf(Int8Array.prototype) === Object.getPrototypeOf(Uint8Array.prototype)",
        "Object.prototype.toString.call(new Int8Array(1))", "Object.prototype.toString.call(new ArrayBuffer(1))",
        "Object.prototype.toString.call(new DataView(new ArrayBuffer(1)))",
        "Int8Array[Symbol.species] === Int8Array",
        "Int8Array()", "ArrayBuffer()", "DataView()",
        "new Int8Array(1) instanceof Int8Array",

        // ArrayBuffer.
        "new ArrayBuffer(8).byteLength", "new ArrayBuffer().byteLength", "new ArrayBuffer(0).byteLength",
        "new ArrayBuffer(-1)", "new ArrayBuffer(NaN).byteLength", "new ArrayBuffer(2.7).byteLength",
        "new ArrayBuffer(8).detached", "ArrayBuffer.isView(new Int8Array(1))", "ArrayBuffer.isView([])",
        "ArrayBuffer.isView(new DataView(new ArrayBuffer(1)))", "ArrayBuffer.isView()",
        "new ArrayBuffer(8).slice(2).byteLength", "new ArrayBuffer(8).slice(2, 5).byteLength",
        "new ArrayBuffer(8).slice(-3).byteLength", "new ArrayBuffer(8).slice(5, 2).byteLength",
        "var b = new ArrayBuffer(4); var v = new Uint8Array(b); v[0] = 7; new Uint8Array(b.slice(0))[0]",
        "var b = new ArrayBuffer(4); b.transfer().byteLength + ':' + b.detached",
        "var b = new ArrayBuffer(4); b.transfer(8).byteLength",
        "var b = new ArrayBuffer(4); b.transfer(); b.transfer()",
        "var b = new ArrayBuffer(4); var v = new Uint8Array(b); b.transfer(); v.length",
        "var b = new ArrayBuffer(4); var v = new Uint8Array(b); b.transfer(); v[0]",
        "ArrayBuffer.prototype.byteLength", "ArrayBuffer.prototype.slice.call({})",

        // Construction from every source.
        "new Int8Array(4).length", "new Int8Array().length", "new Int8Array(0).length",
        "new Int8Array([1, 2, 3]).join()", "new Int8Array([1.7, -1.7, 300]).join()",
        "new Uint8Array([1.7, -1.7, 300]).join()", "new Uint8ClampedArray([-5, 0.5, 1.5, 2.5, 300]).join()",
        "new Int16Array([40000, -40000]).join()", "new Uint16Array([70000, -1]).join()",
        "new Int32Array([2147483648, -2147483649]).join()", "new Uint32Array([-1, 4294967296]).join()",
        "new Float32Array([0.1, 1/3]).join()", "new Float64Array([0.1, 1/3]).join()",
        "new Float64Array([NaN, Infinity, -Infinity, -0]).join()",
        "new Int8Array([NaN, undefined, null]).join()", "new Float64Array([NaN, undefined, null]).join()",
        "new Int8Array(new Int16Array([300, -300])).join()",
        "new Float64Array(new Int8Array([1, 2])).join()",
        "new Int8Array('3').length", "new Int8Array(true)",
        "var b = new ArrayBuffer(8); new Int16Array(b).length",
        "var b = new ArrayBuffer(8); new Int16Array(b, 2).length",
        "var b = new ArrayBuffer(8); new Int16Array(b, 2, 2).length",
        "var b = new ArrayBuffer(8); new Int16Array(b, 3)",
        "var b = new ArrayBuffer(8); new Int16Array(b, 2, 4)",
        "var b = new ArrayBuffer(8); new Int16Array(b, 10)",
        "var b = new ArrayBuffer(7); new Int16Array(b)",
        "var b = new ArrayBuffer(8); new Int16Array(b, -2)",

        // Shared storage.
        "var b = new ArrayBuffer(4); var a = new Uint8Array(b); var c = new Uint8Array(b); a[0] = 9; c[0]",
        "var b = new ArrayBuffer(4); var a = new Uint8Array(b); var c = new Uint32Array(b); a[0] = 1; a[1] = 0; a[2] = 0; a[3] = 0; c[0]",
        "var a = new Int8Array(4); a.buffer.byteLength", "var a = new Int8Array(4); a.byteLength",
        "var a = new Int8Array(4); a.byteOffset", "var b = new ArrayBuffer(8); new Int16Array(b, 4).byteOffset",
        "var a = new Int16Array(4); a.byteLength",

        // Element access and out-of-range behaviour.
        "var a = new Int8Array([1, 2, 3]); a[0]", "var a = new Int8Array([1, 2, 3]); a[5]",
        "var a = new Int8Array([1, 2, 3]); a[-1]", "var a = new Int8Array([1, 2, 3]); a[5] = 9; a.length",
        "var a = new Int8Array([1, 2, 3]); a['1']", "var a = new Int8Array([1, 2, 3]); a['x'] = 5; a.x",
        "var a = new Int8Array([1, 2, 3]); a['1.5']", "var a = new Int8Array([1, 2, 3]); '1' in a",
        "var a = new Int8Array([1, 2, 3]); '5' in a", "var a = new Int8Array([1, 2, 3]); delete a[0]; a[0]",
        "var a = new Int8Array([1, 2, 3]); Object.keys(a).join()",
        "var a = new Int8Array([1, 2, 3]); var n = 0; for (var k in a) n++; n",
        "var a = new Int8Array([1, 2, 3]); JSON.stringify(a)",
        "var a = new Int8Array([1, 2, 3]); Object.getOwnPropertyDescriptor(a, '0').value",
        "var a = new Int8Array([1, 2, 3]); Object.getOwnPropertyDescriptor(a, '0').writable",
        "var a = new Int8Array(1); Object.defineProperty(a, '0', { value: 5 }); a[0]",
        "var a = new Int8Array(1); try { Object.defineProperty(a, '0', { get: function () {} }) } catch (e) { e.name }",

        // The prototype methods.
        "new Int8Array([3, 1, 2]).sort().join()", "new Int8Array([3, 1, 2]).sort(function (a, b) { return b - a }).join()",
        "new Float64Array([3, NaN, 1]).sort().join()", "new Int8Array([1, 2]).sort(5)",
        "new Int8Array([1, 2, 3]).toSorted().join()", "var a = new Int8Array([3, 1]); a.toSorted(); a.join()",
        "new Int8Array([1, 2, 3]).reverse().join()", "new Int8Array([1, 2, 3]).toReversed().join()",
        "var a = new Int8Array([1, 2, 3]); a.toReversed(); a.join()",
        "new Int8Array([1, 2, 3]).with(1, 9).join()", "new Int8Array([1, 2, 3]).with(-1, 9).join()",
        "new Int8Array([1, 2, 3]).with(5, 9)",
        "new Int8Array(3).fill(7).join()", "new Int8Array(4).fill(7, 1, 3).join()", "new Int8Array(4).fill(7, -2).join()",
        "new Int8Array([1, 2, 3, 4, 5]).copyWithin(0, 3).join()",
        "new Int8Array([1, 2, 3, 4, 5]).copyWithin(1, 0, 3).join()",
        "new Int8Array([1, 2, 3, 4, 5]).copyWithin(-2, 0).join()",
        "new Int8Array([1, 2, 3]).slice(1).join()", "new Int8Array([1, 2, 3]).slice(1, 2).join()",
        "new Int8Array([1, 2, 3]).slice(-2).join()", "new Int8Array([1, 2, 3]).slice(5).length",
        "var a = new Int8Array([1, 2, 3, 4]); var s = a.subarray(1, 3); s.join() + ':' + s.byteOffset",
        "var a = new Int8Array([1, 2, 3, 4]); var s = a.subarray(1); s[0] = 9; a[1]",
        "new Int8Array([1, 2, 3]).indexOf(2)", "new Int8Array([1, 2, 3]).indexOf(9)",
        "new Int8Array([1, 2, 3]).indexOf(2, 2)", "new Int8Array([1, 2, 1]).lastIndexOf(1)",
        "new Int8Array([1, 2, 3]).includes(2)", "new Float64Array([NaN]).includes(NaN)",
        "new Float64Array([NaN]).indexOf(NaN)", "new Int8Array([1, 2, 3]).at(-1)", "new Int8Array([1, 2, 3]).at(5)",
        "new Int8Array([1, 2, 3]).join('-')", "new Int8Array([1, 2, 3]).join()", "new Int8Array(0).join()",
        "new Int8Array([1, 2, 3]).toString()", "String(new Int8Array([1, 2, 3]))",
        "new Int8Array([1, 2, 3]).map(function (v) { return v * 2 }).join()",
        "new Int8Array([1, 2, 3]).filter(function (v) { return v > 1 }).join()",
        "new Int8Array([1, 2, 3]).reduce(function (a, b) { return a + b })",
        "new Int8Array([1, 2, 3]).reduceRight(function (a, b) { return a + '' + b })",
        "new Int8Array([1, 2, 3]).every(function (v) { return v > 0 })",
        "new Int8Array([1, 2, 3]).some(function (v) { return v > 2 })",
        "new Int8Array([1, 2, 3]).find(function (v) { return v > 1 })",
        "new Int8Array([1, 2, 3]).findIndex(function (v) { return v > 1 })",
        "new Int8Array([1, 2, 3]).findLast(function (v) { return v > 1 })",
        "new Int8Array([1, 2, 3]).findLastIndex(function (v) { return v > 1 })",
        "var r = []; new Int8Array([1, 2]).forEach(function (v, i) { r.push(i + ':' + v) }); r.join()",
        "[...new Int8Array([1, 2, 3])].join()", "[...new Int8Array([1, 2]).keys()].join()",
        "JSON.stringify([...new Int8Array([1, 2]).entries()])",
        "[...new Int8Array([1, 2]).values()].join()",
        "var a = new Int8Array(3); a.set([1, 2]); a.join()",
        "var a = new Int8Array(3); a.set([1, 2], 1); a.join()",
        "var a = new Int8Array(3); a.set([1, 2, 3, 4])",
        "var a = new Int8Array(3); a.set([1], 5)",
        "var a = new Int8Array(3); a.set([1], -1)",
        "var a = new Int8Array(3); a.set(new Int8Array([1, 2])); a.join()",
        "var b = new ArrayBuffer(4); var a = new Uint8Array(b); a.set([1, 2, 3, 4]); a.set(a.subarray(0, 2), 1); a.join()",
        "Int8Array.from([1, 2, 3]).join()", "Int8Array.from([1, 2], function (v) { return v * 3 }).join()",
        "Int8Array.from('123').join()", "Int8Array.from(new Set([1, 2])).join()",
        "Int8Array.of(1, 2, 3).join()", "Int8Array.of().length",
        "Int8Array.from.call(Array, [1])", "Int8Array.of.call(Array, 1)",
        "Int8Array.prototype.join.call([1, 2])",
        "var a = new Int8Array(1); a.buffer.transfer(); a.length",
        "var a = new Int8Array(1); a.buffer.transfer(); try { a.join() } catch (e) { e.name }",
        "var a = new Int8Array(1); a.buffer.transfer(); a.byteLength",

        // DataView.
        "var d = new DataView(new ArrayBuffer(8)); d.byteLength + ':' + d.byteOffset",
        "var d = new DataView(new ArrayBuffer(8), 2); d.byteLength + ':' + d.byteOffset",
        "var d = new DataView(new ArrayBuffer(8), 2, 4); d.byteLength",
        "new DataView(new ArrayBuffer(8), 10)", "new DataView(new ArrayBuffer(8), 2, 10)",
        "new DataView([])", "new DataView()",
        "var d = new DataView(new ArrayBuffer(8)); d.setInt8(0, -1); d.getInt8(0)",
        "var d = new DataView(new ArrayBuffer(8)); d.setInt8(0, -1); d.getUint8(0)",
        "var d = new DataView(new ArrayBuffer(8)); d.setInt16(0, -2); d.getInt16(0)",
        "var d = new DataView(new ArrayBuffer(8)); d.setInt16(0, 258); d.getUint8(0) + ':' + d.getUint8(1)",
        "var d = new DataView(new ArrayBuffer(8)); d.setInt16(0, 258, true); d.getUint8(0) + ':' + d.getUint8(1)",
        "var d = new DataView(new ArrayBuffer(8)); d.setInt32(0, -2); d.getInt32(0)",
        "var d = new DataView(new ArrayBuffer(8)); d.setUint32(0, 4294967295); d.getUint32(0)",
        "var d = new DataView(new ArrayBuffer(8)); d.setUint32(0, 4294967295, true); d.getUint32(0, true)",
        "var d = new DataView(new ArrayBuffer(8)); d.setFloat32(0, 0.5); d.getFloat32(0)",
        "var d = new DataView(new ArrayBuffer(8)); d.setFloat32(0, 1/3); d.getFloat32(0)",
        "var d = new DataView(new ArrayBuffer(8)); d.setFloat64(0, 1/3); d.getFloat64(0)",
        "var d = new DataView(new ArrayBuffer(8)); d.setFloat64(0, 1/3, true); d.getFloat64(0, true)",
        "var d = new DataView(new ArrayBuffer(8)); d.setFloat64(0, NaN); d.getFloat64(0)",
        "var d = new DataView(new ArrayBuffer(8)); d.setFloat64(0, -0); 1 / d.getFloat64(0)",
        "var d = new DataView(new ArrayBuffer(2)); d.getInt32(0)", "var d = new DataView(new ArrayBuffer(2)); d.setInt32(0, 1)",
        "var d = new DataView(new ArrayBuffer(2)); d.getInt8(-1)", "var d = new DataView(new ArrayBuffer(2)); d.getInt8()",
        "var d = new DataView(new ArrayBuffer(2)); d.setInt8(0); d.getInt8(0)",
        "var b = new ArrayBuffer(4); var d = new DataView(b); var a = new Uint8Array(b); d.setUint8(0, 7); a[0]",
        "var b = new ArrayBuffer(4); var d = new DataView(b); b.transfer(); try { d.getInt8(0) } catch (e) { e.name }",
        "var b = new ArrayBuffer(4); var d = new DataView(b); b.transfer(); try { d.byteLength } catch (e) { e.name }",
        "DataView.prototype.getInt8.call({}, 0)",
    ))

    /**
     * A promise's answer only exists after the microtask queue drains, and the queue drains when
     * the top call returns. So each of these runs as two scripts in one scope: the body writes into
     * a log, and a second evaluation reads the log back once everything has settled.
     *
     * Reading the log inside the same script would give an empty one every time, on both engines,
     * and the comparison would prove nothing.
     */
    private fun checkAfterDrain(bodies: List<String>) {
        val failures = mutableListOf<String>()
        for (body in bodies) {
            val setup = "globalThis.__log = []; var log = globalThis.__log; $body;"
            val expected = try {
                upstream(setup)
                upstream("globalThis.__log.join('|')")
            } catch (e: Throwable) {
                "CRASH $e"
            }
            val actual = try {
                ported(setup)
                ported("globalThis.__log.join('|')")
            } catch (e: Throwable) {
                "CRASH $e"
            }
            if (expected != actual) failures.add("$body\n  upstream: $expected\n  ported:   $actual")
        }
        assertTrue(bodies.size > 10)
        assertEquals(emptyList(), failures, "promise results differ from upstream")
    }

    @Test
    fun promiseBasics() = check(listOf(
        "typeof Promise", "Promise.length", "Promise.name",
        "typeof new Promise(function () {})", "Object.prototype.toString.call(new Promise(function () {}))",
        "new Promise(function () {}) instanceof Promise", "Promise[Symbol.species] === Promise",
        "typeof Promise.prototype.then", "typeof Promise.prototype.catch", "typeof Promise.prototype.finally",
        "typeof Promise.resolve", "typeof Promise.reject", "typeof Promise.all", "typeof Promise.allSettled",
        "typeof Promise.race", "typeof Promise.any", "typeof Promise.withResolvers",
        "new Promise()", "new Promise(5)", "Promise(function () {})",
        "Promise.resolve.call(null, 1)", "Promise.reject.call(undefined, 1)",
        "Promise.prototype.then.call({}, function () {})",
        "typeof Promise.withResolvers().promise", "typeof Promise.withResolvers().resolve",
        "typeof Promise.withResolvers().reject",
        "var r = Promise.resolve(1); r === Promise.resolve(r)",
        "var p = new Promise(function () {}); Promise.resolve(p) === p",
        "Object.getOwnPropertyDescriptor(Promise.prototype, Symbol.toStringTag).value",
    ))

    @Test
    fun promiseOrdering() = checkAfterDrain(listOf(
        // Ordering against synchronous code.
        "log.push('a'); Promise.resolve().then(function () { log.push('c') }); log.push('b')",
        "Promise.resolve(1).then(function (v) { log.push(v) }); log.push('sync')",
        "Promise.resolve().then(function () { log.push(1) }).then(function () { log.push(2) })",
        "Promise.resolve().then(function () { log.push(1) }); Promise.resolve().then(function () { log.push(2) })",
        "Promise.resolve().then(function () { log.push('a'); return Promise.resolve() }).then(function () { log.push('c') }); Promise.resolve().then(function () { log.push('b') })",

        // Values and chaining.
        "Promise.resolve(1).then(function (v) { return v + 1 }).then(function (v) { log.push(v) })",
        "Promise.resolve(1).then(function () {}).then(function (v) { log.push(String(v)) })",
        "Promise.resolve(1).then(null).then(function (v) { log.push(v) })",
        "Promise.resolve(1).then(5).then(function (v) { log.push(v) })",
        "Promise.resolve(Promise.resolve(7)).then(function (v) { log.push(v) })",
        "new Promise(function (res) { res(3) }).then(function (v) { log.push(v) })",
        "new Promise(function (res) { res(3); res(4) }).then(function (v) { log.push(v) })",
        "new Promise(function (res, rej) { res(3); rej(4) }).then(function (v) { log.push('ok' + v) }, function (e) { log.push('no' + e) })",

        // Rejection.
        "Promise.reject('x').catch(function (e) { log.push(e) })",
        "Promise.reject('x').then(null, function (e) { log.push(e) })",
        "Promise.reject('x').then(function () { log.push('bad') }).catch(function (e) { log.push('caught ' + e) })",
        "new Promise(function () { throw 'boom' }).catch(function (e) { log.push(e) })",
        "Promise.resolve().then(function () { throw 'boom' }).catch(function (e) { log.push(e) })",
        "Promise.resolve().then(function () { null.x }).catch(function (e) { log.push(e.name) })",
        "Promise.reject(new Error('m')).catch(function (e) { log.push(e.message) })",
        "Promise.resolve().then(function () { return Promise.reject('r') }).catch(function (e) { log.push(e) })",

        // finally.
        "Promise.resolve(1).finally(function () { log.push('f') }).then(function (v) { log.push(v) })",
        "Promise.reject('e').finally(function () { log.push('f') }).catch(function (e) { log.push(e) })",
        "Promise.resolve(1).finally(function () { return 99 }).then(function (v) { log.push(v) })",
        "Promise.resolve(1).finally(function () { throw 'ff' }).catch(function (e) { log.push(e) })",
        "Promise.resolve(1).finally(5).then(function (v) { log.push(v) })",

        // Thenables.
        "Promise.resolve({ then: function (res) { res(5) } }).then(function (v) { log.push(v) })",
        "Promise.resolve({ then: function (res) { res(1); res(2) } }).then(function (v) { log.push(v) })",
        "Promise.resolve({ then: function (res, rej) { rej('t') } }).catch(function (e) { log.push(e) })",
        "Promise.resolve({ then: function () { throw 'tt' } }).catch(function (e) { log.push(e) })",
        "Promise.resolve({ then: 5 }).then(function (v) { log.push(typeof v) })",
        "var p = new Promise(function (res) { res({ then: function (r) { r(9) } }) }); p.then(function (v) { log.push(v) })",

        // Thenable adoption costs an extra turn, which only shows against a parallel chain.
        "Promise.resolve({ then: function (r) { r('T') } }).then(function (v) { log.push(v) }); Promise.resolve().then(function () { log.push('a') }).then(function () { log.push('b') }).then(function () { log.push('c') })",
        "new Promise(function (res) { res({ then: function (r) { r('T') } }) }).then(function (v) { log.push(v) }); Promise.resolve().then(function () { log.push(1) }).then(function () { log.push(2) }).then(function () { log.push(3) })",
        "Promise.resolve().then(function () { return { then: function (r) { r('T') } } }).then(function (v) { log.push(v) }); Promise.resolve().then(function () { log.push(1) }).then(function () { log.push(2) }).then(function () { log.push(3) }).then(function () { log.push(4) })",
        "Promise.resolve().then(function () { return Promise.resolve('P') }).then(function (v) { log.push(v) }); Promise.resolve().then(function () { log.push(1) }).then(function () { log.push(2) }).then(function () { log.push(3) })",
        "var t = { then: function (r) { log.push('called'); r(1) } }; Promise.resolve(t).then(function () { log.push('settled') }); log.push('sync')",

        // Self resolution.
        "var res; var p = new Promise(function (r) { res = r }); res(p); p.catch(function (e) { log.push(e.name) })",

        // withResolvers.
        "var w = Promise.withResolvers(); w.promise.then(function (v) { log.push(v) }); w.resolve(4)",
        "var w = Promise.withResolvers(); w.promise.catch(function (v) { log.push(v) }); w.reject(5)",

        // Promise.try.
        "Promise.try(function () { return 1 }).then(function (v) { log.push(v) })",
        "Promise.try(function () { throw 'e' }).catch(function (e) { log.push(e) })",
        "Promise.try(function (a, b) { return a + b }, 1, 2).then(function (v) { log.push(v) })",
    ))

    @Test
    fun promiseCombinators() = checkAfterDrain(listOf(
        // all.
        "Promise.all([]).then(function (v) { log.push(v.length) })",
        "Promise.all([1, 2, 3]).then(function (v) { log.push(v.join()) })",
        "Promise.all([Promise.resolve(1), 2]).then(function (v) { log.push(v.join()) })",
        "Promise.all([Promise.reject('a'), Promise.resolve(1)]).catch(function (e) { log.push(e) })",
        "Promise.all([Promise.reject('a'), Promise.reject('b')]).catch(function (e) { log.push(e) })",
        "Promise.all(5).catch(function (e) { log.push(e.name) })",
        "Promise.all([Promise.resolve(1)]).then(function (v) { log.push(Array.isArray(v)) })",
        "Promise.all(new Set([1, 2])).then(function (v) { log.push(v.join()) })",
        "Promise.all('ab').then(function (v) { log.push(v.join()) })",

        // allSettled.
        "Promise.allSettled([]).then(function (v) { log.push(v.length) })",
        "Promise.allSettled([1]).then(function (v) { log.push(v[0].status + ':' + v[0].value) })",
        "Promise.allSettled([Promise.reject('r')]).then(function (v) { log.push(v[0].status + ':' + v[0].reason) })",
        "Promise.allSettled([1, Promise.reject('r')]).then(function (v) { log.push(v.length + ':' + v[0].status + ':' + v[1].status) })",
        "Promise.allSettled([1]).then(function (v) { log.push(Object.keys(v[0]).join()) })",

        // race.
        "Promise.race([]).then(function () { log.push('never') })",
        "Promise.race([1, 2]).then(function (v) { log.push(v) })",
        "Promise.race([Promise.reject('r'), 1]).catch(function (e) { log.push(e) })",
        "Promise.race([Promise.resolve(1), Promise.reject('r')]).then(function (v) { log.push('ok' + v) }, function (e) { log.push('no' + e) })",
        "Promise.race(5).catch(function (e) { log.push(e.name) })",

        // any.
        "Promise.any([1, 2]).then(function (v) { log.push(v) })",
        "Promise.any([Promise.reject('a'), 2]).then(function (v) { log.push(v) })",
        "Promise.any([Promise.reject('a'), Promise.reject('b')]).catch(function (e) { log.push(e.name + ':' + e.errors.join()) })",
        "Promise.any([]).catch(function (e) { log.push(e.name) })",
        "Promise.any(5).catch(function (e) { log.push(e.name) })",

        // Ordering across a combinator.
        "Promise.all([Promise.resolve(1)]).then(function () { log.push('all') }); Promise.resolve().then(function () { log.push('single') })",
        "var order = []; Promise.all([Promise.resolve().then(function () { order.push(1) }), Promise.resolve().then(function () { order.push(2) })]).then(function () { log.push(order.join()) })",
    ))

    @Test
    fun promiseHandlingAndReentrancy() = checkAfterDrain(listOf(
        // A rejection with a handler attached later still counts as handled once it is.
        "var p = Promise.reject('a'); p.catch(function (e) { log.push('late ' + e) })",
        "var p = Promise.reject('a'); Promise.resolve().then(function () { p.catch(function (e) { log.push('later ' + e) }) })",
        "var p = new Promise(function (res, rej) { rej('b') }); p.then(null, function (e) { log.push(e) })",
        // Rejections that nothing ever catches simply do not run anything.
        "Promise.reject('never'); log.push('done')",
        "new Promise(function (res, rej) { rej('never') }); log.push('done')",

        // One promise with several handlers, and handlers added after it settled.
        "var p = Promise.resolve(1); p.then(function (v) { log.push('a' + v) }); p.then(function (v) { log.push('b' + v) })",
        "var p = Promise.resolve(1); p.then(function () { log.push('one'); p.then(function () { log.push('two') }) })",
        "var p = new Promise(function (r) { r(1) }); Promise.resolve().then(function () { p.then(function (v) { log.push('after' + v) }) })",
        "var res; var p = new Promise(function (r) { res = r }); p.then(function (v) { log.push(v) }); res('deferred')",
        "var res; var p = new Promise(function (r) { res = r }); p.then(function (v) { log.push('1:' + v) }); p.then(function (v) { log.push('2:' + v) }); res('x')",

        // Deep chains, so the queue order over several turns is checked.
        "Promise.resolve().then(function () { log.push(1) }).then(function () { log.push(2) }).then(function () { log.push(3) })",
        "Promise.resolve().then(function () { log.push('a1') }).then(function () { log.push('a2') }); Promise.resolve().then(function () { log.push('b1') }).then(function () { log.push('b2') })",
        "var p = Promise.resolve(); for (var i = 0; i < 3; i++) { (function (n) { p = p.then(function () { log.push(n) }) })(i) }",

        // A subclass, so the species path is exercised.
        "function P(e) { Promise.call(this, e) } log.push(typeof Promise.resolve(1).constructor)",
        "var p = Promise.resolve(1); p.constructor = 5; p.then(function (v) { log.push(v) })",
    ))

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
            // A program whose answer only exists after the microtask queue has drained ends with
            // "// AFTER: <expression>". That expression is evaluated as a second top call, which
            // is the point at which the queue has run.
            val after = source.lineSequence().lastOrNull { it.startsWith("// AFTER:") }?.removePrefix("// AFTER:")?.trim()
            val expected = if (after == null) {
                upstream(source)
            } else {
                upstream(source)
                upstream(after)
            }
            val actual = try {
                if (after == null) {
                    ported(source)
                } else {
                    ported(source)
                    ported(after)
                }
            } catch (e: Throwable) {
                "CRASH $e"
            }
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

    @Test
    fun proxyTraps() = check(listOf(
        "var p = new Proxy({a:1}, {}); p.a",
        "var p = new Proxy({a:1}, {get:function(t,k){return k === 'a' ? 42 : t[k];}}); p.a",
        "var p = new Proxy({a:1}, {get:function(t,k){return 'g:' + String(k);}}); p.zzz",
        "var log=[]; var p = new Proxy({a:1,b:2}, {get:function(t,k,r){log.push('get ' + String(k)); return t[k];}}); p.a; p.b; log.join('|')",
        "var p = new Proxy({}, {get:function(t,k,r){return r === p;}}); p.anything",
        "var p = new Proxy([10,20,30], {}); p[1]",
        "var p = new Proxy([10,20,30], {get:function(t,k){return k === '1' ? 99 : t[k];}}); p[1]",
        "var p = new Proxy({}, {set:function(t,k,v){t[k] = v * 2; return true;}}); p.x = 5; p.x",
        "var t={}; var p = new Proxy(t, {set:function(){return false;}}); p.x = 5; String(t.x)",
        "var t={}; var p = new Proxy(t, {}); p.x = 5; t.x",
        "var log=[]; var p = new Proxy({}, {set:function(t,k,v){log.push(String(k) + '=' + v); t[k]=v; return true;}}); p.a=1; p.b=2; log.join(',')",
        "var p = new Proxy({a:1}, {has:function(t,k){return k === 'b';}}); [('a' in p), ('b' in p)].join(',')",
        "var p = new Proxy({a:1}, {}); ('a' in p) + ',' + ('b' in p)",
        "var t={a:1}; var p=new Proxy(t,{deleteProperty:function(tt,k){delete tt[k]; return true;}}); delete p.a; String(t.a)",
        "var t={a:1}; var p=new Proxy(t,{deleteProperty:function(){return false;}}); delete p.a; t.a",
        "var t={a:1}; var p=new Proxy(t,{}); delete p.a; String(t.a)",
        "var p = new Proxy({a:1,b:2}, {}); Object.keys(p).join(',')",
        "var p = new Proxy({a:1,b:2}, {ownKeys:function(t){return ['a','b'];}}); Object.keys(p).join(',')",
        "var p = new Proxy({a:1,b:2}, {ownKeys:function(){return ['b'];}}); Object.keys(p).join(',')",
        "var p = new Proxy({a:1,b:2}, {}); var out=[]; for (var k in p) out.push(k); out.join(',')",
        "var p = new Proxy({a:1},{getOwnPropertyDescriptor:function(t,k){return {value:7, writable:true, enumerable:true, configurable:true};}}); JSON.stringify(Object.getOwnPropertyDescriptor(p,'a'))",
        "var p = new Proxy({a:1},{}); JSON.stringify(Object.getOwnPropertyDescriptor(p,'a'))",
        "var p = new Proxy({a:1},{getOwnPropertyDescriptor:function(){return undefined;}}); String(Object.getOwnPropertyDescriptor(p,'a'))",
        "var p = new Proxy({a:1,b:2},{}); Object.getOwnPropertyNames(p).join(',')",
        "var p = new Proxy({a:1},{getOwnPropertyDescriptor:function(t,k){return {value:1,enumerable:false,configurable:true};}}); Object.keys(p).join(',') + '|' + Object.getOwnPropertyNames(p).join(',')",
        "var t={}; var p=new Proxy(t,{defineProperty:function(tt,k,d){tt[k]=d.value; return true;}}); Object.defineProperty(p,'x',{value:9,configurable:true}); t.x",
        "var t={}; var p=new Proxy(t,{}); Object.defineProperty(p,'x',{value:9,configurable:true}); t.x",
        "var proto={z:1}; var p=new Proxy({},{getPrototypeOf:function(){return proto;}}); Object.getPrototypeOf(p) === proto",
        "var p=new Proxy({},{}); Object.getPrototypeOf(p) === Object.prototype",
        "var log=[]; var p=new Proxy({},{setPrototypeOf:function(t,pr){log.push('spo'); return true;}}); Object.setPrototypeOf(p,{}); log.join(',')",
        "var p = new Proxy({}, {isExtensible:function(t){return Object.isExtensible(t);}}); Object.isExtensible(p)",
        "var p = new Proxy({}, {}); Object.isExtensible(p)",
        "var t={}; var p = new Proxy(t, {preventExtensions:function(tt){Object.preventExtensions(tt); return true;}}); Object.preventExtensions(p); Object.isExtensible(t)",
        "var p = new Proxy(function(a,b){return a+b;}, {}); p(1,2)",
        "var p = new Proxy(function(a,b){return a+b;}, {apply:function(t,th,args){return t.apply(th,args) * 10;}}); p(1,2)",
        "var p = new Proxy(function(){return this.v;}, {}); p.call({v:7})",
        "function F(x){this.x=x;} var p = new Proxy(F, {}); (new p(5)).x",
        "function F(x){this.x=x;} var p = new Proxy(F, {construct:function(t,args){return {x: 3};}}); (new p(5)).x",
        "typeof new Proxy(function(){}, {})",
        "typeof new Proxy({}, {})",
        "Array.isArray(new Proxy([], {}))",
        "Array.isArray(new Proxy({}, {}))",
        "var p = new Proxy({a:1},{}); var o = Object.create(p); o.a",
        "var p = new Proxy({},{get:function(t,k){return 'from proxy ' + String(k);}}); var o = Object.create(p); o.q",
        "var r = Proxy.revocable({a:1},{}); var v = r.proxy.a; r.revoke(); try { r.proxy.a } catch(e) { v + ':' + e.name }",
        "var r = Proxy.revocable({a:1},{}); r.revoke(); r.revoke(); 'ok'",
        "var r = Proxy.revocable({a:1},{}); typeof r.revoke",
        "var r = Proxy.revocable({a:1},{}); typeof r.proxy",
        "var s = Symbol('s'); var o = {}; o[s] = 1; var p = new Proxy(o, {}); p[s]",
        "var s = Symbol('s'); var p = new Proxy({}, {get:function(t,k){return typeof k;}}); p[s]",
        "var s = Symbol('s'); var o = {}; o[s] = 1; var p = new Proxy(o, {has:function(){return false;}}); s in p",
        "var p = new Proxy({}, {}); p.toString()",
        "var p = new Proxy({}, {}); Object.prototype.toString.call(p)",
        "var p = new Proxy([1,2,3], {}); p.length",
        "var p = new Proxy([1,2,3], {}); p.join('-')",
        "var p = new Proxy({a:1}, {}); JSON.stringify(p)",
    ))

    @Test
    fun proxyInvariants() = check(listOf(
        "var t = Object.freeze({a:1}); var p = new Proxy(t, {get:function(){return 2;}}); try { p.a } catch(e) { e.name + ': ' + e.message }",
        "var t = Object.freeze({a:1}); var p = new Proxy(t, {get:function(){return 1;}}); p.a",
        "var t = {}; Object.defineProperty(t,'a',{value:1,configurable:false}); Object.preventExtensions(t); var p = new Proxy(t,{has:function(){return false;}}); try { 'a' in p } catch(e) { e.name }",
        "var t = Object.preventExtensions({a:1}); var p = new Proxy(t,{ownKeys:function(){return [];}}); try { Object.keys(p).join(',') } catch(e) { e.name }",
        "var p = new Proxy({},{ownKeys:function(){return ['a','a'];}}); try { Object.keys(p).join(',') } catch(e) { e.name + ': ' + e.message }",
        "var p = new Proxy({},{ownKeys:function(){return 'nope';}}); try { Object.keys(p).join(',') } catch(e) { e.name }",
        "var p = new Proxy({},{ownKeys:function(){return [1];}}); try { Object.keys(p).join(',') } catch(e) { e.name }",
        "var t = Object.preventExtensions({}); var p = new Proxy(t,{preventExtensions:function(){return true;}}); Object.preventExtensions(p); 'ok'",
        "var p = new Proxy({},{preventExtensions:function(){return true;}}); try { Object.preventExtensions(p) } catch(e) { e.name + ': ' + e.message }",
        "var p = new Proxy({}, {isExtensible:function(){return false;}}); try { Object.isExtensible(p) } catch(e) { e.name + ': ' + e.message }",
        "var p = new Proxy({},{getOwnPropertyDescriptor:function(){return 1;}}); try { Object.getOwnPropertyDescriptor(p,'a') } catch(e) { e.name }",
        "var t = Object.freeze({a:1}); var p = new Proxy(t,{getOwnPropertyDescriptor:function(){return undefined;}}); try { Object.getOwnPropertyDescriptor(p,'a') } catch(e) { e.name + ': ' + e.message }",
        "var p = new Proxy(function(){}, {construct:function(){return 1;}}); try { new p() } catch(e) { e.name + ': ' + e.message }",
        "var t = Object.freeze({a:1}); var p = new Proxy(t, {set:function(){return true;}}); try { p.a = 5; 'no throw' } catch(e) { e.name + ': ' + e.message }",
        "var t = Object.freeze({a:1}); var p = new Proxy(t, {deleteProperty:function(){return true;}}); try { delete p.a; 'no throw' } catch(e) { e.name }",
        "var t = {}; Object.preventExtensions(t); var p = new Proxy(t, {defineProperty:function(){return true;}}); try { Object.defineProperty(p,'x',{value:1}); 'no throw' } catch(e) { e.name + ': ' + e.message }",
        "var t = {}; var p = new Proxy(t, {getPrototypeOf:function(){return 1;}}); try { Object.getPrototypeOf(p) } catch(e) { e.name }",
        "var t = Object.preventExtensions({}); var p = new Proxy(t, {getPrototypeOf:function(){return {};}}); try { Object.getPrototypeOf(p) } catch(e) { e.name + ': ' + e.message }",
        "try { new Proxy() } catch(e) { e.name + ': ' + e.message }",
        "try { new Proxy({}) } catch(e) { e.name + ': ' + e.message }",
        "try { new Proxy(1, {}) } catch(e) { e.name }",
        "try { new Proxy({}, 1) } catch(e) { e.name }",
        "try { Proxy({}, {}) } catch(e) { e.name }",
        "try { new Proxy({}, {get: 1}); 'made' } catch(e) { e.name }",
        "try { var p = new Proxy({}, {get: 1}); p.a } catch(e) { e.name }",
        "try { new Proxy(Symbol('s'), {}) } catch(e) { e.name }",
        "var r = Proxy.revocable({a:1},{}); r.revoke(); try { 'a' in r.proxy } catch(e) { e.name + ': ' + e.message }",
        "var r = Proxy.revocable({a:1},{}); r.revoke(); try { Object.keys(r.proxy) } catch(e) { e.name }",
        "var r = Proxy.revocable(function(){},{}); r.revoke(); try { r.proxy() } catch(e) { e.name }",
        "var r = Proxy.revocable({a:1},{}); r.revoke(); try { r.proxy.a = 1 } catch(e) { e.name }",
    ))

    @Test
    fun reflectMethods() = check(listOf(
        "typeof Reflect",
        "Object.prototype.toString.call(Reflect)",
        "Reflect.get({a:1},'a')",
        "String(Reflect.get({a:1},'b'))",
        "Reflect.get([10,20],1)",
        "Reflect.get([10,20],'1')",
        "var s = Symbol('s'); var o = {}; o[s] = 4; Reflect.get(o, s)",
        "Reflect.set({}, 'x', 5)",
        "var o={}; Reflect.set(o,'x',5); o.x",
        "var o=[]; Reflect.set(o,0,5); o[0]",
        "var o={}; var r={}; Reflect.set(o,'x',5,r); String(o.x) + ',' + r.x",
        "Reflect.has({a:1},'a')",
        "Reflect.has({a:1},'b')",
        "Reflect.has({},'toString')",
        "var s = Symbol('s'); var o = {}; o[s] = 1; Reflect.has(o, s)",
        "var o={a:1}; Reflect.deleteProperty(o,'a'); String(o.a)",
        "Reflect.deleteProperty(Object.freeze({a:1}),'a')",
        "Reflect.ownKeys({a:1,b:2}).join(',')",
        "Reflect.ownKeys([1,2]).join(',')",
        "var s = Symbol('s'); var o = {a:1}; o[s]=2; Reflect.ownKeys(o).length",
        "Reflect.isExtensible({})",
        "var o={}; Reflect.preventExtensions(o); Reflect.isExtensible(o)",
        "Reflect.getPrototypeOf({}) === Object.prototype",
        "Reflect.getPrototypeOf([]) === Array.prototype",
        "var o={}; Reflect.setPrototypeOf(o, null); String(Reflect.getPrototypeOf(o))",
        "var proto={}; var o={}; Reflect.setPrototypeOf(o, proto); Reflect.getPrototypeOf(o) === proto",
        "var o={}; Reflect.setPrototypeOf(o, Object.getPrototypeOf(o))",
        "var a={}; var b=Object.create(a); Reflect.setPrototypeOf(a, b)",
        "Reflect.setPrototypeOf(Object.preventExtensions({}), {})",
        "JSON.stringify(Reflect.getOwnPropertyDescriptor({a:1},'a'))",
        "String(Reflect.getOwnPropertyDescriptor({},'a'))",
        "var o={}; Reflect.defineProperty(o,'x',{value:3}); o.x",
        "var o={}; Reflect.defineProperty(o,'x',{value:3})",
        "Reflect.defineProperty(Object.freeze({}),'x',{value:3})",
        "Reflect.apply(function(a,b){return a+b;}, null, [1,2])",
        "Reflect.apply(function(){return this.v;}, {v:9}, [])",
        "Reflect.apply(Math.max, null, [1,5,2])",
        "function F(a){this.a=a;} Reflect.construct(F,[7]).a",
        "function F(){this.a=1;} function G(){} Reflect.construct(F,[],G) instanceof G",
        "function F(){this.a=1;} function G(){} Reflect.construct(F,[],G).a",
        "Reflect.construct(Array,[3]).length",
        "try { Reflect.get() } catch(e) { e.name }",
        "try { Reflect.get(1,'a') } catch(e) { e.name }",
        "try { Reflect.get(undefined,'a') } catch(e) { e.name }",
        "try { Reflect.apply(function(){}) } catch(e) { e.name + ': ' + e.message }",
        "try { Reflect.construct(1,[]) } catch(e) { e.name }",
        "try { Reflect.construct() } catch(e) { e.name + ': ' + e.message }",
        "try { Reflect.defineProperty({}) } catch(e) { e.name + ': ' + e.message }",
        "try { Reflect.setPrototypeOf({}) } catch(e) { e.name + ': ' + e.message }",
        "try { Reflect.get(Symbol('s'),'a') } catch(e) { e.name }",
        "var p = new Proxy({a:1},{get:function(t,k,r){return Reflect.get(t,k,r);}}); p.a",
        "var p = new Proxy({a:1},{ownKeys:function(t){return Reflect.ownKeys(t);}}); Object.keys(p).join(',')",
        "var p = new Proxy({a:1},{has:function(t,k){return Reflect.has(t,k);}}); 'a' in p",
    ))

    /**
     * Upstream 1.9.1 throws a NullPointerException when a `getOwnPropertyDescriptor` trap answers
     * undefined for a property the target does not have: it reads the target's descriptor without
     * checking for null first. The port returns undefined, which is what the spec asks for and what
     * every other engine does (D-50). Both halves are pinned here so a change on either side shows.
     */
    @Test
    fun getOwnPropertyDescriptorTrapMayReturnUndefined() {
        val script = "var p = new Proxy({}, {getOwnPropertyDescriptor:function(){return undefined;}});" +
            " String(Object.getOwnPropertyDescriptor(p, 'zzz'))"
        assertFailsWith<NullPointerException> { ucx.evaluateString(uscope, script, "test.js", 1, null) }
        assertEquals("\"undefined\"", ported(script))
    }

    /**
     * Upstream hands the `construct` trap the raw Java argument array, which script can only read
     * because Java interop wraps it on the way in. There is no interop here, so the trap gets a
     * real JavaScript array, matching what the `apply` trap already did and what the spec says
     * (D-51). Both halves are pinned so a change on either side shows.
     */
    @Test
    fun constructTrapGetsARealArray() {
        val script = "var p = new Proxy(function (x) { this.x = x }, {construct:function(t,args){" +
            " return { out: Array.isArray(args) + ',' + args.length + ',' + args[0] }; }});" +
            " (new p(7, 8)).out"
        assertTrue(upstream(script).startsWith("\"false,"), "upstream: " + upstream(script))
        assertEquals("\"true,2,7\"", ported(script))
    }

    @Test
    fun bigIntLiteralsAndArithmetic() = check(listOf(
        "1n", "0n", "-1n", "123456789012345678901234567890n", "0x10n", "0o17n", "0b1011n",
        "typeof 1n", "typeof BigInt(1)", "typeof Object(1n)",
        "1n + 2n", "10n - 3n", "6n * 7n", "7n / 2n", "-7n / 2n", "7n % 2n", "-7n % 2n", "2n ** 64n",
        "(2n ** 64n).toString()", "(-2n) ** 3n", "0n - 1n", "1n / 1n",
        "-(5n)", "-(0n)", "+'1'",
        "1n & 3n", "-1n & 3n", "-2n | 1n", "5n ^ 3n", "~5n", "~-1n", "~0n",
        "1n << 64n", "(-5n) >> 1n", "5n >> 1n", "(-1n) >> 100n", "1n << 0n",
        "1n == 1", "1n === 1", "1n == '1'", "1n === 1n", "0n == false", "0n == ''",
        "1n < 2", "2n > 1.5", "1n <= 1", "2n >= 3", "1n < NaN", "1n < Infinity", "1n > -Infinity",
        "9007199254740993n > 9007199254740992", "9007199254740993n == 9007199254740992",
        "Object.is(0n, -0n)", "Object.is(1n, 1n)", "Object.is(1n, 1)",
        "String(1n)", "`\${1n}`", "'' + 1n", "[1n, 2n].join(',')",
        "Boolean(0n)", "Boolean(1n)", "0n ? 'y' : 'n'", "1n ? 'y' : 'n'",
        "var x = 1n; x++; x", "var x = 1n; ++x", "var x = 1n; x--; x", "var x = 1n; x += 2n; x",
        "(1n).constructor === BigInt", "BigInt.prototype.constructor === BigInt",
        "Object.prototype.toString.call(1n)", "Object.prototype.toString.call(Object(1n))",
    ))

    @Test
    fun bigIntMixingThrows() = check(listOf(
        "try { 1n + 1 } catch(e) { e.name + ': ' + e.message }",
        "try { 1n - 1 } catch(e) { e.name }",
        "try { 1n * 1 } catch(e) { e.name }",
        "try { 1n / 1 } catch(e) { e.name }",
        "try { 1n % 1 } catch(e) { e.name }",
        "try { 1n ** 1 } catch(e) { e.name }",
        "try { 1n & 1 } catch(e) { e.name }",
        "try { 1n | 1 } catch(e) { e.name }",
        "try { 1n ^ 1 } catch(e) { e.name }",
        "try { 1n << 1 } catch(e) { e.name }",
        "try { 1 + 1n } catch(e) { e.name }",
        "1n + 'x'", "'x' + 1n",
        "try { Math.max(1n) } catch(e) { e.name }",
        "try { Number.parseInt(1n) } catch(e) { e.name + ':' + e.message }",
        "try { +1n } catch(e) { e.name + ': ' + e.message }",
        "try { Number(1n) } catch(e) { e.name }",
        "Number(1n) === 1",
        "try { 2n ** -1n } catch(e) { e.name + ': ' + e.message }",
        "try { 1n / 0n } catch(e) { e.name + ': ' + e.message }",
        "try { 1n % 0n } catch(e) { e.name }",
        "try { new BigInt(1) } catch(e) { e.name + ': ' + e.message }",
        "try { BigInt(1.5) } catch(e) { e.name + ': ' + e.message }",
        "try { BigInt(NaN) } catch(e) { e.name }",
        "try { BigInt(Infinity) } catch(e) { e.name }",
        "try { BigInt(null) } catch(e) { e.name }",
        "try { BigInt(undefined) } catch(e) { e.name }",
        "try { BigInt(Symbol()) } catch(e) { e.name }",
        "try { BigInt('nope') } catch(e) { e.name }",
        "try { BigInt('') } catch(e) { e.name + ':' + String(BigInt('')) }",
        "try { BigInt.prototype.toString.call(1) } catch(e) { e.name }",
        "try { BigInt.prototype.valueOf.call('x') } catch(e) { e.name }",
        "try { JSON.stringify(1n) } catch(e) { e.name + ': ' + e.message }",
        "try { JSON.stringify({a: 1n}) } catch(e) { e.name }",
    ))

    @Test
    fun bigIntConversionAndFormatting() = check(listOf(
        "BigInt(1)", "BigInt(0)", "BigInt(-1)", "BigInt(true)", "BigInt(false)", "BigInt('10')",
        "BigInt('0x10')", "BigInt('0o17')", "BigInt('0b101')", "BigInt('  12  ')", "BigInt('-12')",
        "BigInt(9007199254740992)", "BigInt('9007199254740993')", "BigInt()",
        "(255n).toString(16)", "(255n).toString(2)", "(255n).toString(36)", "(-255n).toString(16)",
        "(0n).toString(2)", "(123456789012345678901234567890n).toString(36)",
        "try { (1n).toString(1) } catch(e) { e.name }",
        "try { (1n).toString(37) } catch(e) { e.name }",
        "(1n).toLocaleString()", "(1n).valueOf()", "typeof (1n).valueOf()",
        "BigInt.asIntN(8, 255n)", "BigInt.asIntN(8, 256n)", "BigInt.asIntN(8, 127n)", "BigInt.asIntN(8, -129n)",
        "BigInt.asUintN(8, 255n)", "BigInt.asUintN(8, 256n)", "BigInt.asUintN(8, -1n)",
        "BigInt.asIntN(0, 5n)", "BigInt.asUintN(0, 5n)",
        "BigInt.asIntN(64, 2n ** 63n)", "BigInt.asUintN(64, -1n)",
        "BigInt.asIntN(1, 1n)", "BigInt.asIntN(1, 0n)",
        "JSON.stringify({a: 1})", "JSON.stringify({ a: { toJSON: function () { return 5 } } })",
        "JSON.stringify(Object(1n), function (k, v) { return typeof v })",
        "var m = new Map(); m.set(1n, 'a'); m.get(1n)",
        "var m = new Map(); m.set(1n, 'a'); String(m.get(1))",
        "var s = new Set([1n, 1n, 2n]); s.size",
        "var s = new Set([1n, 1]); s.size",
        "[3n, 1n, 2n].sort().join(',')",
        "[1n, 2n].map(function (v) { return v * 2n }).join(',')",
        "Array.from(new Set([1n])).length",
    ))

    @Test
    fun numberToStringInEveryRadix() = check(listOf(
        "(255).toString(16)", "(255).toString(2)", "(255).toString(8)", "(255).toString(36)",
        "(-255).toString(16)", "(0).toString(2)", "(1).toString(2)", "(0.5).toString(2)",
        "(0.1).toString(2)", "(0.1).toString(3)", "(1/3).toString(3)", "(0.25).toString(4)",
        "(3.5).toString(3)", "(1e21).toString(16)", "(1e-7).toString(16)", "(123.456).toString(7)",
        "(1e300).toString(36)", "(Number.MAX_SAFE_INTEGER).toString(2)",
        "(Number.MIN_VALUE).toString(2)", "(Number.MAX_VALUE).toString(16)",
        "(-0).toString(2)", "(NaN).toString(2)", "(Infinity).toString(2)", "(-Infinity).toString(8)",
        "(2147483648).toString(16)", "(4294967295).toString(16)", "(9007199254740993).toString(16)",
        "try { (1).toString(1) } catch(e) { e.name }",
        "try { (1).toString(37) } catch(e) { e.name }",
        "(12345.6789).toString(30)", "(-12345.6789).toString(30)", "(1.0000000000000002).toString(16)",
    ))

    @Test
    fun bigIntTypedArrays() = check(listOf(
        "new BigInt64Array(2).length", "BigInt64Array.BYTES_PER_ELEMENT", "BigUint64Array.BYTES_PER_ELEMENT",
        "var a = new BigInt64Array(2); a[0] = 5n; a[0]",
        "var a = new BigInt64Array(2); a[0] = -5n; a[0]",
        "var a = new BigInt64Array(1); a[0] = 2n ** 63n; a[0]",
        "var a = new BigInt64Array([1n, 2n, 3n]); a.join(',')",
        "var a = new BigInt64Array([1n, 2n, 3n]); a.length",
        "Object.prototype.toString.call(new BigInt64Array(1))",
        "try { var a = new BigInt64Array(1); a[0] = 1; a[0] } catch(e) { e.name }",
        "var a = new BigInt64Array(1); String(a[5])",
        "new BigInt64Array(2).byteLength",
        "var b = new ArrayBuffer(16); var a = new BigInt64Array(b); a.length",
        "var b = new ArrayBuffer(16); var a = new BigUint64Array(b, 8); a.length",
    ))

    /**
     * Upstream's `BigUint64Array` reader masks with `0xffffffff`, a Java int literal, which
     * sign-extends to all ones once it is promoted to a long. Every value with its top bit set
     * therefore reads back as garbage: -1 comes out as -4294967297 rather than 2^64 - 1. The port
     * returns the value that was written (D-53). Both halves are pinned so a change on either
     * side shows.
     */
    @Test
    fun bigUint64ArrayReadsBackWhatWasWritten() {
        val cases = listOf(
            "var a = new BigUint64Array(1); a[0] = 2n ** 63n; a[0]" to "9223372036854775808n",
            "var a = new BigUint64Array(1); a[0] = -1n; a[0]" to "18446744073709551615n",
            "var a = new BigUint64Array(1); a[0] = 2n ** 64n - 1n; a[0]" to "18446744073709551615n",
        )
        val upstreamAnswers = listOf("-18446744073709551616n", "-4294967297n", "-4294967297n")
        for ((i, case) in cases.withIndex()) {
            assertEquals(upstreamAnswers[i], upstream(case.first), "upstream drifted: " + case.first)
            assertEquals(case.second, ported(case.first), case.first)
        }
    }

    /**
     * Every radix against a spread of fractions. The listed cases above miss the branch that picks
     * between two digits that would both round back correctly, and that branch is the whole reason
     * upstream's algorithm is here rather than something simpler.
     */
    @Test
    fun numberToStringSweepsEveryRadix() {
        val values = listOf(
            "0.1", "0.2", "0.3", "0.7", "1.1", "2.5", "3.5", "0.05", "123.456", "1e-5", "1e5",
            "0.9999999999999999", "1/3", "2/7", "5/11", "1e-300", "1e300", "0.125", "1023.99609375",
            "1e-320", "1.7976931348623157e308", "5e-324",
        )
        val scripts = mutableListOf<String>()
        for (radix in 2..36) for (v in values) scripts.add("($v).toString($radix)")
        check(scripts)
    }
}
