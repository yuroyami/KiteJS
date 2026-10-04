/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Async functions and `await` (ECMAScript 2017, 14.7 and 25.5; #12, D-97). Every expected value is
 * what V8 answers. Evaluating runs the microtasks a script queued before it returns, so [logged]
 * reads what the async code wrote to `log` once it has all run.
 */
class AsyncFunctionTest {

    /** What [source] pushed to `log`, joined, once its microtasks ran. */
    private fun logged(source: String): String = KiteJs().use { js ->
        js.evaluate("var log = [];")
        js.evaluate(source)
        js.evaluate("log.join(', ')").asString()
    }

    private fun check(expected: String, source: String) {
        assertEquals(expected, KiteJs().use { js -> js.evaluate(source).asString() }, source)
    }

    /** What `eval` of [source] throws, or "ok". */
    private fun early(source: String): String =
        "try { eval(${quote(source)}); 'ok' } catch (e) { e.name }"

    private fun quote(s: String): String = "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"

    /** The checks of #12. */
    @Test
    fun the_issue() {
        assertEquals(
            "in, after, got 1, done 2",
            logged(
                "async function f(x) { log.push('in'); var v = await x; log.push('got ' + v); return v + 1 }\n" +
                    "f(Promise.resolve(1)).then(function (r) { log.push('done ' + r) });\n" +
                    "log.push('after');",
            ),
        )
        assertEquals("no", logged("async function g() { throw new Error('no') } g().catch(function (e) { log.push(e.message) })"))
        assertEquals(
            "3",
            logged("var h = async () => { try { await Promise.reject(3) } catch (e) { return e } }; h().then(function (r) { log.push(r) })"),
        )
        check("function", "typeof async function () {}")
        check("5", "var await = 5; await")
    }

    /** Each await takes its own turn of the microtask queue, interleaving as V8 does. */
    @Test
    fun ordering() {
        assertEquals(
            "a1, b1, a2, b2, p, a3",
            logged(
                "async function a() { log.push('a1'); await null; log.push('a2'); await null; log.push('a3') }\n" +
                    "async function b() { log.push('b1'); await null; log.push('b2') }\n" +
                    "a(); b(); Promise.resolve().then(() => log.push('p'))",
            ),
        )
        assertEquals("fin, 1", logged("async function f() { try { return await 1 } finally { log.push('fin') } } f().then(v => log.push(v))"))
        assertEquals("10", logged("async function f() { for (var i = 0, s = 0; i < 5; i++) s += await i; return s } f().then(v => log.push(v))"))
        assertEquals("42", logged("async function f() { return await { then(r) { r(42) } } } f().then(v => log.push(v))"))
        assertEquals("9", logged("async function f(x) { return x } f(Promise.resolve(9)).then(v => log.push(v))"))
    }

    /** A throw before the first await rejects the promise too, parameter defaults included (ECMAScript 2017, 14.7.11). */
    @Test
    fun rejections() {
        assertEquals("TypeError true", logged("async function f() { null.x } f().catch(e => log.push(e.name + ' ' + (e instanceof TypeError)))"))
        assertEquals("boom", logged("async function f(a = (() => { throw 'boom' })()) {} f().catch(e => log.push(e))"))
        assertEquals("caught 1", logged("async function f() { try { await Promise.reject(1) } catch (e) { return 'caught ' + e } } f().then(v => log.push(v))"))
    }

    /** Every form an async function takes. */
    @Test
    fun forms() {
        assertEquals("8", logged("var o = { async m(x) { return await x * 2 } }; o.m(4).then(v => log.push(v))"))
        assertEquals("7", logged("class C { constructor() { this.v = 7 } async m() { return this.v } }; new C().m().then(v => log.push(v))"))
        assertEquals("5", logged("class C { static async m() { return 5 } }; C.m().then(v => log.push(v))"))
        assertEquals("2", logged("var f = async x => x + 1; f(1).then(v => log.push(v))"))
        assertEquals("12", logged("var f = async (a, b) => a * b; f(3, 4).then(v => log.push(v))"))
        assertEquals("3", logged("var f = async (...r) => r.length; f(1, 2, 3).then(v => log.push(v))"))
        assertEquals("3", logged("var f = async ({ a }, [b]) => a + b; f({ a: 1 }, [2]).then(v => log.push(v))"))
        assertEquals("3", logged("async function f(a = 1, ...r) { return a + r.length } f(undefined, 2, 3).then(v => log.push(v))"))
        assertEquals("2", logged("(async function () { return arguments.length })(1, 2).then(v => log.push(v))"))
    }

    /**
     * Called from a function that keeps its variables in an activation, the async body enters
     * its own and leaves the caller's in place, whether it throws before an await or after.
     */
    @Test
    fun called_from_a_function() {
        assertEquals(
            "err TypeError, 2, err TypeError",
            logged(
                "function t(f) { try { f().then(function () { log.push('done') }, function (e) { log.push('err ' + e.name) }) } catch (s) { log.push('sync') } }\n" +
                    "t(async function () { null.x }); t(async function () { await 0; null.x });\n" +
                    "(function () { var k = 2; return (async () => k)() })().then(function (v) { log.push(v) })",
            ),
        )
    }

    /** A return computes its value before the finally blocks run, which may await on the way out. */
    @Test
    fun return_through_finally() {
        assertEquals(
            "expr, fin1, fin, fin2, 5, r2",
            logged(
                "async function g() { for (const x of [1, 2]) { try { if (x == 1) continue; return x } finally { log.push('fin' + await x) } } }\n" +
                    "g().then(v => log.push('r' + v), e => log.push('e' + e));\n" +
                    "async function h() { try { return await new Promise(r => r(log.push('expr') && 5)) } finally { log.push('fin') } }\n" +
                    "h().then(v => log.push(v))",
            ),
        )
    }

    /** %AsyncFunction% and what an async function object looks like (ECMAScript 2017, 25.5). */
    @Test
    fun the_function_object() {
        check("AsyncFunction", "Object.getPrototypeOf(async function () {})[Symbol.toStringTag]")
        check("true", "Object.getPrototypeOf(async () => 1) === Object.getPrototypeOf(async function () {})")
        check("true", "Object.getPrototypeOf(Object.getPrototypeOf(async function () {})) === Function.prototype")
        check("false", "async function f() {} f.hasOwnProperty('prototype')")
        check("TypeError", "async function f() {} try { new f(); 'no' } catch (e) { e.name }")
        check("true", "async function f() {} f() instanceof Promise")
        check("async function f() { await 1 }", "async function f() { await 1 } String(f)")
        check("async (a) => a", "String(async (a) => a)")
        check("async m() {}", "String({ async m() {} }.m)")
        check("AsyncFunction", "Object.getPrototypeOf(async function () {}).constructor.name")
        check("true", "typeof AsyncFunction === 'undefined'")
        assertEquals("2", logged("var A = Object.getPrototypeOf(async function () {}).constructor; new A('a', 'return await a + 1')(1).then(v => log.push(v))"))
    }

    /** `async` and `await` stay names wherever they are no keyword. */
    @Test
    fun contextual_words() {
        check("30", "var async = x => x * 10; async(3)")
        check("1", "var async = 1; async")
        check("4", "function async() { return 4 } async()")
        check("2", "var o = { async: 2 }; o.async")
        check("5", "var o = { async() { return 5 } }; o.async()")
        check("6", "var async = 6; ({ async }).async")
        check("7", "function f() { var await = 7; return await } f()")
        check("1", "var async = function (f) { return 1 }; var x = async\n(function () {})\nx")
        // A line break after `async` ends the statement, and the function after it is a plain one.
        check("ReferenceError", "try { (function () { async\nfunction foo() {} })(); 'none' } catch (e) { e.name }")
        check("true", "var async = 0; async\nfunction foo() {}\nObject.getPrototypeOf(foo) === Function.prototype")
    }

    /** The early errors of ECMAScript 2017, 14.7.1 and 14.2.1, as V8 reports them. */
    @Test
    fun early_errors() {
        check("SyntaxError", early("async function f() { var await }"))
        check("SyntaxError", early("async function f(await) {}"))
        check("SyntaxError", early("async function f(x = await 1) {}"))
        check("SyntaxError", early("async (x = await 1) => x"))
        check("SyntaxError", early("async (await) => 1"))
        check("SyntaxError", early("async await => 1"))
        check("SyntaxError", early("async ({ await }) => 1"))
        check("SyntaxError", early("async function f() { await 1 ** 2 }"))
        check("SyntaxError", early("async function f() { yield 1 }"))
        check("SyntaxError", early("(async function await() {})"))
        check("SyntaxError", early("class C { async constructor() {} }"))
        check("SyntaxError", early("class C { async get x() {} }"))
        check("SyntaxError", early("({ async get x() {} })"))
        check("SyntaxError", early("({ async\nm() {} })"))
        check("SyntaxError", early("if (1) async function f() {}"))
        check("SyntaxError", early("l: async function f() {}"))
        check("SyntaxError", early("async x\n=> x"))
        check("SyntaxError", early("x = async\n(y) => y"))
        check("SyntaxError", early("async (a)(b) => 1"))
        check("SyntaxError", early("async (...a, b) => 1"))
        check("SyntaxError", early("async (...a,) => 1"))
        check("SyntaxError", early("async (a, a) => 1"))
        check("SyntaxError", early("(a, a) => 1"))
        check("SyntaxError", early("async function f() { (x = await 1) => x }"))
        check("SyntaxError", early("class C { static { await } }"))
        check("SyntaxError", early("async () => { 'use strict' } ; async (a = 1) => { 'use strict' }"))
        check("ok", early("async function f() { () => await }"))
        check("ok", early("async function f() { function g() { var await } }"))
        check("ok", early("async (x = function await() {}) => 1"))
        check("ok", early("async (x = () => await) => 1"))
        check("ok", early("async ({ await: x }) => 1"))
        check("ok", early("async function f() { class C { x = await; } }"))
        check("ok", early("async function f() { class C { [await 1]() {} } }"))
        check("ok", early("class C { async\nm() {} }"))
        check("ok", early("class C { static async m() {} async 'x'() {} async [1]() {} async = 1 }"))
        check("ok", early("async function f() { await\n1 }"))
        check("ok", early("async function f() { -await 1; typeof await 1; delete await 1; await await 1 }"))
        check("ok", early("async function f() { (await 1) ** 2 }"))
        // No syntax error: the name `async`, which is not defined, then a plain function.
        check("ReferenceError", early("async\nfunction f() {}"))
    }
}
