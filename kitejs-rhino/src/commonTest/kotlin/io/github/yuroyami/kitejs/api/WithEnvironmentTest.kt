/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The object environment a `with` statement makes, as D-89 left it: a name is bound when the
 * object has the property (HasProperty, so a proxy's `has` trap decides) and its @@unscopables does
 * not block the name, and a binding that disappears before it is read or written is undefined, or
 * a ReferenceError in strict code. The expected values are V8's with two exceptions where V8
 * leaves the spec and test262 follows it: the trap order, where V8 skips GetBindingValue's second
 * `has` and resolves the assignment target after the right-hand side, and a sloppy write to a
 * binding the right-hand side deleted, which goes to the object (test262 S11.13.1_A5_T2) where V8
 * resolves the name again.
 */
class WithEnvironmentTest {

    private fun eval(source: String): String = KiteJs().use { js -> js.evaluate(source).asString() }

    @Test
    fun unscopables_hide_names() {
        assertEquals("string", eval("var keys = 'outer'; var r; with ([]) { r = typeof keys } r"))
        assertEquals("outer", eval("var values = 'outer'; var r; with (Array.prototype) { r = values } r"))
        assertEquals("outer:1:2", eval("var o = { x: 1, [Symbol.unscopables]: { x: true } }; var x = 'outer'; var r; with (o) { r = x; x = 2 } r + ':' + o.x + ':' + x"))
        assertEquals("1:1", eval("var o = { x: 1, [Symbol.unscopables]: { x: true } }; var x = 0; with (o) { x++ } o.x + ':' + x"))
        assertEquals("number", eval("try { throw 5 } catch (toString) { typeof toString }"))
    }

    @Test
    fun a_proxy_binds_only_what_its_has_trap_reports() {
        assertEquals(
            "1:has r,has y,get Symbol(Symbol.unscopables),has y,get y",
            eval("var log = []; var p = new Proxy({ y: 1 }, { has: function (t, k) { log.push('has ' + String(k)); return k in t }, get: function (t, k) { log.push('get ' + String(k)); return t[k] } }); var y = 'outer'; var r; with (p) { r = y } r + ':' + log.join()"),
        )
        assertEquals("outer:has r,has z", eval("var log = []; var p = new Proxy({}, { has: function (t, k) { log.push('has ' + String(k)); return false } }); var z = 'outer'; var r; with (p) { r = z } r + ':' + log.join()"))
        assertEquals("outer", eval("var p = new Proxy({}, {}); var w = 'outer'; var r; with (p) { r = w } r"))
        assertEquals("outer", eval("var p = new Proxy({ attr: 1 }, { has: function (t, k) { return k === 'attr' ? undefined : k in t } }); var attr = 'outer'; var r; with (p) { r = attr } r"))
    }

    @Test
    fun a_binding_that_disappears_is_a_reference_error_in_strict_code() {
        assertEquals("ReferenceError", eval("var scope = { x: 1 }; var r; with (scope) { r = (function () { 'use strict'; try { x = (delete scope.x, 2); return 'assigned:' + scope.x } catch (e) { return e.name } })() } r"))
        assertEquals("2", eval("var scope = { x: 1 }; with (scope) { x = (delete scope.x, 2) } String(scope.x)"))
        assertEquals(
            "undefined:1",
            eval("var n = 0; var env = { b: 1 }; Object.defineProperty(env, Symbol.unscopables, { get: function () { n++; delete env.b; return {} } }); var r; with (env) { r = b } String(r) + ':' + n"),
        )
    }

    @Test
    fun const_bindings_behind_a_with_still_refuse_writes() {
        assertEquals("TypeError", eval("try { (function () { const c = 1; with ({}) { c++ } })(); 'returned' } catch (e) { e.name }"))
        assertEquals("TypeError,TypeError", eval("var r = []; for (const k of [1, 2]) { try { k++ } catch (e) { r.push(e.name) } } r.join()"))
    }
}
