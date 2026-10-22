/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

// Deliberately declared in the upstream package: TokenStream is package-private in the
// Rhino jar, and same-package test sources are the sanctioned classpath trick to reach
// it for differential testing.
package org.mozilla.javascript

import kotlin.test.Test
import kotlin.test.assertEquals

class KeywordParityTest {

    @Test
    fun keywordClassificationMatchesUpstream() {
        val words = listOf(
            "break", "case", "catch", "class", "const", "continue", "debugger", "default",
            "delete", "do", "else", "enum", "export", "extends", "false", "finally", "for",
            "function", "if", "implements", "import", "in", "instanceof", "interface",
            "let", "new", "null", "package", "private", "protected", "public", "return",
            "static", "super", "switch", "this", "throw", "true", "try", "typeof",
            "undefined", "var", "void", "while", "with", "yield", "await",
            // java-reserved words that only matter pre-ES6
            "abstract", "boolean", "byte", "char", "double", "final", "float", "goto",
            "int", "long", "native", "short", "synchronized", "throws", "transient",
            "volatile",
            // non-keywords and case sensitivity
            "notakeyword", "Break", "IF", "", "awaits",
        )
        val versions = listOf(
            Context.VERSION_1_5,
            Context.VERSION_1_8,
            Context.VERSION_ES6,
            Context.VERSION_ECMASCRIPT,
        )
        for (word in words) {
            for (version in versions) {
                for (strict in listOf(false, true)) {
                    // `await` is a name the parser reads by context, the operator only inside an
                    // async function, where upstream's ES6 table reserves it everywhere (D-97).
                    // `let` is a contextual name in modern sloppy code (D-113).
                    val upstream = TokenStream.isKeyword(word, version, strict) &&
                        !(word == "await" && version >= Context.VERSION_ES6) &&
                        !(word == "let" && version >= Context.VERSION_ES6 && !strict)
                    assertEquals(
                        upstream,
                        io.github.yuroyami.kitejs.rhino.TokenStream.isKeyword(word, version, strict),
                        "isKeyword(\"$word\", $version, strict=$strict) diverges from upstream",
                    )
                }
            }
        }
    }
}
