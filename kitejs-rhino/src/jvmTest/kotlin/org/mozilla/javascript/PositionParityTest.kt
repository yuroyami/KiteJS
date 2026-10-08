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

    /**
     * Tokens the port lexes differently on purpose, by source and token index: a keyword spelled
     * with an escape is a name (ECMAScript 2015, 11.6.2), `class` is a keyword of its own now
     * that the port parses classes (D-95), and `await` is a name the parser makes a keyword inside
     * async functions (D-97). yield is a name in sloppy code outside a generator (D-107).
     */
    private val intended = mapOf(
        ("\\u0069f \\u0041bc" to 0) to io.github.yuroyami.kitejs.rhino.Token.NAME,
        ("if else class let await yield undefined" to 2) to io.github.yuroyami.kitejs.rhino.Token.CLASS,
        ("if else class let await yield undefined" to 4) to io.github.yuroyami.kitejs.rhino.Token.NAME,
        ("if else class let await yield undefined" to 5) to io.github.yuroyami.kitejs.rhino.Token.NAME,
    )

    @Test
    fun tokenStreamMatchesUpstreamTokenByToken() {
        for (src in sources) {
            val uEnv = CompilerEnvirons()
            val uParser = Parser(uEnv)
            val u = TokenStream(uParser, null, src, 1)
            uParser.currentPos = u

            val kEnv = io.github.yuroyami.kitejs.rhino.CompilerEnvirons()
            val kParser = io.github.yuroyami.kitejs.rhino.Parser(kEnv)
            val k = io.github.yuroyami.kitejs.rhino.TokenStream(kParser, src, 1)
            kParser.currentPos = k

            var index = 0
            while (true) {
                val ut = u.getToken()
                val kt = k.getToken()
                val at = "source=<$src> token#$index"
                assertEquals(intended[src to index] ?: ut, kt, "token code at $at")
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
