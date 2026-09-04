/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mozilla.javascript.CompilerEnvirons as UCompilerEnvirons
import org.mozilla.javascript.Context as UContext
import org.mozilla.javascript.Parser as UParser

/**
 * The phase 1 acceptance test. Every corpus file goes through the upstream Rhino parser and
 * through this port, and the two must agree: same accept-or-reject decision, and for an accepted
 * file the same rendered tree.
 *
 * `toSource` is the right comparison: it walks the whole tree, so a wrong node type, a missing
 * child, a bad operator or a mangled literal all surface as a text difference. A separate test
 * compares node positions, which `toSource` can hide.
 *
 * The corpus is checked at ES6 and at the default language version. Plenty of files use syntax
 * the default version does not allow; those are expected to be rejected by both parsers, which is
 * itself worth testing.
 */
class ParserOracleTest {

    private fun corpusFiles(): List<File> {
        val url = javaClass.classLoader.getResource("corpus")
            ?: error("corpus resources are missing from the test classpath")
        val dir = File(url.toURI())
        return dir.listFiles { f: File -> f.name.endsWith(".js") }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    private fun upstream(source: String, version: Int): String {
        val env = UCompilerEnvirons()
        env.languageVersion = version
        return UParser(env).parse(source, "corpus.js", 1).toSource()
    }

    private fun ported(source: String, version: Int): String {
        val env = CompilerEnvirons()
        env.languageVersion = version
        return Parser(env).parse(source, "corpus.js", 1).toSource()
    }

    private fun <T> attempt(block: () -> T): Result<T> = runCatching(block)

    @Test
    fun corpusIsPresent() {
        val files = corpusFiles()
        assertTrue(files.size >= 25, "expected a real corpus, found ${files.size} files")
    }

    @Test
    fun everyCorpusFileParsesAtEs6() {
        // The corpus is meant to be valid ES6 as Rhino 1.9.1 understands it. If upstream rejects a
        // file the corpus is wrong, not the port, and this says so directly.
        val rejected = corpusFiles().filter {
            attempt { upstream(it.readText(), UContext.VERSION_ES6) }.isFailure
        }
        assertEquals(
            emptyList(),
            rejected.map { it.name },
            "upstream rejects these corpus files at ES6, so the corpus needs fixing",
        )
    }

    @Test
    fun corpusParityAtEs6() {
        checkCorpus(UContext.VERSION_ES6, Context.VERSION_ES6)
    }

    @Test
    fun corpusParityAtDefaultVersion() {
        checkCorpus(UContext.VERSION_DEFAULT, Context.VERSION_DEFAULT)
    }

    private fun checkCorpus(upstreamVersion: Int, portedVersion: Int) {
        val failures = mutableListOf<String>()
        for (file in corpusFiles()) {
            val source = file.readText()
            val expected = attempt { upstream(source, upstreamVersion) }
            val actual = attempt { ported(source, portedVersion) }

            if (expected.isFailure) {
                // Both parsers have to reject it, otherwise the port is more permissive.
                if (actual.isSuccess) {
                    failures.add(
                        "${file.name}: upstream rejects it but the port accepts it",
                    )
                }
                continue
            }
            if (actual.isFailure) {
                failures.add("${file.name}: upstream accepts it but the port threw " +
                    "${actual.exceptionOrNull()}")
                continue
            }
            if (expected.getOrThrow() != actual.getOrThrow()) {
                failures.add(
                    "${file.name} differs\n--- upstream ---\n${expected.getOrThrow()}" +
                        "\n--- ported ---\n${actual.getOrThrow()}",
                )
            }
        }
        assertEquals(
            emptyList(),
            failures,
            "corpus parity failed at language version $upstreamVersion",
        )
    }

    @Test
    fun commentRecordingParityAtEs6() {
        val failures = mutableListOf<String>()
        for (file in corpusFiles()) {
            val source = file.readText()

            val uenv = UCompilerEnvirons()
            uenv.languageVersion = UContext.VERSION_ES6
            uenv.isRecordingComments = true
            val uroot = attempt { UParser(uenv).parse(source, "corpus.js", 1) }
            if (uroot.isFailure) continue

            val kenv = CompilerEnvirons()
            kenv.languageVersion = Context.VERSION_ES6
            kenv.recordingComments = true
            val kroot = Parser(kenv).parse(source, "corpus.js", 1)

            val expected = uroot.getOrThrow().comments?.map { it.value } ?: emptyList()
            val actual = kroot.comments?.map { it.value } ?: emptyList()
            if (expected != actual) {
                failures.add("${file.name}: comments differ\n  $expected\n  $actual")
            }
            if (uroot.getOrThrow().toSource() != kroot.toSource()) {
                failures.add("${file.name}: source differs while recording comments")
            }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun nodePositionsMatchUpstream() {
        val failures = mutableListOf<String>()
        for (file in corpusFiles()) {
            val source = file.readText()

            val uenv = UCompilerEnvirons()
            uenv.languageVersion = UContext.VERSION_ES6
            val uroot = attempt { UParser(uenv).parse(source, "corpus.js", 1) }
            if (uroot.isFailure) continue

            val kenv = CompilerEnvirons()
            kenv.languageVersion = Context.VERSION_ES6
            val kroot = Parser(kenv).parse(source, "corpus.js", 1)

            val upstreamShape = StringBuilder()
            uroot.getOrThrow().visit { node ->
                upstreamShape.append(node.getType())
                    .append(' ').append(node.getPosition())
                    .append(' ').append(node.getLength())
                    .append(' ').append(node.getLineno())
                    .append('\n')
                true
            }

            val portedShape = StringBuilder()
            kroot.visit { node ->
                portedShape.append(node.type)
                    .append(' ').append(node.position)
                    .append(' ').append(node.length)
                    .append(' ').append(node.lineno)
                    .append('\n')
                true
            }

            if (upstreamShape.toString() != portedShape.toString()) {
                failures.add("${file.name}: node types, positions, lengths or line numbers differ")
            }
        }
        assertEquals(emptyList(), failures)
    }
}
