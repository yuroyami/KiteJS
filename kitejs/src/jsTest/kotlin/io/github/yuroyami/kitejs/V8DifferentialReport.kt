/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.test.Test

/**
 * Runs the corpus through KiteJS and through the engine KiteJS is running on top of, and prints
 * where the two disagree.
 *
 * This reports, it never fails. Upstream Rhino disagrees with V8 all over the place: it has no
 * classes and no modules, and the port inherits that on purpose. What the report is for is showing
 * a reader where the engine stands against a modern one, and catching the occasional case where
 * KiteJS is wrong in a way upstream parity cannot see, because upstream is wrong the same way.
 */
class V8DifferentialReport {

    private fun runHost(source: String, after: String?): String = try {
        // Indirect eval, so the program gets the global scope rather than this function's.
        val indirect: dynamic = js("(0, eval)")
        val first = indirect(source)
        val value = if (after == null) first else indirect(after)
        "" + value
    } catch (e: Throwable) {
        "throws " + (e.message ?: e.toString())
    }

    private fun runKite(source: String, after: String?): String = ContextFactory.getGlobal().call { cx ->
        cx.languageVersion = Context.VERSION_ES6
        val scope = cx.initStandardObjects()
        try {
            val first = cx.evaluateString(scope, source, "corpus.js", 1)
            val value = if (after == null) first else cx.evaluateString(scope, after, "corpus.js", 1)
            when {
                value == null -> "null"
                Undefined.isUndefined(value) -> "undefined"
                value is Boolean -> value.toString()
                value is Number -> ScriptRuntime.numberToString(value.toDouble(), 10)
                value is CharSequence -> value.toString()
                else -> ScriptRuntime.toString(value)
            }
        } catch (e: RhinoException) {
            "throws " + e.details()
        }
    }

    @Test
    fun reportWhereKiteJsAndTheHostEngineDisagree() {
        val disagreements = mutableListOf<String>()
        var agreed = 0

        for (program in EvalCorpusSlice.programs) {
            val after = program.source.lineSequence()
                .lastOrNull { it.startsWith("// AFTER:") }
                ?.removePrefix("// AFTER:")?.trim()
            val ours = runKite(program.source, after)
            val theirs = runHost(program.source, after)
            if (ours == theirs) agreed++ else disagreements.add("${program.name}\n  KiteJS: $ours\n  host:   $theirs")
        }

        println("")
        println("KiteJS against the host engine, over ${EvalCorpusSlice.programs.size} corpus programs:")
        println("  $agreed agree, ${disagreements.size} differ")
        // Informational: upstream Rhino disagrees with a modern engine too, so this cannot be a gate.
        disagreements.forEach { println("  $it") }
        println("")
    }
}
