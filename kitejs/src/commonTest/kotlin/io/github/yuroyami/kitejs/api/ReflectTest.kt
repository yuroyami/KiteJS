/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `Reflect` and the proxy traps it reaches, as D-89 left them. `Reflect.get`, `Reflect.set` and
 * `Reflect.deleteProperty` run the target's own [[Get]], [[Set]] and [[Delete]] with the receiver
 * the caller passed, a proxy's traps receive that receiver, and a write or delete that a trap or a
 * read-only property refuses answers false (a TypeError in strict code). Every expected value here
 * is what V8 answers.
 */
class ReflectTest {

    private fun eval(source: String): String = KiteJs().use { js -> js.evaluate(source).asString() }

    @Test
    fun set_answers_whether_the_write_was_made() {
        assertEquals("false:1", eval("var o = Object.freeze({ x: 1 }); Reflect.set(o, 'x', 2) + ':' + o.x"))
        assertEquals("false:false", eval("var o = Object.preventExtensions({}); Reflect.set(o, 'y', 2) + ':' + ('y' in o)"))
        assertEquals("false:1", eval("var o = { get x() { return 1 } }; Reflect.set(o, 'x', 3) + ':' + o.x"))
        assertEquals("false", eval("var o = {}; Object.defineProperty(o, 'x', { set: undefined, get: function () { return 1 } }); String(Reflect.set(o, 'x', 2))"))
        assertEquals("true:2:1:true", eval("var p = Object.create({ x: 1 }); Reflect.set(p, 'x', 2) + ':' + p.x + ':' + Object.getPrototypeOf(p).x + ':' + p.hasOwnProperty('x')"))
        assertEquals("false:1:false", eval("var p = Object.create(Object.freeze({ x: 1 })); Reflect.set(p, 'x', 2) + ':' + p.x + ':' + p.hasOwnProperty('x')"))
        assertEquals("true:true:undefined", eval("var o = {}; Reflect.set(o, 'x') + ':' + ('x' in o) + ':' + o.x"))
        assertEquals("false:false", eval("'use strict'; var o = Object.freeze({ x: 1 }); Reflect.set(o, 'x', 2) + ':' + Reflect.set(Object.preventExtensions({}), 'y', 1)"))
        assertEquals("true,5,true", eval("var proto = { set x(v) { this._x = v } }; var o = Object.create(proto); [Reflect.set(o, 'x', 5), o._x, o.hasOwnProperty('_x')].join()"))
    }

    @Test
    fun set_with_a_receiver_checks_writability_and_writes_to_the_receiver() {
        assertEquals("false:undefined", eval("var o = {}; Object.defineProperty(o, 'x', { value: 1, writable: false, configurable: true }); var r = {}; Reflect.set(o, 'x', 2, r) + ':' + r.x"))
        assertEquals("true:2:1", eval("var o = {}; Object.defineProperty(o, 'x', { value: 1, writable: true, configurable: false }); var r = {}; Reflect.set(o, 'x', 2, r) + ':' + r.x + ':' + o.x"))
        assertEquals("true:true,3", eval("var log = []; var o = { set x(v) { log.push(this === r, v) } }; var r = {}; Reflect.set(o, 'x', 3, r) + ':' + log"))
        assertEquals("false:9", eval("var o = { x: 1 }; var r = {}; Object.defineProperty(r, 'x', { get: function () { return 9 }, configurable: true }); Reflect.set(o, 'x', 2, r) + ':' + r.x"))
        assertEquals("false:5", eval("var o = { x: 1 }; var r = {}; Object.defineProperty(r, 'x', { value: 5, writable: false, configurable: true }); Reflect.set(o, 'x', 2, r) + ':' + r.x"))
        assertEquals("true:2:1", eval("var o = { x: 1 }; var r = { x: 5 }; Reflect.set(o, 'x', 2, r) + ':' + r.x + ':' + o.x"))
        assertEquals("false:1", eval("var o = { x: 1 }; Reflect.set(o, 'x', 2, 5) + ':' + o.x"))
        assertEquals("false", eval("String(Reflect.set({}, 'x', 1, undefined))"))
        assertEquals("false:false", eval("var o = { x: 1 }; var r = Object.preventExtensions({}); Reflect.set(o, 'y', 2, r) + ':' + ('y' in r)"))
        assertEquals("true:object", eval("var o = { set x(v) { 'use strict'; this.r = typeof this } }; var t = {}; Reflect.set(o, 'x', 1, t) + ':' + t.r"))
        assertEquals(
            "true:gopd,def",
            eval("var log = []; var p = new Proxy({}, { getOwnPropertyDescriptor: function (t, k) { log.push('gopd'); return undefined }, defineProperty: function (t, k, d) { log.push('def'); return Reflect.defineProperty(t, k, d) } }); var r = Reflect.set({}, 'q', 1, p); r + ':' + log.join()"),
        )
    }

    @Test
    fun set_on_exotic_objects() {
        assertEquals("true:1:1", eval("var a = [1, 2]; Reflect.set(a, 'length', 1) + ':' + a.length + ':' + a"))
        assertEquals("true:6", eval("var a = [1, 2]; Reflect.set(a, 5, 9) + ':' + a.length"))
        assertEquals("false:1", eval("var a = Object.freeze([1, 2]); Reflect.set(a, 0, 9) + ':' + a[0]"))
        assertEquals("true|5|true|1,2,x,,", eval("var a = [1, 2]; [Reflect.set(a, 'length', 5), a.length, Reflect.set(a, 2, 'x'), a.join()].join('|')"))
        assertEquals("true:44:true", eval("var t = new Uint8Array(2); Reflect.set(t, 0, 300) + ':' + t[0] + ':' + Reflect.set(t, 5, 1)"))
        assertEquals("false,a,true,q,false,2", eval("var s = new String('ab'); [Reflect.set(s, 0, 'z'), s[0], Reflect.set(s, 5, 'q'), s[5], Reflect.set(s, 'length', 9), s.length].join()"))
        assertEquals("true:9:9", eval("(function (a) { var r = Reflect.set(arguments, 0, 9); return r + ':' + a + ':' + arguments[0] })(1)"))
        assertEquals("true:1:9", eval("(function (a) { 'use strict'; var r = Reflect.set(arguments, 0, 9); return r + ':' + a + ':' + arguments[0] })(1)"))
        assertEquals("true,true,true,5", eval("var f = function () {}; [Reflect.get(f, 'prototype') === f.prototype, Reflect.has(f, 'prototype'), Reflect.set(f, 'prototype', 5), f.prototype].join()"))
        assertEquals("m,true,n", eval("var e = new Error('m'); [Reflect.get(e, 'message'), Reflect.set(e, 'message', 'n'), e.message].join()"))
        assertEquals("true,3,3,true,true,undefined", eval("var g = this; [Reflect.set(g, 'zz9', 3), zz9, Reflect.get(g, 'zz9'), Reflect.has(g, 'zz9'), Reflect.deleteProperty(g, 'zz9'), typeof zz9].join()"))
        assertEquals("true,true,false,true", eval("var o = {}; [Reflect.get(o, '__proto__') === Object.prototype, Reflect.set(o, '__proto__', Array.prototype), Array.isArray(o), o instanceof Array].join()"))
    }

    @Test
    fun get_reads_with_the_receiver_and_converts_the_key() {
        assertEquals("2", eval("var o = { y: 1, get x() { return this.y } }; String(Reflect.get(o, 'x', { y: 2 }))"))
        assertEquals("3:4", eval("var o = Object.create({ get x() { return this.y } }); o.y = 3; Reflect.get(o, 'x') + ':' + Reflect.get(o, 'x', { y: 4 })"))
        assertEquals("true", eval("var o = {}; Object.defineProperty(o, 'x', { get: function () { return this }, configurable: true }); var r = {}; String(Reflect.get(o, 'x', r) === r)"))
        assertEquals("object", eval("var o = { get x() { return typeof this } }; Reflect.get(o, 'x', 5)"))
        assertEquals("true", eval("var o = { get x() { 'use strict'; return this === undefined } }; String(Reflect.get(o, 'x', undefined))"))
        assertEquals("7,8,9", eval("var k = { '-1': 7, '0.5': 8, 'NaN': 9 }; [Reflect.get(k, -1), Reflect.get(k, 0.5), Reflect.get(k, NaN)].join()"))
        assertEquals("1", eval("var s = Symbol(); var o = {}; o[s] = 1; String(Reflect.get(o, { [Symbol.toPrimitive]: function () { return s } }))"))
        assertEquals("1", eval("var m = new Map([[1, 2]]); String(Reflect.get(Map.prototype, 'size', m))"))
        assertEquals("3,3", eval("var t = new Uint8Array(3); [Reflect.get(Uint8Array.prototype.__proto__, 'length', t), Reflect.get(t, 'length')].join()"))
        assertEquals("true", eval("var d = new Date(0); String(Reflect.get(Date.prototype, 'getTime', d) === Date.prototype.getTime)"))
    }

    @Test
    fun an_omitted_key_is_the_string_undefined() {
        assertEquals("7:true:true:undefined", eval("var o = { undefined: 7 }; Reflect.get(o) + ':' + Reflect.has(o) + ':' + Reflect.deleteProperty(o) + ':' + o.undefined"))
        assertEquals("{\"value\":7,\"writable\":true,\"enumerable\":true,\"configurable\":true}", eval("var o = { undefined: 7 }; JSON.stringify(Reflect.getOwnPropertyDescriptor(o))"))
        assertEquals("true:true:false", eval("var o = { 1: 'a' }; Reflect.has(o, 1) + ':' + Reflect.deleteProperty(o, 1) + ':' + Reflect.has(o, '1')"))
        assertEquals("returned", eval("try { Reflect.get({}); 'returned' } catch (e) { e.name }"))
        assertEquals("true:1", eval("var s = Symbol('s'); var o = {}; Reflect.set(o, s, 1) + ':' + o[s]"))
        assertEquals("true:1", eval("var o = {}; Reflect.set(o, { toString: function () { return 'k' } }, 1) + ':' + o.k"))
    }

    @Test
    fun define_property_answers_false_where_a_definition_is_refused() {
        assertEquals("false", eval("var t = Object.preventExtensions({}); var p = new Proxy(t, {}); String(Reflect.defineProperty(p, 'x', { value: 1 }))"))
        assertEquals("TypeError", eval("var t = Object.preventExtensions({}); var p = new Proxy(t, {}); try { Object.defineProperty(p, 'x', { value: 1 }); 'returned' } catch (e) { e.name }"))
        assertEquals("false", eval("var t = Object.preventExtensions([1, 2, 3]); var p = new Proxy(new Proxy(t, {}), { set: null }); String(Reflect.set(p, 'foo', 2))"))
        val shrinking = "var a = [1, 2]; var n = 0; var len = { valueOf: function () { if (++n === 2) Object.defineProperty(a, 'length', { writable: false }); return a.length } };"
        assertEquals("false:2", eval("$shrinking String(Reflect.defineProperty(a, 'length', { value: len, writable: true })) + ':' + n"))
        assertEquals("TypeError:2", eval("$shrinking try { Object.defineProperty(a, 'length', { value: len, writable: true }); 'returned' } catch (e) { e.name + ':' + n }"))
        assertEquals("TypeError", eval("var p = new Proxy({}, { defineProperty: function () { return true } }); try { String(Reflect.defineProperty(p, 'x', { value: 1, configurable: false })) } catch (e) { e.name }"))
        assertEquals("TypeError", eval("try { String(Reflect.defineProperty({}, 'x', { get: 1 })) } catch (e) { e.name }"))
    }

    @Test
    fun copy_within_asks_has_property() {
        assertEquals("true:undefined", eval("var o = { 0: undefined, 1: 1, length: 2 }; Array.prototype.copyWithin.call(o, 1, 0); (1 in o) + ':' + o[1]"))
        assertEquals("Error", eval("var p = new Proxy({ 0: 42, length: 1 }, { has: function () { throw new Error() } }); try { Array.prototype.copyWithin.call(p, 0, 0); 'returned' } catch (e) { e.name }"))
    }

    @Test
    fun delete_property_touches_the_target_alone() {
        assertEquals("true:function", eval("var r = Reflect.deleteProperty({}, 'toString'); r + ':' + typeof Object.prototype.toString"))
        assertEquals("true:1", eval("var o = Object.create({ x: 1 }); Reflect.deleteProperty(o, 'x') + ':' + o.x"))
        assertEquals("false", eval("var p = new Proxy({}, { deleteProperty: function () { return false } }); String(Reflect.deleteProperty(p, 'x'))"))
        assertEquals("TypeError", eval("'use strict'; var p = new Proxy({ x: 1 }, { deleteProperty: function () { return false } }); try { delete p.x; 'returned' } catch (e) { e.name }"))
    }

    @Test
    fun construct_demands_an_argument_list() {
        for (call in listOf("Reflect.construct(F)", "Reflect.construct(F, undefined)", "Reflect.construct(F, null)")) {
            assertEquals("TypeError", eval("function F() { this.x = 1 } try { $call; 'returned' } catch (e) { e.name }"), call)
        }
        assertEquals("1", eval("String(Reflect.construct(function () { this.x = 1 }, []).x)"))
        assertEquals("4", eval("String(Reflect.construct(function (a) { this.x = a }, { length: 1, 0: 4 }).x)"))
    }

    @Test
    fun proxy_traps_receive_the_receiver_and_their_answer_counts() {
        assertEquals("false:a,1,true", eval("var log = []; var p = new Proxy({}, { set: function (t, k, v, r) { log.push(k, v, r === p); return false } }); Reflect.set(p, 'a', 1) + ':' + log"))
        assertEquals("true:true", eval("var log = []; var r = {}; var p = new Proxy({}, { set: function (t, k, v, rr) { log.push(rr === r); return true } }); Reflect.set(p, 'a', 1, r) + ':' + log"))
        assertEquals("true:false", eval("var log = []; var p = new Proxy({}, { set: function (t, k, v, r) { log.push(r === o); t[k] = v; return true } }); var o = Object.create(p); o.a = 1; log + ':' + o.hasOwnProperty('a')"))
        assertEquals("TypeError", eval("'use strict'; var p = new Proxy({}, { set: function () { return false } }); try { p.a = 1; 'returned' } catch (e) { e.name }"))
        assertEquals("sloppy ok", eval("var p = new Proxy({}, { set: function () { return false } }); p.a = 1; 'sloppy ok'"))
        assertEquals("true,false", eval("var p = new Proxy({}, { set: function (t, k, v, r) { return 1 } }); [Reflect.set(p, 'a', 1), 'a' in p].join()"))
        assertEquals("true,true", eval("var log = []; var p = new Proxy({}, { get: function (t, k, r) { log.push(r === o); return 1 } }); var o = Object.create(p); o.z; Reflect.get(p, 'z', o); log.join()"))
        assertEquals("true:true", eval("var p = new Proxy({}, { get: function (t, k, r) { return r } }); var o = Object.create(p); (o.q === o) + ':' + (p.q === p)"))
        assertEquals("symbol", eval("var s = Symbol(); var p = new Proxy({}, { get: function (t, k, r) { return typeof k } }); p[s]"))
        assertEquals("true,", eval("var p = new Proxy({}, { has: function () { return true } }); var c = Object.create(p); ['z' in c, c.z].join()"))
        assertEquals("|true", eval("var log = []; var p = new Proxy({}, { has: function (t, k) { log.push('has ' + k); return false } }); var o = Object.create(p); o.a = 1; log.join() + '|' + o.hasOwnProperty('a')"))
        assertEquals("undefined", eval("var p = new Proxy({}, { getPrototypeOf: function () { throw new Error('gpo') } }); try { String(p.nope) } catch (e) { e.message }"))
    }

    @Test
    fun a_trapless_proxy_forwards_with_itself_as_the_receiver() {
        assertEquals("true:1", eval("var t = {}; var p = new Proxy(t, {}); Reflect.set(p, 'a', 1) + ':' + t.a"))
        assertEquals("true:true:true", eval("var t = { get x() { return this } }; var p = new Proxy(t, {}); (p.x === p) + ':' + (Reflect.get(p, 'x') === p) + ':' + (Reflect.get(p, 'x', t) === t)"))
        assertEquals("4,true,4", eval("var t = Object.create({ inherited: 4 }); var p = new Proxy(t, {}); [p.inherited, 'inherited' in p, Reflect.get(p, 'inherited')].join()"))
        assertEquals("2,1,2,true", eval("var p = new Proxy([], {}); p.push(1, 2); [p.length, p.join(), Array.isArray(p)].join()"))
        assertEquals("4|4|3", eval("var t = []; var p = new Proxy(t, {}); p[3] = 1; [t.length, p.length, Object.keys(t).join()].join('|')"))
        assertEquals("1,true,true,false,0", eval("var p = new Proxy({ a: 1 }, {}); [p.a, 'a' in p, delete p.a, 'a' in p, Object.keys(p).length].join()"))
        assertEquals("1,2,true", eval("var o = { x: 1 }; var p = new Proxy(o, {}); var c = Object.create(p); c.x = 2; [o.x, c.x, c.hasOwnProperty('x')].join()"))
        assertEquals("1,1,false", eval("var o = Object.freeze({ x: 1 }); var p = new Proxy(o, {}); var c = Object.create(p); c.x = 2; [o.x, c.x, c.hasOwnProperty('x')].join()"))
        assertEquals("TypeError", eval("'use strict'; var p = new Proxy(Object.freeze({ x: 1 }), {}); try { p.x = 2; 'returned' } catch (e) { e.name }"))
        assertEquals("7,function,0", eval("var p = new Proxy(function () { return 7 }, {}); [p(), typeof p, Reflect.get(p, 'length')].join()"))
        assertEquals(
            "gopd x ; def x {\"value\":2} ; gopd y ; def y {\"value\":3,\"writable\":true,\"enumerable\":true,\"configurable\":true} -> 2,3",
            eval("var log = []; var t = { x: 1 }; var p = new Proxy(t, { defineProperty: function (tt, k, d) { log.push('def ' + k + ' ' + JSON.stringify(d)); return Reflect.defineProperty(tt, k, d) }, getOwnPropertyDescriptor: function (tt, k) { log.push('gopd ' + k); return Reflect.getOwnPropertyDescriptor(tt, k) } }); p.x = 2; p.y = 3; log.join(' ; ') + ' -> ' + t.x + ',' + t.y"),
        )
    }

    @Test
    fun invariants_compare_with_same_value() {
        assertEquals("TypeError", eval("var t = {}; Object.defineProperty(t, 'x', { value: 1, writable: false, configurable: false }); var p = new Proxy(t, { set: function () { return true } }); try { p.x = 2; 'returned' } catch (e) { e.name }"))
        assertEquals("1", eval("var t = {}; Object.defineProperty(t, 'x', { value: 1, writable: false, configurable: false }); var p = new Proxy(t, { get: function () { return 1.0 } }); String(p.x)"))
        assertEquals("TypeError", eval("var t = {}; Object.defineProperty(t, 'x', { value: 0, writable: false, configurable: false }); var p = new Proxy(t, { get: function () { return -0 } }); try { p.x; 'returned' } catch (e) { e.name }"))
        assertEquals("false", eval("var o = {}; Object.defineProperty(o, 'x', { value: NaN, writable: false, configurable: false }); String(!Reflect.defineProperty(o, 'x', { value: NaN }))"))
    }
}
