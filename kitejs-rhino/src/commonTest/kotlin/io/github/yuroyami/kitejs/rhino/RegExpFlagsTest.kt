/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `flags` getter refuses a primitive `this`, and `Symbol.replace` takes `global` from what
 * `flags` reports, not from the flags the pattern was made with. The expected string is
 * Node 26.11 output.
 */
class RegExpFlagsTest {
    @Test
    fun replaceReadsTheReportedFlags() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ECMASCRIPT
            val result = cx.evaluateString(cx.initStandardObjects(), SOURCE, "flags.js", 1, null)
            assertEquals("TypeError,TypeError,TypeError,TypeError,TypeError,ba,ac", ScriptRuntime.toString(result))
        } finally {
            Context.exit()
        }
    }

    private companion object {
        val SOURCE = """
        var out = [];
        var flags = Object.getOwnPropertyDescriptor(RegExp.prototype, 'flags').get;
        for (var v of [4, 'x', false, Symbol(), 4n]) {
          try { flags.call(v); out.push('no error'); } catch (e) { out.push(e.constructor.name); }
        }
        var r = /a/g;
        Object.defineProperty(r, 'global', { value: false });
        out.push(r[Symbol.replace]('aa', 'b'), 'aa'.replace(r, 'c'));
        out.join();
        """
    }
}
