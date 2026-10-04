/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An accessor whose descriptor says `set: undefined` has no setter, exactly as one that leaves
 * `set` out (ECMAScript 2015, 6.2.4.5 and 9.1.9.1): a write in strict code is a TypeError, and a
 * write in sloppy code changes nothing. Each case runs on the object itself and on one that
 * inherits the accessor, as a Web IDL attribute is reached through its prototype.
 */
class AccessorWithoutSetterTest {

    private fun eval(source: String): String = KiteJs().use { js -> js.evaluate(source).asString() }

    private val descriptors = listOf(
        "{ get: function () { return 1; }, set: undefined, configurable: true }",
        "{ get: function () { return 1; }, configurable: true }",
        "{ get: undefined, set: undefined, configurable: true }",
    )

    private val targets = listOf("o", "Object.create(o)")

    @Test
    fun a_strict_write_to_an_accessor_without_a_setter_throws() {
        for (descriptor in descriptors) {
            for (target in targets) {
                assertEquals(
                    "TypeError",
                    eval(
                        "var o = Object.defineProperty({}, 'z', $descriptor); var t = $target;" +
                            " (function () { 'use strict'; try { t.z = 2; } catch (e) { return e.name; } return 'written'; })()",
                    ),
                    "$descriptor on $target",
                )
            }
        }
    }

    @Test
    fun a_sloppy_write_to_an_accessor_without_a_setter_changes_nothing() {
        for (descriptor in descriptors) {
            for (target in targets) {
                // Neither a data property on the inheriting object nor a value behind the accessor.
                assertEquals(
                    "inherited:" + if ("function" in descriptor) "1" else "undefined",
                    eval(
                        "var o = Object.defineProperty({}, 'z', $descriptor); var t = $target; t.z = 2;" +
                            " (t !== o && Object.prototype.hasOwnProperty.call(t, 'z') ? 'own:' : 'inherited:') + t.z",
                    ),
                    "$descriptor on $target",
                )
            }
        }
    }

    @Test
    fun a_setter_that_is_a_function_still_runs() {
        assertEquals(
            "2",
            eval(
                "var seen; var o = Object.defineProperty({}, 'z', { get: function () { return seen; }, set: function (v) { seen = v; }, configurable: true });" +
                    " (function () { 'use strict'; Object.create(o).z = 2; })(); String(o.z)",
            ),
        )
    }

    @Test
    fun the_issue_example_answers_type_error() {
        assertEquals(
            "TypeError",
            eval(
                "var o = {}; Object.defineProperty(o, 'z', { get: function () { return 1; }, set: undefined, configurable: true });" +
                    " var r = 'no'; (function () { 'use strict'; try { o.z = 1; } catch (e) { r = e.name; } })(); r",
            ),
        )
    }
}
