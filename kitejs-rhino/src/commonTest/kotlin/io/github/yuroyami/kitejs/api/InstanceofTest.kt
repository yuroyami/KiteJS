/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.LanguageVersion
import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `instanceof` is InstanceofOperator (ECMAScript 2015, 12.9.4): the target's `Symbol.hasInstance`
 * decides, and only a target without one has to be callable and is asked OrdinaryHasInstance
 * (#75, D-92). Every expected value here is what V8 answers, except the one test on the legacy
 * StopIteration, which V8 does not have.
 */
class InstanceofTest {

    private fun eval(source: String, version: LanguageVersion = LanguageVersion.LATEST): String =
        KiteJs(Rhino) { languageVersion = version }.use { js -> js.evaluate(source).asString() }

    private val attempt = "function t(f) { try { return String(f()) } catch (e) { return e.name } }"

    @Test
    fun a_functions_own_symbol_hasInstance_decides() {
        assertEquals(
            "true false true",
            eval(
                "function F() {} Object.defineProperty(F, Symbol.hasInstance, { value: function (v) { return v === 1; } });" +
                    " [1 instanceof F, ({}) instanceof F, 1 instanceof F.bind()].join(' ')",
            ),
        )
    }

    @Test
    fun an_objects_method_gets_the_target_as_this_and_the_left_side_as_its_argument() {
        assertEquals(
            "true,false,true",
            eval(
                "var o = {}; o[Symbol.hasInstance] = function (v) { return this === o && v.a === 1; };" +
                    " [({a: 1}) instanceof o, ({a: 2}) instanceof o, 2 instanceof { [Symbol.hasInstance]: function (v) { return v === 2 } }].join()",
            ),
        )
        assertEquals(
            "true true,1,string",
            eval(
                "var seen; var o = { [Symbol.hasInstance]: function (v) { seen = [this === o, arguments.length, typeof v].join(); return true } };" +
                    " ['s' instanceof o, seen].join(' ')",
            ),
        )
        // The answer goes through ToBoolean.
        assertEquals(
            "true,false",
            eval(
                "[({}) instanceof { [Symbol.hasInstance]: function () { return 'x' } }," +
                    " ({}) instanceof { [Symbol.hasInstance]: function () { return 0 } }].join()",
            ),
        )
    }

    @Test
    fun a_symbol_hasInstance_that_cannot_be_called_throws_and_null_means_none() {
        assertEquals(
            "TypeError,TypeError,TypeError,true",
            eval(
                "$attempt function F() {} Object.defineProperty(F, Symbol.hasInstance, { value: 1 });" +
                    " function G() {} Object.defineProperty(G, Symbol.hasInstance, { value: null });" +
                    " [t(function () { return 1 instanceof { [Symbol.hasInstance]: 1 } }), t(function () { return ({}) instanceof F })," +
                    " t(function () { return ({}) instanceof { [Symbol.hasInstance]: null } }), t(function () { return new G() instanceof G })].join()",
            ),
        )
        assertEquals(
            "RangeError",
            eval(
                "var o = Object.defineProperty(function () {}, Symbol.hasInstance, { get: function () { throw new RangeError('h') } });" +
                    " try { ({}) instanceof o } catch (e) { e.name }",
            ),
        )
    }

    @Test
    fun the_method_is_looked_up_before_the_target_has_to_be_callable() {
        assertEquals(
            "Symbol(Symbol.hasInstance),TypeError",
            eval(
                "var log = []; var p = new Proxy({}, { get: function (t, k) { log.push(String(k)) } });" +
                    " try { ({}) instanceof p } catch (e) { log.push(e.name) } log.join()",
            ),
        )
        assertEquals(
            "false Symbol(Symbol.hasInstance),prototype",
            eval(
                "var log = []; var P = new Proxy(function () {}, { get: function (t, k) { log.push(String(k)); return Reflect.get(t, k) } });" +
                    " [({}) instanceof P, log.join()].join(' ')",
            ),
        )
    }

    @Test
    fun a_bound_function_asks_instanceof_of_its_target() {
        assertEquals(
            "false,false",
            eval(
                "function F() {} var bf = F.bind(); var o = new F();" +
                    " Object.defineProperty(F, Symbol.hasInstance, { value: function () { return false } });" +
                    " [o instanceof bf, o instanceof F].join()",
            ),
        )
        assertEquals(
            "true,true",
            eval(
                "function F() {} Object.defineProperty(F, Symbol.hasInstance, { value: function (v) { return v === 1 } });" +
                    " var B = F.bind().bind(); [1 instanceof B, Function.prototype[Symbol.hasInstance].call(B, 1)].join()",
            ),
        )
    }

    @Test
    fun function_prototype_symbol_hasInstance_is_ordinary_has_instance() {
        assertEquals(
            "true,false,true,false,true",
            eval(
                "var h = Function.prototype[Symbol.hasInstance];" +
                    " function F() {} Object.defineProperty(F, Symbol.hasInstance, { value: function (v) { return v === 1; } });" +
                    " [h.call(Array.bind(), []), h.call(F, 1), h.call(F.bind(), 1), h.call({}, []), h.call(Array, [])].join()",
            ),
        )
        assertEquals(
            "false,false,false,[Symbol.hasInstance],1",
            eval(
                "var d = Object.getOwnPropertyDescriptor(Function.prototype, Symbol.hasInstance); var h = Function.prototype[Symbol.hasInstance];" +
                    " [d.writable, d.enumerable, d.configurable, h.name, h.length].join()",
            ),
        )
    }

    @Test
    fun ordinary_targets_and_primitives_answer_as_before() {
        assertEquals(
            "false,TypeError,false,true,TypeError",
            eval(
                "$attempt var H = function () {}; H.prototype = 3;" +
                    " [t(function () { return 1 instanceof H }), t(function () { return ({}) instanceof H })," +
                    " t(function () { return Symbol() instanceof Symbol }), t(function () { return Object(Symbol()) instanceof Symbol })," +
                    " t(function () { return 1 instanceof Symbol() })].join()",
            ),
        )
        assertEquals(
            "true,true,true,true,false",
            eval("[[] instanceof Array, new Map() instanceof Map, (function () {}) instanceof Function, [] instanceof Object, Object.create(null) instanceof Object].join()"),
        )
    }

    @Test
    fun the_legacy_stop_iteration_answers_by_its_class() {
        // Rhino's own StopIteration cannot be called and has no Symbol.hasInstance, yet a thrown
        // string tested against it is false, as upstream answers, rather than a TypeError. Only
        // ES5 has it; from ES6 on there is no StopIteration (#113).
        assertEquals(
            "false,false,false",
            eval("[1 instanceof StopIteration, 'x' instanceof StopIteration, ({}) instanceof StopIteration].join()", LanguageVersion.ES5),
        )
        assertEquals("undefined", eval("typeof StopIteration"))
    }
}
