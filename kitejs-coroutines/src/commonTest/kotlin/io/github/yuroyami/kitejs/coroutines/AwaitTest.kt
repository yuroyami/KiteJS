/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.coroutines

import io.github.yuroyami.kitejs.api.JsError
import io.github.yuroyami.kitejs.api.JsType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

/** `await` resolves a value the way `await` does in a script, and always comes back. */
class AwaitTest {

    @Test
    fun aThenableThatAnswersWithAPromiseComesBackAsItsValue() = runTest {
        val js = asyncKiteJs()
        try {
            val v = js.evaluateAwaiting("({ then: function (resolve) { resolve(Promise.resolve(7)) } })")
            assertEquals(JsType.NUMBER, v.type)
            assertEquals(7.0, v.asDouble())
            assertEquals(8.0, js.evaluateAwaiting("({ then: function (r) { r({ then: function (r2) { r2(8) } }) } })").asDouble())
        } finally {
            js.close()
        }
    }

    @Test
    fun aThenableThatFailsRejects() = runTest {
        val js = asyncKiteJs()
        try {
            val inner = assertFailsWith<JsError> {
                js.evaluateAwaiting("({ then: function (r) { r({ then: function (_, j) { j(new TypeError('inner')) } }) } })")
            }
            assertEquals("TypeError", inner.name)
            assertEquals("inner", inner.errorMessage)
            assertEquals("boom", assertFailsWith<JsError> { js.evaluateAwaiting("({ get then() { throw 'boom' } })") }.errorMessage)
            assertEquals("early", assertFailsWith<JsError> { js.evaluateAwaiting("({ then: function () { throw new Error('early') } })") }.errorMessage)
            assertEquals(1.0, js.evaluateAwaiting("({ then: function (r) { r(1); r(2); throw new Error('late') } })").asDouble())
        } finally {
            js.close()
        }
    }

    @Test
    fun aRejectionWhoseReasonCannotBeReadStillEndsTheWait() = runTest {
        val js = asyncKiteJs()
        try {
            for (source in listOf(
                "Promise.reject({ get name() { throw new Error('bad name') } })",
                "Promise.reject({ get message() { throw new Error('bad message') } })",
                "Promise.reject({ toString: function () { throw new Error('bad string') } })",
                "var r = Proxy.revocable({}, {}); r.revoke(); Promise.reject(r.proxy)",
                "Promise.reject(Symbol('s'))",
                "Promise.reject(new RangeError('plain'))",
            )) {
                assertFailsWith<JsError>(source) { js.evaluateAwaiting(source) }
            }
            val scalar = assertFailsWith<JsError> { js.evaluateAwaiting("Promise.reject(42)") }
            assertEquals(42.0, scalar.value.asDouble())
            assertEquals("Symbol(s)", assertFailsWith<JsError> { js.evaluateAwaiting("Promise.reject(Symbol('s'))") }.errorMessage)
            assertEquals(3.0, js.evaluate("1 + 2").asDouble())
        } finally {
            js.close()
        }
    }
}
