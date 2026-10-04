/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * for-in walks an object by EnumerateObjectProperties (ECMAScript 2025, 14.7.5.9): the keys of each
 * object come from [[OwnPropertyKeys]] and their attributes from [[GetOwnProperty]] as the loop
 * reaches them, a key whose property is gone is skipped, and every own key of an object passed
 * hides a prototype's property of the same name, enumerable or not (#90). Every expected value is
 * what V8 answers, but for the order of the traps, which the standard leaves open.
 */
class ForInEnumerationTest {

    private val keys = "function keys(o) { var k = []; for (var x in o) k.push(x); return k.join(','); }"

    private fun eval(source: String, version: LanguageVersion = LanguageVersion.LATEST): String =
        KiteJs { languageVersion = version }.use { js -> js.evaluate("$keys $source").asString() }

    @Test
    fun a_proxy_hands_out_the_keys_its_traps_report_though_its_target_lacks_them() {
        // The third key is made at run time, as a host's own names are.
        assertEquals(
            "a,c:d",
            eval(
                "function join(a, b) { return a + ':' + b; } keys(new Proxy({}, { ownKeys: function () { return ['a', 'b', join('c', 'd')]; }," +
                    " getOwnPropertyDescriptor: function (t, k) { return { value: 1, enumerable: k !== 'b', configurable: true }; } }))",
            ),
        )
        assertEquals("0,1", eval("keys(new Proxy([5, 6], {}))"))
    }

    @Test
    fun a_proxy_is_asked_for_its_keys_its_descriptors_and_its_prototype_and_nothing_else() {
        // V8 asks for the prototype before the descriptors, as it lists the keys of the whole chain first;
        // this is the order of the informative definition in 14.7.5.9.
        assertEquals(
            "x,y / ownKeys,getOwnPropertyDescriptor x,getOwnPropertyDescriptor y,getPrototypeOf",
            eval(
                "var log = [], h = {}; ['ownKeys', 'getOwnPropertyDescriptor', 'has', 'getPrototypeOf', 'get'].forEach(function (n) {" +
                    " h[n] = function (t, k) { log.push(n + (k === undefined || typeof k === 'object' ? '' : ' ' + String(k)));" +
                    " return Reflect[n].apply(Reflect, arguments); }; });" +
                    " keys(new Proxy({ x: 1, y: 2 }, h)) + ' / ' + log.join(',')",
            ),
        )
        assertEquals(
            "0 / string 0,string length",
            eval(
                "var log = []; var p = new Proxy([5], { getOwnPropertyDescriptor: function (t, k) { log.push(typeof k + ' ' + k);" +
                    " return Reflect.getOwnPropertyDescriptor(t, k); } }); keys(p) + ' / ' + log.join(',')",
            ),
        )
    }

    @Test
    fun a_key_whose_property_is_gone_when_the_loop_reaches_it_is_skipped() {
        assertEquals(
            "x",
            eval(
                "var gone = false; var p = new Proxy({ x: 1, y: 2 }, { getOwnPropertyDescriptor: function (t, k) {" +
                    " return gone && k === 'y' ? undefined : Reflect.getOwnPropertyDescriptor(t, k); } });" +
                    " var k = []; for (var x in p) { k.push(x); gone = true; } k.join(',')",
            ),
        )
        assertEquals("a", eval("var o = { a: 1, b: 2 }; var k = []; for (var x in o) { k.push(x); delete o.b; } k.join(',')"))
    }

    @Test
    fun every_own_key_hides_the_inherited_one_of_the_same_name() {
        assertEquals("y", eval("var o = Object.create({ x: 1, y: 2 }); Object.defineProperty(o, 'x', { value: 3, enumerable: false }); keys(o)"))
        // The object with the hiding key has no enumerable key of its own.
        assertEquals(
            "w",
            eval("var p2 = { x: 1, w: 2 }; var p1 = Object.create(p2); Object.defineProperty(p1, 'x', { value: 3, enumerable: false }); keys(Object.create(p1))"),
        )
        assertEquals(
            "z",
            eval(
                "keys(new Proxy(Object.create({ x: 1, z: 3 }), { ownKeys: function () { return ['x']; }," +
                    " getOwnPropertyDescriptor: function (t, k) { return k === 'x' ? { value: 1, enumerable: false, configurable: true } : undefined; } }))",
            ),
        )
        // A key handed out and deleted afterwards still hides; one deleted before the loop reached it does not.
        assertEquals("a", eval("var o = Object.create({ a: 1 }); o.a = 2; var k = []; for (var x in o) { k.push(x); delete o.a; } k.join(',')"))
        assertEquals("a,b", eval("var o = Object.create({ b: 1 }); o.a = 2; o.b = 3; var k = []; for (var x in o) { k.push(x); delete o.b; } k.join(',')"))
        assertEquals("y,x", eval("var o = Object.create(new Proxy({ x: 1 }, {})); o.y = 2; keys(o)"))
    }

    @Test
    fun the_loops_that_worked_still_work() {
        assertEquals("0,1,extra", eval("Array.prototype.extra = 1; keys([5, 6])"))
        assertEquals("a,m", eval("function F() { this.a = 1; } F.prototype.m = function () {}; keys(new F())"))
        assertEquals("0,1|0,1||", eval("keys('ab') + '|' + keys(new Uint8Array(2)) + '|' + keys(null) + '|' + keys(undefined)"))
        assertEquals(
            "RangeError",
            eval("try { keys(Object.create(new Proxy({ a: 1 }, { ownKeys: function () { throw new RangeError('k'); } }))); 'none' } catch (e) { e.name }"),
        )
    }

    @Test
    fun the_iterator_protocol_of_javascript_1_7_belongs_to_its_versions() {
        val source = "keys({ a: 1, __iterator__: function () { return { next: function () { throw StopIteration; } }; } })"
        assertEquals("a,__iterator__", eval(source))
        assertEquals("", eval(source, LanguageVersion.ES5))
    }
}
