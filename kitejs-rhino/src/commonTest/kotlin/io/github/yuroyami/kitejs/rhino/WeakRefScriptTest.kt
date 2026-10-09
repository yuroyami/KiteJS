/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

/** The script-side `WeakRef` and `FinalizationRegistry` (#89): their API and type checks. The expected string is Node 26.11 output. */
class WeakRefScriptTest {
    @Test
    fun apiAndTypeChecks() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val result = cx.evaluateString(cx.initStandardObjects(), SOURCE, "weakref.js", 1, null)
            assertEquals(EXPECTED, ScriptRuntime.toString(result))
        } finally {
            Context.exit()
        }
    }

    private companion object {
        const val EXPECTED = "function,1,true,[object WeakRef],true,TypeError,TypeError,TypeError,TypeError,true,hi,true,function,1," +
            "[object FinalizationRegistry],2,1,,,TypeError,TypeError,TypeError,TypeError,TypeError,TypeError,true,false,false," +
            "constructor/register/unregister,constructor/deref"

        val SOURCE = """
        var out = [];
        function err(f) { try { f(); return 'none'; } catch (e) { return e.constructor.name; } }
        var o = {}; var w = new WeakRef(o);
        out.push(typeof WeakRef, WeakRef.length, w.deref() === o, Object.prototype.toString.call(w), w instanceof WeakRef);
        out.push(err(() => WeakRef(o)), err(() => new WeakRef(1)), err(() => new WeakRef(Symbol.for('x'))), err(() => WeakRef.prototype.deref.call({})));
        var s = Symbol('s'); out.push(new WeakRef(s).deref() === s);
        class MyRef extends WeakRef { hi() { return 'hi'; } }
        out.push(new MyRef(o).hi(), new MyRef(o).deref() === o);
        var calls = [];
        var r = new FinalizationRegistry(function (held) { calls.push(held); });
        out.push(typeof FinalizationRegistry, FinalizationRegistry.length, Object.prototype.toString.call(r));
        out.push(r.register.length, r.unregister.length, r.register(o, 'held'), r.register({}, 1, o));
        out.push(err(() => r.register(1)), err(() => r.register(o, o)), err(() => r.register({}, 1, 1)), err(() => r.unregister(1)));
        out.push(err(() => FinalizationRegistry(function () {})), err(() => new FinalizationRegistry(1)));
        var token = {};
        r.register({}, 'a', token); r.register({}, 'b', token);
        out.push(r.unregister(token), r.unregister(token), r.unregister({}));
        out.push(Object.getOwnPropertyNames(FinalizationRegistry.prototype).sort().join('/'), Object.getOwnPropertyNames(WeakRef.prototype).sort().join('/'));
        out.join();
        """
    }
}
