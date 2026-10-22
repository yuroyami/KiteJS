/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

/** A primitive `this` stays primitive in strict code and in the String built-ins (#47, #78), with Node 26.10 controls. */
class PrimitiveThisTest {
    private fun check(expected: String, source: String) {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            assertEquals(expected, ScriptRuntime.toString(cx.evaluateString(cx.initStandardObjects(), source, "primitive-this.js", 1, null)))
        } finally {
            Context.exit()
        }
    }

    @Test
    fun reflectApplyPassesTheThisArgumentAsGiven() = check(
        "undefined:undefined object:null number:5 string:s boolean:true bigint:10 symbol:Symbol(Symbol.iterator) object:[object Object]",
        """
        var f = function () { 'use strict'; return typeof this + ':' + String(this); };
        [undefined, null, 5, 's', true, 10n, Symbol.iterator, {}].map(function (t) { return Reflect.apply(f, t, []); }).join(' ');
        """,
    )

    @Test
    fun strictMethodsOfPrimitivesSeeThePrimitive() = check("string string number boolean number number boolean,boolean", """
        String.prototype.kind = function () { 'use strict'; return typeof this; };
        Number.prototype.kind = String.prototype.kind;
        Boolean.prototype.kind = String.prototype.kind;
        var bound = String.prototype.kind.bind(false);
        ['x'.kind(), 'x'['kind'](), (3).kind(), true.kind(), String.prototype.kind.call(5),
         String.prototype.kind.apply(5), [bound(), bound()].join()].join(' ');
    """)

    @Test
    fun sloppyMethodsOfPrimitivesSeeTheWrapper() = check("object object object", """
        String.prototype.kind = function () { return typeof this; };
        ['x'.kind(), String.prototype.kind.call(5), String.prototype.kind.bind(true)()].join(' ');
    """)

    @Test
    fun arrowsEvalAndGeneratorsShareTheStrictPrimitive() = check("string,string,string,true,2 | object | string,string", """
        String.prototype.g = function () { 'use strict';
            return [typeof this, (() => typeof this)(), eval('typeof this'), this === 'ab', this.length].join(); };
        String.prototype.h = function () { return (() => { 'use strict'; return typeof this; })(); };
        String.prototype.gen = function* () { 'use strict'; yield typeof this; yield typeof this; };
        ['ab'.g(), 'ab'.h(), [...'q'.gen()].join()].join(' | ');
    """)

    @Test
    fun aWrapperThatEscapesStaysAnObject() = check("object object", """
        var o = Object.prototype.valueOf.call('x');
        var s = function () { 'use strict'; return typeof this; };
        o.s = s;
        [s.call(o), o.s()].join(' ');
    """)

    @Test
    fun stringMethodsReadAPrimitiveWithoutItsToString() = check("abc 2 2 xy q M | toString", """
        var ran = [];
        var ts = String.prototype.toString;
        String.prototype.toString = function () { ran.push('toString'); return ts.call(this); };
        var r = ['ABC'.toLowerCase(), 'a-b'.split('-').length, 'abc'.indexOf('c'), 'x'.concat('y'),
                 new String('Q').toLowerCase(), String.prototype.toUpperCase.call('m')];
        String.prototype.toString = ts;
        r.join(' ') + ' | ' + ran.join(',');
    """)

    @Test
    fun strictUndefinedThisIsTypeofUndefined() = check("undefined undefined undefined undefined", """
        var f = function () { 'use strict'; return typeof this; };
        [f(), f.call(undefined), Reflect.apply(f, undefined, []), f.bind(undefined)()].join(' ');
    """)

    @Test
    fun aCallbackSeesThePrimitiveOnEveryCall() = check(
        "string,string,string,string,string,string,string,string,string,string,string,string",
        """
        var r = [];
        var k = function () { 'use strict'; r.push(typeof this); return 0; };
        [1, 2].forEach(k, 's'); [1, 2].map(k, 's'); [1, 2].flatMap(k, 's'); Array.from([1, 2], k, 's');
        Uint8Array.from([1, 2], k, 's'); new Uint8Array(2).forEach(k, 's');
        r.join();
        """,
    )

    @Test
    fun aGetterOnAPrimitiveSeesThePrimitive() = check(
        "string,string,string,string/string,object,string",
        """
        Object.defineProperty(String.prototype, 'g', { get: function () { 'use strict'; return typeof this; }, configurable: true });
        Object.defineProperty(String.prototype, 'm', { get: function () { 'use strict'; var t = typeof this;
            return function () { 'use strict'; return t + '/' + typeof this; }; }, configurable: true });
        Object.defineProperty(String.prototype, 'w', { get: function () { return typeof this; }, configurable: true });
        var seen; var it = String.prototype[Symbol.iterator];
        Object.defineProperty(String.prototype, Symbol.iterator, { get: function () { 'use strict'; seen = typeof this; return it; }, configurable: true });
        [...'ab'];
        ['a'.g, 'a'['g'], 'a'.g, 'a'.m(), 'a'.w, seen].join();
        """,
    )
}
