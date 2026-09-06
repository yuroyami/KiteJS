/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The corpus slice on every target. [EvalCorpusSlice] holds whole programs together with the
 * answer upstream Rhino gives; the JVM oracle checks those recorded answers are still upstream's,
 * and this test checks the port gives them on JVM, JS and native alike.
 */
class EvalCorpusTest {

    /**
     * A program whose answer only exists after the microtask queue has drained ends with
     * "// AFTER: <expression>", and that expression is evaluated as a second top call. The queue
     * drains when a top call returns, so this is the first point at which promises have settled.
     */
    private fun eval(source: String): String = ContextFactory.getGlobal().call { cx ->
        cx.languageVersion = Context.VERSION_ES6
        val scope = cx.initStandardObjects()
        val after = source.lineSequence().lastOrNull { it.startsWith("// AFTER:") }?.removePrefix("// AFTER:")?.trim()
        val v = try {
            val first = cx.evaluateString(scope, source, "corpus.js", 1)
            if (after == null) first else cx.evaluateString(scope, after, "corpus.js", 1)
        } catch (e: RhinoException) {
            return@call "throws " + e.details()
        }
        when {
            v == null -> "null"
            Undefined.isUndefined(v) -> "undefined"
            v is Boolean -> v.toString()
            v is Number -> ScriptRuntime.numberToString(v.toDouble(), 10)
            v is CharSequence -> v.toString()
            v is Scriptable -> "[object " + v.className + "]"
            else -> v.toString()
        }
    }

    @Test
    fun everyProgramGivesUpstreamsAnswer() {
        assertTrue(EvalCorpusSlice.programs.size >= 10)
        val failures = mutableListOf<String>()
        for (program in EvalCorpusSlice.programs) {
            val actual = eval(program.source)
            if (actual != program.expected) failures.add("${program.name}\n  expected: ${program.expected}\n  actual:   $actual")
        }
        assertEquals(emptyList(), failures, "corpus programs differ from the recorded upstream answers")
    }
}
