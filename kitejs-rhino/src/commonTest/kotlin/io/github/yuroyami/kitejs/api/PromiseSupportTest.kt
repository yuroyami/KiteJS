/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The host's view of promises: what counts as thenable (issue 65), and `onSettled`, which attaches
 * to a value the way `await` does (issue 36).
 */
class PromiseSupportTest {

    @Test
    fun thenable_means_a_callable_then() {
        KiteJs(Rhino).use { js ->
            for (source in listOf("1", "'then'", "true", "null", "undefined", "10n", "Symbol('s')")) {
                assertFalse(js.evaluate(source).isThenable, source)
            }
            for (source in listOf("({})", "[]", "(function () {})", "({ then: 5 })", "({ then: null })")) {
                assertFalse(js.evaluate(source).isThenable, source)
            }
            for (source in listOf(
                "Promise.resolve(1)",
                "({ then: function () {} })",
                "var a = []; a.then = function (r) { r(4) }; a",
                "var f = function () {}; f.then = function () {}; f",
                "({ get then() { return function () {} } })",
                "Object.create({ then: function () {} })",
            )) {
                assertTrue(js.evaluate(source).isThenable, source)
            }
        }
    }

    @Test
    fun a_then_getter_that_throws_reaches_the_caller() {
        KiteJs(Rhino).use { js ->
            val value = js.evaluate("({ get then() { throw new RangeError('no then') } })")
            val e = assertFailsWith<JsError> { value.isThenable }
            assertEquals("RangeError", e.name)
            assertEquals(3.0, js.evaluate("1 + 2").asDouble())
        }
    }

    @Test
    fun a_scalar_is_not_thenable_anywhere_but_an_object_needs_its_engine() {
        val number: JsValue
        val obj: JsValue
        KiteJs(Rhino).use { js ->
            number = js.evaluate("42")
            obj = js.evaluate("({ then: function () {} })")
        }
        assertFalse(number.isThenable)
        assertFailsWith<JsEngineError> { obj.isThenable }
    }

    /** What `onSettled` reported, drained the way a host drains. */
    private fun settle(js: KiteJs, source: String): Pair<Boolean, String> {
        var outcome = "pending"
        var calls = 0
        val registered = js.onSettled(js.evaluate(source)) { value, error ->
            calls++
            outcome = if (error == null) "ok:$value" else "error:$error"
        }
        js.runMicrotasks()
        assertTrue(calls <= 1, "settled $calls times: $source")
        return registered to outcome
    }

    @Test
    fun a_thenable_is_followed_to_its_end() {
        KiteJs(Rhino).use { js ->
            assertEquals(true to "ok:7", settle(js, "({ then: function (resolve) { resolve(Promise.resolve(7)) } })"))
            assertEquals(true to "ok:8", settle(js, "({ then: function (r) { r({ then: function (r2) { r2(8) } }) } })"))
            assertEquals(
                true to "error:TypeError: inner",
                settle(js, "({ then: function (r) { r({ then: function (_, j) { j(new TypeError('inner')) } }) } })"),
            )
            // A rejection is not followed: the reason is the promise itself.
            assertEquals(true to "error:[object Promise]", settle(js, "({ then: function (_, j) { j(Promise.resolve(1)) } })"))
        }
    }

    @Test
    fun a_thenable_settles_once_and_a_throw_rejects() {
        KiteJs(Rhino).use { js ->
            assertEquals(true to "ok:1", settle(js, "({ then: function (r) { r(1); r(2) } })"))
            assertEquals(true to "ok:1", settle(js, "({ then: function (r) { r(1); throw new Error('late') } })"))
            assertEquals(true to "error:Error: early", settle(js, "({ then: function () { throw new Error('early') } })"))
            assertEquals(true to "error:boom", settle(js, "({ get then() { throw 'boom' } })"))
        }
    }

    @Test
    fun then_is_read_once() {
        KiteJs(Rhino).use { js ->
            val source = "var reads = 0; ({ get then() { reads++; return function (r) { r('read') } } })"
            assertEquals(true to "ok:read", settle(js, source))
            assertEquals(1.0, js.evaluate("reads").asDouble())
        }
    }

    @Test
    fun a_promise_is_watched_without_calling_its_then() {
        KiteJs(Rhino).use { js ->
            val source = "var p = Promise.resolve(5); p.then = function () { throw new Error('called') }; p"
            assertEquals(true to "ok:5", settle(js, source))
        }
    }

    @Test
    fun a_promise_resolved_with_itself_rejects() {
        KiteJs(Rhino).use { js ->
            val source = "var res; var p = new Promise(function (r) { res = r }); res(p); p"
            val (registered, outcome) = settle(js, source)
            assertTrue(registered)
            assertTrue(outcome.startsWith("error:TypeError"), outcome)
        }
    }

    @Test
    fun a_value_that_is_not_thenable_registers_nothing() {
        KiteJs(Rhino).use { js ->
            for (source in listOf("5", "'x'", "({})", "({ then: 5 })", "[]")) {
                assertEquals(false to "pending", settle(js, source), source)
            }
        }
    }

    @Test
    fun a_reason_that_cannot_be_read_still_makes_an_error() {
        KiteJs(Rhino).use { js ->
            fun from(source: String) = JsError.from(js.evaluate(source))

            val badName = from("({ get name() { throw new Error('bad name') }, message: 'm' })")
            assertEquals("Error", badName.name)
            assertEquals("m", badName.errorMessage)

            val badMessage = from("({ name: 'Custom', get message() { throw new Error('bad message') } })")
            assertEquals("Custom", badMessage.name)
            assertEquals("[object Object]", badMessage.errorMessage)

            val badString = from("({ toString: function () { throw new Error('bad string') } })")
            assertEquals("[object Object]", badString.errorMessage)

            assertEquals("Symbol(s)", from("Symbol('s')").errorMessage)

            val revoked = from("var r = Proxy.revocable({}, {}); r.revoke(); r.proxy")
            assertEquals("Error", revoked.name)

            val scalar = from("42")
            assertEquals("42", scalar.errorMessage)
            assertEquals(42.0, scalar.value.asDouble())
            assertNull(scalar.cause)
        }
    }
}
