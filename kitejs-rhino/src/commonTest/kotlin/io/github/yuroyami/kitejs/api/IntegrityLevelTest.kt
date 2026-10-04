/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `Object.seal`, `Object.freeze` and `Object.defineProperty` as the typed array work left them
 * (D-88). Sealing and freezing go through each object's own [[DefineOwnProperty]] with partial
 * descriptors, so exotic objects and proxies see the same requests V8 sends them, and a refused
 * definition is a TypeError. Every expected value here is what V8 answers.
 */
class IntegrityLevelTest {

    private fun eval(source: String): String = KiteJs(Rhino).use { js -> js.evaluate(source).asString() }

    private val logging =
        "var log = []; var p = new Proxy({ a: 1 }, { defineProperty: function (t, k, d) { log.push(k + ':' + JSON.stringify(d)); return Reflect.defineProperty(t, k, d) }," +
            " getOwnPropertyDescriptor: function (t, k) { log.push('gopd ' + k); return Reflect.getOwnPropertyDescriptor(t, k) } });"

    @Test
    fun ordinary_objects_keep_their_values_and_kinds() {
        assertEquals(
            "1,true,true,false,function,true,false,true,false",
            eval("var o = { a: 1, get b() { return 2 } }; Object.seal(o); var d = Object.getOwnPropertyDescriptors(o); [d.a.value, d.a.writable, d.a.enumerable, d.a.configurable, typeof d.b.get, d.b.enumerable, d.b.configurable, Object.isSealed(o), Object.isFrozen(o)].join()"),
        )
        assertEquals(
            "1,false,false,function,false,true",
            eval("var o = { a: 1, get b() { return 2 }, set b(v) {} }; Object.freeze(o); var d = Object.getOwnPropertyDescriptors(o); [d.a.value, d.a.writable, d.a.configurable, typeof d.b.set, d.b.configurable, Object.isFrozen(o)].join()"),
        )
        assertEquals(
            "{\"value\":1,\"writable\":false,\"enumerable\":false,\"configurable\":false}",
            eval("var o = {}; Object.defineProperty(o, 'x', { value: 1, configurable: true }); Object.seal(o); JSON.stringify(Object.getOwnPropertyDescriptor(o, 'x'))"),
        )
        assertEquals("1,2,true", eval("var o = { a: 1 }; Object.defineProperty(o, 'b', { value: 2, writable: true, configurable: true }); Object.freeze(o); o.a = 5; o.b = 6; [o.a, o.b, Object.isFrozen(o)].join()"))
        assertEquals("2:false", eval("var o = Object.freeze({ a: { b: 1 } }); o.a.b = 2; o.a.b + ':' + Object.isFrozen(o.a)"))
        assertEquals("5,false,2,true,true", eval("var a = [1, 2]; Object.seal(a); a[0] = 5; [a[0], delete a[0], a.length, Object.isSealed(a), Object.getOwnPropertyDescriptor(a, 'length').writable].join()"))
        assertEquals("true,false,false", eval("var f = function g(x) {}; Object.freeze(f); [Object.isFrozen(f), Object.getOwnPropertyDescriptor(f, 'length').configurable, Object.getOwnPropertyDescriptor(f, 'name').configurable].join()"))
        assertEquals("true,true,false", eval("var f = function () {}; Object.seal(f); [Object.isSealed(f), Object.getOwnPropertyDescriptor(f, 'prototype').writable, Object.getOwnPropertyDescriptor(f, 'prototype').configurable].join()"))
    }

    @Test
    fun a_proxy_sees_partial_descriptors_and_its_refusal_is_a_type_error() {
        assertEquals("a:{\"configurable\":false}", eval("$logging Object.seal(p); log.join(' ')"))
        assertEquals("gopd a a:{\"writable\":false,\"configurable\":false}", eval("$logging Object.freeze(p); log.join(' ')"))
        assertEquals("TypeError", eval("var p = new Proxy({ a: 1 }, { defineProperty: function () { return false } }); try { Object.freeze(p); 'returned' } catch (e) { e.name }"))
        for (call in listOf("Object.defineProperty(p, 'x', { value: 1 })", "Object.defineProperties(p, { x: { value: 1 } })")) {
            assertEquals("TypeError", eval("var p = new Proxy({}, { defineProperty: function () { return false } }); try { $call; 'returned' } catch (e) { e.name }"), call)
        }
        assertEquals("false", eval("var p = new Proxy({}, { defineProperty: function () { return false } }); String(Reflect.defineProperty(p, 'x', { value: 1 }))"))
    }

    @Test
    fun define_property_converts_the_key_before_the_descriptor() {
        assertEquals(
            "key,desc",
            eval("var log = []; try { Object.defineProperty({}, { toString: function () { log.push('key'); return 'k' } }, { get value() { log.push('desc'); return 1 } }) } catch (e) {} log.join()"),
        )
        assertEquals("TypeError:1", eval("var s = Symbol('q'); var o = {}; Object.defineProperty(o, s, { value: 1 }); try { Object.defineProperty(o, s, { value: 2 }); 'returned' } catch (e) { e.name + ':' + o[s] }"))
        assertEquals("TypeError:1", eval("var o = Object.freeze([1, 2]); try { Object.defineProperty(o, 0, { value: 9 }); 'returned' } catch (e) { e.name + ':' + o[0] }"))
        assertEquals("TypeError", eval("var o = Object.preventExtensions({}); try { Object.defineProperty(o, 'x', { value: 1 }); 'returned' } catch (e) { e.name }"))
        // Turning an accessor into a data property without a value leaves the value undefined.
        assertEquals(
            "{\"writable\":false,\"enumerable\":false,\"configurable\":true}",
            eval("var o = {}; Object.defineProperty(o, 'v', { get: function () { return 3 }, configurable: true }); Object.defineProperty(o, 'v', { writable: false }); JSON.stringify(Object.getOwnPropertyDescriptor(o, 'v'))"),
        )
    }

    @Test
    fun partial_descriptors_compare_only_the_fields_they_have() {
        assertEquals("ok", eval("var obj = {}; obj.foo = 10; Object.seal(obj); try { Object.seal(obj); Object.freeze(obj); Object.freeze(obj); Object.isFrozen(obj) ? 'ok' : 'not frozen' } catch (e) { e.name }"))
        assertEquals("ok", eval("var obj = {}; Object.defineProperty(obj, 'foo', { value: 10, writable: true, enumerable: true, configurable: false }); try { Object.freeze(obj); 'ok' } catch (e) { e.name }"))
        val enumerableX = "var o = {}; Object.defineProperty(o, 'x', { value: 1, enumerable: true });"
        for (desc in listOf("{ value: 1 }", "{}", "{ writable: false }")) {
            assertEquals("ok", eval("$enumerableX try { Object.defineProperty(o, 'x', $desc); 'ok' } catch (e) { e.name }"), desc)
        }
        assertEquals("ok", eval("var o = {}; Object.defineProperty(o, 'x', { get: function () {}, enumerable: true }); try { Object.defineProperty(o, 'x', { set: undefined }); 'ok' } catch (e) { e.name }"))
        assertEquals("TypeError", eval("var o = {}; Object.defineProperty(o, 'x', { value: 1 }); try { Object.defineProperty(o, 'x', { get: undefined }); 'ok' } catch (e) { e.name }"))
        assertEquals(
            "false,true,false,true",
            eval("$enumerableX [Reflect.defineProperty(o, 'x', { enumerable: false }), Reflect.defineProperty(o, 'x', { enumerable: true }), Reflect.defineProperty(o, 'x', { value: 2 }), Reflect.defineProperty(o, 'x', { value: 1, writable: false, configurable: false })].join()"),
        )
    }

    @Test
    fun a_descriptor_object_is_read_the_way_to_property_descriptor_reads_it() {
        assertEquals(
            "has enumerable,get enumerable,has configurable,has value,get value,has writable,has get,has set",
            eval(
                "var log = []; var d = new Proxy({ value: 1, enumerable: 1 }, { has: function (t, k) { log.push('has ' + k); return k in t }," +
                    " get: function (t, k) { log.push('get ' + k); return t[k] } }); Object.defineProperty({}, 'x', d); log.join()",
            ),
        )
        // The flags are booleans once read, whatever value the descriptor object held.
        assertEquals(
            "{\"value\":1,\"writable\":false,\"enumerable\":true,\"configurable\":false}",
            eval("var o = {}; Object.defineProperty(o, 'x', { value: 1, enumerable: 'yes', writable: 0, configurable: null }); JSON.stringify(Object.getOwnPropertyDescriptor(o, 'x'))"),
        )
        assertEquals("true,true,true", eval("var s = new String('ab'); [Reflect.defineProperty(s, 0, { enumerable: 1 }), Reflect.defineProperty(s, 0, { writable: 0, enumerable: 'y' }), Reflect.defineProperty(s, 0, { configurable: '' })].join()"))
        assertEquals(
            "ok",
            eval(
                "var p = new Proxy({}, { defineProperty: function (t, k, d) { return Reflect.defineProperty(t, k, d) } }); Object.defineProperty(p, 'x', { value: 1, configurable: false });" +
                    " try { Object.defineProperty(p, 'x', { enumerable: 0, value: 1 }); 'ok' } catch (e) { e.name }",
            ),
        )
    }

    @Test
    fun arrays_stop_growing_once_they_may_not() {
        for (call in listOf("push()", "unshift()")) {
            assertEquals("TypeError", eval("'use strict'; var array = []; Object.freeze(array); try { array.$call; 'no throw:' + array.length } catch (e) { e.name }"), call)
        }
        assertEquals("TypeError", eval("'use strict'; var a = []; Object.defineProperty(a, 'length', { writable: false }); try { a.push(); 'no throw' } catch (e) { e.name }"))
        assertEquals("TypeError:2", eval("'use strict'; var a = [1, 2]; Object.preventExtensions(a); try { a.push(3); 'pushed:' + a.length } catch (e) { e.name + ':' + a.length }"))
        assertEquals("1:false", eval("var a = [1]; Object.defineProperty(a, 'length', { writable: false }); a[5] = 1; a.length + ':' + a.hasOwnProperty(5)"))
        assertEquals("TypeError:3", eval("var a = [1, 2, 3]; Object.defineProperty(a, 'length', { writable: false }); try { Object.defineProperty(a, 3, { value: 4 }); 'returned' } catch (e) { e.name + ':' + a.length }"))
        assertEquals("true|false|1,9,3", eval("var a = [1, 2, 3]; Object.defineProperty(a, 'length', { writable: false }); [Reflect.defineProperty(a, 1, { value: 9 }), Reflect.defineProperty(a, 3, { value: 9 }), a.join()].join('|')"))
        assertEquals("5,2|2|2|1|false", eval("var a = [1, 2]; Object.preventExtensions(a); a[0] = 5; a[2] = 3; [a.join(), a.length, a.pop(), a.length, Object.isExtensible(a)].join('|')"))
        assertEquals("0:0", eval("var a = []; Object.preventExtensions(a); for (var i = 0; i < 3; i++) { a[i] = i } a.length + ':' + Object.keys(a).length"))
        assertEquals("3,2,1", eval("var a = [3, 1, 2]; Object.preventExtensions(a); a.sort(); a.reverse(); a.join()"))
        assertEquals("3", eval("var a = [1, 2, 3]; Object.defineProperty(a, 'length', { writable: false }); a.length = 0; String(a.length)"))
        assertEquals("4:9,,,1", eval("var a = []; a[3] = 1; Object.defineProperty(a, 0, { value: 9, enumerable: true, writable: true, configurable: true }); a.length + ':' + a.join()"))
    }

    @Test
    fun a_computed_property_keeps_its_value_when_it_becomes_a_plain_one() {
        assertEquals(
            "true,true,false",
            eval("var old = Error.stackTraceLimit; var t = typeof old; Object.defineProperty(Error, 'stackTraceLimit', { writable: false }); var d = Object.getOwnPropertyDescriptor(Error, 'stackTraceLimit'); [t === typeof d.value, d.value === old, d.writable].join()"),
        )
    }

    @Test
    fun string_objects_answer_for_their_characters() {
        assertEquals("true,a,2", eval("var s = new String('ab'); Object.freeze(s); [Object.isFrozen(s), s[0], s.length].join()"))
        assertEquals("true,true,false", eval("var s = new String('ab'); Object.seal(s); [Object.isSealed(s), Object.isFrozen(s), Object.isExtensible(s)].join()"))
        assertEquals(
            "true,false,true,false,true,c,2",
            eval(
                "var s = new String('ab'); [Reflect.defineProperty(s, 0, { value: 'a' }), Reflect.defineProperty(s, 0, { value: 'b' })," +
                    " Reflect.defineProperty(s, 0, { writable: false, enumerable: true, configurable: false }), Reflect.defineProperty(s, 1, { enumerable: false })," +
                    " Reflect.defineProperty(s, 2, { value: 'c', enumerable: true, configurable: true, writable: true }), s[2], s.length].join()",
            ),
        )
    }

    @Test
    fun arguments_stay_mapped_until_a_property_becomes_read_only() {
        assertEquals("true:1", eval("(function () { Object.freeze(arguments); return Object.isFrozen(arguments) + ':' + arguments[0] })(1, 2)"))
        assertEquals("true:1:true", eval("(function (a, b) { 'use strict'; Object.seal(arguments); return Object.isSealed(arguments) + ':' + arguments[0] + ':' + Object.getOwnPropertyDescriptor(arguments, 0).writable })(1, 2)"))
        assertEquals("1:9:true", eval("(function (a, b) { Object.freeze(arguments); a = 9; return arguments[0] + ':' + a + ':' + Object.isFrozen(arguments) })(1, 2)"))
        assertEquals("9:true", eval("(function (a, b) { Object.seal(arguments); a = 9; return arguments[0] + ':' + Object.isSealed(arguments) })(1, 2)"))
        assertEquals("1:9", eval("(function (a) { Object.defineProperty(arguments, 0, { writable: false }); a = 9; return arguments[0] + ':' + a })(1)"))
        assertEquals("5:9", eval("(function (a) { Object.defineProperty(arguments, 0, { value: 5, writable: false }); a = 9; return arguments[0] + ':' + a })(1)"))
        assertEquals("9:0", eval("(function (a) { Object.defineProperty(arguments, 0, { enumerable: false }); a = 9; return arguments[0] + ':' + Object.keys(arguments).length })(1)"))
    }
}
