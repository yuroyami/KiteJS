/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Assigning to a `const` is a TypeError in any mode, and the const keeps its value. ECMAScript
 * 2015, 8.1.1.1.5 (SetMutableBinding) says so for an immutable binding, and every browser agrees.
 * Each case runs in a function, where a const lives in the frame, at the top level, where it is a
 * property of the scope, and under a closure, where the function keeps an activation object.
 */
class ConstAssignmentTest {

    private fun eval(source: String): String = KiteJs().use { js -> js.evaluate(source).asString() }

    private val writes = listOf(
        "c = 2", "c += 1", "c++", "--c", "c &&= 4", "[c] = [2]", "({ c } = { c: 2 })", "for (c in { a: 1 });",
        "for (c of [2]);",
    )

    @Test
    fun every_kind_of_write_to_a_const_in_a_function_throws() {
        for (write in writes) {
            assertEquals(
                "TypeError:1",
                eval("(function () { const c = 1; try { $write } catch (e) { return e.name + ':' + c } return 'assigned' })()"),
                write,
            )
        }
    }

    @Test
    fun every_kind_of_write_to_a_top_level_const_throws() {
        for (write in writes) {
            assertEquals("TypeError:1", eval("const c = 1; var r = 'assigned'; try { $write } catch (e) { r = e.name + ':' + c } r"), write)
        }
    }

    @Test
    fun every_kind_of_write_from_a_closure_throws() {
        for (write in writes) {
            assertEquals(
                "TypeError:1",
                eval(
                    "(function () { const c = 1; return (function () { try { $write } catch (e) { return e.name + ':' + c }" +
                        " return 'assigned' })() })()",
                ),
                write,
            )
        }
    }

    @Test
    fun the_issue_example_answers_type_error() {
        assertEquals("TypeError", eval("(function () { const c = 1; try { c = 2 } catch (e) { return e.name } return 'assigned' })()"))
        assertEquals(
            "TypeError: Cannot modify readonly property: c.",
            eval("(function () { const c = 1; try { c = 2 } catch (e) { return String(e) } })()"),
        )
    }

    @Test
    fun strict_mode_and_eval_throw_too() {
        assertEquals("TypeError", eval("(function () { 'use strict'; const c = 1; try { c = 2 } catch (e) { return e.name } })()"))
        assertEquals("TypeError:1", eval("(function () { const c = 1; try { eval('c = 3') } catch (e) { return e.name + ':' + c } })()"))
        assertEquals("TypeError", eval("try { eval(\"'use strict'; const c = 1; c = 2\"); 'none' } catch (e) { e.name }"))
    }

    /** A write that does not happen is no error: the logical assignments short-circuit first. */
    @Test
    fun a_short_circuited_logical_assignment_does_not_throw() {
        assertEquals("1", eval("(function () { const c = 1; c ||= 2; c ??= 3; return c })() + ''"))
    }

    /** The operand of ++ is converted before the write fails, as the spec orders it. */
    @Test
    fun increment_converts_its_operand_first() {
        assertEquals(
            "TypeError:1",
            eval(
                "(function () { var n = 0; const c = { valueOf: function () { n++; return 1 } };" +
                    " try { c++ } catch (e) { return e.name + ':' + n } })()",
            ),
        )
    }

    /** Read-only properties that are not const bindings still fail silently outside strict mode. */
    @Test
    fun a_read_only_global_is_not_a_const() {
        assertEquals("NaN", eval("NaN = 1; String(NaN)"))
        assertEquals("undefined", eval("undefined = 1; typeof undefined"))
        assertEquals("1", eval("var o = Object.defineProperty({}, 'x', { value: 1, enumerable: true }); with (o) { x = 2 } o.x + ''"))
    }

    /** A name that a `with` object supplies is that object's property, not the const behind it. */
    @Test
    fun with_reaches_its_own_object_first() {
        assertEquals("9:1", eval("(function () { const c = 1; var o = { c: 0 }; with (o) { c = 9 } return o.c + ':' + c })()"))
    }

    @Test
    fun reading_and_redeclaring_are_unchanged() {
        assertEquals("1", eval("const c = 1; c + ''"))
        assertEquals("TypeError", eval("const c = 1; try { eval('var c = 2') } catch (e) { e.name }"))
    }
}
