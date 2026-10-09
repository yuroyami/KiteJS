/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The name of a named function expression is a read-only binding in a scope around the function
 * (ECMAScript 2015, 14.1.20). Sloppy code cannot change it, strict code gets a TypeError, and a
 * parameter or var of the same name shadows it. The expected string is Node 26.11 output.
 */
class FunctionNameBindingTest {
    @Test
    fun theNameIsReadOnlyAndShadowable() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val result = cx.evaluateString(cx.initStandardObjects(), SOURCE, "function-name.js", 1, null)
            assertEquals("function,TypeError,function,function,TypeError,5,7,true,function,false,function:true,function,outer,55", ScriptRuntime.toString(result))
        } finally {
            Context.exit()
        }
    }

    private companion object {
        val SOURCE = """
        var out = [];
        var f = function F() { F = 1; F += 2; F++; return typeof F; }; out.push(f());
        var g = function G() { 'use strict'; try { G = 1; } catch (e) { return e.constructor.name; } }; out.push(g());
        var h = function H() { (() => { H = 1; })(); return typeof H; }; out.push(h());
        var i = function I() { eval('I = 1'); return typeof I; }; out.push(i());
        var j = function J() { 'use strict'; try { eval('J = 1'); } catch (e) { return e.constructor.name; } }; out.push(j());
        var k = function K() { var K = 5; return K; }; out.push(k());
        var l = function L(L) { return L; }; out.push(l(7));
        var m = function M(a = M) { return a === M; }; out.push(m());
        var n = function* N() { N = 1; yield typeof N; }; out.push(n().next().value);
        var p = function P() { return delete P; }; out.push(p());
        var q = function Q() { return [typeof Q, Q === q]; }; out.push(q().join(':'));
        var F = 'outer'; out.push((function F() { return typeof F; })(), F);
        var fib = function fib(x) { return x < 2 ? x : fib(x - 1) + fib(x - 2); }; out.push(fib(10));
        out.join();
        """
    }
}
