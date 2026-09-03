/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.test.Test
import kotlin.test.assertEquals
import org.mozilla.javascript.Token as RhinoToken

/**
 * Differential test against the upstream Rhino jar: every token constant the port
 * defines must hold the exact upstream value, because the interpreter reuses these
 * numbers as bytecodes.
 */
class TokenParityTest {

    @Test
    fun tokenConstantsMatchUpstream() {
        val pairs = listOf(
            "ERROR" to (Token.ERROR to RhinoToken.ERROR),
            "EOF" to (Token.EOF to RhinoToken.EOF),
            "EOL" to (Token.EOL to RhinoToken.EOL),
            "FIRST_BYTECODE_TOKEN" to (Token.FIRST_BYTECODE_TOKEN to RhinoToken.FIRST_BYTECODE_TOKEN),
            "RETURN" to (Token.RETURN to RhinoToken.RETURN),
            "BITOR" to (Token.BITOR to RhinoToken.BITOR),
            "EQ" to (Token.EQ to RhinoToken.EQ),
            "ADD" to (Token.ADD to RhinoToken.ADD),
            "MOD" to (Token.MOD to RhinoToken.MOD),
            "NEW" to (Token.NEW to RhinoToken.NEW),
            "GETPROP" to (Token.GETPROP to RhinoToken.GETPROP),
            "SETELEM_SUPER" to (Token.SETELEM_SUPER to RhinoToken.SETELEM_SUPER),
            "CALL" to (Token.CALL to RhinoToken.CALL),
            "NAME" to (Token.NAME to RhinoToken.NAME),
            "NUMBER" to (Token.NUMBER to RhinoToken.NUMBER),
            "STRING" to (Token.STRING to RhinoToken.STRING),
            "SHEQ" to (Token.SHEQ to RhinoToken.SHEQ),
            "REGEXP" to (Token.REGEXP to RhinoToken.REGEXP),
            "YIELD" to (Token.YIELD to RhinoToken.YIELD),
            "SUPER" to (Token.SUPER to RhinoToken.SUPER),
            "EXP" to (Token.EXP to RhinoToken.EXP),
            "BIGINT" to (Token.BIGINT to RhinoToken.BIGINT),
            "LAST_BYTECODE_TOKEN" to (Token.LAST_BYTECODE_TOKEN to RhinoToken.LAST_BYTECODE_TOKEN),
            "TRY" to (Token.TRY to RhinoToken.TRY),
            "SEMI" to (Token.SEMI to RhinoToken.SEMI),
            "LC" to (Token.LC to RhinoToken.LC),
            "RP" to (Token.RP to RhinoToken.RP),
            "ASSIGN" to (Token.ASSIGN to RhinoToken.ASSIGN),
            "ASSIGN_LOGICAL_OR" to (Token.ASSIGN_LOGICAL_OR to RhinoToken.ASSIGN_LOGICAL_OR),
            "ASSIGN_EXP" to (Token.ASSIGN_EXP to RhinoToken.ASSIGN_EXP),
            "ASSIGN_NULLISH" to (Token.ASSIGN_NULLISH to RhinoToken.ASSIGN_NULLISH),
            "HOOK" to (Token.HOOK to RhinoToken.HOOK),
            "INC" to (Token.INC to RhinoToken.INC),
            "FUNCTION" to (Token.FUNCTION to RhinoToken.FUNCTION),
            "IF" to (Token.IF to RhinoToken.IF),
            "WHILE" to (Token.WHILE to RhinoToken.WHILE),
            "VAR" to (Token.VAR to RhinoToken.VAR),
            "RESERVED" to (Token.RESERVED to RhinoToken.RESERVED),
            "BLOCK" to (Token.BLOCK to RhinoToken.BLOCK),
            "SCRIPT" to (Token.SCRIPT to RhinoToken.SCRIPT),
            "DOTDOT" to (Token.DOTDOT to RhinoToken.DOTDOT),
            "XMLATTR" to (Token.XMLATTR to RhinoToken.XMLATTR),
            "LET" to (Token.LET to RhinoToken.LET),
            "CONST" to (Token.CONST to RhinoToken.CONST),
            "DEBUGGER" to (Token.DEBUGGER to RhinoToken.DEBUGGER),
            "COMMENT" to (Token.COMMENT to RhinoToken.COMMENT),
            "METHOD" to (Token.METHOD to RhinoToken.METHOD),
            "ARROW" to (Token.ARROW to RhinoToken.ARROW),
            "TEMPLATE_LITERAL" to (Token.TEMPLATE_LITERAL to RhinoToken.TEMPLATE_LITERAL),
            "TEMPLATE_LITERAL_SUBST" to (Token.TEMPLATE_LITERAL_SUBST to RhinoToken.TEMPLATE_LITERAL_SUBST),
            "TAGGED_TEMPLATE_LITERAL" to (Token.TAGGED_TEMPLATE_LITERAL to RhinoToken.TAGGED_TEMPLATE_LITERAL),
            "DOTDOTDOT" to (Token.DOTDOTDOT to RhinoToken.DOTDOTDOT),
            "NULLISH_COALESCING" to (Token.NULLISH_COALESCING to RhinoToken.NULLISH_COALESCING),
            "QUESTION_DOT" to (Token.QUESTION_DOT to RhinoToken.QUESTION_DOT),
            "LAST_TOKEN" to (Token.LAST_TOKEN to RhinoToken.LAST_TOKEN),
        )
        for ((name, values) in pairs) {
            assertEquals(values.second, values.first, "Token.$name diverges from upstream")
        }
    }

    @Test
    fun typeToNameMatchesUpstreamAcrossTheWholeRange() {
        for (code in Token.FIRST_TOKEN..Token.LAST_TOKEN) {
            val upstream = try {
                RhinoToken.typeToName(code)
            } catch (e: IllegalStateException) {
                null
            }
            val ported = try {
                Token.typeToName(code)
            } catch (e: IllegalStateException) {
                null
            }
            assertEquals(upstream, ported, "typeToName($code) diverges from upstream")
        }
    }
}
