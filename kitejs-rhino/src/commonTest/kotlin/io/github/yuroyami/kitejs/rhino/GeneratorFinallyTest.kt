/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

/** A generator's finally block may yield while return() runs, and the return keeps its value. Node 26.10 controls. */
class GeneratorFinallyTest {
    private fun check(expected: String, source: String) {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            assertEquals(expected, ScriptRuntime.toString(cx.evaluateString(cx.initStandardObjects(), source, "generator.js", 1, null)))
        } finally {
            Context.exit()
        }
    }

    @Test
    fun aFinallyBlockYieldsDuringReturn() = check("{\"value\":\"f\",\"done\":false}|{\"value\":7,\"done\":true}|{\"done\":true}|after", """
        var log = [];
        function* g() { try { yield 1 } finally { yield 'f'; log.push('after') } }
        var it = g(); it.next();
        [JSON.stringify(it.return(7)), JSON.stringify(it.next()), JSON.stringify(it.next()), log.join()].join('|');
    """)
}
