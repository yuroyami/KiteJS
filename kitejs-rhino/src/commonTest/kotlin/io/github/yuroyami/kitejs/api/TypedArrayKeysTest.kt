/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The integer-indexed exotic object methods of typed arrays (issues 39, 40 and 41, D-88). Every
 * canonical numeric property name stays on the element path whether or not it names a real
 * element, elements answer for themselves in descriptors and key lists, and a value is converted
 * for the element type before an invalid index is ignored. Every expected value here is what V8
 * answers, except where a test says otherwise.
 */
class TypedArrayKeysTest {

    private fun eval(source: String): String = KiteJs().use { js -> js.evaluate(source).asString() }

    private val numberViews = listOf(
        "Int8Array", "Uint8Array", "Uint8ClampedArray", "Int16Array", "Uint16Array",
        "Int32Array", "Uint32Array", "Float32Array", "Float64Array",
    )

    @Test
    fun an_element_has_a_data_descriptor_and_nothing_else_does() {
        assertEquals(
            "{\"value\":7,\"writable\":true,\"enumerable\":true,\"configurable\":true}",
            eval("JSON.stringify(Object.getOwnPropertyDescriptor(new Uint8Array([7]), '0'))"),
        )
        assertEquals(
            "{\"value\":\"7n\",\"writable\":true,\"enumerable\":true,\"configurable\":true}",
            eval("JSON.stringify(Object.getOwnPropertyDescriptor(new BigInt64Array([7n]), 0), function (k, v) { return typeof v === 'bigint' ? v + 'n' : v })"),
        )
        assertEquals(
            "undefined,undefined,undefined,undefined",
            eval("var a = new Uint8Array([7]); [Object.getOwnPropertyDescriptor(a, 1), Object.getOwnPropertyDescriptor(a, '-0'), Object.getOwnPropertyDescriptor(a, 'NaN'), Object.getOwnPropertyDescriptor(a, '-1')].map(String).join()"),
        )
        assertEquals(
            "{\"0\":{\"value\":1.5,\"writable\":true,\"enumerable\":true,\"configurable\":true},\"1\":{\"value\":0,\"writable\":true,\"enumerable\":true,\"configurable\":true}}",
            eval("JSON.stringify(Object.getOwnPropertyDescriptors(new Float64Array([1.5, -0])))"),
        )
        // A subarray's elements are numbered from its own start.
        assertEquals("0,1|3|undefined", eval("var s = new Uint8Array([1, 2, 3]).subarray(1); [Reflect.ownKeys(s).join(), Object.getOwnPropertyDescriptor(s, 1).value, Object.getOwnPropertyDescriptor(s, 2)].map(String).join('|')"))
        // A detached view has no elements at all.
        assertEquals(
            "undefined,false,undefined,false",
            eval("var a = new Uint8Array(2); a.buffer.transfer(); a[0] = 5; [a[0], 0 in a, Object.getOwnPropertyDescriptor(a, 0), Reflect.defineProperty(a, 0, { value: 1 })].map(String).join()"),
        )
    }

    @Test
    fun defining_an_element_follows_the_typed_array_rules_for_every_view() {
        val perView = "2,undefined,false,false,false,undefined,false,0 1"
        assertEquals(
            List(numberViews.size) { perView }.joinToString("|"),
            eval(
                "[${numberViews.joinToString()}].map(function (C) { var a = new C([1, 2]); return [Object.getOwnPropertyDescriptor(a, 1).value," +
                    " Object.getOwnPropertyDescriptor(a, 2), Reflect.defineProperty(a, '1.5', { value: 1 }), Reflect.defineProperty(a, 'Infinity', { value: 1 })," +
                    " Reflect.defineProperty(a, '-Infinity', { value: 1 }), a['-Infinity'], 'Infinity' in a, Reflect.ownKeys(a).join(' ')].map(String).join() }).join('|')",
            ),
        )
        assertEquals(
            "2,undefined,false,true,5,0 1|2,undefined,false,true,5,0 1",
            eval(
                "[BigInt64Array, BigUint64Array].map(function (C) { var a = new C([1n, 2n]); return [Object.getOwnPropertyDescriptor(a, 1).value," +
                    " Object.getOwnPropertyDescriptor(a, 2), Reflect.defineProperty(a, '1.5', { value: 1n }), Reflect.defineProperty(a, 0, { value: 5n }), a[0]," +
                    " Reflect.ownKeys(a).join(' ')].map(String).join() }).join('|')",
            ),
        )
        assertEquals(
            "true,true,false,false,false,false,false,false|5,6",
            eval(
                "var a = new Uint8Array(2); [Reflect.defineProperty(a, 0, { value: 5 }), Reflect.defineProperty(a, 1, { value: 6, writable: true, enumerable: true, configurable: true })," +
                    " Reflect.defineProperty(a, 0, { configurable: false }), Reflect.defineProperty(a, 0, { enumerable: false }), Reflect.defineProperty(a, 0, { writable: false })," +
                    " Reflect.defineProperty(a, 0, { get: function () {} }), Reflect.defineProperty(a, 2, { value: 1 }), Reflect.defineProperty(a, '-0', { value: 1 })].join() + '|' + a.join()",
            ),
        )
        // The value is converted only once the index is known to be valid.
        assertEquals(
            "1:1",
            eval("var a = new Uint8Array(1); var n = 0; Reflect.defineProperty(a, 5, { value: { valueOf: function () { n++; return 1 } } }); Reflect.defineProperty(a, 0, { value: { valueOf: function () { n++; return 1 } } }); n + ':' + a[0]"),
        )
        assertEquals("returned", eval("var a = new BigInt64Array([1n]); try { Reflect.defineProperty(a, 5, { value: 1 }); 'returned' } catch (e) { e.name }"))
        assertEquals("TypeError", eval("var a = new BigInt64Array([1n]); try { Object.defineProperty(a, 0, { value: 1 }); 'returned' } catch (e) { e.name }"))
    }

    @Test
    fun a_refused_definition_is_a_type_error_from_object_define_property() {
        assertEquals(
            "TypeError:7",
            eval("var a = new Uint8Array([7]); var before = Object.getOwnPropertyDescriptor(a, '0'); try { Object.defineProperty(a, '0.5', { value: 9 }); String(before) + ':' + a[0] } catch (e) { e.name + ':' + a[0] }"),
        )
        assertEquals("false", eval("String(Reflect.defineProperty(new Uint8Array([7]), 'NaN', { value: 9 }))"))
        assertEquals("TypeError:0", eval("var a = new Uint8Array(1); try { Object.defineProperty(a, 0, { value: 3, configurable: false }); 'returned' } catch (e) { e.name + ':' + a[0] }"))
        assertEquals("4:1", eval("var a = new Uint8Array(1); Object.defineProperties(a, { 0: { value: 4 }, x: { value: 1, enumerable: true } }); a[0] + ':' + a.x"))
        // defineProperties stops at the first refusal, after defining what came before it.
        assertEquals("TypeError:4", eval("var a = new Uint8Array(1); try { Object.defineProperties(a, { 0: { value: 4 }, 3: { value: 1 } }); 'returned' } catch (e) { e.name + ':' + a[0] }"))
        assertEquals("1:2", eval("var s = Symbol(); var a = new Uint8Array(1); Object.defineProperty(a, s, { value: 1, enumerable: true }); a[s] + ':' + Reflect.ownKeys(a).length"))
    }

    @Test
    fun every_key_listing_holds_the_elements_then_the_ordinary_keys() {
        assertEquals("0,foo:0,foo,Symbol(s)", eval("var a = new Uint8Array([7]); a.foo = 8; a[Symbol('s')] = 9; Object.keys(a).join(',') + ':' + Reflect.ownKeys(a).map(String).join(',')"))
        assertEquals(
            "0,1,x,h|0,1,x|0",
            eval("var a = new Uint8Array(2); a.x = 1; Object.defineProperty(a, 'h', { value: 2, enumerable: false }); Object.getOwnPropertyNames(a).join() + '|' + Object.keys(a).join() + '|' + Object.getOwnPropertySymbols(a).length"),
        )
        assertEquals("0,1,x", eval("var a = new Uint8Array(2); a.x = 1; var k = []; for (var p in a) k.push(p); k.join()"))
        assertEquals("{\"0\":1,\"1\":2,\"x\":3}", eval("var a = new Uint8Array([1, 2]); a.x = 3; JSON.stringify(Object.assign({}, a))"))
        assertEquals("g:0,1,x", eval("var a = new Uint8Array(2); Object.defineProperty(a, 'x', { get: function () { return 'g' }, enumerable: true }); a.x + ':' + Object.keys(a).join()"))
        assertEquals("2:{\"0\":1,\"1\":2}", eval("var a = new Uint8Array([1, 2]); var c = 0; for (var k in a) c++; c + ':' + JSON.stringify(a)"))
        // Detaching drops the elements and keeps the rest.
        assertEquals("x|x", eval("var a = new Uint8Array([1, 2]); a.x = 3; a.buffer.transfer(); Reflect.ownKeys(a).join() + '|' + Object.keys(a).join()"))
        // Names that only look numeric are ordinary properties.
        assertEquals("3|4|0,01,1e0", eval("var a = new Uint8Array([7]); a['01'] = 3; a['1e0'] = 4; [a['01'], a['1e0'], Object.keys(a).join()].join('|')"))
    }

    @Test
    fun an_invalid_index_never_reaches_the_prototype() {
        assertEquals(
            "undefined:1",
            eval("var a = new Uint8Array([7]); Object.setPrototypeOf(a, { NaN: 9 }); var n = 0; a['-1'] = { valueOf: function () { n++; return 1 } }; a.NaN + ':' + n"),
        )
        assertEquals(
            "undefined,undefined,false,false,true,true,false",
            eval(
                "var a = new Uint8Array(1); Object.prototype['-1'] = 5; Object.prototype[3] = 6; var r = [a['-1'], a[3], '-1' in a, 3 in a, 0 in a, '0' in a, '01' in a].map(String).join();" +
                    " delete Object.prototype['-1']; delete Object.prototype[3]; r",
            ),
        )
        assertEquals(
            "undefined,undefined,false",
            eval(
                "var a = new Uint8Array(1); var proto = Object.create(Uint8Array.prototype, { 5: { set: function (v) { throw new Error('setter') } }, '1.5': { get: function () { return 'got' } } });" +
                    " Object.setPrototypeOf(a, proto); a[5] = 1; [a[5], a['1.5'], a.hasOwnProperty(5)].map(String).join()",
            ),
        )
        assertEquals("undefined,7,undefined,undefined,undefined,undefined", eval("var a = new Uint8Array([7]); [a['-0'], a[-0], a['0.0'], a['+0'], a.Infinity, a['-Infinity']].map(String).join()"))
        assertEquals("7|0,0.0", eval("var a = new Uint8Array([7]); a['-0'] = 9; a.Infinity = 9; a['0.0'] = 5; a[0] + '|' + Object.keys(a).join()"))
        assertEquals("0|||0", eval("var a = new Float64Array(1); a['1.5'] = 2; a[0.5] = 3; [a[0], a['1.5'], a[0.5], Object.keys(a).join()].join('|')"))
        assertEquals("true:1", eval("var a = new Uint8Array(1); var sym = Symbol('t'); Object.prototype[sym] = 1; var r = (sym in a) + ':' + a[sym]; delete Object.prototype[sym]; r"))
    }

    @Test
    fun a_typed_array_prototype_answers_for_its_elements_and_ignores_writes_to_invalid_ones() {
        assertEquals("3,true,undefined,false,undefined", eval("var o = Object.create(new Uint8Array([3])); [o[0], 0 in o, o['-1'], '-1' in o, o['1.5']].map(String).join()"))
        // A valid index is written onto the receiver, an invalid one is dropped without creating anything.
        assertEquals("true,0,false,5", eval("var o = Object.create(new Uint8Array(2)); o[0] = 5; o[7] = 1; [o.hasOwnProperty(0), Object.getPrototypeOf(o)[0], o.hasOwnProperty(7), o[0]].join()"))
        assertEquals("false,false,undefined,undefined", eval("var o = Object.create(new Uint8Array(1)); o['-1'] = 4; o['1.5'] = 5; [o.hasOwnProperty('-1'), o.hasOwnProperty('1.5'), o['-1'], o['1.5']].map(String).join()"))
    }

    @Test
    fun a_write_converts_the_value_before_ignoring_an_invalid_index() {
        assertEquals("3:9:0", eval("var a = new Uint8Array([1]); var n = 0; a[1.5] = { valueOf: function () { n++; return 1 } }; a[-0] = { valueOf: function () { n++; return 9 } }; a.NaN = { valueOf: function () { n++; return 1 } }; n + ':' + a[0] + ':' + Object.keys(a).join()"))
        assertEquals("1:0", eval("var a = new Uint8Array([1]); a.buffer.transfer(); var n = 0; a[0] = { valueOf: function () { n++; return 1 } }; n + ':' + Object.keys(a).length"))
        for (call in listOf("new Uint8Array([7])['-1'] = 1n", "new BigInt64Array([7n])['-1'] = 1", "new BigInt64Array([7n])[5] = 1")) {
            assertEquals("TypeError", eval("try { $call; 'returned' } catch (e) { e.name }"), call)
        }
        // Strict mode changes nothing: an ignored write is still a successful one.
        assertEquals("returned:0", eval("'use strict'; var a = new Uint8Array(1); try { a[5] = 1; a['-0'] = 1; a['1.5'] = 2; 'returned:' + Object.keys(a).join() } catch (e) { e.name }"))
        assertEquals("returned", eval("'use strict'; var a = Object.freeze(new Uint8Array(0)); try { a[0] = 1; 'returned' } catch (e) { e.name }"))
    }

    @Test
    fun an_element_cannot_be_deleted() {
        assertEquals("false,true,true,true,7", eval("var a = new Uint8Array([7]); [delete a[0], delete a['-1'], delete a['1.5'], delete a.foo, a[0]].join()"))
        assertEquals("TypeError", eval("'use strict'; var a = new Uint8Array([7]); try { delete a[0]; 'returned' } catch (e) { e.name }"))
        assertEquals("true,true,true", eval("'use strict'; var a = new Uint8Array([7]); [delete a[1], delete a['-1'], delete a.nope].join()"))
        assertEquals("false,true,true", eval("var a = new Uint8Array([7]); [Reflect.deleteProperty(a, 0), Reflect.deleteProperty(a, 1), Reflect.deleteProperty(a, '-0')].join()"))
    }

    @Test
    fun integrity_levels_follow_the_element_rules() {
        assertEquals("true,true", eval("var a = new Uint8Array(0); Object.freeze(a); [Object.isFrozen(a), Object.isSealed(a)].join()"))
        assertEquals("TypeError:false", eval("var a = new Uint8Array([1]); try { Object.freeze(a); 'returned' } catch (e) { e.name + ':' + Object.isExtensible(a) }"))
        assertEquals("TypeError", eval("var a = new Uint8Array([1, 2, 3]); Object.freeze(a.subarray(0, 0)); try { Object.freeze(a); 'frozen' } catch (e) { e.name }"))
        assertEquals("9,undefined,false", eval("var a = new Uint8Array([1, 2]); Object.preventExtensions(a); a[0] = 9; a.x = 1; [a[0], a.x, Object.isFrozen(new Uint8Array(0))].map(String).join()"))
        // Sealing asks every element to become non-configurable, which an element refuses
        // (ECMAScript 2021 onwards, 10.4.5.3), so it is a TypeError. V8 still seals the view.
        assertEquals("TypeError:false", eval("var a = new Uint8Array([1]); try { Object.seal(a); 'returned' } catch (e) { e.name + ':' + Object.isExtensible(a) }"))
    }
}
