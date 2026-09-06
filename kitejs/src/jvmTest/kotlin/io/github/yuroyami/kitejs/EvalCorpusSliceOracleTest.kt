/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.test.Test
import kotlin.test.assertEquals
import org.mozilla.javascript.Context as UContext

/**
 * Keeps [EvalCorpusSlice] honest: each recorded answer has to be what upstream Rhino gives today,
 * and each program has to be the file of the same name in `resources/eval`.
 */
class EvalCorpusSliceOracleTest {

    @Test
    fun recordedAnswersAreUpstreams() {
        val ucx = UContext.enter()
        try {
            ucx.languageVersion = UContext.VERSION_ES6
            ucx.isInterpretedMode = true
            val failures = mutableListOf<String>()
            for (program in EvalCorpusSlice.programs) {
                val scope = ucx.initStandardObjects()
                // A program that ends with "// AFTER: <expression>" is read back in a second top
                // call, which is when the microtask queue has drained.
                val after = program.source.lineSequence().lastOrNull { it.startsWith("// AFTER:") }?.removePrefix("// AFTER:")?.trim()
                val actual = try {
                    val first = ucx.evaluateString(scope, program.source, program.name, 1, null)
                    val v = if (after == null) first else ucx.evaluateString(scope, after, program.name, 1, null)
                    org.mozilla.javascript.ScriptRuntime.toString(v)
                } catch (e: org.mozilla.javascript.RhinoException) {
                    "throws " + e.details()
                }
                if (actual != program.expected) failures.add(program.name)
            }
            assertEquals(emptyList(), failures, "recorded answers are stale; regenerate EvalCorpusSlice")
        } finally {
            UContext.exit()
        }
    }

    @Test
    fun programsAreTheCorpusFiles() {
        val failures = mutableListOf<String>()
        for (program in EvalCorpusSlice.programs) {
            val file = java.io.File("src/jvmTest/resources/eval/${program.name}.js")
            if (!file.exists() || file.readText() != program.source) failures.add(program.name)
        }
        assertEquals(emptyList(), failures, "slice programs differ from the corpus files")
    }
}
