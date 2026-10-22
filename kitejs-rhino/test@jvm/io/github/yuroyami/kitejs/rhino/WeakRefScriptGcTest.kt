/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A script's `WeakRef` lets its target go once the job queue drains, and the registry's callback
 * then runs with the held value (#89). Only the JVM lets a test ask for a collection.
 */
class WeakRefScriptGcTest {
    @Test
    fun aCollectedTargetDerefsToUndefinedAndRunsTheCleanup() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            cx.evaluateString(scope, SETUP, "setup.js", 1, null)
            // The constructor kept the target alive until the drain that ends the script.
            cx.processMicrotasks()
            var state = ""
            repeat(50) {
                System.gc()
                Thread.sleep(10)
                cx.processMicrotasks()
                state = ScriptRuntime.toString(cx.evaluateString(scope, CHECK, "check.js", 1, null))
                if (state == "undefined|held") return@repeat
            }
            assertEquals("undefined|held", state)
        } finally {
            Context.exit()
        }
    }

    @Test
    fun aLiveTargetStaysAndRunsNoCleanup() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            cx.evaluateString(scope, "var live = {}; var ref = new WeakRef(live); var calls = [];" +
                "var reg = new FinalizationRegistry(h => calls.push(h)); reg.register(live, 'x');", "live.js", 1, null)
            repeat(5) {
                System.gc()
                Thread.sleep(5)
                cx.processMicrotasks()
            }
            assertEquals("true|0", ScriptRuntime.toString(cx.evaluateString(scope, "(ref.deref() === live) + '|' + calls.length", "check.js", 1, null)))
        } finally {
            Context.exit()
        }
    }

    private companion object {
        const val SETUP = """
            var calls = [];
            var registry = new FinalizationRegistry(function (held) { calls.push(held); });
            var ref = (function () { var o = {}; registry.register(o, 'held'); return new WeakRef(o); })();
        """
        const val CHECK = "String(ref.deref()) + '|' + calls.join()"
    }
}
