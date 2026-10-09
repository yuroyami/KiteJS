/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reads one validated artifact file, through Karma in browsers and the filesystem elsewhere. */
internal expect fun readTest262Bundle(name: String): String

/** Records the case about to run, where the target can write a file. */
internal expect fun markTest262Case(name: String)

/** Explicit corpus replay. Missing, stale, truncated or inaccessible inputs are failures. */
class Test262SliceTest {
    private val knownPlatformDifferences = setOf(
        "built-ins/String/prototype/toLowerCase/special_casing_conditional.js",
        "built-ins/String/prototype/toLocaleLowerCase/special_casing_conditional.js",
        // Case mapping comes from the host's Unicode tables, and JDK 21 has no Garay letters
        // (Unicode 16), which Node 26 maps.
        "staging/sm/String/string-code-point-upper-lower-mapping.js",
        // Runs out of memory everywhere, and each platform names that crash differently.
        "staging/sm/String/replace-math.js",
        // Two files whose outcome is not a property of the engine.
        // The first builds a BigInt of a size that fits the parity run's larger heap and not this
        // one's, so it passes there and runs out of memory here.
        "staging/sm/BigInt/large-bit-length.js",
        // The second throws an error whose message is a function's own source, and a function
        // prints its body differently once it is a built-in.
        "staging/sm/generators/delegating-yield-11.js",
    )

    private fun decode(text: String): String = Base64.decode(text).decodeToString()

    private fun json(text: String): String = buildString {
        append('"')
        for (c in text) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> if (c < ' ') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('"')
    }

    @Test
    fun everyTargetReachesTheOutcomesTheJvmRecorded() {
        assertEquals(TEST262_MANIFEST, readTest262Bundle("manifest.json"), "Replay manifest changed after validation")
        val harness = readTest262Bundle("harness.tsv").lineSequence().filter { it.isNotEmpty() }.associate { line ->
            val parts = line.split('\t')
            assertEquals(2, parts.size, "Malformed harness record")
            parts[0] to decode(parts[1])
        }
        val differences = mutableListOf<String>()
        val intentional = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var checked = 0
        var passed = 0
        var strictCases = 0
        for (shard in TEST262_SHARDS) {
            val records = readTest262Bundle(shard).lineSequence().filter { it.isNotEmpty() }
            for (line in records) {
                val parts = line.split('\t')
                assertEquals(4, parts.size, "Malformed replay record in $shard")
                val (relative, mode, outcome, encodedSource) = parts
                assertTrue(mode == "strict" || mode == "sloppy", "Invalid mode: $mode")
                assertTrue(seen.add("$relative [$mode]"), "Duplicate replay case: $relative [$mode]")
                val source = decode(encodedSource)
                val expected = decode(outcome)
                val meta = Test262FrontMatter.parse(source)
                markTest262Case("$relative [$mode]")
                val actual = Test262Execution.run(relative, source, meta, strict = mode == "strict",
                    harness = meta.harnessFiles().map { harness[it] ?: error("Missing harness: $it") })
                checked++
                if (mode == "strict") strictCases++
                if (actual == Test262Execution.PASS) passed++
                if (actual != expected) {
                    val detail = "$relative [$mode]: expected $expected; actual $actual"
                    if (relative in knownPlatformDifferences) intentional.add(detail) else differences.add(detail)
                }
            }
        }
        // A machine-readable record survives in each runner's XML output, including on browsers.
        // These are replay matches, not a claim that all selected cases conform to ECMAScript.
        println("TEST262_RESULT={" +
            "\"engineCommit\":" + json(TEST262_ENGINECOMMIT) +
            ",\"engineSources\":" + json(TEST262_ENGINESOURCES) +
            ",\"corpusCommit\":" + json(TEST262_CORPUSCOMMIT) +
            ",\"oracleVersion\":" + json(TEST262_ORACLEVERSION) +
            ",\"executed\":$checked,\"strict\":$strictCases,\"sloppy\":${checked - strictCases}" +
            ",\"passed\":$passed,\"nonPassing\":${checked - passed},\"mismatches\":${differences.size}" +
            ",\"intentionalDifferences\":[" + intentional.joinToString(",") { json(it) } + "]}")
        assertTrue(TEST262_CASE_COUNT > 0, "Replay denominator must be positive")
        assertEquals(TEST262_CASE_COUNT, checked, "Replay did not execute the exact selected denominator")
        assertEquals(emptyList(), differences.take(30), "${differences.size} cases differ from the recorded JVM outcomes")
    }
}
