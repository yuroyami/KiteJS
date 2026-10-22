/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

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

    private fun portedTransformed(source: String, version: Int, optionalChainFixture: Boolean = false, iterationFixture: Boolean = false): String {
        val env = CompilerEnvirons()
        env.languageVersion = version
        val ast = Parser(env).parse(source, "corpus.js", 1)
        val tree = IRFactory(env, "corpus.js", source, env.errorReporter).transformTree(ast)!!
        NodeTransformer().transform(tree, env)
        if (optionalChainFixture) OptionalChainOracleExpectations.assertStructure(tree)
        if (iterationFixture) BlockFunctionOracleExpectations.assertIterationMarkers(tree)
        return buildString {
            append(IrDump.ported(tree, omitIterationMarkers = iterationFixture))
            for (i in 0 until tree.functionCount) {
                append("--- function ").append(i).append(" ---\n")
                append(IrDump.ported(tree.getFunctionNode(i)))
            }
        }
    }

    /**
     * Upstream assigns a named function expression's name at the start of its body. The port
     * binds the name in a scope around the function instead, so it has no such statement.
     */
    private fun withoutOwnNameAssignments(dump: String): String {
        val lines = dump.lines().toMutableList()
        var i = 0
        while (i + 3 < lines.size) {
            val own = lines[i].trim() == "149 ln=-1 col=-1" && lines[i + 1].trim() == "62 ln=-1 col=-1" &&
                lines[i + 2].trim().startsWith("46 str=") && lines[i + 3].trim() == "70 ln=-1 col=-1"
            if (own) repeat(4) { lines.removeAt(i) } else i++
        }
        return lines.joinToString("\n")
    }

    private fun checkCorpus(upstreamVersion: Int, portedVersion: Int) {
        val failures = mutableListOf<String>()
        var compared = 0
        for (file in corpusFiles()) {
            val source = file.readText()
            val expected = runCatching { withoutOwnNameAssignments(upstreamTransformed(source, upstreamVersion)) }
            val actual = runCatching { portedTransformed(source, portedVersion, file.name == "optional-chaining.js", file.name == "let-const.js" && portedVersion >= Context.VERSION_ES6) }

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
            if (file.name == "optional-chaining.js") continue // Its corrected structure is checked by portedTransformed.
            if (portedVersion >= Context.VERSION_ES6 && file.name == "destructuring.js") {
                // Patterns iterate and check their value (#81, #96); DestructuringIteratorTest
                // checks them against fixed Node results.
                assertTrue(expected.getOrThrow() != actual.getOrThrow())
                continue
            }
            if (portedVersion >= Context.VERSION_ES6 && file.name == "spread.js") {
                // D-116 changes these parameter bodies. Compare the enclosing script exactly;
                // ParameterEnvironmentTest checks their binding behavior against fixed V8 results.
                assertEquals(expected.getOrThrow().substringBefore("--- function"), actual.getOrThrow().substringBefore("--- function"))
                assertTrue(expected.getOrThrow() != actual.getOrThrow())
                continue
            }
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

            val expected = runCatching { withoutOwnNameAssignments(upstreamTransformed(source, UContext.VERSION_ES6)) }
            val actual = runCatching { portedTransformed(source, Context.VERSION_ES6, file.name == "optional-chaining.js", file.name == "let-const.js") }
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
            if (file.name == "destructuring.js") {
                assertTrue(expected.getOrThrow() != actual.getOrThrow())
                continue
            }
            if (file.name == "spread.js") {
                assertEquals(expected.getOrThrow().substringBefore("--- function"), actual.getOrThrow().substringBefore("--- function"))
                assertTrue(expected.getOrThrow() != actual.getOrThrow())
                continue
            }
            if (file.name != "optional-chaining.js" && expected.getOrThrow() != actual.getOrThrow()) {
                failures.add(
                    "${file.name}: " +
                        IrDump.describeDifference(expected.getOrThrow(), actual.getOrThrow()),
                )
            }
        }
        assertEquals(emptyList(), failures, "the transform pass differs in strict mode")
    }
}
