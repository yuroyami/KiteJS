/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mozilla.javascript.Context as UContext
import org.mozilla.javascript.Script as UScript
import org.mozilla.javascript.Scriptable as UScriptable

/**
 * test262 through both engines, comparing outcomes.
 *
 * The point is not a pass rate. It is that for every file upstream runs, the port passes exactly
 * when upstream passes. A test both engines fail is fine and expected: upstream has no classes and
 * no modules either. A test where they disagree is either a bug to fix or a ledger entry.
 *
 * The suite is fetched by `tools/fetch-test262.sh` at the commit upstream pins. This test reports
 * that it was skipped rather than failing when the tree is not there, so a checkout without it
 * still builds.
 *
 * Run with `./gradlew test262Parity`. `-Dtest262.filter=built-ins/Array` narrows it while working.
 */
class Test262ParityTest {

    private val testRoot = File("../reference/test262/test")
    private val harnessRoot = File("../reference/test262/harness")
    private val propertiesFile = File("src/jvmTest/resources/test262.properties")

    /** Upstream's list, copied. A test needing any of these is not run by either engine. */
    private val unsupportedFeatures = setOf(
        "Atomics", "IsHTMLDDA", "async-functions", "async-iteration", "class",
        "class-fields-private", "class-fields-public", "default-arg", "new.target",
        "object-rest", "regexp-dotall", "regexp-unicode-property-escapes",
        "resizable-arraybuffer", "SharedArrayBuffer", "tail-call-optimization", "Temporal",
        "upsert", "u180e",
    )

    /**
     * Files where the two engines are known to disagree, with the reason. Each one is a ledger
     * entry, and each is asserted to still differ: if upstream changes, the entry goes stale and
     * this test says so rather than quietly passing.
     */
    private val knownDifferences = mapOf(
        "built-ins/Date/prototype/Symbol.toPrimitive/prop-desc.js" to
            "D-56: the port makes Date.prototype[Symbol.toPrimitive] non-writable, as the spec asks. " +
            "Upstream leaves it writable.",
    )

    private val upstreamHarness = HashMap<String, UScript>()
    private val portedHarness = HashMap<String, Script>()

    @Test
    fun theSuiteBehavesTheSameOnBothEngines() {
        if (!testRoot.isDirectory) {
            println("test262 not fetched; run tools/fetch-test262.sh. Skipping parity run.")
            return
        }

        val properties = Test262Properties.load(propertiesFile, testRoot)
        val filter = System.getProperty("test262.filter") ?: ""

        val differences = mutableListOf<String>()
        val staleExpectations = mutableListOf<String>()
        val agreedButExpectedToDiffer = mutableListOf<String>()
        var ran = 0
        var skipped = 0
        // Counting outcomes, not just differences: a run where both engines pass everything and a
        // run where the harness quietly does nothing both report zero differences otherwise.
        var passed = 0
        var failedBoth = 0

        for (file in testRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".js") }.sorted()) {
            val relative = file.relativeTo(testRoot).path.replace('\\', '/')
            if (filter.isNotEmpty() && !relative.startsWith(filter)) continue
            // _FIXTURE files are imported by other tests, never run on their own.
            if (relative.endsWith("_FIXTURE.js")) continue
            if (properties.isSkipped(relative)) { skipped++; continue }

            val source = file.readText()
            val meta = Test262FrontMatter.parse(source)
            if (meta.features.any { it in unsupportedFeatures }) { skipped++; continue }
            // Neither engine has modules, and async needs a host event loop the runner has not got.
            if (meta.hasFlag("module") || meta.hasFlag("async")) { skipped++; continue }

            val modes = buildList {
                if (!meta.hasFlag("onlyStrict")) add(false)
                if (!meta.hasFlag("noStrict") && !meta.hasFlag("raw")) add(true)
            }

            for (strict in modes) {
                ran++
                val upstream = runUpstream(relative, source, meta, strict)
                val ported = runPorted(relative, source, meta, strict)
                val known = relative in knownDifferences
                if (upstream != ported) {
                    if (!known) {
                        val mode = if (strict) "strict" else "non-strict"
                        differences.add("$relative [$mode]\n  upstream: $upstream\n  ported:   $ported")
                    }
                } else if (known) {
                    agreedButExpectedToDiffer.add(relative)
                } else if (upstream == PASS) {
                    passed++
                } else {
                    failedBoth++
                }
                if (!strict && relative in properties.expectedToFail && upstream == PASS) {
                    staleExpectations.add(relative)
                }
            }
        }

        println(
            "test262 parity: ran $ran cases, skipped $skipped files. " +
                "$passed agree passing, $failedBoth agree failing, ${differences.size} differ.",
        )
        assertTrue(ran > 0, "no test262 cases ran; is the filter '$filter' right?")

        if (staleExpectations.isNotEmpty()) {
            println("${staleExpectations.size} files are marked as failing but upstream passes them:")
            staleExpectations.take(20).forEach { println("  $it") }
        }

        assertEquals(
            emptyList(),
            agreedButExpectedToDiffer.distinct(),
            "these are listed as known differences but the two engines now agree; drop the entry",
        )
        assertEquals(
            emptyList(),
            differences.take(MAX_REPORTED),
            "the port and upstream disagree on ${differences.size} cases",
        )
    }

    // ---- Running one case on each engine --------------------------------------------------------

    private fun runUpstream(path: String, source: String, meta: Test262FrontMatter, strict: Boolean): String {
        val cx = UContext.enter()
        try {
            cx.languageVersion = UContext.VERSION_ES6
            cx.isInterpretedMode = true
            val scope = cx.initSafeStandardObjects(org.mozilla.javascript.TopLevel(), false)
            for (name in meta.harnessFiles()) {
                upstreamHarness.getOrPut(name) {
                    cx.compileString(harnessSource(name), "harness/$name", 1, null)
                }.exec(cx, scope, scope)
            }
            Test262Host.installUpstream(cx, scope)

            var failedEarly = true
            return try {
                val text = if (strict) "\"use strict\";\n$source" else source
                val script = cx.compileString(text, path, if (strict) 0 else 1, null)
                failedEarly = false
                script.exec(cx, scope, scope)
                if (meta.isNegative) unexpectedPass(meta) else PASS
            } catch (e: org.mozilla.javascript.RhinoException) {
                judge(meta, errorNameUpstream(e), failedEarly)
            }
        } catch (e: Throwable) {
            return crash(e)
        } finally {
            UContext.exit()
        }
    }

    private fun runPorted(path: String, source: String, meta: Test262FrontMatter, strict: Boolean): String {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initSafeStandardObjects(TopLevel(), false)
            for (name in meta.harnessFiles()) {
                portedHarness.getOrPut(name) {
                    cx.compileString(harnessSource(name), "harness/$name", 1, null)
                }.exec(cx, scope, scope)
            }
            Test262Host.installPorted(cx, scope)

            var failedEarly = true
            return try {
                val text = if (strict) "\"use strict\";\n$source" else source
                val script = cx.compileString(text, path, if (strict) 0 else 1, null)
                failedEarly = false
                script.exec(cx, scope, scope)
                if (meta.isNegative) unexpectedPass(meta) else PASS
            } catch (e: RhinoException) {
                judge(meta, errorNamePorted(e), failedEarly)
            }
        } catch (e: Throwable) {
            return crash(e)
        } finally {
            Context.exit()
        }
    }

    // ---- Turning what happened into a string the two engines can be compared on -----------------

    /** Both engines answer this the same way when they behave the same way. */
    private fun judge(meta: Test262FrontMatter, errorName: String, failedEarly: Boolean): String {
        if (!meta.isNegative) return "threw $errorName"
        if (meta.hasEarlyError && !failedEarly) return "expected an early ${meta.negativeType}, got $errorName at runtime"
        if (errorName != meta.negativeType) return "expected ${meta.negativeType}, got $errorName"
        return PASS
    }

    private fun unexpectedPass(meta: Test262FrontMatter): String =
        "expected ${meta.negativeType} at ${meta.negativePhase} but nothing was thrown"

    /** A crash is not a JavaScript outcome, so it is reported by type and never by message. */
    private fun crash(e: Throwable): String = "crashed with ${e::class.simpleName}"

    private fun errorNameUpstream(e: org.mozilla.javascript.RhinoException): String {
        if (e is org.mozilla.javascript.EvaluatorException) return "SyntaxError"
        return e.details().substringBefore(":")
    }

    private fun errorNamePorted(e: RhinoException): String {
        if (e is EvaluatorException) return "SyntaxError"
        return e.details().substringBefore(":")
    }

    private val harnessCache = HashMap<String, String>()

    private fun harnessSource(name: String): String =
        harnessCache.getOrPut(name) { File(harnessRoot, name).readText() }

    private companion object {
        const val PASS = "passed"

        /** Enough differences to see the shape of a problem without an unreadable failure. */
        const val MAX_REPORTED = 40
    }
}
