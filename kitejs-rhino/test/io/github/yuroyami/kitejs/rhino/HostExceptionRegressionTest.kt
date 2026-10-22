/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Scripts that once let a Kotlin exception out of the engine instead of a script error or a
 * result. The full test262 run found each of them. The expected strings are Node 26.11 output,
 * except `toSource`, which Node does not have.
 */
class HostExceptionRegressionTest {
    private fun run(cx: Context, scope: Scriptable, source: String): String =
        ScriptRuntime.toString(cx.evaluateString(scope, source, "host-exception.js", 1, null))

    @Test
    fun scriptsGetScriptResults() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ECMASCRIPT
            assertEquals(
                "TypeError,true,,1208925819614629174706176,-1000000000000000000000,309,true,true,TypeError,TypeError",
                run(cx, cx.initStandardObjects(), SOURCE),
            )
        } finally {
            Context.exit()
        }
    }

    /** A promise job runs outside any script call, and building a subclass instance there failed. */
    @Test
    fun aPromiseJobConstructsASubclass() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ECMASCRIPT
            val scope = cx.initStandardObjects()
            run(cx, scope, """
                var settled = 'pending';
                class P extends Promise { constructor(f) { super(f); } }
                P.resolve(1).finally(function () {}).then(function (v) { settled = 'done ' + v; });
            """)
            assertEquals("done 1", run(cx, scope, "settled"))
        } finally {
            Context.exit()
        }
    }

    private companion object {
        val SOURCE = """
        var out = [];
        var iter;
        function* g() { iter.next(); }
        iter = g();
        try { iter.next(); } catch (e) { out.push(e.constructor.name); }
        var r = iter.next();
        out.push(r.done, r.value);
        out.push(String(BigInt(2 ** 80)), String(BigInt(-1e21)), String(BigInt(Number.MAX_VALUE)).length);
        function f(a, b) { return a === b; }
        out.push(f(delete undefined?.(), true), [delete null?.()][0]);
        for (var name of ['Object', 'Error']) {
          try { this[name].prototype.toSource.call(null); } catch (e) { out.push(e.constructor.name); }
        }
        out.join();
        """
    }
}
