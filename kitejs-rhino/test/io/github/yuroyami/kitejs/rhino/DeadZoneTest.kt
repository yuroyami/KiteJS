/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The temporal dead zone of `let`, `const` and class bindings (ECMAScript 2015, 13.3.1; #137). Every
 * expected value is what Node 22 gives for the same source.
 */
class DeadZoneTest {

    private fun eval(source: String): String {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ECMASCRIPT
            val scope = cx.initStandardObjects()
            return ScriptRuntime.toString(cx.evaluateString(scope, source, "tdz.js", 1, null))
        } finally {
            Context.exit()
        }
    }

    private fun check(expected: String, source: String) = assertEquals(expected, eval(source), source)

    @Test
    fun aBindingCannotBeReachedBeforeItsDeclaration() {
        check("ReferenceError", "function f() { try { x } catch (e) { return e.name } let x = 1; return 'no error' } f()")
        check("ReferenceError", "function f() { try { new C() } catch (e) { return e.name } class C {} } f()")
        check("ReferenceError", "function f() { try { typeof x } catch (e) { return e.name } let x } f()")
        check("ReferenceError", "function f() { try { x = 2 } catch (e) { return e.name } let x; return x } f()")
        check("ReferenceError", "function f() { try { x++ } catch (e) { return e.name } let x = 1 } f()")
        check("ReferenceError", "function f() { try { c } catch (e) { return e.name } const c = 1 } f()")
        check("ReferenceError", "(function () { 'use strict'; try { q = 1 } catch (e) { return e.name } let q })()")
        check("ReferenceError", "function f() { try { eval('x') } catch (e) { return e.name } let x } f()")
        check("ReferenceError", "{ try { bx } catch (e) { e.name } let bx = 1 }")
    }

    @Test
    fun aClosureMeetsTheDeadZoneWhenItRunsEarly() {
        check("ReferenceError", "function f() { var g = () => x; try { g() } catch (e) { return e.name } let x = 1 } f()")
        check("ReferenceError", "function f() { function h() { return w } try { h() } catch (e) { return e.name } let w = 1 } f()")
        check("1", "function f() { var g = () => x; let x = 1; return g() } f()")
        check("4", "function f() { var o = { m() { return x } }; let x = 4; return o.m() } f()")
        check("ReferenceError", "function* g() { yield x; let x } try { g().next() } catch (e) { e.name }")
    }

    @Test
    fun eachEntryStartsTheDeadZoneAgain() {
        check("ReferenceError,ReferenceError", "function f() { var r = []; for (var i = 0; i < 2; i++) { try { r.push(y) } catch (e) { r.push(e.name) } let y = i } return r.join() } f()")
        check("undefined,undefined,undefined", "function f() { var r = []; for (var i = 0; i < 3; i++) { let x; r.push(String(x)); x = i } return r.join() } f()")
        check("ReferenceError", "function f() { switch (1) { case 0: let x = 1; break; case 1: try { x } catch (e) { return e.name } } } f()")
        check("ReferenceError", "function f() { try { for (let i = i; ;) {} } catch (e) { return e.name } } f()")
    }

    @Test
    fun declaredBindingsWorkAsBefore() {
        check("undefined", "function f() { let x; return String(x) } f()")
        check("0,1,2", "function f() { var r = []; for (let i = 0; i < 3; i++) r.push(() => i); return r.map(g => g()).join() } f()")
        check("3,7", "function f() { var r = []; for (let [a, b] of [[1, 2], [3, 4]]) r.push(a + b); return r.join() } f()")
        check("a,b", "function f() { var r = []; for (let k in {a: 1, b: 2}) r.push(k); return r.join() } f()")
        check("1,2", "function f() { var r = []; for (const v of [1, 2]) r.push(v); return r.join() } f()")
        check("6", "function f() { let [a, b] = [1, 2]; let {c} = {c: 3}; return a + b + c } f()")
        check("4", "function f() { let x = 1; x++; x += 2; return x } f()")
        check("1,2", "function* g() { let x = 1; yield x; x++; yield x } [...g()].join()")
        check("function", "function f() { class A { m() { return B } } class B {} return typeof new A().m() } f()")
        check("2", "function f(a = 1) { let y = a + 1; return y } f()")
        check("2", "function f() { let x = 1; function inner() { return x } x = 2; return inner() } f()")
    }
}
