/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/**
 * test262 on every target, against the outcomes the JVM parity run recorded.
 *
 * The JVM run is what compares this engine with upstream. This one asks a narrower question: does
 * the same file reach the same verdict off the JVM? Every difference here is a platform bug, since
 * the code is identical and only the runtime underneath has changed.
 *
 * It needs `./gradlew test262Parity` to have run, which is what writes the expectations. Without
 * them it says so and returns, so a plain check still builds on a machine that has never fetched
 * the suite.
 */
class Test262SliceTest {

    private val fs = SystemFileSystem

    /**
     * The one operation that is not the port's own, so the one place targets can disagree.
     * `String.prototype.toLowerCase` calls Kotlin's `lowercase()`, which is the platform's: the
     * JVM applies the conditional special casing for a Greek final sigma and JS and Native do not.
     * The JVM answer is upstream's, so the parity run is clean and this one is not (D-60).
     */
    private val knownPlatformDifferences = setOf(
        "built-ins/String/prototype/toLowerCase/special_casing_conditional.js",
        "built-ins/String/prototype/toLocaleLowerCase/special_casing_conditional.js",
    )

    private fun read(path: String): String = fs.source(Path(path)).buffered().use { it.readString() }

    /**
     * A browser has no file system at all, and asking kotlinx-io for one there throws rather than
     * answering false. The slice needs the suite on disk, so a target that cannot reach a disk
     * skips it the same way a machine that has not fetched the suite does.
     */
    private fun exists(path: String): Boolean = try {
        fs.exists(Path(path))
    } catch (e: UnsupportedOperationException) {
        false
    }

    @Test
    fun everyTargetReachesTheOutcomesTheJvmRecorded() {
        if (!exists(TEST262_EXPECTATIONS)) {
            println("test262 expectations not found; run ./gradlew test262Parity first. Skipping.")
            return
        }
        if (!exists("$TEST262_ROOT/test")) {
            println("test262 not reachable from here; run tools/fetch-test262.sh. Skipping.")
            return
        }

        val harnessCache = HashMap<String, String>()
        fun harness(name: String): String = harnessCache.getOrPut(name) { read("$TEST262_ROOT/harness/$name") }

        val differences = mutableListOf<String>()
        var checked = 0

        for (line in read(TEST262_EXPECTATIONS).lineSequence()) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size < 3) continue
            val (relative, mode) = parts
            val expected = parts[2]

            val source = read("$TEST262_ROOT/test/$relative")
            val meta = Test262FrontMatter.parse(source)
            val actual = Test262Execution.run(
                relative,
                source,
                meta,
                strict = mode == "strict",
                harness = meta.harnessFiles().map { harness(it) },
            )
            checked++
            if (relative in knownPlatformDifferences) continue
            if (actual != expected) {
                differences.add("$relative [$mode]\n  jvm:  $expected\n  here: $actual")
            }
        }

        println("test262 on this target: $checked cases, ${differences.size} differ from the JVM")
        assertTrue(checked > 0, "the expectations file was empty")
        assertEquals(
            emptyList(),
            differences.take(REPORTED),
            "${differences.size} cases behave differently here than on the JVM",
        )
    }

    private companion object {
        /** Enough to see the shape of a problem without an unreadable failure message. */
        const val REPORTED = 30
    }
}
