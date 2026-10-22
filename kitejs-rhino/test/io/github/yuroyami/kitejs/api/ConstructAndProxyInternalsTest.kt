/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [[Construct]] with a newTarget, IsConstructor, a proxy's own internal methods, the enumeration
 * that asks [[GetOwnProperty]] per key, [[SetPrototypeOf]] and JSON, as D-91 left them. Every
 * expected value here is what V8 answers.
 */
class ConstructAndProxyInternalsTest {

    private fun eval(source: String): String = KiteJs(Rhino).use { js -> js.evaluate(source).asString() }

    @Test
    fun a_constructor_takes_its_prototype_from_new_target() {
        assertEquals(
            List(14) { "true" }.joinToString(","),
            eval(
                """function N() {} var ctors = [[Map, []], [Set, []], [WeakMap, []], [Array, []], [Error, []], [TypeError, []], [RegExp, ['a']], [Object, []], [Uint8Array, [1]], [ArrayBuffer, [1]], [Date, [0]], [Promise, [function () {}]], [Boolean, [true]], [Function, []]];
                ctors.map(function (c) { return Object.getPrototypeOf(Reflect.construct(c[0], c[1], N)) === N.prototype }).join()""",
            ),
        )
        assertEquals(
            "true,true,true",
            eval(
                """function N() {} N.prototype = 1;
                [Object.getPrototypeOf(Reflect.construct(Map, [], N)) === Map.prototype, Object.getPrototypeOf(Reflect.construct(Array, [], N)) === Array.prototype,
                 Object.getPrototypeOf(Reflect.construct(function () {}, [], N)) === Object.prototype].join()""",
            ),
        )
        assertEquals(
            "1,true,false",
            eval("function F() { this.x = 1 } function N() {} var o = Reflect.construct(F, [], N); [o.x, Object.getPrototypeOf(o) === N.prototype, F.prototype.isPrototypeOf(o)].join()"),
        )
        assertEquals(
            "1,true,1",
            eval("var B = function () { this.b = 1 }.bind(null); function N() {} var o = Reflect.construct(B, [], N); [o.b, Object.getPrototypeOf(o) === N.prototype, new B().b].join()"),
        )
    }

    @Test
    fun only_a_function_with_construct_is_a_constructor() {
        assertEquals(
            "ok,ok,ok,TypeError,TypeError,TypeError,TypeError,TypeError,TypeError,TypeError,ok,TypeError",
            eval(
                """var o = { m() {}, get g() { return 1 } };
                var nts = [function () {}.bind(null), Symbol, BigInt, () => {}, o.m, Object.getOwnPropertyDescriptor(o, 'g').get, function* () {}, parseInt, Math.max,
                    Proxy.revocable({}, {}).revoke, new Proxy(function () {}, {}), new Proxy(() => {}, {})];
                nts.map(function (nt) { try { Reflect.construct(function () {}, [], nt); return 'ok' } catch (e) { return e.name } }).join()""",
            ),
        )
        assertEquals(
            "P,Array",
            eval(
                """var log = []; var P = new Proxy(function () {}, { construct: function (t, a, nt) { log.push(nt === P ? 'P' : nt === Array ? 'Array' : '?'); return {} } });
                new P(); Reflect.construct(P, [], Array); log.join()""",
            ),
        )
    }

    @Test
    fun a_proxy_validates_what_its_traps_answer() {
        assertEquals(
            "TypeError TypeError TypeError {\"value\":2,\"writable\":false,\"enumerable\":false,\"configurable\":true}",
            eval(
                """var p = new Proxy({}, { getOwnPropertyDescriptor: function (t, k) {
                    return k === 'a' ? { value: 1, configurable: false } : k === 'b' ? { get: 1 } : k === 'c' ? 1 : { value: 2, configurable: true } } });
                ['a', 'b', 'c', 'd'].map(function (k) { try { return JSON.stringify(Object.getOwnPropertyDescriptor(p, k)) } catch (e) { return e.name } }).join(' ')""",
            ),
        )
        assertEquals(
            "get,set,enumerable,configurable:function",
            eval(
                """var d = Object.getOwnPropertyDescriptor(new Proxy({}, { getOwnPropertyDescriptor: function () { return { get: function () { return 1 }, configurable: true } } }), 'a');
                Object.keys(d).join() + ':' + typeof d.get""",
            ),
        )
        assertEquals(
            "length,0 b,a TypeError",
            eval(
                """[Reflect.ownKeys(new Proxy(Object.freeze([1]), { ownKeys: function () { return ['length', '0'] } })).join(),
                 Reflect.ownKeys(new Proxy(Object.preventExtensions({ a: 1, b: 2 }), { ownKeys: function () { return ['b', 'a'] } })).join(),
                 (function () { try { Reflect.ownKeys(new Proxy({}, { ownKeys: function () { return ['a', 'a'] } })); return 'ok' } catch (e) { return e.name } })()].join(' ')""",
            ),
        )
        assertEquals(
            "false,TypeError,TypeError",
            eval(
                """var refuse = new Proxy({}, { setPrototypeOf: function () { return false } }); var r = []; r.push(Reflect.setPrototypeOf(refuse, {}));
                try { Object.setPrototypeOf(refuse, {}); r.push('ok') } catch (e) { r.push(e.name) }
                try { refuse.__proto__ = {}; r.push('ok') } catch (e) { r.push(e.name) } r.join()""",
            ),
        )
        assertEquals(
            "TypeError",
            eval("try { Object.getPrototypeOf(new Proxy({}, { getPrototypeOf: function () { return 1 } })); 'ok' } catch (e) { e.name }"),
        )
    }

    @Test
    fun enumeration_asks_each_key_for_its_descriptor() {
        assertEquals(
            "ownKeys,gopd:a,gopd:b,|,ownKeys,gopd:a,get:a,gopd:b,get:b,|,ownKeys,gopd:a,get:a,gopd:b,get:b,|," +
                "ownKeys,gopd:a,get:a,gopd:b,get:b,|,get:toJSON,ownKeys,gopd:a,gopd:b,get:a,get:b",
            eval(
                """var log = [];
                var h = { ownKeys: function (t) { log.push('ownKeys'); return Reflect.ownKeys(t) },
                    getOwnPropertyDescriptor: function (t, k) { log.push('gopd:' + String(k)); return Reflect.getOwnPropertyDescriptor(t, k) },
                    get: function (t, k) { log.push('get:' + String(k)); return t[k] }, has: function (t, k) { log.push('has:' + String(k)); return k in t } };
                var p = new Proxy({ a: 1, b: 2 }, h);
                Object.keys(p); log.push('|'); Object.assign({}, p); log.push('|'); Object.entries(p); log.push('|'); var c = { ...p }; log.push('|'); JSON.stringify(p); log.join()""",
            ),
        )
        assertEquals("[4,2,3]", eval("JSON.stringify(Object.assign([1, 2, 3], [4]))"))
        assertEquals("0,1,2|0,1", eval("var r = []; for (var k in new Proxy([1, 2, 3], {})) r.push(k); r.join() + '|' + Object.keys(new Proxy([1, 2], {})).join()"))
        assertEquals(
            "b|{\"b\":\"vb\"}|vb",
            eval(
                """var p = new Proxy({}, { ownKeys: function () { return ['a', 'b', Symbol('s')] },
                    getOwnPropertyDescriptor: function (t, k) { return k === 'a' ? { value: 1, enumerable: false, configurable: true } : { value: 2, enumerable: true, configurable: true } },
                    get: function (t, k) { return 'v' + String(k) } });
                Object.keys(p).join() + '|' + JSON.stringify(p) + '|' + Object.values(p).join()""",
            ),
        )
        assertEquals(
            "1,2147483648,4294967294,b,4294967295,a|9,10,z",
            eval("[Object.keys({ b: 1, 4294967295: 1, 4294967294: 1, a: 1, 2147483648: 1, 1: 1 }).join(), Object.getOwnPropertyNames({ z: 1, 10: 1, 9: 1 }).join()].join('|')"),
        )
    }

    @Test
    fun own_property_checks_convert_the_key_first_and_ask_a_proxy_for_a_descriptor() {
        assertEquals(
            "false,false,false,true,0",
            eval(
                """var log = []; var p = new Proxy(Object.create({ x: 1 }, { y: { value: 2, enumerable: false } }), { has: function (t, k) { log.push('has'); return k in t } });
                [Object.prototype.propertyIsEnumerable.call(p, 'x'), Object.prototype.propertyIsEnumerable.call(p, 'y'), Object.prototype.hasOwnProperty.call(p, 'x'),
                 Object.hasOwn(p, 'y'), log.length].join()""",
            ),
        )
        assertEquals(
            "true,true,true,2,RangeError",
            eval(
                """var s = Symbol(); var w = {}; w[Symbol.toPrimitive] = function () { return s }; var o = {}; o[s] = 1;
                var r = [o.hasOwnProperty(w), Object.hasOwn(o, w), o.propertyIsEnumerable(w), Object.hasOwn.length];
                try { Object.prototype.hasOwnProperty.call(undefined, { toString: function () { throw new RangeError() } }) } catch (e) { r.push(e.name) } r.join()""",
            ),
        )
    }

    @Test
    fun object_prototype_keeps_its_null_prototype_and_proto_follows_set_prototype_of() {
        assertEquals(
            "TypeError,false,true,TypeError,",
            eval(
                """var r = []; try { Object.setPrototypeOf(Object.prototype, Object.create(null)); r.push('ok') } catch (e) { r.push(e.name) }
                r.push(Reflect.setPrototypeOf(Object.prototype, Object.create(null)), Reflect.setPrototypeOf(Object.prototype, null));
                try { Object.prototype.__proto__ = Object.create(null); r.push('ok') } catch (e) { r.push(e.name) }
                r.push(Object.getPrototypeOf(Object.prototype)); r.join()""",
            ),
        )
        assertEquals(
            "true,true,,,TypeError,5,true",
            eval(
                """var get = Object.getOwnPropertyDescriptor(Object.prototype, '__proto__').get; var set = Object.getOwnPropertyDescriptor(Object.prototype, '__proto__').set;
                var r = [get.call(1) === Number.prototype, get.call('s') === String.prototype, set.call(1, {}), set.call({}, 1)];
                try { get.call(undefined) } catch (e) { r.push(e.name) }
                var o = {}; r.push((o.__proto__ = 5), Object.getPrototypeOf(o) === Object.prototype); r.join()""",
            ),
        )
        assertEquals(
            ",",
            eval("Object.setPrototypeOf(Date.now, null); var f = function () {}; Object.setPrototypeOf(f, null); [Object.getPrototypeOf(Date.now), Object.getPrototypeOf(f)].join()"),
        )
    }

    @Test
    fun json_keeps_negative_zero_reads_to_json_once_and_internalizes_with_the_spec_operations() {
        assertEquals("-Infinity,-Infinity,0,Infinity", eval("[1 / JSON.parse('-0'), 1 / JSON.parse('[-0]')[0], JSON.stringify(-0), 1 / JSON.parse('0')].join()"))
        assertEquals(
            "\"x\"1|string,string,string,string|[1,{\"a\":2}]|{\"b\":2}",
            eval(
                """var calls = 0; var o = { get toJSON() { calls++; return function () { return 'x' } } }; var r = JSON.stringify(o); var t = [];
                JSON.stringify([1], function (k, v) { t.push(typeof k); return v }); JSON.parse('[1]', function (k, v) { t.push(typeof k); return v });
                r + calls + '|' + t.join() + '|' + JSON.stringify(new Proxy([1, { a: 2 }], {})) + '|' + JSON.stringify({ a: 1, b: 2 }, new Proxy(['b'], {}))""",
            ),
        )
        assertEquals(
            "[1,[2]]|0{\"value\":7,\"writable\":true,\"enumerable\":true,\"configurable\":true}|inh",
            eval(
                """var r = JSON.parse('[1,[2]]', function (k, v) { if (k === '0') Object.freeze(this); return typeof v === 'number' ? v * 10 : v });
                var calls = 0;
                var r2 = JSON.parse('{"a":1,"b":2}', function (k, v) {
                    if (k === 'a') Object.defineProperty(this, 'b', { configurable: true, set: function () { calls++ }, get: function () { return 2 } });
                    return k === 'b' ? 7 : v });
                var log = [];
                JSON.parse('[1,2]', function (k, v) { if (k === '0') { delete this[1]; Array.prototype[1] = 'inh' } if (k === '1') log.push(v); return v });
                delete Array.prototype[1];
                JSON.stringify(r) + '|' + calls + JSON.stringify(Object.getOwnPropertyDescriptor(r2, 'b')) + '|' + log.join()""",
            ),
        )
    }

    @Test
    fun only_a_call_to_the_name_eval_is_a_direct_eval() {
        assertEquals(
            "global,local,numbernumber",
            eval(
                """var x = 'global';
                function f() { var x = 'local'; return this.eval('x') }
                function g() { var x = 'local'; return eval('x') }
                [f(), g(), (function () { this.eval('var leaked = 1'); return typeof leaked })() + typeof leaked].join()""",
            ),
        )
    }
}
