/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.Context
import io.github.yuroyami.kitejs.RhinoException
import io.github.yuroyami.kitejs.ScriptRuntime
import io.github.yuroyami.kitejs.Scriptable
import io.github.yuroyami.kitejs.Undefined
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The facade is a wrapper, not a second language. Every script here runs twice, once through
 * `KiteJs.evaluate` and once through `Context.evaluateString`, and the two answers have to match.
 * A difference means the facade grew semantics of its own, which it must not.
 */
class FacadeEquivalenceTest {

    /** The same rendering on both sides, so only a real difference shows up. */
    private fun render(v: Any?): String = when {
        v == null -> "null"
        Undefined.isUndefined(v) -> "undefined"
        v is Boolean -> v.toString()
        v is CharSequence -> "\"" + v.toString() + "\""
        v is Number -> ScriptRuntime.numberToString(v.toDouble(), 10)
        v is Scriptable -> "[object " + v.className + "]"
        else -> v.toString()
    }

    private fun throughTheEngine(source: String): String {
        val cx = Context.enter()
        return try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            render(cx.evaluateString(scope, source, "eq.js", 1))
        } catch (e: RhinoException) {
            "throws " + e.details()
        } finally {
            Context.exit()
        }
    }

    private fun throughTheFacade(source: String): String =
        KiteJs { languageVersion = LanguageVersion.ES6 }.use { js ->
            try {
                render(js.evaluate(source, "eq.js").raw)
            } catch (e: JsError) {
                "throws " + (if (e.name.isEmpty()) e.errorMessage else "${e.name}: ${e.errorMessage}")
            } catch (e: JsSyntaxError) {
                "throws " + e.message
            }
        }

    private fun check(sources: List<String>) {
        val differences = mutableListOf<String>()
        for (source in sources) {
            val engine = throughTheEngine(source)
            val facade = throughTheFacade(source)
            if (engine != facade) differences.add("$source\n  engine: $engine\n  facade: $facade")
        }
        assertTrue(sources.size > 10)
        assertEquals(emptyList(), differences, "the facade answered differently")
    }

    @Test
    fun theCorpusAnswersTheSameThroughBothDoors() {
        val files = File("src/jvmTest/resources/eval").listFiles { f: File -> f.extension == "js" }
        assertTrue(files != null && files.size > 30, "corpus not found")
        check(files!!.sortedBy { it.name }.map { it.readText() })
    }

    @Test
    fun everyKindOfResultRendersTheSame() = check(listOf(
        "1 + 1", "'a' + 'b'", "1 < 2", "null", "undefined", "void 0",
        "[1, 2, 3]", "({ a: 1 })", "(function () {})", "1n", "Symbol('s')",
        "NaN", "Infinity", "-Infinity", "-0", "0.1 + 0.2", "1e21", "2 ** 53",
        "new Date(0).toISOString()", "/ab+c/gi.source", "JSON.stringify({ a: [1, null] })",
        "[...new Set([1, 1, 2])].join()", "new Map([[1, 'a']]).get(1)",
        "String(Object.getOwnPropertyNames(Object.prototype).length > 5)",
        "(function* g() { yield 1 })().next().value",
        "Array.from({ length: 3 }, function (_, i) { return i }).join()",
    ))

    @Test
    fun errorsRenderTheSameThroughBothDoors() = check(listOf(
        "null.x", "undefined.x", "(void 0)()", "notDefined",
        "throw new TypeError('boom')", "throw new RangeError('r')", "throw new Error('plain')",
        "JSON.parse('{')", "[].reduce(function () {})", "new (function () {})().x.y",
        "(1).toString(40)", "Object.defineProperty(Object.freeze({}), 'a', { value: 1 })",
        "'use strict'; var o = Object.freeze({}); o.a = 1",
        "decodeURIComponent('%')", "new Array(-1)",
    ))

    @Test
    fun syntaxErrorsRenderTheSameThroughBothDoors() = check(listOf(
        "function (", "var 1a = 2", "{", "]", "for (;;", "'unterminated",
        "class", "let let = 1", "a => => b", "if (", "return 1", "break",
        "var x = ;", "({ a: })", "1 +",
    ))
}
