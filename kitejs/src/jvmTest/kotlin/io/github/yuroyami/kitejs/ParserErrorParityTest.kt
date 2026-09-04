/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.ast.ErrorCollector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mozilla.javascript.CompilerEnvirons as UCompilerEnvirons
import org.mozilla.javascript.Context as UContext
import org.mozilla.javascript.Parser as UParser
import org.mozilla.javascript.ast.ErrorCollector as UErrorCollector

/**
 * Malformed input has to fail the same way in both parsers: the same problems, in the same order,
 * with the same message text, offset and length.
 *
 * Everything runs in IDE mode, where the parser collects problems instead of throwing on the
 * first one. That reaches far more of the error-recovery paths than a throwing parse would.
 */
class ParserErrorParityTest {

    private val malformed = listOf(
        // Declarations and statements.
        "var = ;",
        "var 1 = 2;",
        "var a b;",
        "let;",
        "const;",
        "if () {}",
        "if (a {}",
        "while () {}",
        "do a(); while;",
        "for (;;",
        "for (a b c) {}",
        "switch a {}",
        "switch (a) { case: b(); }",
        "switch (a) { default: b(); default: c(); }",
        "try { a(); }",
        "try a(); catch (e) {}",
        "catch (e) {}",
        "finally {}",
        "throw;",
        "throw\n1;",
        "with a {}",
        // Functions.
        "function () {}",
        "function f( {}",
        "function f(1) {}",
        "function f() {",
        "function f(a,,b) {}",
        // Expressions.
        "a +;",
        "a = ;",
        "(a;",
        "a[;",
        "a.;",
        "a.1;",
        "1 = 2;",
        "a++++;",
        "++1;",
        "a ? b;",
        "a ? b : ;",
        // Literals.
        "var a = [1,;",
        "var a = {b};",
        "var a = {b:};",
        "var a = {1 2};",
        "var a = 'unterminated;",
        "var a = /unterminated;",
        // Labels and jumps.
        "break;",
        "continue;",
        "break nosuchlabel;",
        "continue nosuchlabel;",
        "a: a: b();",
        // Strict-mode problems.
        "'use strict'; var eval = 1;",
        "'use strict'; function f(a, a) {}",
        "'use strict'; with (a) {}",
        // Nesting and recovery.
        "function f() { var = ; } var b = ;",
        "if (a) { var = ; } else { var = ; }",
        "{{{",
        "}}}",
        // Empty and whitespace only.
        "",
        "   ",
        "\n\n",
    )

    private fun upstreamProblems(source: String): List<String> {
        val env = UCompilerEnvirons.ideEnvirons()
        env.languageVersion = UContext.VERSION_ES6
        val collector = env.errorReporter as UErrorCollector
        runCatching { UParser(env).parse(source, "err.js", 1) }
        return collector.errors.map { it.toString() }
    }

    private fun portedProblems(source: String): List<String> {
        val env = CompilerEnvirons.ideEnvirons()
        env.languageVersion = Context.VERSION_ES6
        val collector = env.errorReporter as ErrorCollector
        runCatching { Parser(env).parse(source, "err.js", 1) }
        return collector.errors.map { it.toString() }
    }

    @Test
    fun collectedProblemsMatchUpstream() {
        val failures = mutableListOf<String>()
        for (source in malformed) {
            val expected = upstreamProblems(source)
            val actual = portedProblems(source)
            if (expected != actual) {
                failures.add(
                    "source: ${source.replace("\n", "\\n")}" +
                        "\n  upstream: $expected" +
                        "\n  ported:   $actual",
                )
            }
        }
        assertEquals(emptyList(), failures, "error reporting differs from upstream")
    }

    @Test
    fun theseSourcesActuallyProduceErrors() {
        // Guards the test above against silently passing because nothing reported anything.
        val silent = malformed.filter { it.isNotBlank() && upstreamProblems(it).isEmpty() }
        assertTrue(
            silent.size <= 4,
            "expected most malformed sources to report a problem, these were silent: $silent",
        )
    }

    @Test
    fun throwingModeRaisesTheSameMessage() {
        // Outside IDE mode the parser throws instead of collecting. The message has to match too.
        val failures = mutableListOf<String>()
        for (source in malformed) {
            val uenv = UCompilerEnvirons()
            uenv.languageVersion = UContext.VERSION_ES6
            val expected =
                runCatching { UParser(uenv).parse(source, "err.js", 1) }
                    .exceptionOrNull()
                    ?.message

            val kenv = CompilerEnvirons()
            kenv.languageVersion = Context.VERSION_ES6
            val actual =
                runCatching { Parser(kenv).parse(source, "err.js", 1) }
                    .exceptionOrNull()
                    ?.message

            if (expected != actual) {
                failures.add(
                    "source: ${source.replace("\n", "\\n")}" +
                        "\n  upstream: $expected" +
                        "\n  ported:   $actual",
                )
            }
        }
        assertEquals(emptyList(), failures, "thrown error messages differ from upstream")
    }

    @Test
    fun strictModeWarningsMatchUpstream() {
        val warningSources = listOf(
            "if (a = 1) b();",
            "var a; var a;",
            "function f(a, a) {}",
            "a;",
            "1;",
            "var a = 1",
            "for (var i = 0; i < 1; i++) ;",
        )
        val failures = mutableListOf<String>()
        for (source in warningSources) {
            val expected = upstreamProblems(source)
            val actual = portedProblems(source)
            if (expected != actual) {
                failures.add(
                    "source: $source\n  upstream: $expected\n  ported:   $actual",
                )
            }
        }
        assertEquals(emptyList(), failures, "strict-mode warnings differ from upstream")
    }
}
