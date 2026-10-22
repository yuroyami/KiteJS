/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals

class EscapeArgumentsTest {
    private fun check(expected: String, source: String) {
        KiteJs(Rhino).use { js ->
            assertEquals(expected, js.evaluate(source, "escape-arguments.js").asString())
            assertEquals(2, js.evaluate("1 + 1").asInt())
        }
    }

    @Test
    fun arrayMapIndicesDoNotChangeEncoding() = check("a%20b,c%20d,e,f,g,h,i,j,k%20l", """
        ['a b', 'c d', 'e', 'f', 'g', 'h', 'i', 'j', 'k l'].map(escape).join();
    """.trimIndent())

    @Test
    fun everyExtraValueIsIgnoredWithoutConversion() = check("true|0", """
        var effects = 0, token = {}, extra = { valueOf: function () { effects++; throw token; } };
        var values = [0, 1, 2, 4, 7, 8, -1, NaN, Infinity, null, undefined, Symbol('s'), 1n, extra];
        var valid = values.every(function (value) { return escape('hello rhino', value) === 'hello%20rhino'; });
        valid + '|' + effects;
    """.trimIndent())

    @Test
    fun standardUnescapedCharactersAndUtf16EncodingRemainIntact() = check(
        "AZaz09@*_+-./|%20%25%21%23%5B%5D%00%FF%u0100%uD83D%uDE00%uD800",
        """
            escape('AZaz09@*_+-./') + '|' + escape(' %!#[]\u0000\u00ff\u0100\ud83d\ude00\ud800');
        """.trimIndent(),
    )

    @Test
    fun encodingRoundTripsEveryCodeUnit() = check("true", """
        var s = '';
        for (var i = 0; i < 65536; i++) s += String.fromCharCode(i);
        String(unescape(escape(s)) === s);
    """.trimIndent())

    @Test
    fun onlyTheFirstArgumentIsConvertedOnce() = check("x%20y|first|1", """
        var events = [], value = { toString: function () { events.push('first'); return 'x y'; } };
        var ignored = { valueOf: function () { events.push('extra'); throw 'unexpected'; } };
        escape(value, ignored) + '|' + events.join() + '|' + escape.length;
    """.trimIndent())

    @Test
    fun firstArgumentExceptionsStayCatchable() = check("true|undefined|null", """
        var token = {}, caught = false;
        try { escape({ toString: function () { throw token; } }, 8); } catch (e) { caught = e === token; }
        caught + '|' + escape() + '|' + escape(null);
    """.trimIndent())
}
