/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Deeply nested source ends with the engine's own error on every target (#139). Upstream catches
 * the host's stack overflow and reports "Too deep recursion while parsing"; the port counts the
 * depth of its recursive descent instead, because native code cannot catch a stack overflow.
 */
class ParseDepthTest {

    private val deep = 100_000

    @Test
    fun deeplyNestedSourceIsAnErrorThatTheEngineReports() {
        for ((name, source) in listOf(
            "parentheses" to "(".repeat(deep) + "0" + ")".repeat(deep),
            "brackets" to "[".repeat(deep) + "]".repeat(deep),
            "blocks" to "{".repeat(deep) + "}".repeat(deep),
            "unary operators" to "!".repeat(deep) + "0",
            "new" to "new ".repeat(deep) + "Object",
        )) {
            KiteJs(Rhino).use { js ->
                val e = assertFailsWith<JsException>(name) { js.evaluate(source) }
                assertTrue(e.message!!.contains("Too deep recursion while parsing"), "$name: ${e.message}")
                // The engine is still usable afterwards.
                assertEquals("2", js.evaluate("1 + 1").asString())
            }
        }
    }

    @Test
    fun nestingThatRealScriptsUseStillParses() {
        KiteJs(Rhino).use { js ->
            assertEquals("0", js.evaluate("(".repeat(60) + "0" + ")".repeat(60)).asString())
            assertEquals("true", js.evaluate("!".repeat(200) + "1").asString())
            assertEquals("1", js.evaluate("var a = 0; " + "{".repeat(200) + "a = 1;" + "}".repeat(200) + " a").asString())
        }
    }
}
