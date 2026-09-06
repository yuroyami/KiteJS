/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Evaluation on every target. The JVM oracle proves the answers match upstream; this proves the
 * same code gives the same answers off the JVM, where numbers, strings and collections all have a
 * different runtime underneath.
 */
class EvalSmokeTest {

    private fun eval(source: String): String = ContextFactory.getGlobal().call { cx ->
        cx.languageVersion = Context.VERSION_ES6
        val scope = cx.initStandardObjects()
        val v = try {
            cx.evaluateString(scope, source, "smoke.js", 1)
        } catch (e: RhinoException) {
            return@call "throws " + e.details()
        }
        when {
            v == null -> "null"
            Undefined.isUndefined(v) -> "undefined"
            v is Boolean -> v.toString()
            v is Number -> ScriptRuntime.numberToString(v.toDouble(), 10)
            v is CharSequence -> v.toString()
            v is Scriptable -> "[object " + v.className + "]"
            else -> v.toString()
        }
    }

    /**
     * Runs [setup], then reads [answer] in a second top call. A promise's result only exists once
     * the microtask queue has drained, and the queue drains when the top call returns, so reading
     * it inside the same script would always give the state before anything ran.
     */
    private fun evalAfterDrain(setup: String, answer: String): String = ContextFactory.getGlobal().call { cx ->
        cx.languageVersion = Context.VERSION_ES6
        val scope = cx.initStandardObjects()
        try {
            cx.evaluateString(scope, setup, "smoke.js", 1)
            val v = cx.evaluateString(scope, answer, "smoke.js", 1)
            when {
                v == null -> "null"
                Undefined.isUndefined(v) -> "undefined"
                v is Boolean -> v.toString()
                v is Number -> ScriptRuntime.numberToString(v.toDouble(), 10)
                v is CharSequence -> v.toString()
                v is Scriptable -> "[object " + v.className + "]"
                else -> v.toString()
            }
        } catch (e: RhinoException) {
            "throws " + e.details()
        }
    }

    @Test
    fun arithmetic() {
        assertEquals("3", eval("1 + 2"))
        assertEquals("3.5", eval("7 / 2"))
        assertEquals("0.30000000000000004", eval("0.1 + 0.2"))
        assertEquals("Infinity", eval("1 / 0"))
        assertEquals("NaN", eval("0 / 0"))
        assertEquals("1024", eval("2 ** 10"))
        assertEquals("-4", eval("-16 >> 2"))
        assertEquals("1073741820", eval("-16 >>> 2"))
        assertEquals("2147483648", eval("2147483647 + 1"))
        assertEquals("12", eval("'3' * '4'"))
        assertEquals("34", eval("'3' + 4"))
        assertEquals("1e+21", eval("'' + 1e21"))
        assertEquals("1e-7", eval("'' + 1e-7"))
        // Values read back out of an array are boxed. On Kotlin/JS every number passes `is Int`,
        // and the Int fast paths used to swallow fractions, NaN and infinities (D-32).
        assertEquals("1.5", eval("[2.5][0] - 1"))
        assertEquals("NaN", eval("['a'][0] - 1"))
        assertEquals("NaN", eval("[NaN][0] * 2"))
        assertEquals("Infinity", eval("[Infinity][0] - 1"))
        assertEquals("2", eval("[2.5][0] | 0"))
        assertEquals("-2.5", eval("-[2.5][0]"))
        assertEquals("-3", eval("~[2.5][0]"))
        assertEquals("3.5", eval("var a = [2.5]; a[0]++; a[0]"))
        assertEquals("9999999999", eval("[1e10][0] - 1"))
        assertEquals("-Infinity", eval("1 / ([-0][0] + [-0][0])"))
        assertEquals("v", eval("Object.fromEntries([[1.5, 'v']])['1.5']"))
        assertEquals("5", eval("[5.5][0] - [0.5][0]"))
        // Upstream prints the operand with Java's Double.toString here, so the port does the same everywhere.
        assertEquals("throws TypeError: 5.5 is not a function, it is number.", eval("new 5.5"))
    }

    @Test
    fun comparisonAndCoercion() {
        assertEquals("true", eval("1 == '1'"))
        assertEquals("false", eval("1 === '1'"))
        assertEquals("true", eval("null == undefined"))
        assertEquals("false", eval("'10' < 9"))
        assertEquals("false", eval("10 < 9"))
        assertEquals("true", eval("'abc' < 'abd'"))
        assertEquals("number", eval("typeof 1"))
        assertEquals("object", eval("typeof null"))
        assertEquals("function", eval("typeof function(){}"))
        assertEquals("d", eval("null ?? 'd'"))
        assertEquals("0", eval("0 ?? 'd'"))
    }

    @Test
    fun functionsAndClosures() {
        assertEquals("3", eval("function f(a, b) { return a + b } f(1, 2)"))
        assertEquals("3", eval("function mk() { var c = 0; return function () { return ++c } } var g = mk(); g(); g(); g()"))
        assertEquals("3628800", eval("function fact(n) { return n <= 1 ? 1 : n * fact(n - 1) } fact(10)"))
        assertEquals("610", eval("function fib(n) { return n < 2 ? n : fib(n - 1) + fib(n - 2) } fib(15)"))
        assertEquals("7", eval("var o = { v: 7, m() { return this.v } }; o.m()"))
        assertEquals("12", eval("var f = x => x * 3; f(4)"))
        assertEquals("5", eval("function f(a = 5) { return a } f()"))
        assertEquals("localglobal", eval("var x = 'global'; function f() { var x = 'local'; return x } f() + x"))
        assertEquals("hoisted", eval("function f() { return g() } function g() { return 'hoisted' } f()"))
    }

    @Test
    fun controlFlow() {
        assertEquals("45", eval("var s = 0; for (var i = 0; i < 10; i++) s += i; s"))
        assertEquals("20", eval("var s = 0; for (var i = 0; i < 10; i++) { if (i % 2) continue; s += i } s"))
        assertEquals("bc", eval("var r = ''; switch (2) { case 1: r += 'a'; case 2: r += 'b'; case 3: r += 'c'; break; default: r += 'd' } r"))
        assertEquals("abc", eval("var o = { a: 1, b: 2, c: 3 }; var keys = ''; for (var k in o) keys += k; keys"))
        assertEquals("ba", eval("var o = { a: 1 }; var p = { __proto__: o, b: 2 }; var keys = ''; for (var k in p) keys += k; keys"))
        assertEquals("2", eval("outer: for (var i = 0; i < 3; i++) { for (var j = 0; j < 3; j++) { if (j == 1) continue outer; if (i == 2) break outer } } i"))
        assertEquals("1", eval("var x = 1; { let x = 2; } x"))
    }

    @Test
    fun exceptions() {
        assertEquals("boom", eval("var r; try { throw 'boom' } catch (e) { r = e } r"))
        assertEquals("cf", eval("var r = ''; try { throw 'x' } catch (e) { r += 'c' } finally { r += 'f' } r"))
        assertEquals("b", eval("function f() { try { return 'a' } finally { return 'b' } } f()"))
        assertEquals("0ff2f", eval("var r = ''; for (var i = 0; i < 3; i++) { try { if (i == 1) continue; r += i } finally { r += 'f' } } r"))
        assertEquals("throws ReferenceError: \"undeclared\" is not defined.", eval("undeclared"))
        assertEquals("throws TypeError: Cannot read property \"x\" from null", eval("null.x"))
        assertEquals("throws TypeError: 1 is not a function, it is number.", eval("(1)()"))
    }

    @Test
    fun standardObjects() {
        assertEquals("42", eval("parseInt('42abc')"))
        assertEquals("31", eval("parseInt('0x1f')"))
        assertEquals("3.14", eval("parseFloat('3.14abc')"))
        assertEquals("NaN", eval("parseFloat('')"))
        assertEquals("true", eval("isNaN('x')"))
        assertEquals("3", eval("Math.max(1, 2, 3)"))
        assertEquals("-Infinity", eval("Math.max()"))
        assertEquals("3", eval("Math.round(2.5)"))
        assertEquals("-2", eval("Math.round(-2.5)"))
        assertEquals("1.00", eval("(1.005).toFixed(2)"))
        assertEquals("3", eval("(2.5).toFixed(0)"))
        assertEquals("1.23e+2", eval("(123.456).toExponential(2)"))
        assertEquals("123.5", eval("(123.456).toPrecision(4)"))
        assertEquals("true", eval("Object.is(NaN, NaN)"))
        assertEquals("false", eval("Object.is(0, -0)"))
        assertEquals("5", eval("var o = {}; Object.defineProperty(o, 'x', { value: 5 }); o.x = 6; o.x"))
        assertEquals("true", eval("Object.isFrozen(Object.freeze({}))"))
        assertEquals("throws TypeError: Cannot add properties to this object because extensible is false.", eval("'use strict'; var o = Object.preventExtensions({}); o.x = 1"))
        assertEquals("m", eval("new Error('m').message"))
        assertEquals("TypeError: t", eval("'' + new TypeError('t')"))
        assertEquals("true", eval("try { null.x } catch (e) { e instanceof TypeError }"))
        assertEquals("true", eval("new Boolean(false) == false"))
        assertEquals("true", eval("Number.isInteger(5)"))
        assertEquals("false", eval("Number.isSafeInteger(2 ** 53)"))
        assertEquals("a%20b%26c%3Dd%2F%C3%A9", eval("encodeURIComponent('a b&c=d/\u00e9')"))
        assertEquals("A \u00fc?", eval("decodeURIComponent('%41%20%C3%BC%3F')"))
        assertEquals("10", eval("var x = 5; eval('x * 2')"))
        assertEquals("5", eval("new Function('a', 'b', 'return a + b')(2, 3)"))
        assertEquals("3", eval("function f() { return arguments.length } f(1, 2, 3)"))
        assertEquals("({a:1, b:\"x\"})", eval("uneval({ a: 1, b: 'x' })"))
    }

    @Test
    fun arrays() {
        assertEquals("3", eval("[1, 2, 3].length"))
        assertEquals("1,2,3", eval("'' + [1, 2, 3]"))
        assertEquals("1-2-3", eval("[1, 2, 3].join('-')"))
        assertEquals("4", eval("var a = [1, 2, 3]; a.push(4); a.length"))
        assertEquals("3", eval("var a = [1, 2, 3]; a.pop()"))
        assertEquals("1,10,9", eval("[10, 9, 1].sort().join()"))
        assertEquals("3,2,1", eval("[3, 1, 2].sort((a, b) => b - a).join()"))
        assertEquals("2,3", eval("[1, 2, 3, 4, 5].slice(1, 3).join()"))
        assertEquals("1,x,y,3", eval("var a = [1, 2, 3]; a.splice(1, 1, 'x', 'y'); a.join()"))
        assertEquals("2,4", eval("[1, 2, 3, 4].filter(x => x % 2 == 0).join()"))
        assertEquals("1,4,9", eval("[1, 2, 3].map(x => x * x).join()"))
        assertEquals("6", eval("[1, 2, 3].reduce((a, b) => a + b)"))
        assertEquals("true", eval("[NaN].includes(NaN)"))
        assertEquals("-1", eval("[NaN].indexOf(NaN)"))
        assertEquals("1,2,3,4", eval("[1, [2, [3, [4]]]].flat(Infinity).join()"))
        assertEquals("123", eval("var r = ''; for (var x of [1, 2, 3]) r += x; r"))
        assertEquals("2", eval("var [a, b] = [1, 2, 3]; b"))
        assertEquals("5", eval("Math.max.apply(null, [1, 5, 3])"))
        assertEquals("a,b", eval("Object.keys({ a: 1, b: 2 }).join()"))
        assertEquals("6", eval("var a = []; a[5] = 1; a.length"))
        assertEquals("1", eval("var a = [1, 2, 3]; a.length = 1; a.join()"))
        assertEquals("true", eval("Array.isArray([])"))
        assertEquals("[object Array]", eval("Object.prototype.toString.call([])"))
        assertEquals("throws RangeError: Inappropriate array length.", eval("new Array(-1)"))
    }

    @Test
    fun stringsAndJson() {
        assertEquals("3", eval("'abc'.length"))
        assertEquals("b", eval("'abc'[1]"))
        assertEquals("98", eval("'abc'.charCodeAt(1)"))
        assertEquals("128512", eval("'\uD83D\uDE00'.codePointAt(0)"))
        assertEquals("2", eval("'abcabc'.indexOf('c')"))
        assertEquals("5", eval("'abcabc'.lastIndexOf('c')"))
        assertEquals("bc", eval("'abcdef'.slice(1, 3)"))
        assertEquals("ef", eval("'abcdef'.slice(-2)"))
        assertEquals("bcd", eval("'abcdef'.substr(1, 3)"))
        assertEquals("ABC", eval("'abc'.toUpperCase()"))
        assertEquals("a b", eval("'  a b  '.trim()"))
        assertEquals("a|b|c", eval("'a,b,c'.split(',').join('|')"))
        assertEquals("a|b|c", eval("'abc'.split('').join('|')"))
        assertEquals("ababab", eval("'ab'.repeat(3)"))
        assertEquals("005", eval("'5'.padStart(3, '0')"))
        assertEquals("a-b-c", eval("'aXbXc'.replaceAll('X', '-')"))
        assertEquals("a[b]c", eval("'abc'.replace('b', '[$&]')"))
        assertEquals("Hi", eval("String.fromCharCode(72, 105)"))
        assertEquals("true", eval("'abc'.startsWith('ab') && 'abc'.endsWith('bc') && 'abc'.includes('b')"))
        assertEquals("a-b-c-", eval("var r = ''; for (var c of 'abc') r += c + '-'; r"))
        assertEquals("0,1,2", eval("Object.keys('abc').join()"))
        assertEquals("{\"a\":1,\"b\":[1,\"x\",null,true,null]}", eval("JSON.stringify({ a: 1, b: [1, 'x', null, true, undefined] })"))
        assertEquals("{\n  \"a\": [\n    1\n  ]\n}", eval("JSON.stringify({ a: [1] }, null, 2)"))
        assertEquals("\"\\u001f\\\"\\n\"", eval("JSON.stringify('\\u001f\"\\n')"))
        assertEquals("2", eval("JSON.parse('{\"a\":[1,2]}').a[1]"))
        assertEquals("A\n", eval("JSON.parse('\"\\\\u0041\\\\n\"')"))
        assertEquals("4", eval("JSON.parse('[1,2]', function (k, v) { return typeof v == 'number' ? v * 2 : v })[1]"))
        assertEquals("throws SyntaxError: Unterminated object literal", eval("JSON.parse('{')"))
        assertEquals("throws TypeError: Cyclic {0} value not allowed.".replace("{0}", "NativeObject"), eval("var o = {}; o.o = o; JSON.stringify(o)"))
    }

    @Test
    fun symbols() {
        assertEquals("symbol", eval("typeof Symbol('a')"))
        assertEquals("Symbol(a)", eval("Symbol('a').toString()"))
        assertEquals("a", eval("Symbol('a').description"))
        assertEquals("false", eval("Symbol('a') === Symbol('a')"))
        assertEquals("true", eval("Symbol.for('k') === Symbol.for('k')"))
        assertEquals("k", eval("Symbol.keyFor(Symbol.for('k'))"))
        assertEquals("7", eval("var s = Symbol('k'); var o = {}; o[s] = 7; o[s]"))
        assertEquals("1", eval("var s = Symbol('k'); var o = {}; o[s] = 7; Object.getOwnPropertySymbols(o).length"))
        assertEquals("{}", eval("var s = Symbol('k'); var o = {}; o[s] = 7; JSON.stringify(o)"))
        assertEquals("[object Thing]", eval("var o = {}; o[Symbol.toStringTag] = 'Thing'; Object.prototype.toString.call(o)"))
        assertEquals("function", eval("typeof [][Symbol.iterator]"))
        assertEquals("true", eval("Array[Symbol.species] === Array"))
        assertEquals("throws TypeError: The object is not a number", eval("+Symbol()"))
    }

    @Test
    fun generators() {
        assertEquals("object", eval("function* g() { yield 1 } typeof g()"))
        assertEquals("1", eval("function* g() { yield 1 } g().next().value"))
        assertEquals("true", eval("function* g() { yield 1 } var it = g(); it.next(); it.next().done"))
        assertEquals("1,2,3", eval("function* g() { yield 1; yield 2; yield 3 } [...g()].join()"))
        assertEquals("42", eval("function* g() { var x = yield 1; yield x * 2 } var it = g(); it.next(); it.next(21).value"))
        assertEquals("0,1,2,3", eval("function* inner() { yield 1; yield 2 } function* g() { yield 0; yield* inner(); yield 3 } [...g()].join()"))
        assertEquals("1,2,3", eval("function* g() { yield* [1, 2, 3] } [...g()].join()"))
        assertEquals("9", eval("function* g() { yield 1; yield 2 } var it = g(); it.next(); it.return(9).value"))
        assertEquals("caught boom", eval("function* g() { try { yield 1 } catch (e) { yield 'caught ' + e } } var it = g(); it.next(); it.throw('boom').value"))
        assertEquals("1,2", eval("var o = { *g() { yield 1; yield 2 } }; [...o.g()].join()"))
        assertEquals("true", eval("function* g() { yield 1 } var it = g(); it[Symbol.iterator]() === it"))
        // Rhino runs the finally on an explicit return(), but not when a for-of loop breaks.
        assertEquals("ran", eval("function* g() { try { yield 1 } finally { globalThis.fin = 'ran' } } var it = g(); it.next(); it.return(0); globalThis.fin"))
        assertEquals("undefined", eval("function* g() { try { yield 1; yield 2 } finally { globalThis.brk = 'ran' } } for (var v of g()) break; globalThis.brk"))
        assertEquals("0,1,2", eval("var o = {}; o[Symbol.iterator] = function () { var n = 0; return { next: function () { return n < 3 ? { value: n++, done: false } : { done: true } } } }; [...o].join()"))
    }

    @Test
    fun mapAndSet() {
        assertEquals("2", eval("new Map([['a', 1], ['b', 2]]).size"))
        assertEquals("1", eval("var m = new Map(); m.set('a', 1); m.get('a')"))
        assertEquals("undefined", eval("new Map().get('missing')"))
        assertEquals("b,a,c", eval("var m = new Map(); m.set('b', 1); m.set('a', 2); m.set('c', 3); [...m.keys()].join()"))
        assertEquals("1:true", eval("var m = new Map(); m.set(NaN, 1); m.get(NaN) + ':' + m.has(NaN)"))
        assertEquals("1", eval("var m = new Map(); m.set(0, 'a'); m.set(-0, 'b'); m.size"))
        assertEquals("a=1,b=2", eval("var m = new Map([['a', 1], ['b', 2]]); var r = []; m.forEach(function (v, k) { r.push(k + '=' + v) }); r.join()"))
        assertEquals("{}", eval("JSON.stringify(new Map([['a', 1]]))"))
        assertEquals("3", eval("new Set([1, 1, 2, 3]).size"))
        assertEquals("h,e,l,o", eval("[...new Set('hello')].join()"))
        assertEquals("true:1", eval("var s = new Set(); s.add(1); s.has(1) + ':' + s.size"))
        assertEquals("1,2,3,4", eval("[...new Set([1, 2, 3]).union(new Set([3, 4]))].join()"))
        assertEquals("2,3", eval("[...new Set([1, 2, 3]).intersection(new Set([2, 3, 4]))].join()"))
        assertEquals("true", eval("new Set([1, 2]).isSubsetOf(new Set([1, 2, 3]))"))
        assertEquals("odd,even", eval("var g = Map.groupBy([1, 2, 3, 4], function (n) { return n % 2 ? 'odd' : 'even' }); [...g.keys()].join()"))
        assertEquals("a,c", eval("var m = new Map([['a', 1], ['b', 2]]); var r = []; m.forEach(function (v, k) { r.push(k); if (k === 'a') m.delete('b'); if (k === 'a') m.set('c', 3) }); r.join()"))
    }

    @Test
    fun regularExpressions() {
        assertEquals("abc", eval("/abc/.source"))
        assertEquals("/a+b/", eval("String(/a+b/)"))
        assertEquals("gi", eval("/abc/gi.flags"))
        assertEquals("true", eval("/a+/.test('caaat')"))
        assertEquals("aaa", eval("/a+/.exec('caaat')[0]"))
        assertEquals("1", eval("/a+/.exec('caaat').index"))
        assertEquals("a#b#", eval("'a1b2'.replace(/\\d/g, '#')"))
        assertEquals("1,2", eval("'a1b2'.match(/\\d/g).join()"))
        assertEquals("a|b|c", eval("'a,b;c'.split(/[,;]/).join('|')"))
        assertEquals("2", eval("'hello'.search(/l/)"))
        assertEquals("42", eval("/(?<x>\\d+)/.exec('n42').groups.x"))
        assertEquals("02/01/2024", eval("'2024-01-02'.replace(/(\\d+)-(\\d+)-(\\d+)/, '\$3/\$2/\$1')"))
        assertEquals("2", eval("[...'aXbXc'.matchAll(/X/g)].length"))
        assertEquals("true", eval("/^\\p{Lu}$/u.test('A')"))
        assertEquals("false", eval("/^\\p{Ll}$/u.test('A')"))
        assertEquals("true", eval("/a/i.test('A')"))
        assertEquals("true", eval("/./s.test('\\n')"))
        assertEquals("1", eval("var r = /a/g; r.exec('aa'); r.lastIndex"))
        assertEquals("aa", eval("/(a)\\1/.exec('aa')[0]"))
        assertEquals("b", eval("/(?<=a)b/.exec('ab')[0]"))
        assertEquals("123", eval("/(\\d+)/.exec('a123b'); RegExp.\$1"))
        assertEquals("throws SyntaxError: Unterminated parenthetical ", eval("new RegExp('(')"))
    }

    @Test
    fun typedArrays() {
        assertEquals("4", eval("new ArrayBuffer(4).byteLength"))
        assertEquals("1,2,3", eval("new Int8Array([1, 2, 3]).join()"))
        assertEquals("1,-1,44", eval("new Int8Array([1.7, -1.7, 300]).join()"))
        // Clamping rounds half to even: 0.5 and 2.5 go down, 1.5 goes up.
        assertEquals("0,0,2,2,255", eval("new Uint8ClampedArray([-5, 0.5, 1.5, 2.5, 300]).join()"))
        assertEquals("9", eval("var b = new ArrayBuffer(4); var a = new Uint8Array(b); var c = new Uint8Array(b); a[0] = 9; c[0]"))
        assertEquals("undefined", eval("new Int8Array([1, 2, 3])[5]"))
        assertEquals("2", eval("new Int8Array(4).fill(2)[3]"))
        assertEquals("1,3,NaN", eval("new Float64Array([3, NaN, 1]).sort().join()"))
        assertEquals("2,4,6", eval("new Int8Array([1, 2, 3]).map(function (v) { return v * 2 }).join()"))
        assertEquals("2,3", eval("new Int8Array([1, 2, 3]).subarray(1).join()"))
        assertEquals("-1", eval("var d = new DataView(new ArrayBuffer(8)); d.setInt8(0, -1); d.getInt8(0)"))
        assertEquals("1:2", eval("var d = new DataView(new ArrayBuffer(8)); d.setInt16(0, 258); d.getUint8(0) + ':' + d.getUint8(1)"))
        assertEquals("2:1", eval("var d = new DataView(new ArrayBuffer(8)); d.setInt16(0, 258, true); d.getUint8(0) + ':' + d.getUint8(1)"))
        assertEquals("4294967295", eval("var d = new DataView(new ArrayBuffer(8)); d.setUint32(0, 4294967295); d.getUint32(0)"))
        assertEquals("0.3333333333333333", eval("var d = new DataView(new ArrayBuffer(8)); d.setFloat64(0, 1/3); d.getFloat64(0)"))
        assertEquals("1,2,3", eval("Int8Array.from([1, 2, 3]).join()"))
        assertEquals("[object Int8Array]", eval("Object.prototype.toString.call(new Int8Array(1))"))
    }

    @Test
    fun promises() {
        val log = "var log = []; globalThis.read = function () { return log.join('|') };"
        assertEquals("a|b|c", evalAfterDrain("$log log.push('a'); Promise.resolve().then(function () { log.push('c') }); log.push('b')", "read()"))
        assertEquals("1|2|3", evalAfterDrain("$log Promise.resolve().then(function () { log.push(1) }).then(function () { log.push(2) }).then(function () { log.push(3) })", "read()"))
        assertEquals("2", evalAfterDrain("$log Promise.resolve(1).then(function (v) { return v + 1 }).then(function (v) { log.push(v) })", "read()"))
        assertEquals("caught x", evalAfterDrain("$log Promise.reject('x').catch(function (e) { log.push('caught ' + e) })", "read()"))
        assertEquals("boom", evalAfterDrain("$log new Promise(function () { throw 'boom' }).catch(function (e) { log.push(e) })", "read()"))
        assertEquals("1,2", evalAfterDrain("$log Promise.all([1, Promise.resolve(2)]).then(function (v) { log.push(v.join()) })", "read()"))
        assertEquals("fulfilled:1 rejected:e", evalAfterDrain("$log Promise.allSettled([1, Promise.reject('e')]).then(function (rs) { log.push(rs.map(function (r) { return r.status.trim() + ':' + (r.status.trim() === 'fulfilled' ? r.value : r.reason) }).join(' ')) })", "read()"))
        assertEquals("1", evalAfterDrain("$log Promise.race([Promise.resolve(1), 2]).then(function (v) { log.push(v) })", "read()"))
        assertEquals("2", evalAfterDrain("$log Promise.any([Promise.reject(1), Promise.resolve(2)]).then(function (v) { log.push(v) })", "read()"))
        assertEquals("AggregateError", evalAfterDrain("$log Promise.any([Promise.reject(1), Promise.reject(2)]).catch(function (e) { log.push(e.name) })", "read()"))
        assertEquals("f|1", evalAfterDrain("$log Promise.resolve(1).finally(function () { log.push('f') }).then(function (v) { log.push(v) })", "read()"))
        assertEquals("5", evalAfterDrain("$log Promise.resolve({ then: function (r) { r(5) } }).then(function (v) { log.push(v) })", "read()"))
        assertEquals("4", evalAfterDrain("$log var w = Promise.withResolvers(); w.promise.then(function (v) { log.push(v) }); w.resolve(4)", "read()"))
        assertEquals("[object Promise]", eval("Object.prototype.toString.call(Promise.resolve())"))
        assertEquals("true", eval("Promise.resolve() instanceof Promise"))
    }

    @Test
    fun objectsAndPrototypes() {
        assertEquals("deep", eval("var o = { a: { b: { c: 'deep' } } }; o.a.b.c"))
        assertEquals("42", eval("var o = { get x() { return 42 } }; o.x"))
        assertEquals("8", eval("var o = { set x(v) { this._x = v * 2 } }; o.x = 4; o._x"))
        assertEquals("custom", eval("var o = { toString: function () { return 'custom' } }; '' + o"))
        assertEquals("three", eval("var o = { [1 + 2]: 'three' }; o[3]"))
        assertEquals("2", eval("var o = { a: 1, ...{ b: 2 } }; o.b"))
        assertEquals("undefined", eval("var o = null; o?.a"))
        assertEquals("3", eval("function F() { this.a = 1 } F.prototype.b = 2; var o = new F(); o.a + o.b"))
        assertEquals("true", eval("function F() {} var o = new F(); o instanceof F"))
        assertEquals("true", eval("function F() {} var o = new F(); o.constructor === F"))
        assertEquals("2", eval("var o = { x: 1 }; with (o) { x = 2 } o.x"))
        assertEquals("false", eval("var o = { a: 1 }; delete o.a; 'a' in o"))
    }

    @Test
    fun proxies() {
        assertEquals("1", eval("var p = new Proxy({a:1}, {}); p.a"))
        assertEquals("42", eval("var p = new Proxy({a:1}, {get:function(t,k){return k === 'a' ? 42 : t[k];}}); p.a"))
        assertEquals("10", eval("var p = new Proxy({}, {set:function(t,k,v){t[k]=v*2; return true;}}); p.x = 5; p.x"))
        assertEquals("false,true", eval("var p = new Proxy({a:1}, {has:function(t,k){return k==='b';}}); ('a' in p) + ',' + ('b' in p)"))
        assertEquals("undefined", eval("var t={a:1}; var p=new Proxy(t,{deleteProperty:function(tt,k){delete tt[k]; return true;}}); delete p.a; String(t.a)"))
        assertEquals("b", eval("var p = new Proxy({a:1,b:2}, {ownKeys:function(){return ['b'];}}); Object.keys(p).join(',')"))
        assertEquals("a,b", eval("var p = new Proxy({a:1,b:2},{}); var out=[]; for (var k in p) out.push(k); out.join(',')"))
        assertEquals("30", eval("var p = new Proxy(function(a,b){return a+b;}, {apply:function(t,th,args){return t.apply(th,args)*10;}}); p(1,2)"))
        assertEquals("3", eval("function F(x){this.x=x;} var p = new Proxy(F, {construct:function(t,args){return {x:3};}}); (new p(5)).x"))
        // The construct trap gets a real array, not the raw argument list (D-51).
        assertEquals("true,2,7", eval("var p = new Proxy(function(x){this.x=x;}, {construct:function(t,args){return {out: Array.isArray(args) + ',' + args.length + ',' + args[0]};}}); (new p(7,8)).out"))
        assertEquals("function", eval("typeof new Proxy(function(){}, {})"))
        assertEquals("true", eval("Array.isArray(new Proxy([], {}))"))
        assertEquals("true", eval("var proto={z:1}; var p=new Proxy({},{getPrototypeOf:function(){return proto;}}); Object.getPrototypeOf(p) === proto"))
        assertEquals("1:TypeError", eval("var r = Proxy.revocable({a:1},{}); var v = r.proxy.a; r.revoke(); try { r.proxy.a } catch(e) { v + ':' + e.name }"))
        assertEquals("TypeError", eval("var t = Object.freeze({a:1}); var p = new Proxy(t, {get:function(){return 2;}}); try { p.a } catch(e) { e.name }"))
        // Upstream crashes on this one; the port answers undefined, as the spec says (D-50).
        assertEquals("undefined", eval("var p = new Proxy({}, {getOwnPropertyDescriptor:function(){return undefined;}}); String(Object.getOwnPropertyDescriptor(p,'zzz'))"))
    }

    @Test
    fun reflection() {
        assertEquals("1", eval("Reflect.get({a:1},'a')"))
        assertEquals("a,b", eval("Reflect.ownKeys({a:1,b:2}).join(',')"))
        assertEquals("false", eval("Reflect.has({a:1},'b')"))
        assertEquals("5", eval("var o={}; Reflect.set(o,'x',5); o.x"))
        assertEquals("3", eval("var o={}; Reflect.defineProperty(o,'x',{value:3}); o.x"))
        assertEquals("7", eval("function F(a){this.a=a;} Reflect.construct(F,[7]).a"))
        assertEquals("true", eval("function F(){this.a=1;} function G(){} Reflect.construct(F,[],G) instanceof G"))
        assertEquals("5", eval("Reflect.apply(Math.max,null,[1,5,2])"))
        assertEquals("true", eval("Reflect.isExtensible({})"))
        assertEquals("false", eval("var o={}; Reflect.preventExtensions(o); Reflect.isExtensible(o)"))
        assertEquals("[object Reflect]", eval("Object.prototype.toString.call(Reflect)"))
        assertEquals("1", eval("var p = new Proxy({a:1},{get:function(t,k,r){return Reflect.get(t,k,r);}}); p.a"))
    }
}
