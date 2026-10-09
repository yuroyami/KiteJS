/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Array patterns iterate, patterns take rest elements, rest properties and computed keys, and
 * patterns and for-of loops close their iterators (#81, #83, #96). The expected strings are
 * Node 26.10 output.
 */
class DestructuringIteratorTest {
    private fun check(expected: String, source: String) {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            assertEquals(expected, ScriptRuntime.toString(cx.evaluateString(cx.initStandardObjects(), source, "destructuring.js", 1, null)))
        } finally {
            Context.exit()
        }
    }

    private val counting = """
        var log = [];
        function endless(value) {
            return { [Symbol.iterator]() { return {
                next() { return { value: value, done: false } },
                return() { log.push('r'); return {} } } } };
        }
    """

    @Test
    fun restElementsRestPropertiesAndComputedKeys() = check("2|2|2|{\"b\":2}|3|1,2,0,c,4,5", """
        var out = [];
        var [a, ...b] = [1, 2, 3]; out.push(b.length);
        var x, y; [x, ...y] = [1, 2, 3]; out.push(y.length);
        out.push((function ([a, ...b]) { return b.length })([1, 2, 3]));
        var { a, ...r } = { a: 1, b: 2 }; out.push(JSON.stringify(r));
        var k = 'a'; var { [k]: v } = { a: 3 }; out.push(v);
        var s = Symbol('s'), kb = 'b';
        var { a: a2, [kb]: bb, ...r2 } = { a: 1, b: 2, c: 3, [s]: 4, 0: 5 };
        out.push([a2, bb, Object.keys(r2).join(), r2[s], r2[0]].join());
        out.join('|');
    """)

    @Test
    fun everyArrayPatternIterates() = check("s1|s1|s1|s1|s1|s1|k,v|gen|h,l,lo", """
        function set() { return new Set(['s1', 's2']) }
        var out = [];
        var [a] = set(); out.push(a);
        let [b] = set(); out.push(b);
        (function () { const [c] = set(); out.push(c) })();
        var f; [f] = set(); out.push(f);
        for (var [i] of [set()]) out.push(i);
        var { x: [l] } = { x: set() }; out.push(l);
        var [m] = new Map([['k', 'v']]); out.push(m);
        var [g] = (function* () { yield 'gen' })(); out.push(g);
        var [h, , l2, ...rest] = 'hello'; out.push([h, l2, rest.join('')].join());
        out.join('|');
    """)

    @Test
    fun nonIterablesAndNullThrow() = check("TypeError,TypeError,TypeError,TypeError,TypeError,TypeError", """
        [() => { var [z] = { 0: 'zero', length: 1 } }, () => { var [] = 1 }, () => { var {} = null },
         () => { ({} = undefined) }, () => { (function ([]) {})({}) }, () => { for (var {} of [null]); }]
            .map(function (t) { try { t(); return 'none' } catch (e) { return e.constructor.name } }).join();
    """)

    @Test
    fun aGetterWithADefaultRunsOnceAndAVarKeepsTheValue() = check("1,1|2,3", """
        var n = 0; var { a = 1 } = { get a() { n++; return undefined } };
        var x = 5; var [x = 1] = [2]; var y = 5; var { y = 1 } = { y: 3 };
        [a, n].join() + '|' + [x, y].join();
    """)

    @Test
    fun computedKeysRunInOrder() = check("k1,get a,k2,get b", """
        var order = [];
        var src = { get a() { order.push('get a'); return 1 }, get b() { order.push('get b'); return 2 } };
        var { [(order.push('k1'), 'a')]: x, [(order.push('k2'), 'b')]: y } = src;
        order.join();
    """)

    @Test
    fun misplacedRestIsASyntaxError() = check("SyntaxError,SyntaxError,SyntaxError,SyntaxError", """
        ['var [...a,] = []', 'var [...a, b] = []', 'var {...a, b} = {}', 'var [...a = 1] = []']
            .map(function (s) { try { eval(s); return 'none' } catch (e) { return e.constructor.name } }).join();
    """)

    @Test
    fun assignmentPatternsTakePropertyTargets() = check("{\"a\":1,\"b\":[2,3],\"c\":1,\"d\":{\"y\":2}}", """
        var o = {}; [o.a, ...o.b] = [1, 2, 3]; ({ x: o.c, ...o.d } = { x: 1, y: 2 }); JSON.stringify(o);
    """)

    @Test
    fun forOfAndPatternsCloseTheirIterator() = check("4", counting + """
        var it = endless(1);
        for (var x of it) break;
        try { for (var x of it) throw 1 } catch (e) {}
        var [a] = it;
        (function () { for (var x of it) return 1 })();
        log.length;
    """)

    @Test
    fun aCatchInsideTheLoopBodyLeavesTheIteratorOpen() = check("0,1,2", """
        var log = [];
        var it = { [Symbol.iterator]() { var i = 0; return {
            next() { return { value: i++, done: i > 3 } }, return() { log.push('r'); return {} } } } };
        for (var x of it) { try { throw x } catch (e) { log.push(e) } }
        log.join();
    """)

    @Test
    fun aThrowInAPatternClosesItsIterator() = check("r|c2", counting + """
        try { var [b = (() => { throw 2 })()] = endless(undefined) } catch (e) { log.push('c' + e) }
        log.join('|');
    """)

    @Test
    fun continueToAnOuterLoopClosesTheInnerOne() = check("r,r|4", counting + """
        outer: for (var i = 0; i < 2; i++) { for (var x of endless(1)) { continue outer } }
        var first = log.join(); log = []; var n = 0;
        outer2: for (var x of endless(1)) { if (n++ > 2) break; for (var y of endless(1)) { continue outer2 } }
        first + '|' + log.length;
    """)

    @Test
    fun returnRunsFinallyThenCloses() = check("f,r,v", counting + """
        function f() { for (var x of endless(1)) { try { return 'v' } finally { log.push('f') } } }
        log.push(f()); log.join();
    """)

    @Test
    fun closingAGeneratorClosesItsLoopAndPattern() = check("1,fin|r|r|f,r,x", counting + """
        var out = [];
        function* g() { try { yield 1; yield 2 } finally { log.push('fin') } }
        for (const x of g()) { log.push(x); break }
        out.push(log.join()); log = [];
        function* pat() { var [a = yield 1] = endless(undefined) }
        var gen = pat(); gen.next(); gen.return(5); out.push(log.join()); log = [];
        function* loop() { for (var x of endless(1)) yield x }
        gen = loop(); gen.next(); gen.return(5); out.push(log.join()); log = [];
        function* fin() { for (var x of endless(1)) { try { yield x } finally { log.push('f') } } }
        gen = fin(); gen.next();
        try { gen.throw(new Error('x')) } catch (e) { log.push(e.message) }
        out.push(log.join());
        out.join('|');
    """)

    @Test
    fun aBadReturnResultThrowsOnlyOnANormalExit() = check("TypeError|1", """
        var bad = { [Symbol.iterator]() { return { next() { return { value: 1, done: false } }, return() { return 1 } } } };
        var throwing = { [Symbol.iterator]() { return { next() { return { value: 1, done: false } }, return() { throw 9 } } } };
        var a; try { for (var x of bad) break; a = 'none' } catch (e) { a = e.constructor.name }
        var b; try { for (var x of throwing) throw 1 } catch (e) { b = e }
        a + '|' + b;
    """)

    @Test
    fun parameterDefaultsAndNestedPatterns() = check("0|9|8,7|1|{\"f\":2}|1,2,3,5", """
        function f(a, [b, ...c] = [9, 8, 7], { d, ...e } = { d: 1, f: 2 }) { return [a, b, c.join(), d, JSON.stringify(e)].join('|') }
        var [[a, ...b], { c: [d] = [5] }] = [[1, 2, 3], {}];
        f(0) + '|' + [a, b.join(), d].join();
    """)

    /**
     * A for-let head keeps the iterator of its pattern in a scope of its own, which strict code
     * needs declared. A property target is evaluated before the iterator steps.
     */
    @Test
    fun forLetHeadsAndPropertyTargets() = check("3,30,open next key a step key rest,undefined,0", """
        'use strict';
        var out = [];
        for (let [a, b = 2] = [1]; ;) { out.push(a + b); break; }
        for (let [x] = [5], [y] = [6]; ;) { out.push(x * y); break; }
        var log = [];
        var iterator = { get next() { log.push('next'); return function () { log.push('step'); return { done: true }; }; } };
        var iterable = { [Symbol.iterator]() { log.push('open'); return iterator; } };
        var target = {};
        function key(k) { log.push('key ' + k); return k; }
        [target[key('a')], ...target[key('rest')]] = iterable;
        out.push(log.join(' '), String(target.a), target.rest.length);
        out.join();
    """)
}
