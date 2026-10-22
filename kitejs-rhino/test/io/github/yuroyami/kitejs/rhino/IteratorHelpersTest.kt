/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

/** The ECMAScript 2025 Iterator constructor and its helpers (#113). The expected string is Node 26.10 output. */
class IteratorHelpersTest {
    @Test
    fun helpersConstructorAndClosing() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val result = cx.evaluateString(cx.initStandardObjects(), SOURCE, "iterator-helpers.js", 1, null)
            assertEquals(EXPECTED, ScriptRuntime.toString(result))
        } finally {
            Context.exit()
        }
    }

    private companion object {
        const val EXPECTED = "function|function|function|function|true|undefined|2,4,6,8|1,3|1,2|4|1,1,2,2,3|10|20|true|true|3|1:0,2:1,3:2,4:3|10,20|b,c|1,1|x,y|true|TypeError|TypeError|true|0|TypeError|RangeError|RangeError|4|5|true|6|[object Iterator Helper]|Iterator|true|next,return|0|1|1|Reduce of a done iterator with no initial value|true|5|TypeError|function|function|false|true|TypeError"

        val SOURCE = """
        var out = [];
        function* g() { yield 1; yield 2; yield 3; yield 4 }
        out.push(typeof Iterator, typeof Iterator.from, typeof [].values().map, typeof g().take, Object.getPrototypeOf(Object.getPrototypeOf([].values())) === Iterator.prototype, typeof StopIteration);
        out.push(g().map(x => x * 2).toArray().join(), g().filter(x => x % 2).toArray().join(), g().take(2).toArray().join(), g().drop(3).toArray().join());
        out.push(g().flatMap(x => [x, x]).take(5).toArray().join(), g().reduce((a, b) => a + b), g().reduce((a, b) => a + b, 10), g().some(x => x > 3), g().every(x => x > 0), g().find(x => x > 2));
        var log = []; g().forEach((x, i) => log.push(x + ':' + i)); out.push(log.join());
        out.push(new Map([[1, 'a'], [2, 'b']]).keys().map(k => k * 10).toArray().join(), 'abc'[Symbol.iterator]().drop(1).toArray().join());
        out.push(Iterator.from({ next() { return { value: 1, done: false } } }).take(2).toArray().join(), Iterator.from('xy').toArray().join());
        var it = g(); out.push(Iterator.from(it) === it);
        try { new Iterator() } catch (e) { out.push(e.constructor.name) }
        try { Iterator() } catch (e) { out.push(e.constructor.name) }
        class MyIt extends Iterator { next() { return { done: true } } }
        out.push(new MyIt() instanceof Iterator, new MyIt().toArray().length);
        var closed = 0; var src = { next() { return { value: 1, done: false } }, return() { closed++; return {} }, __proto__: Iterator.prototype };
        try { src.map(5) } catch (e) { out.push(e.constructor.name) }
        try { src.take(-1) } catch (e) { out.push(e.constructor.name) }
        try { src.take(NaN) } catch (e) { out.push(e.constructor.name) }
        var h = src.map(x => x); h.next(); h.return(); out.push(closed);
        var h2 = src.take(1); h2.next(); h2.next(); out.push(closed);
        out.push(src.some(x => true), closed);
        out.push(Object.prototype.toString.call(g().map(x => x)), Iterator.prototype[Symbol.toStringTag], Iterator.prototype.constructor === Iterator);
        var hp = Object.getPrototypeOf(g().map(x=>x)); out.push(Object.getOwnPropertyNames(hp).join(), Iterator.length, Iterator.prototype.map.length, Iterator.prototype.reduce.length);
        try { [].reduce } catch (e) {}
        try { [].values().reduce((a, b) => a) } catch (e) { out.push(e.message) }
        var o = {}; Iterator.prototype.constructor; var obj = Object.create(Iterator.prototype); obj.constructor = 5; out.push(obj.hasOwnProperty('constructor'), obj.constructor);
        try { Iterator.prototype.constructor = 1 } catch (e) { out.push(e.constructor.name) }
        var d = Object.getOwnPropertyDescriptor(Iterator.prototype, 'constructor'); out.push(typeof d.get, typeof d.set, d.enumerable, d.configurable);
        var running = g().map(function (x) { try { running.next() } catch (e) { return e.constructor.name } }); out.push(running.next().value);
        out.join('|');
        """
    }
}
