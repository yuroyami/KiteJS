/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The interpreter reuses these numbers as bytecodes, so every value has to equal upstream's. The
 * icodes count down from 0 and the bytecode tokens count up, and the two ranges must not meet.
 */
class IcodeParityTest {

    private fun upstreamIcodeConstants(): Map<String, Int> {
        val cls = Class.forName("org.mozilla.javascript.Icode")
        return cls.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .associate { field ->
                field.isAccessible = true
                field.name to field.getInt(null)
            }
    }

    private fun portedIcodeConstants(): Map<String, Int> {
        // A Kotlin `const val` in a companion object becomes a static field on the class itself.
        val cls = Class.forName("io.github.yuroyami.kitejs.Icode")
        return cls.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .associate { field ->
                field.isAccessible = true
                field.name to field.getInt(null)
            }
    }

    @Test
    fun everyIcodeValueMatchesUpstream() {
        val expected = upstreamIcodeConstants()
        val actual = portedIcodeConstants()
        assertTrue(expected.size >= 89, "expected the upstream icode table, got ${expected.size}")

        val failures = mutableListOf<String>()
        for ((name, value) in expected) {
            val ported = actual[name]
            if (ported == null) {
                failures.add("$name is missing from the port")
            } else if (ported != value) {
                failures.add("$name: upstream $value, ported $ported")
            }
        }
        assertEquals(emptyList(), failures, "icode values differ from upstream")
    }

    @Test
    fun theIcodeRangeIsWhereUpstreamPutsIt() {
        val expected = upstreamIcodeConstants()
        assertEquals(expected["MIN_ICODE"], Icode.MIN_ICODE)
        assertEquals(0, Icode.Icode_DELNAME)
        // The icodes must stay clear of the bytecode tokens, which run upward from FIRST_BYTECODE.
        assertTrue(Icode.MIN_ICODE < 0)
        assertTrue(Token.FIRST_BYTECODE_TOKEN > 0)
    }

    @Test
    fun validityChecksMatchUpstream() {
        for (code in (Icode.MIN_ICODE - 5)..(Token.LAST_BYTECODE_TOKEN + 5)) {
            assertEquals(
                code >= Icode.MIN_ICODE && code <= 0 ||
                    code >= Token.FIRST_BYTECODE_TOKEN && code <= Token.LAST_BYTECODE_TOKEN,
                Icode.validBytecode(code),
                "validBytecode($code)",
            )
        }
    }

    @Test
    fun everyIcodeHasAName() {
        // bytecodeName returns the number unless printICode is on, so this checks the mapping
        // exists rather than its text: reaching the "icode without name" branch would throw.
        for (code in Icode.MIN_ICODE..0) {
            assertEquals(code.toString(), Icode.bytecodeName(code))
        }
        for (code in Token.FIRST_BYTECODE_TOKEN..Token.LAST_BYTECODE_TOKEN) {
            assertEquals(code.toString(), Icode.bytecodeName(code))
        }
    }
}
