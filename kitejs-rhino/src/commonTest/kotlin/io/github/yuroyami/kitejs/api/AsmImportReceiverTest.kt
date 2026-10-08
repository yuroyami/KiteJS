/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.BaseFunction
import io.github.yuroyami.kitejs.rhino.Callable
import io.github.yuroyami.kitejs.rhino.Context
import io.github.yuroyami.kitejs.rhino.ContextFactory
import io.github.yuroyami.kitejs.rhino.Rhino
import io.github.yuroyami.kitejs.rhino.Scriptable
import io.github.yuroyami.kitejs.rhino.ScriptableObject
import io.github.yuroyami.kitejs.rhino.asmReports
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** An imported function is called as a bare value, never as its own receiver. */
class AsmImportReceiverTest {
    private val module = """
        var m = (function (s, f) {
          "use asm";
          var cb = f.cb;
          function g() { return cb() | 0; }
          return { g: g };
        })({}, { cb: callback });
    """.trimIndent()

    private fun parity(expected: String, setup: String, call: String = "String(m.g())") {
        for (budget in listOf(0, 1_000_000)) for (enabled in listOf(false, true)) {
            KiteJs(Rhino) { asmJs = enabled; instructionBudget = budget }.use { js ->
                js.evaluate(setup + "\n" + module, "asm-import-receiver.js")
                assertEquals(expected, js.evaluate(call).asString(), "asmJs=$enabled budget=$budget")
                if (enabled) {
                    val report = js.asmReports.single()
                    assertTrue(report.compiled && report.linked, "$report")
                }
            }
        }
    }

    @Test
    fun sloppyCallbacksReceiveTheGlobalObject() = parity("1", """
        var callback = function () { return this === globalThis ? 1 : 2; };
    """)

    @Test
    fun callbacksReadGlobalPropertiesInsteadOfTheirOwnProperties() = parity("17", """
        var receiverValue = 17;
        var callback = function () { return this.receiverValue; };
        callback.receiverValue = 29;
    """)

    @Test
    fun strictCallbacksUseTheSameReceiverAsAnOrdinaryBareCall() = parity("1", """
        var seen, record = true;
        var callback = function () {
          "use strict";
          if (record) seen = this;
          return this === seen ? 1 : 2;
        };
        callback(); record = false;
    """)

    @Test
    fun boundAndArrowCallbacksKeepTheirOwnReceiver() {
        parity("23", """
            var callback = (function () { return this.value; }).bind({ value: 23 });
        """)
        parity("31", """
            var callback = (function () { return () => this.value; }).call({ value: 31 });
        """)
    }

    @Test
    fun receiverDependentErrorsMatchTheOrdinaryPath() = parity("global", """
        var callback = function () { throw this === globalThis ? 'global' : 'function'; };
    """, "try { m.g(); } catch (e) { String(e); }")

    @Test
    fun hostCallbacksReceiveTheOrdinaryBareCallReceiver() {
        for (scriptable in listOf(false, true)) for (enabled in listOf(false, true)) {
            val factory = object : ContextFactory() {
                override fun hasFeature(cx: Context, featureIndex: Int): Boolean =
                    if (featureIndex == Context.FEATURE_ASM_JS) enabled else super.hasFeature(cx, featureIndex)
            }
            factory.call { cx ->
                cx.languageVersion = Context.VERSION_ES6
                val scope = cx.initStandardObjects()
                val receivers = mutableListOf<Scriptable?>()
                val callback = if (scriptable) object : BaseFunction() {
                    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any {
                        receivers.add(thisObj)
                        return 7
                    }
                } else object : Callable {
                    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any {
                        receivers.add(thisObj)
                        return 7
                    }
                }
                ScriptableObject.putProperty(scope, "callback", callback)
                cx.evaluateString(scope, "callback();\n" + module + "\nm.g();", "asm-host-receiver.js", 1)
                assertEquals(2, receivers.size)
                assertSame(scope, receivers[0])
                assertSame(receivers[0], receivers[1], "scriptable=$scriptable asmJs=$enabled")
                if (enabled) assertTrue(cx.asmDiagnostics.single().linked)
            }
        }
    }
}
