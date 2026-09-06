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
    private val knownDifferences = buildMap {
        // The port accepts identifier characters upstream rejects. Upstream asks the JDK's
        // Character.isJavaIdentifierStart, which is Java's rule and not JavaScript's; the port
        // uses its own generated ID_Start tables (D-43, D-57). The port is the correct one.
        for (version in listOf("5.2.0", "6.1.0", "7.0.0", "8.0.0", "11.0.0", "13.0.0", "15.0.0")) {
            put("language/identifiers/start-unicode-$version.js", "D-57: the port follows ID_Start, upstream follows Java identifiers")
        }

        // Tests upstream fails and the port passes. Nothing to fix here, but they are pinned so
        // that an upstream fix shows up as a stale entry rather than as silence (D-58).
        for (path in listOf(
            "built-ins/Proxy/construct/call-parameters.js",
            "built-ins/TypedArray/prototype/set/BigInt/bigint-tobiguint64.js",
            "built-ins/TypedArrayConstructors/ctors-bigint/object-arg/bigint-tobiguint64.js",
            "built-ins/TypedArrayConstructors/from/nan-conversion.js",
            "built-ins/TypedArrayConstructors/from/new-instance-from-sparse-array.js",
            "built-ins/TypedArrayConstructors/internals/Set/BigInt/bigint-tobiguint64.js",
            "built-ins/Proxy/construct/arguments-realm.js",
            "language/destructuring/binding/keyed-destructuring-property-reference-target-evaluation-order-with-bindings.js",
        )) {
            put(path, "D-58: upstream fails this and the port passes it")
        }

        // Upstream's getOwnPropertyDescriptor trap reads the target descriptor without a null
        // check and throws a NullPointerException. The port answers undefined (D-50).
        for (path in listOf(
            "built-ins/Object/getOwnPropertyDescriptors/proxy-undefined-descriptor.js",
            "built-ins/Proxy/getOwnPropertyDescriptor/result-is-undefined-targetdesc-is-undefined.js",
            "built-ins/Proxy/getOwnPropertyDescriptor/trap-is-null-target-is-proxy.js",
        )) {
            put(path, "D-50: upstream crashes where the port answers undefined")
        }

        // Date.prototype[Symbol.toPrimitive] is non-writable here and writable upstream (D-56).
        put(
            "built-ins/Date/prototype/Symbol.toPrimitive/prop-desc.js",
            "D-56: the port makes the property non-writable, as the spec asks",
        )

        // The two places the port is the weaker one, both for want of Unicode data that common
        // Kotlin has none of: there is no normalizer (D-38) and no collation (D-37).
        for (path in listOf(
            "built-ins/String/prototype/normalize/return-normalized-string.js",
            "built-ins/String/prototype/normalize/return-normalized-string-from-coerced-form.js",
            "built-ins/String/prototype/normalize/return-normalized-string-using-default-parameter.js",
        )) {
            put(path, "D-38: there is no Unicode normalizer in common Kotlin, so normalize returns its input")
        }
        put(
            "built-ins/String/prototype/localeCompare/15.5.4.9_CE.js",
            "D-37: there is no collation data, so localeCompare falls back to code unit order",
        )
    }

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
        val perFolder = HashMap<String, IntArray>()

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
                currentTest = relative
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
                val folder = relative.split('/').take(2).joinToString("/")
                val tally = perFolder.getOrPut(folder) { IntArray(2) }
                if (upstream == PASS && ported == PASS) tally[0]++
                tally[1]++
                if (!strict && relative in properties.expectedToFail && upstream == PASS) {
                    staleExpectations.add(relative)
                }
            }
        }

        println(
            "test262 parity: ran $ran cases, skipped $skipped files. " +
                "$passed agree passing, $failedBoth agree failing, ${differences.size} differ.",
        )

        // The whole list goes to a file: a long run is expensive, so nothing it learned is thrown
        // away just because the assertion message has to stay readable.
        if (differences.isNotEmpty()) {
            val report = File("build/test262/differences.txt")
            report.parentFile.mkdirs()
            report.writeText(differences.joinToString("\n\n"))
            println("full difference list: ${report.absolutePath}")

            if (portCrashByTest.isNotEmpty()) {
                val byTest = File("build/test262/crashes.txt")
                byTest.parentFile.mkdirs()
                byTest.writeText(
                    portCrashByTest.distinct().sortedBy { it.first }
                        .joinToString("\n") { "${it.first}\t${it.second}" },
                )
            }
            if (portCrashSites.isNotEmpty()) {
                println("where the port crashed:")
                portCrashSites.entries.sortedByDescending { it.value }.take(20)
                    .forEach { println("  ${it.value.toString().padStart(6)}  ${it.key}") }
            }
            println("differences by folder:")
            differences.groupingBy { it.substringBefore(" [").split('/').take(2).joinToString("/") }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(30)
                .forEach { println("  ${it.value.toString().padStart(6)}  ${it.key}") }
        }
        assertTrue(ran > 0, "no test262 cases ran; is the filter '$filter' right?")

        // A summary PORTING_STATUS can quote, so the numbers there come from a run.
        val summary = File("build/test262/summary.md")
        summary.parentFile.mkdirs()
        summary.writeText(
            buildString {
                appendLine("| Folder | Both pass | Cases |")
                appendLine("|---|---:|---:|")
                perFolder.entries.sortedBy { it.key }.forEach {
                    appendLine("| ${it.key} | ${it.value[0]} | ${it.value[1]} |")
                }
                appendLine()
                appendLine("Total: $passed of $ran cases pass on both engines, $failedBoth fail on both.")
            },
        )

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
            } catch (e: Throwable) {
                crash(e, record = true)
            }
        } catch (e: Throwable) {
            return crash(e, record = true)
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

    /** Where the port crashed, counted per site, so the report groups by cause not by test. */
    private val portCrashSites = HashMap<String, Int>()

    /** The site for each crashing test, so a site can be traced back to something runnable. */
    private val portCrashByTest = mutableListOf<Pair<String, String>>()

    /** Set while a case runs, so [crash] knows which test it belongs to. */
    private var currentTest = ""

    /**
     * A crash is not a JavaScript outcome, so it is compared by type alone and never by message or
     * line: the two engines are different code and would never agree on a frame. The port's crash
     * site is recorded on the side, which is what makes 100 failures readable as three bugs.
     */
    private fun crash(e: Throwable, record: Boolean = false): String {
        if (record) {
            val frame = e.stackTrace.firstOrNull { it.className.startsWith("io.github.yuroyami.kitejs") }
                ?: e.stackTrace.firstOrNull()
            val where = frame?.let {
                "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
            } ?: "unknown"
            val key = "${e::class.simpleName} at $where"
            portCrashSites[key] = (portCrashSites[key] ?: 0) + 1
            portCrashByTest.add(key to currentTest)
        }
        return "crashed with ${e::class.simpleName}"
    }

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
