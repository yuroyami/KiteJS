/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals

class UnevalObjectTest {
    private fun eval(source: String): String = KiteJs(Rhino).use { it.evaluate(source).asString() }

    @Test
    fun methodsKeepTheirKindsAndResolvedKeys() {
        assertEquals("3,4,5,6,7,false,false", eval("""
            var o = eval(uneval({c() { return 3 }, *gen() { yield 4 },
                "quoted key"() { return 5 }, 1() { return 6 },
                ['comp' + 1]() { return 7 }}));
            [o.c(), o.gen().next().value, o['quoted key'](), o[1](), o.comp1(),
             'prototype' in o.c, 'prototype' in o.comp1].join(',');
        """))
    }

    @Test
    fun asyncMethodsRemainAsync() {
        assertEquals("function,true,false", eval("""
            var o = eval(uneval({async am() { return 7 }}));
            [typeof o.am, o.am() instanceof Promise, 'prototype' in o.am].join(',');
        """))
    }

    @Test
    fun sourceOffsetsHandleComputedNamesCommentsAndNestedFunctions() {
        assertEquals("1,9,9", eval("""
            var calls = 0;
            function name() { calls++; return 'x)(' }
            var original = { [name() /* ( misleading ) */](v = (2 + 3)) {
                return function(n) { return v + n }(4);
            }};
            var copy = eval(uneval(original));
            [calls, original['x)('](), copy['x)(']() ].join(',');
        """))
    }

    @Test
    fun renamedMethodsUseTheCurrentKey() {
        assertEquals("42,false,true", eval("""
            var method = { original() { return this.value } }.original;
            var copy = eval(uneval({renamed: method, value: 42}));
            [copy.renamed(), 'original' in copy, 'renamed' in copy].join(',');
        """))
    }

    @Test
    fun accessorsArePreservedWithoutCallingThem() {
        assertEquals("0,function,function,8,1", eval("""
            var calls = 0;
            var original = {value: 3, get g() { calls++; return this.value },
                set g(v) { this.value = v }};
            var copy = eval(uneval(original));
            var d = Object.getOwnPropertyDescriptor(copy, 'g');
            var before = calls;
            copy.g = 8;
            [before, typeof d.get, typeof d.set, copy.g, calls].join(',');
        """))
    }

    @Test
    fun getterOnlySetterOnlyAndQuotedComputedAccessors() {
        assertEquals("2,7,function,undefined,undefined,function", eval("""
            var copy = eval(uneval({get ['a' + ')']() { return 2 },
                set 'b key'(v) { this.value = v }}));
            copy['b key'] = 7;
            var a = Object.getOwnPropertyDescriptor(copy, 'a)');
            var b = Object.getOwnPropertyDescriptor(copy, 'b key');
            [copy['a)'], copy.value, typeof a.get, typeof a.set, typeof b.get, typeof b.set].join(',');
        """))
    }

    @Test
    fun descriptorAccessorsPreserveArrowsAndOrdinaryFunctions() {
        assertEquals("first,g,s,last,9,6,true,false", eval("""
            var o = {first: 1};
            Object.defineProperty(o, 'g', {get: () => 9, enumerable: true});
            Object.defineProperty(o, 's', {set: function setValue(v) { this.value = v }, enumerable: true});
            o.last = 2;
            var copy = eval(uneval(o));
            var keys = Object.keys(copy).join(',');
            copy.s = 6;
            var d = Object.getOwnPropertyDescriptor(copy, 's');
            [keys, copy.g, copy.value, 'prototype' in d.set, d.configurable].join(',');
        """))
    }

    @Test
    fun emptyAccessorAndArbitraryParameterListsStayAccessors() {
        assertEquals("true,false,undefined,undefined", eval("""
            var o = {};
            Object.defineProperty(o, 'empty', {get: undefined, set: undefined, enumerable: true});
            Object.defineProperty(o, 'g', {get: function(unused) { return unused }, enumerable: true});
            var copy = eval(uneval(o));
            var d = Object.getOwnPropertyDescriptor(copy, 'empty');
            [('get' in d), ('value' in d), String(copy.empty), String(copy.g)].join(',');
        """))
    }

    @Test
    fun ordinaryFunctionsArrowsDataAndNestedObjectsStillRoundTrip() {
        assertEquals("3,4,null,true,5", eval("""
            var copy = eval(uneval({a: () => 3, f: function named() { return 4 },
                n: null, b: true, nested: { m() { return 5 } }}));
            [copy.a(), copy.f(), String(copy.n), copy.b, copy.nested.m()].join(',');
        """))
    }

    @Test
    fun protoNamedDataAndMethodsRemainOwnProperties() {
        assertEquals("true,5,6", eval("""
            var original = {};
            Object.defineProperty(original, '__proto__', {value: 5, enumerable: true});
            var data = eval(uneval(original));
            var method = eval(uneval({__proto__() { return 6 }}));
            [Object.prototype.hasOwnProperty.call(data, '__proto__'), data.__proto__, method.__proto__()].join(',');
        """))
    }

    @Test
    fun removedPropertiesDoNotLeaveLeadingCommas() {
        assertEquals("({b:2})", eval("""
            var p = new Proxy({a: 1, b: 2}, {getOwnPropertyDescriptor(t, k) {
                return k === 'a' ? undefined : Reflect.getOwnPropertyDescriptor(t, k);
            }});
            uneval(p);
        """))
    }

    @Test
    fun circularObjectsKeepTheExistingRecursionGuard() {
        assertEquals("({self:{}})", eval("var o = {}; o.self = o; uneval(o)"))
    }
}
