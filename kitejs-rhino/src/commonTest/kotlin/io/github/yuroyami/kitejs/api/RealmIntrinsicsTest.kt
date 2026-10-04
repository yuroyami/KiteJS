/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Context
import io.github.yuroyami.kitejs.rhino.NativeObject
import io.github.yuroyami.kitejs.rhino.ScriptRuntime
import io.github.yuroyami.kitejs.rhino.Scriptable
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the engine makes on its own, a literal, a primitive's wrapper, an error it throws, comes
 * from the realm's intrinsics, never from a global binding a script may have replaced (#77,
 * D-94). Every expected value here is what V8 answers.
 */
class RealmIntrinsicsTest {

    private fun eval(source: String): String = KiteJs().use { js -> js.evaluate(source).asString() }

    @Test
    fun the_examples_of_the_issue() {
        assertEquals(
            "true function",
            eval("var SP = String.prototype, gp = Object.getPrototypeOf; String = function () {}; (gp('a') === SP) + ' ' + typeof ''.trim"),
        )
        assertEquals("true", eval("var OP = Object.prototype, gp = Object.getPrototypeOf; Object = function () {}; String(gp({}) === OP)"))
        assertEquals(
            "true string",
            eval("var TE = TypeError; TypeError = function () {}; try { null.x; } catch (e) { (e instanceof TE) + ' ' + typeof e.message; }"),
        )
        assertEquals("function", eval("Array = function () {}; typeof [].join"))
    }

    @Test
    fun literals_and_the_arrays_built_ins_return() {
        assertEquals("true,function", eval("var P = RegExp.prototype, gp = Object.getPrototypeOf; RegExp = function () {}; [gp(/a/) === P, typeof /a/.test].join()"))
        assertEquals(
            "true,true,true,true,true",
            eval(
                "var P = Object.prototype, AP = Array.prototype, gp = Object.getPrototypeOf; Object = function () {}; Array = function () {};" +
                    " [gp(JSON.parse('{}')) === P, gp(JSON.parse('[]')) === AP, gp('a,b'.split(',')) === AP, gp(/a/.exec('a')) === AP, gp({}) === P].join()",
            ),
        )
        assertEquals(
            "true,function,true",
            eval("var P = Function.prototype, gp = Object.getPrototypeOf; Function = function () {}; [gp(function () {}) === P, typeof (function () {}).call, gp(() => 1) === P].join()"),
        )
    }

    @Test
    fun primitives_reach_their_own_prototypes() {
        assertEquals(
            "function,true,function,true",
            eval(
                "var P = Number.prototype, BP = Boolean.prototype, gp = Object.getPrototypeOf; Number = function () {}; Boolean = function () {};" +
                    " [typeof (1).toFixed, gp(1) === P, typeof true.valueOf, gp(true) === BP].join()",
            ),
        )
        assertEquals(
            "true,function",
            eval("var P = Symbol.prototype, gp = Object.getPrototypeOf; Symbol = function () {}; var s = P.constructor(); [gp(Object(s)) === P, typeof s.toString].join()"),
        )
        assertEquals("function,true", eval("var P = BigInt.prototype, gp = Object.getPrototypeOf; BigInt = function () {}; [typeof (1n).toString, gp(1n) === P].join()"))
    }

    @Test
    fun errors_the_engine_makes() {
        assertEquals(
            "true,string",
            eval("var EP = RangeError.prototype, gp = Object.getPrototypeOf; RangeError = function () {}; try { new Array(-1) } catch (e) { [gp(e) === EP, typeof e.message].join() }"),
        )
        assertEquals("true,string", eval("var SE = SyntaxError; SyntaxError = function () {}; try { eval('(') } catch (e) { [e instanceof SE, typeof e.message].join() }"))
        assertEquals(
            "true,true",
            KiteJs().use { js ->
                js.evaluate(
                    "var r; var AE = AggregateError; AggregateError = function () {};" +
                        " Promise.any([]).catch(function (e) { r = [e instanceof AE, e.constructor === AE].join() });",
                )
                js.evaluate("r").asString()
            },
        )
        assertEquals("string", eval("var cst = Error.captureStackTrace; Error = function () {}; var o = {}; cst(o); typeof o.stack"))
    }

    @Test
    fun map_group_by_makes_a_map_of_its_own_realm() {
        assertEquals(
            "true,1",
            eval("var MP = Map.prototype, gp = Object.getPrototypeOf; var M = Map; Map = function () {}; var m = M.groupBy([1], function () { return 'k' }); [gp(m) === MP, m.get('k').length].join()"),
        )
    }

    @Test
    fun a_scope_of_any_class_keeps_its_intrinsics() {
        val source = "var SP = String.prototype; String = function () {}; [Object.getPrototypeOf('a') === SP, typeof ''.trim].join()"
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            // An embedder's own global object.
            val plain = cx.initStandardObjects(NativeObject(), false)
            assertEquals("true,function", ScriptRuntime.toString(cx.evaluateString(plain, source, "plain.js", 1, null)))
            // A scope made per request on top of a shared one, which inherits its globals.
            val shared = cx.initStandardObjects()
            val perRequest: Scriptable = NativeObject().also { it.prototype = shared }
            assertEquals("true,function", ScriptRuntime.toString(cx.evaluateString(perRequest, source, "request.js", 1, null)))
            assertEquals("function", ScriptRuntime.toString(cx.evaluateString(shared, "typeof String.prototype.trim", "shared.js", 1, null)))
            // The global a scope-less call makes is a TopLevel, which Node names as V8 does.
            assertEquals("[object global]", ScriptRuntime.toString(cx.evaluateString(shared, "Object.prototype.toString.call(this)", "class.js", 1, null)))
        } finally {
            Context.exit()
        }
    }
}
