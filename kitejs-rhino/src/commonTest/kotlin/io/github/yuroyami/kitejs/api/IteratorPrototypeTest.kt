/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Context
import io.github.yuroyami.kitejs.rhino.ScriptRuntime
import io.github.yuroyami.kitejs.rhino.TopLevel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The array, map, set, string, regexp string and generator iterator prototypes all inherit one
 * %IteratorPrototype% per realm, which alone holds `[Symbol.iterator]` (ECMAScript 2015, 25.1.2),
 * and %GeneratorFunction.prototype% and %GeneratorPrototype% name each other (#74, D-93). Every
 * expected value here is what V8 answers, except where a test says it is about a sealed scope.
 */
class IteratorPrototypeTest {

    private fun eval(source: String): String = KiteJs().use { js -> js.evaluate(source).asString() }

    private val protos =
        "var gp = Object.getPrototypeOf; var IP = gp(gp([][Symbol.iterator]()));" +
            " var protos = { Array: gp([][Symbol.iterator]()), Map: gp(new Map().entries()), Set: gp(new Set().values())," +
            " String: gp(''[Symbol.iterator]()), RegExp: gp('a'.matchAll(/a/g)), Generator: gp(gp((function* () {})())) };"

    @Test
    fun every_iterator_prototype_inherits_one_iterator_prototype() {
        assertEquals(
            "false false false function",
            eval(
                "var IP = Object.getPrototypeOf(Object.getPrototypeOf([][Symbol.iterator]())); var MIP = Object.getPrototypeOf(new Map().entries());" +
                    " var GP = Object.getPrototypeOf(Object.getPrototypeOf((function* () {})()));" +
                    " [IP === Object.prototype, Object.getPrototypeOf(MIP) === Object.prototype, Object.getPrototypeOf(GP) === Object.prototype," +
                    " typeof IP[Symbol.iterator]].join(' ')",
            ),
        )
        assertEquals(
            "Array:true:false Map:true:false Set:true:false String:true:false RegExp:true:false Generator:true:false",
            eval(
                "$protos Object.keys(protos).map(function (k) {" +
                    " return k + ':' + (gp(protos[k]) === IP) + ':' + Object.prototype.hasOwnProperty.call(protos[k], Symbol.iterator) }).join(' ')",
            ),
        )
    }

    @Test
    fun its_symbol_iterator_answers_this_and_has_the_built_in_shape() {
        assertEquals(
            "[Symbol.iterator],0,true,false,true,true,true",
            eval(
                "$protos var f = IP[Symbol.iterator], d = Object.getOwnPropertyDescriptor(IP, Symbol.iterator), o = {};" +
                    " [f.name, f.length, d.writable, d.enumerable, d.configurable, gp(IP) === Object.prototype, f.call(o) === o].join()",
            ),
        )
        // Every built-in iterator still iterates through the inherited method.
        assertEquals(
            "1,2|a,b|3|x,y|0|7",
            eval(
                "function* g() { yield 7 } var m = new Map([['a', 1], ['b', 2]]);" +
                    " [Array.from([1, 2].values()), Array.from(m.keys()), Array.from(new Set([3]).values()), Array.from('xy'[Symbol.iterator]())," +
                    " Array.from('a'.matchAll(/a/g)).map(function (r) { return r.index }), Array.from(g())].join('|')",
            ),
        )
    }

    @Test
    fun a_method_added_to_it_reaches_every_iterator() {
        assertEquals(
            "x,x,x,x,x,x",
            eval(
                "$protos IP.extra = function () { return 'x' };" +
                    " [[].keys().extra(), new Map().keys().extra(), new Set().entries().extra(), ''[Symbol.iterator]().extra()," +
                    " 'a'.matchAll(/a/g).extra(), (function* () {})().extra()].join()",
            ),
        )
        // And a library's own iterator finds the same object to inherit from.
        assertEquals(
            "true,1",
            eval(
                "$protos var mine = Object.create(IP); mine.next = function () { return { done: this.n++ > 0, value: 1 } }; mine.n = 0;" +
                    " [mine[Symbol.iterator]() === mine, Array.from(mine).join()].join()",
            ),
        )
    }

    @Test
    fun the_generator_prototypes_name_each_other() {
        assertEquals(
            "false,false,true,0,true,false,false,true",
            eval(
                "var G = Object.getPrototypeOf(function* () {}); var GP = G.prototype;" +
                    " var d = Object.getOwnPropertyDescriptor(G, 'prototype'), c = Object.getOwnPropertyDescriptor(GP, 'constructor');" +
                    " [d.writable, d.enumerable, d.configurable, Object.keys(G).length, c.value === G, c.writable, c.enumerable, c.configurable].join()",
            ),
        )
    }

    @Test
    fun a_sealed_scope_seals_both_and_still_runs_generators() {
        // Not a V8 comparison: a sealed scope is an embedder's choice, and its built-ins refuse changes.
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects(TopLevel(), true)
            val source =
                "'use strict'; function* g() { yield 1; yield 2 } var gp = Object.getPrototypeOf; var IP = gp(gp([][Symbol.iterator]()));" +
                    " var GP = gp(g).prototype;" +
                    " function t(f) { try { f(); return 'changed' } catch (e) { return e.name } }" +
                    " var sealedAnswer = t(function () { Array.prototype.x = 1 });" +
                    " [Array.from(g()).join(), GP.constructor === gp(g), sealedAnswer !== 'changed', t(function () { IP.x = 1 }) === sealedAnswer," +
                    " t(function () { GP.x = 1 }) === sealedAnswer].join('|')"
            // Whatever a sealed built-in throws, Rhino's InternalError, these two throw as well.
            assertEquals("1,2|true|true|true|true", ScriptRuntime.toString(cx.evaluateString(scope, source, "sealed.js", 1, null)))
        } finally {
            Context.exit()
        }
    }
}
