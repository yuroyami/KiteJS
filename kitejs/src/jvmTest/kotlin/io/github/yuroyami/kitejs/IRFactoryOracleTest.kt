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
import org.mozilla.javascript.Parser as UParser

/**
 * The phase 2 acceptance test. Every corpus file is parsed and lowered to IR by both the upstream
 * Rhino jar and this port, and the two IR trees must be identical.
 *
 * `Node.toStringTree` is not used for the comparison: upstream gates it behind the
 * `rhino.printTrees` system property, which is read once at class-init time. Both trees are walked
 * through the public API instead, which is reachable and says exactly what is being asserted.
 */
class IRFactoryOracleTest {

    private fun corpusFiles(): List<File> {
        val url = javaClass.classLoader.getResource("corpus")
            ?: error("corpus resources are missing from the test classpath")
        return File(url.toURI()).listFiles { f: File -> f.name.endsWith(".js") }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    private fun upstreamIr(source: String, version: Int): String {
        val env = UCompilerEnvirons()
        env.languageVersion = version
        val ast = UParser(env).parse(source, "corpus.js", 1)
        val irf = UIRFactory(env, "corpus.js", source, env.errorReporter)
        return IrDump.upstream(irf.transformTree(ast))
    }

    private fun portedIr(source: String, version: Int): String {
        val env = CompilerEnvirons()
        env.languageVersion = version
        val ast = Parser(env).parse(source, "corpus.js", 1)
        val irf = IRFactory(env, "corpus.js", source, env.errorReporter)
        return IrDump.ported(irf.transformTree(ast))
    }

    private fun checkCorpus(upstreamVersion: Int, portedVersion: Int) {
        val failures = mutableListOf<String>()
        var compared = 0
        for (file in corpusFiles()) {
            val source = file.readText()
            val expected = runCatching { upstreamIr(source, upstreamVersion) }
            val actual = runCatching { portedIr(source, portedVersion) }

            if (expected.isFailure) {
                // Both have to reject it, otherwise the port is more permissive.
                if (actual.isSuccess) {
                    failures.add("${file.name}: upstream rejects it but the port accepts it")
                }
                continue
            }
            if (actual.isFailure) {
                failures.add(
                    "${file.name}: upstream lowers it but the port threw ${actual.exceptionOrNull()}",
                )
                continue
            }
            compared++
            if (expected.getOrThrow() != actual.getOrThrow()) {
                failures.add(
                    "${file.name}: IR " +
                        IrDump.describeDifference(expected.getOrThrow(), actual.getOrThrow()),
                )
            }
        }
        assertTrue(compared > 0, "nothing was compared at language version $upstreamVersion")
        assertEquals(emptyList(), failures, "IR differs at language version $upstreamVersion")
    }

    @Test
    fun corpusLowersIdenticallyAtEs6() {
        checkCorpus(UContext.VERSION_ES6, Context.VERSION_ES6)
    }

    @Test
    fun corpusLowersIdenticallyAtDefaultVersion() {
        checkCorpus(UContext.VERSION_DEFAULT, Context.VERSION_DEFAULT)
    }

    @Test
    fun functionTablesMatchUpstream() {
        // The nested-function table and its activation flags drive codegen, so compare them too.
        val failures = mutableListOf<String>()
        for (file in corpusFiles()) {
            val source = file.readText()

            val uenv = UCompilerEnvirons()
            uenv.languageVersion = UContext.VERSION_ES6
            val uast = runCatching { UParser(uenv).parse(source, "corpus.js", 1) }
            if (uast.isFailure) continue
            val utree = UIRFactory(uenv, "corpus.js", source, uenv.errorReporter)
                .transformTree(uast.getOrThrow()) ?: continue

            val kenv = CompilerEnvirons()
            kenv.languageVersion = Context.VERSION_ES6
            val kast = Parser(kenv).parse(source, "corpus.js", 1)
            val ktree = IRFactory(kenv, "corpus.js", source, kenv.errorReporter)
                .transformTree(kast) ?: error("${file.name}: the port produced no tree")

            val expected = buildString {
                append("functions=").append(utree.functionCount).append('\n')
                for (i in 0 until utree.functionCount) {
                    val fn = utree.getFunctionNode(i)
                    append(fn.name).append(' ')
                        .append(fn.functionType).append(' ')
                        .append(fn.requiresActivation()).append(' ')
                        .append(fn.requiresArgumentObject()).append(' ')
                        .append(fn.isGenerator).append(' ')
                        .append(fn.paramCount).append('\n')
                }
                append("symbols=").append(utree.symbols.size).append('\n')
                append("regexps=").append(utree.regexpCount).append('\n')
                append("templates=").append(utree.templateLiteralCount).append('\n')
            }
            val actual = buildString {
                append("functions=").append(ktree.functionCount).append('\n')
                for (i in 0 until ktree.functionCount) {
                    val fn = ktree.getFunctionNode(i)
                    append(fn.name).append(' ')
                        .append(fn.functionType).append(' ')
                        .append(fn.requiresActivation).append(' ')
                        .append(fn.requiresArgumentObject).append(' ')
                        .append(fn.isGenerator).append(' ')
                        .append(fn.paramCount).append('\n')
                }
                append("symbols=").append(ktree.symbols.size).append('\n')
                append("regexps=").append(ktree.regexpCount).append('\n')
                append("templates=").append(ktree.templateLiteralCount).append('\n')
            }
            if (expected != actual) {
                failures.add("${file.name}:\n  upstream: $expected\n  ported:   $actual")
            }
        }
        assertEquals(emptyList(), failures, "function tables differ from upstream")
    }
}
