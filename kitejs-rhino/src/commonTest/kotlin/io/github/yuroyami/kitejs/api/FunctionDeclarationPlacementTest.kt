/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A function declaration is not a statement, so it cannot be the body of a loop, a `with` or an
 * `if`, labelled or not (ECMAScript 2015, 13.6.1 and 13.7.1.1). Annex B lets sloppy code keep a
 * plain one as the body of an `if` and as a labelled statement, which is what browsers do (D-76).
 */
class FunctionDeclarationPlacementTest {

    private fun eval(source: String): String = KiteJs().use { js -> js.evaluate(source).asString() }

    private fun outcome(source: String): String {
        val quoted = source.replace("\\", "\\\\").replace("'", "\\'")
        return eval("try { eval('$quoted'); 'accepted' } catch (e) { e.name }")
    }

    @Test
    fun a_function_as_the_body_of_a_loop_or_a_with_is_an_early_error() {
        for (source in listOf(
            "while (0) function f() {}",
            "do function f() {} while (0)",
            "for (;0;) function f() {}",
            "for (var k in {}) function f() {}",
            "for (var x of []) function f() {}",
            "with ({}) function f() {}",
            "while (0) l: function f() {}",
            "while (0) l1: l2: function f() {}",
            "for (var x of []) l: function f() {}",
            "l: while (0) function f() {}",
        )) {
            assertEquals("SyntaxError", outcome(source), source)
        }
    }

    @Test
    fun an_if_body_may_be_a_plain_function_only_in_sloppy_code() {
        assertEquals("accepted", outcome("if (1) function f() {} else function g() {}"))
        assertEquals("3", eval("if (1) function f() { return 3 } f() + ''"))
        for (source in listOf(
            "'use strict'; if (1) function f() {}",
            "if (1) function* g() {}",
            "if (1) l: function f() {}",
            "if (1) ; else l: function f() {}",
        )) {
            assertEquals("SyntaxError", outcome(source), source)
        }
    }

    @Test
    fun a_labelled_function_is_sloppy_code_only_and_never_a_generator() {
        assertEquals("accepted", outcome("l: function f() {}"))
        assertEquals("accepted", outcome("l1: l2: function f() {}"))
        assertEquals("SyntaxError", outcome("'use strict'; l: function f() {}"))
        assertEquals("SyntaxError", outcome("l: function* g() {}"))
    }

    @Test
    fun a_function_in_a_block_is_still_fine() {
        for (source in listOf(
            "while (0) { function f() {} }",
            "l: { function f() {} }",
            "'use strict'; { function f() {} }",
            "for (;0;) { l: function f() {} }",
        )) {
            assertEquals("accepted", outcome(source), source)
        }
    }
}
