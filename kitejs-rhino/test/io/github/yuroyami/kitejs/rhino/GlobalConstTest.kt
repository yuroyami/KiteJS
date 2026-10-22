/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A top-level `const` whose name the global object only inherits, such as `valueOf`, or holds as
 * a configurable property, such as `Array`. Upstream throws "redeclaration of var" for both.
 * The expected strings are Node 26.11 output.
 */
class GlobalConstTest {
    private fun run(vararg sources: String): String {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ECMASCRIPT
            val scope = cx.initStandardObjects()
            var result: Any? = null
            for (source in sources) {
                result = try {
                    cx.evaluateString(scope, source, "const.js", 1, null)
                } catch (e: RhinoException) {
                    "threw"
                }
            }
            return ScriptRuntime.toString(result)
        } finally {
            Context.exit()
        }
    }

    @Test
    fun anInheritedNameCanBeAConst() {
        assertEquals("1,2", run("const valueOf = 1; const toString = 2; [valueOf, toString].join()"))
    }

    @Test
    fun aConfigurableGlobalCanBeShadowed() {
        assertEquals("4", run("const parseInt = 3; parseInt + 1"))
        assertEquals("2", run("globalThis.h = 1", "const h = 2; h"))
    }

    @Test
    fun aVarOrAFixedGlobalStillClashes() {
        assertEquals("threw", run("var g = 1", "const g = 2"))
        assertEquals("threw", run("const NaN = 1"))
    }
}
