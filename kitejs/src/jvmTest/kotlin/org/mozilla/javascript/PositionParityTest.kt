/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

// Same-package trick: upstream TokenStream is package-private, and this differential
// test needs to drive it directly.
package org.mozilla.javascript

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Differential lexer test: run upstream Rhino and the port over the same sources and
 * compare every token code, token boundary, column and line number.
 */
class PositionParityTest {

    private val sources = listOf(
        "ab cd",
        "let x = 5",
        "a\nb",
        "a\r\nb",
        "1 + 2.5 * .5e2",
        "0x1F 0o17 0b101 0777 08 1_000",
        "'str' \"two\" x",
        "'a\\nb\\x41\\u0041\\u{1F600}'",
        "a?.b ?? c ??= d",
        "x ||= y &&= z ** w **= v",
        "=== !== >>> >>>= => ... .. .",
        "// comment\ncode",
        "/* block */ after",
        "/** jsdoc */ x",
        "<!-- html\nx",
        "a --> b",
        "\\u0069f \\u0041bc",
        "if else class let await yield undefined",
        "été \$dollar _under",
        "`abc` x",
        "{ } [ ] ( ) ; , : ~ %= @",
        "123n 0xFFn",
    )

    @Test
    fun tokenStreamMatchesUpstreamTokenByToken() {
        for (src in sources) {
            val uEnv = CompilerEnvirons()
            val uParser = Parser(uEnv)
            val u = TokenStream(uParser, null, src, 1)
            uParser.currentPos = u

            val kEnv = io.github.yuroyami.kitejs.CompilerEnvirons()
            val kParser = io.github.yuroyami.kitejs.Parser(kEnv)
            val k = io.github.yuroyami.kitejs.TokenStream(kParser, src, 1)
            kParser.currentPos = k

            var index = 0
            while (true) {
                val ut = u.getToken()
                val kt = k.getToken()
                val at = "source=<$src> token#$index"
                assertEquals(ut, kt, "token code at $at")
                assertEquals(u.tokenBeg, k.tokenBeg, "tokenBeg at $at")
                assertEquals(u.tokenEnd, k.tokenEnd, "tokenEnd at $at")
                assertEquals(u.tokenColumn, k.tokenColumn, "tokenColumn at $at")
                assertEquals(u.tokenStartLineno, k.tokenStartLineno, "tokenStartLineno at $at")
                assertEquals(u.lineno, k.lineno, "lineno at $at")
                if (ut == Token.NUMBER) {
                    assertEquals(u.number, k.number, "number at $at")
                }
                if (ut == Token.NAME || ut == Token.STRING) {
                    assertEquals(u.string, k.string, "string at $at")
                }
                if (ut == Token.EOF) break
                index++
            }
        }
    }
}
