/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenTest {

    @Test
    fun coreTokenValuesAnchorTheIcodeSpace() {
        // The interpreter reuses token values as bytecodes and the keyword tables assume
        // EOF == 0, so these exact numbers are load-bearing.
        assertEquals(-1, Token.ERROR)
        assertEquals(0, Token.EOF)
        assertEquals(1, Token.EOL)
        assertEquals(2, Token.FIRST_BYTECODE_TOKEN)
        assertEquals(Token.BIGINT, Token.LAST_BYTECODE_TOKEN)
        assertEquals(Token.ASSIGN, Token.FIRST_ASSIGN)
        assertEquals(Token.ASSIGN_NULLISH, Token.LAST_ASSIGN)
    }

    @Test
    fun typeToNameReturnsHumanReadableNames() {
        assertEquals("ERROR", Token.typeToName(Token.ERROR))
        assertEquals("EOF", Token.typeToName(Token.EOF))
        assertEquals("LP", Token.typeToName(Token.LP))
        assertEquals("ARROW", Token.typeToName(Token.ARROW))
        assertEquals("TEMPLATE_LITERAL", Token.typeToName(Token.TEMPLATE_LITERAL))
        assertEquals("QUESTION_DOT", Token.typeToName(Token.QUESTION_DOT))
        assertEquals("BIGINT", Token.typeToName(Token.BIGINT))
    }

    @Test
    fun typeToNameRejectsUnknownCodes() {
        assertFailsWith<IllegalStateException> { Token.typeToName(Token.LAST_TOKEN + 1) }
    }

    @Test
    fun keywordToNameMapsKeywordTokens() {
        assertEquals("break", Token.keywordToName(Token.BREAK))
        assertEquals("instanceof", Token.keywordToName(Token.INSTANCEOF))
        assertEquals("super", Token.keywordToName(Token.SUPER))
        assertNull(Token.keywordToName(Token.LP))
    }

    @Test
    fun isValidTokenChecksBounds() {
        assertTrue(Token.isValidToken(Token.ERROR))
        assertTrue(Token.isValidToken(Token.LAST_TOKEN))
        assertFalse(Token.isValidToken(Token.LAST_TOKEN + 1))
        assertFalse(Token.isValidToken(-2))
    }

    @Test
    fun nameWithoutDebugFlagsIsNumeric() {
        assertEquals(Token.LP.toString(), Token.name(Token.LP))
    }
}
