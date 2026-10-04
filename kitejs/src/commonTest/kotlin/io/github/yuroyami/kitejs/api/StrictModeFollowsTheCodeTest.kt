/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Whether a failed write throws depends on the code that makes it, and on nothing else
 * (ECMAScript 2015, 10.2.1): strict code throws a TypeError wherever it is called from, and
 * sloppy code changes nothing quietly wherever it is called from. Each failed write runs in a
 * strict callback called by a sloppy function, by a builtin and by a generator's resumption,
 * and in a sloppy function called from strict code.
 */
class StrictModeFollowsTheCodeTest {

    private fun eval(source: String): String = KiteJs().use { js -> js.evaluate(source).asString() }

    /** Statements that each make one failed write, given a frozen `o` and an accessor `a` without a setter. */
    private val failedWrites = listOf(
        "o.z = 2;",
        "o.y = 2;",
        "a.z = 2;",
        "delete o.z;",
        "delete Object.prototype;",
        "Object.preventExtensions({}).x = 1;",
        "o['z'] = 2;",
        "o[0] = 2;",
    )

    private val setup = "var o = Object.freeze({ z: 1 }); var a = Object.defineProperty({}, 'z', { get: function () { return 1; } }); "

    /**
     * A `try` around [body] that answers `TypeError` when it throws one and `quiet` when it does not. It sits
     * in the caller and not in the strict code, as a `try` there gives that code an activation of its own,
     * which hid the defect.
     */
    private fun outcome(body: String) = "try { $body; return 'quiet'; } catch (e) { return e.name; }"

    @Test
    fun a_strict_callback_called_by_sloppy_code_throws() {
        for (write in failedWrites) {
            val source = setup + "function sloppyCall(fn) { ${outcome("fn()")} } " +
                "(function () { 'use strict'; return sloppyCall(function () { $write }); })()"
            assertEquals("TypeError", eval(source), write)
        }
    }

    @Test
    fun a_strict_function_called_by_a_sloppy_one_throws() {
        for (write in failedWrites) {
            val source = setup + "function strict() { 'use strict'; $write } " +
                "function sloppy() { ${outcome("strict()")} } sloppy()"
            assertEquals("TypeError", eval(source), write)
        }
    }

    @Test
    fun a_strict_callback_called_by_a_builtin_from_sloppy_code_throws() {
        for (write in failedWrites) {
            val source = setup + "(function () { ${outcome("[1].map(function () { 'use strict'; $write })")} })()"
            assertEquals("TypeError", eval(source), write)
        }
    }

    @Test
    fun a_strict_generator_resumed_by_sloppy_code_throws() {
        for (write in failedWrites) {
            val source = setup + "function* g() { 'use strict'; yield 0; $write } " +
                "(function () { var it = g(); it.next(); ${outcome("it.next()")} })()"
            assertEquals("TypeError", eval(source), write)
        }
    }

    @Test
    fun a_sloppy_function_called_from_strict_code_is_quiet() {
        for (write in failedWrites) {
            val source = setup + "function sloppy() { $write return 'quiet'; } " +
                "(function () { 'use strict'; ${outcome("return sloppy()")} })()"
            assertEquals("quiet", eval(source), write)
        }
    }

    @Test
    fun a_sloppy_callback_called_by_a_builtin_from_strict_code_is_quiet() {
        for (write in failedWrites) {
            val source = setup + "function sloppy() { $write return 'quiet'; } " +
                "(function () { 'use strict'; ${outcome("return [1].map(sloppy)[0]")} })()"
            assertEquals("quiet", eval(source), write)
        }
    }

    @Test
    fun a_function_from_the_function_constructor_is_sloppy_in_strict_code() {
        for (write in failedWrites) {
            val source = "'use strict'; $setup var f = Function(\"o\", \"a\", \"$write return 'quiet';\"); " +
                "(function () { ${outcome("return f(o, a)")} })()"
            assertEquals("quiet", eval(source), write)
        }
    }

    @Test
    fun a_strict_callback_called_by_a_function_from_the_function_constructor_throws() {
        for (write in failedWrites) {
            val source = setup + "var call = Function('fn', 'try { fn(); return \\'quiet\\'; } catch (e) { return e.name; }'); " +
                "(function () { 'use strict'; return call(function () { $write }); })()"
            assertEquals("TypeError", eval(source), write)
        }
    }

    /**
     * Eval code is strict when it starts with its own directive, or when strict code calls `eval`
     * directly; an indirect call is global code, which no caller makes strict (ECMAScript 2015,
     * 18.2.1.1).
     */
    @Test
    fun eval_code_is_strict_when_it_says_so_or_strict_code_calls_eval_directly() {
        val write = "var o = Object.freeze({ z: 1 }); o.z = 2; \\'quiet\\';"
        val cases = mapOf(
            "(function () { 'use strict'; ${outcome("return eval('$write')")} })()" to "TypeError",
            "(function () { ${outcome("return eval('\\'use strict\\'; $write')")} })()" to "TypeError",
            "(function () { 'use strict'; ${outcome("return (0, eval)('$write')")} })()" to "quiet",
            "(function () { 'use strict'; var indirect = eval; ${outcome("return indirect('$write')")} })()" to "quiet",
            "(function () { 'use strict'; ${outcome("return (0, eval)('\\'use strict\\'; $write')")} })()" to "TypeError",
            "(function () { ${outcome("return (0, eval)('$write')")} })()" to "quiet",
        )
        for ((source, expected) in cases) assertEquals(expected, eval(source), source)
    }

    @Test
    fun an_indirect_eval_from_strict_code_declares_a_global_variable() {
        assertEquals("number", eval("(function () { 'use strict'; (0, eval)('var declared = 1;'); })(); typeof declared"))
    }

    @Test
    fun the_arguments_of_a_sloppy_function_stay_mapped_in_a_strict_script() {
        assertEquals(
            "2,2",
            eval("'use strict'; var f = Function('a', 'arguments[0] = 2; var read = function () { return arguments[0]; }; return [a, read(arguments[0])].join();'); f(1)"),
        )
        assertEquals(
            "2",
            eval("'use strict'; var f = Function('a', 'return { args: arguments, a: function () { return a; } };'); " +
                "var r = f(1); (function () { r.args[0] = 2; return r.a(); })()"),
        )
    }

    @Test
    fun the_arguments_of_a_strict_function_stay_unmapped_when_sloppy_code_reads_them() {
        assertEquals(
            "1,2",
            eval("function f(a) { 'use strict'; return { args: arguments, a: function () { return a; } }; } " +
                "var r = f(1); (function () { r.args[0] = 2; return [r.a(), r.args[0]].join(); })()"),
        )
    }
}
