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
import org.mozilla.javascript.IRFactory as UIRFactory
import org.mozilla.javascript.NodeTransformer as UNodeTransformer
import org.mozilla.javascript.Parser as UParser

/**
 * The transform pass is where loops become plain gotos with their unwinding, names resolve to
 * variable slots, and block scopes either flatten or turn into `with` objects. Every corpus file
 * goes through parse, lower and transform on both sides, and the resulting trees must match.
 *
 * Both the script tree and every nested function are compared, since the transform runs on each
 * function separately and the flattening decision differs per function.
 */
class NodeTransformerOracleTest {

    private fun corpusFiles(): List<File> {
        val url = javaClass.classLoader.getResource("corpus")
            ?: error("corpus resources are missing from the test classpath")
        return File(url.toURI()).listFiles { f: File -> f.name.endsWith(".js") }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    private fun upstreamTransformed(source: String, version: Int): String {
        val env = UCompilerEnvirons()
        env.languageVersion = version
        val ast = UParser(env).parse(source, "corpus.js", 1)
        val tree = UIRFactory(env, "corpus.js", source, env.errorReporter).transformTree(ast)!!
        UNodeTransformer().transform(tree, env)
        return buildString {
            append(IrDump.upstream(tree))
            for (i in 0 until tree.functionCount) {
                append("--- function ").append(i).append(" ---\n")
                append(IrDump.upstream(tree.getFunctionNode(i)))
            }
        }
    }

    private fun portedTransformed(source: String, version: Int): String {
        val env = CompilerEnvirons()
        env.languageVersion = version
        val ast = Parser(env).parse(source, "corpus.js", 1)
        val tree = IRFactory(env, "corpus.js", source, env.errorReporter).transformTree(ast)!!
        NodeTransformer().transform(tree, env)
        return buildString {
            append(IrDump.ported(tree))
            for (i in 0 until tree.functionCount) {
                append("--- function ").append(i).append(" ---\n")
                append(IrDump.ported(tree.getFunctionNode(i)))
            }
        }
    }

    private fun checkCorpus(upstreamVersion: Int, portedVersion: Int) {
        val failures = mutableListOf<String>()
        var compared = 0
        for (file in corpusFiles()) {
            val source = file.readText()
            val expected = runCatching { upstreamTransformed(source, upstreamVersion) }
            val actual = runCatching { portedTransformed(source, portedVersion) }

            if (expected.isFailure) {
                if (actual.isSuccess) {
                    failures.add("${file.name}: upstream rejects it but the port accepts it")
                }
                continue
            }
            if (actual.isFailure) {
                failures.add(
                    "${file.name}: upstream transforms it but the port threw " +
                        "${actual.exceptionOrNull()}",
                )
                continue
            }
            compared++
            if (expected.getOrThrow() != actual.getOrThrow()) {
                failures.add(
                    "${file.name}: " +
                        IrDump.describeDifference(expected.getOrThrow(), actual.getOrThrow()),
                )
            }
        }
        assertTrue(compared > 0, "nothing was compared at language version $upstreamVersion")
        assertEquals(
            emptyList(),
            failures,
            "the transform pass differs at language version $upstreamVersion",
        )
    }

    @Test
    fun corpusTransformsIdenticallyAtEs6() {
        checkCorpus(UContext.VERSION_ES6, Context.VERSION_ES6)
    }

    @Test
    fun corpusTransformsIdenticallyAtDefaultVersion() {
        checkCorpus(UContext.VERSION_DEFAULT, Context.VERSION_DEFAULT)
    }

    @Test
    fun strictModeTransformsIdentically() {
        // Strict mode changes SETNAME into STRICT_SETNAME, so run the corpus that way too.
        val failures = mutableListOf<String>()
        for (file in corpusFiles()) {
            val source = "'use strict';\n" + file.readText()

            val expected = runCatching { upstreamTransformed(source, UContext.VERSION_ES6) }
            val actual = runCatching { portedTransformed(source, Context.VERSION_ES6) }
            if (expected.isFailure) {
                if (actual.isSuccess) {
                    failures.add("${file.name}: upstream rejects it but the port accepts it")
                }
                continue
            }
            if (actual.isFailure) {
                failures.add("${file.name}: the port threw ${actual.exceptionOrNull()}")
                continue
            }
            if (expected.getOrThrow() != actual.getOrThrow()) {
                failures.add(
                    "${file.name}: " +
                        IrDump.describeDifference(expected.getOrThrow(), actual.getOrThrow()),
                )
            }
        }
        assertEquals(emptyList(), failures, "the transform pass differs in strict mode")
    }
}
