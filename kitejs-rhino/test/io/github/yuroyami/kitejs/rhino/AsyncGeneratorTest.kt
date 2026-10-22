/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Async generators, `for await` and `Array.fromAsync` (#91). Each script stores its answer in
 * `__r` once its promises settle. The expected strings are Node 26.10 output, tick order included.
 */
class AsyncGeneratorTest {
    private fun check(expected: String, source: String) {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            cx.evaluateString(scope, source, "async-generator.js", 1, null)
            cx.processMicrotasks()
            assertEquals(expected, ScriptRuntime.toString(ScriptableObject.getProperty(scope, "__r")))
        } finally {
            Context.exit()
        }
    }

    private val ticker = """
        var log = [];
        function tick(n) { var p = Promise.resolve(); for (var i = 0; i < n; i++) p = p.then(() => {}); return p }
        var t = 0; (function loop() { if (t < 30) { log.push('t' + t++); Promise.resolve().then(loop) } })();
    """

    @Test
    fun nextReturnYieldStarAndForAwait() = check(
        "start|{\"value\":1,\"done\":false}|got b|{\"value\":2,\"done\":false}|{\"value\":3,\"done\":false}|fin|" +
            "{\"value\":\"R\",\"done\":true}|{\"done\":true}|fa1|fa2|fa3|start|h1|got undefined|h2|h3|fin|h7|h8|" +
            "[object AsyncGenerator]|symbol|AsyncGeneratorFunction",
        """
        var log = [];
        async function* g() { log.push('start'); var x = yield 1; log.push('got ' + x); yield Promise.resolve(2); try { yield 3 } finally { log.push('fin') } return 'r' }
        var it = g();
        (async function () {
            log.push(JSON.stringify(await it.next('a')));
            log.push(JSON.stringify(await it.next('b')));
            log.push(JSON.stringify(await it.next()));
            log.push(JSON.stringify(await it.return(Promise.resolve('R'))));
            log.push(JSON.stringify(await it.next()));
            for await (const v of [1, Promise.resolve(2), 3]) log.push('fa' + v);
            async function* h() { yield* g(); yield* [7, Promise.resolve(8)]; }
            for await (const v of h()) log.push('h' + v);
            log.push(Object.prototype.toString.call(it), typeof Symbol.asyncIterator, Object.getPrototypeOf(g).constructor.name);
        })().then(function () { __r = log.join('|') }, function (e) { __r = 'ERR ' + e });
        """,
    )

    @Test
    fun requestsQueueAndLoopsCloseInTickOrder() = check(
        "t0 next next next t1 next ret ret t2 n1:1 ret caught boom t3 n2:2 n3:true after break returned t4 r:9 n4:true " +
            (5 until 30).joinToString(" ") { "t$it" },
        ticker + """
        async function* g() { yield 1; yield 2; }
        var it = g();
        it.next().then(v => log.push('n1:' + v.value));
        it.next().then(v => log.push('n2:' + v.value));
        it.next().then(v => log.push('n3:' + v.done));
        it.return(9).then(v => log.push('r:' + v.value));
        it.next().then(v => log.push('n4:' + v.done));
        var closeIt = { [Symbol.asyncIterator]() { var i = 0; return {
            next() { log.push('next'); return Promise.resolve({ value: i++, done: false }) },
            return() { log.push('ret'); return Promise.resolve({}) } } } };
        (async () => { for await (var x of closeIt) { if (x == 1) break } log.push('after break') })();
        (async () => { try { for await (var x of closeIt) { throw 'boom' } } catch (e) { log.push('caught ' + e) } })();
        (async () => { for await (var x of closeIt) { return } })().then(() => log.push('returned'));
        tick(40).then(() => { __r = log.join(' ') });
        """,
    )

    @Test
    fun errorsClosingAndDelegation() = check(
        listOf(
            "0:TypeError", "1:r", "2:body", "3:body", "4:rej", "5:TypeError", "6:TypeError", "7:TypeError", "8:pr", "9:v",
            "10:ok:{\"value\":4,\"done\":true}", "11:ok:early {\"done\":true}", "12:ok:{\"value\":\"x\",\"done\":true}",
            "13:ok:{\"value\":\"cE\",\"done\":false}{\"done\":true}", "14:ok:TypeError: The iterator does not provide a 'throw' method.",
            "15:ok:{\"value\":7,\"done\":true}", "16:ok:async function* f(a) { yield a } async *m() {} function", "17:ok:false",
            "18:ok:Symbol(Symbol.asyncIterator),5", "19:ok:TypeError", "20:ok:false false", "21:ok:TypeError",
            "sync closed,fin awaited,inner fin,outer fin,ret called,loop closed",
        ).joinToString("\n"),
        """
        var log = [];
        function mk(ret) { return { [Symbol.asyncIterator]() { return { next() { return { value: 1, done: false } }, return: ret } } } }
        var tests = [
            async () => { for await (var x of mk(() => 5)) break },
            async () => { for await (var x of mk(() => { throw 'r' })) break },
            async () => { for await (var x of mk(() => { throw 'r' })) throw 'body' },
            async () => { for await (var x of mk(() => Promise.reject('rej'))) throw 'body' },
            async () => { for await (var x of mk(() => Promise.reject('rej'))) break },
            async () => { for await (var x of { [Symbol.asyncIterator]() { return { next() { return 3 } } } }) ; },
            async () => { for await (var x of 5) ; },
            async () => { for await (var x of { [Symbol.asyncIterator]: 1 }) ; },
            async () => { for await (var x of [Promise.reject('pr')]) ; },
            async () => { var s = { [Symbol.iterator]() { return { next() { return { value: Promise.reject('v'), done: false } }, return() { log.push('sync closed'); return {} } } } }; for await (var x of s) ; },
            async () => { async function* g() { try { yield 1 } finally { await null; log.push('fin awaited') } } var it = g(); await it.next(); return JSON.stringify(await it.return(4)) },
            async () => { async function* g() { yield 1 } var it = g(); try { await it.throw(new Error('early')) } catch (e) { return e.message + ' ' + JSON.stringify(await it.next()) } },
            async () => { async function* inner() { try { yield 1; yield 2 } finally { log.push('inner fin') } } async function* outer() { try { yield* inner() } finally { log.push('outer fin') } } var it = outer(); await it.next(); return JSON.stringify(await it.return('x')) },
            async () => { async function* inner() { try { yield 1 } catch (e) { yield 'c' + e } } async function* outer() { var r = yield* inner(); return r } var it = outer(); await it.next(); return JSON.stringify(await it.throw('E')) + JSON.stringify(await it.next()) },
            async () => { var inner = { [Symbol.asyncIterator]() { return this }, next() { return { value: 1, done: false } }, return() { log.push('ret called'); return {} } }; async function* outer() { yield* inner } var it = outer(); await it.next(); try { await it.throw('T') } catch (e) { return String(e) } },
            async () => { async function* g() { for await (var x of mk(() => { log.push('loop closed'); return {} })) yield x } var it = g(); await it.next(); return JSON.stringify(await it.return(7)) },
            async () => { return (async function* f(a) { yield a }).toString() + ' ' + ({ async *m() {} }).m.toString() + ' ' + typeof (class { async *m() {} }).prototype.m },
            async () => { var AGF = Object.getPrototypeOf(async function*(){}).constructor; var f = new AGF('a', 'yield a*2'); var r = await f(21).next(); return r.value + ' ' + Object.getPrototypeOf(f.prototype) === Object.getPrototypeOf((async function*(){}).prototype) },
            async () => { var p = Object.getPrototypeOf(Object.getPrototypeOf((async function*(){}).prototype)); return [Object.getOwnPropertySymbols(p).filter(s => s !== Symbol.asyncDispose).map(String), p[Symbol.asyncIterator].call(5)].join() },
            async () => { try { new (async function*(){}) } catch (e) { return e.constructor.name } },
            async () => { var r = await (async function*(){}).prototype.constructor; return String(r === Object) + ' ' + ((async function*(){}).prototype.hasOwnProperty('constructor')) },
            async () => { var p = Object.getPrototypeOf((async function*(){}).prototype); try { await p.next.call({}) } catch (e) { return e.constructor.name } },
        ];
        var out = [];
        tests.reduce((p, t, i) => p.then(() => t()).then(v => out.push(i + ':ok:' + v), e => out.push(i + ':' + (e && e.constructor && e.constructor.name !== 'String' ? e.constructor.name : e))), Promise.resolve())
            .then(() => { __r = out.join('\n') + '\n' + log.join(',') });
        """,
    )

    @Test
    fun arrayFromAsync() = check(
        "0:[1,2,3] 1:[1,2,3] 2:[\"a\",\"b\"] 3:[10,20] 4:[100,201,302] 5:E:TypeError 6:E:TypeError 7:E:r 8:E:m " +
            "9:[true,2,true] 10:[\"a\",\"b\"] | t0,t1,t2,t3,t4,t5,t6,t7,t8,123456fa7," + (9 until 25).joinToString(",") { "t$it" } +
            ",closed | 1fromAsync",
        """
        var log = [], out = [];
        async function* g() { yield 1; yield Promise.resolve(2); yield 3 }
        var tests = [
            () => Array.fromAsync([1, Promise.resolve(2), 3]),
            () => Array.fromAsync(g()),
            () => Array.fromAsync({ length: 2, 0: 'a', 1: Promise.resolve('b') }),
            () => Array.fromAsync([1, 2], async x => x * 10),
            () => Array.fromAsync(g(), function (x, i) { return this.m * x + i }, { m: 100 }),
            () => Array.fromAsync(null),
            () => Array.fromAsync([1], 5),
            () => Array.fromAsync([Promise.reject('r')]),
            () => { var it = { [Symbol.asyncIterator]() { return { next() { return { value: 1, done: false } }, return() { log.push('closed'); return {} } } } }; return Array.fromAsync(it, () => { throw 'm' }) },
            () => { function C() { this.made = true } return Array.fromAsync.call(C, [1, 2]).then(a => [a.made, a.length, a instanceof C]) },
            () => Array.fromAsync('ab'),
        ];
        var t = 0; (function loop() { if (t < 25) { log.push('t' + t++); Promise.resolve().then(loop) } })();
        tests.reduce((p, f, i) => p.then(() => f()).then(v => out.push(i + ':' + JSON.stringify(v)), e => out.push(i + ':E:' + (e instanceof Error ? e.constructor.name : e))), Promise.resolve())
            .then(() => { __r = out.join(' ') + ' | ' + log.join(',') + ' | ' + Array.fromAsync.length + Array.fromAsync.name });
        var order = [];
        Array.fromAsync([1, 2]).then(() => order.push('fa'));
        Promise.resolve().then(() => order.push(1)).then(() => order.push(2)).then(() => order.push(3)).then(() => order.push(4))
            .then(() => order.push(5)).then(() => order.push(6)).then(() => order.push(7)).then(() => { log.push(order.join('')) });
        """,
    )

    @Test
    fun earlyErrors() = check(
        "SyntaxError,SyntaxError,SyntaxError,SyntaxError,SyntaxError,ok,SyntaxError,SyntaxError,SyntaxError,SyntaxError,ok,ok,ok,ok,ok,SyntaxError,ok,SyntaxError,ok",
        """
        __r = ['function f() { for await (x of y); }', 'async function f() { for await (x in y); }', 'async function f() { for await (;;); }',
         'async function* g(a = yield) {}', 'async function* g(a = await 1) {}', 'async function* await() {}', '(async function* yield() {})',
         'async function* g() { var await; }', 'async function* g() { var yield; }', 'async function* g() { yield\n* 1 }',
         'async function f() { for await (var x of []) ; }', 'async function* g() { yield* []; await 1; for await (let [a, b] of []) {} }',
         'class C { async *m() { yield 1 } static async *[Symbol.iterator]() {} }', '({ async *m() {}, async *[1]() {} })',
         'async function f() { for await (const x of []) ; }', 'for await (x of y);', 'async () => { for await (x of y); }',
         'function* g() { for await (x of y); }', 'async function f() { label: for await (x of y) continue label; }']
            .map(function (s) { try { new Function(s); return 'ok' } catch (e) { return e.constructor.name } }).join();
        """,
    )
}
