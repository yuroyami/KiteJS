/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals

class NullPropertyErrorTest {
    private fun check(expected: String, source: String) {
        KiteJs(Rhino).use { js ->
            assertEquals(expected, js.evaluate(source, "null-property-error.js").asString())
            assertEquals(2, js.evaluate("1 + 1").asInt())
        }
    }

    @Test
    fun nullishOperationsDoNotConvertObjectKeys() {
        for (base in listOf("null", "undefined")) for (operation in listOf(
            "base[key]", "base[key] = 42", "base[key] += 1", "base[key]++", "delete base[key]", "base[key]()",
        )) check("TypeError|0", """
            var base = $base, effects = 0, token = {};
            var key = { toString: function () { effects++; throw token; } }, name = 'missing';
            try { $operation; } catch (e) { name = e instanceof TypeError ? 'TypeError' : 'other'; }
            name + '|' + effects;
        """.trimIndent())
    }

    @Test
    fun failedWritesNeverConvertTheirValues() {
        for (base in listOf("null", "undefined")) check("TypeError|0", """
            var base = $base, effects = 0, token = {}, name = 'missing';
            var value = { toString: function () { effects++; throw token; } };
            try { base.x = value; } catch (e) { name = e instanceof TypeError ? 'TypeError' : 'other'; }
            name + '|' + effects;
        """.trimIndent())
    }

    @Test
    fun gettersPrimitiveHooksAndProxyTrapsStayUntouched() = check("TypeError,TypeError,TypeError|0", """
        var effects = 0, token = {}, keys = [
          { get toString() { effects++; throw token; } },
          { [Symbol.toPrimitive]: function () { effects++; throw token; } },
          new Proxy({}, { get: function () { effects++; throw token; } })
        ];
        keys.map(function (key) {
          try { null[key]; return 'missing'; } catch (e) { return e instanceof TypeError ? 'TypeError' : 'other'; }
        }).join() + '|' + effects;
    """.trimIndent())

    @Test
    fun keyAndRightHandExpressionsStillRunInTheirOriginalOrder() = check("TypeError|key-evaluate,rhs", """
        var events = [], key = { toString: function () { events.push('convert'); return 'x'; } };
        var value = { toString: function () { events.push('value'); return 'v'; } }, name = 'missing';
        try { null[(events.push('key-evaluate'), key)] = (events.push('rhs'), value); }
        catch (e) { name = e instanceof TypeError ? 'TypeError' : 'other'; }
        name + '|' + events.join();
    """.trimIndent())

    @Test
    fun existingPrimitivePropertyMessagesRemainUseful() = check("Cannot read property \"x\" from null", """
        try { null.x; } catch (e) { e.message; }
    """.trimIndent())

    @Test
    fun nonNullObjectsStillPerformRequiredKeyConversion() = check("7|1", """
        var effects = 0, key = { toString: function () { effects++; return 'x'; } }, object = { x: 7 };
        object[key] + '|' + effects;
    """.trimIndent())

    @Test
    fun symbolBigIntAndRopeKeysStillProduceTheRequiredTypeError() = check("true", """
        var keys = [Symbol('key'), 1n, 'long-property-' + 'name'];
        String(keys.every(function (key) {
          try { null[key]; return false; } catch (e) { return e instanceof TypeError; }
        }));
    """.trimIndent())

}
