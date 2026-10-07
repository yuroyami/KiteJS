/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals

class DateJsonTest {
    private fun check(expected: String, source: String) {
        KiteJs(Rhino).use { js ->
            assertEquals(expected, js.evaluate(source, "date-json.js").asString())
            assertEquals(2, js.evaluate("1 + 1").asInt())
        }
    }

    @Test
    fun arbitraryIsoResultsPreserveValueAndIdentity() = check("true", """
        var values = [{ x: 1 }, [1, 2], null, undefined, Symbol('s'), 1n, 3, 'text', true];
        String(values.every(function (value) {
          var receiver = { valueOf: function () { return 0; }, toISOString: function () { return value; } };
          return Date.prototype.toJSON.call(receiver) === value;
        }));
    """.trimIndent())

    @Test
    fun structuredResultsAreNotCoercedToBuildAnErrorMessage() = check("true|0", """
        var effects = 0, token = {}, result = { toString: function () { effects++; throw token; } };
        var receiver = { valueOf: function () { return 0; }, toISOString: function () { return result; } };
        (Date.prototype.toJSON.call(receiver) === result) + '|' + effects;
    """.trimIndent())

    @Test
    fun jsonStringifyAcceptsCustomStructuredResults() = check("{\"date\":{\"x\":[1,2]}}", """
        var date = new Date(0);
        date.toISOString = function () { return { x: [1, 2] }; };
        JSON.stringify({ date: date });
    """.trimIndent())

    @Test
    fun nonfiniteNumericPrimitivesSkipTheIsoGetter() = check("true|0", """
        var gets = 0;
        var valid = [NaN, Infinity, -Infinity].every(function (value) {
          var o = { valueOf: function () { return value; }, get toISOString() { gets++; throw 'unexpected'; } };
          return Date.prototype.toJSON.call(o) === null;
        });
        valid + '|' + gets;
    """.trimIndent())

    @Test
    fun conversionGetterAndCallFollowTheSpecifiedOrder() = check("true|convert:number,get,call:true:0", """
        var events = [], value = {}, receiver = {
          [Symbol.toPrimitive]: function (hint) { events.push('convert:' + hint); return 'NaN'; },
          get toISOString() {
            events.push('get');
            return function () { events.push('call:' + (this === receiver) + ':' + arguments.length); return value; };
          }
        };
        (Date.prototype.toJSON.call(receiver, 'ignored') === value) + '|' + events.join();
    """.trimIndent())

    @Test
    fun missingAndNoncallableMethodsThrowCatchableTypeErrors() = check("true", """
        var receivers = [{}, { toISOString: 1 }, { toISOString: null }];
        String(receivers.every(function (o) {
          try { Date.prototype.toJSON.call(o); return false; } catch (e) { return e instanceof TypeError; }
        }));
    """.trimIndent())

    @Test
    fun conversionGetterAndMethodExceptionsRetainTheirIdentity() = check("true", """
        var token = {}, receivers = [
          { valueOf: function () { throw token; } },
          { valueOf: function () { return 0; }, get toISOString() { throw token; } },
          { valueOf: function () { return 0; }, toISOString: function () { throw token; } }
        ];
        String(receivers.every(function (o) {
          try { Date.prototype.toJSON.call(o); return false; } catch (e) { return e === token; }
        }));
    """.trimIndent())

    @Test
    fun ordinaryAndInvalidDatesRetainTheirJsonValues() = check("1970-01-01T00:00:00.000Z|null", """
        new Date(0).toJSON() + '|' + new Date(NaN).toJSON();
    """.trimIndent())
}
