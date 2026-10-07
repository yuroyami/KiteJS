/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/** Local fields outside TimeClip's range must be rejected before calendar remapping. */
class DateRangeTest {
    private fun runIn(zoneId: String, source: String): String {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            cx.timeZone = TimeZone.of(zoneId)
            val scope = cx.initStandardObjects()
            val result = cx.evaluateString(scope, source, "date-range.js", 1, null)
            assertEquals(2.0, cx.evaluateString(scope, "1 + 1", "recovery.js", 1, null))
            return ScriptRuntime.toString(result)
        } finally {
            Context.exit()
        }
    }

    @Test
    fun extremeFiniteFieldsProduceInvalidDates() {
        for (zone in listOf("UTC", "UTC+18:00", "UTC-18:00", "Europe/Berlin", "America/New_York")) {
            assertEquals("true", runIn(zone, """
                var values = [1e20, -1e20, 1e308, -1e308], valid = true;
                for (var i = 0; i < 7; i++) for (var j = 0; j < values.length; j++) {
                  var a = [1970, 0, 1, 0, 0, 0, 0]; a[i] = values[j];
                  var d = new Date(a[0], a[1], a[2], a[3], a[4], a[5], a[6]);
                  valid = valid && isNaN(d.getTime());
                }
                String(valid);
            """.trimIndent()), zone)
        }
    }

    @Test
    fun dateUsedAsAnExtremeDayProducesAnInvalidDate() = assertEquals("true", runIn("UTC", """
        var d = new Date(40, 0, 0);
        String(isNaN(new Date(40, 0, d).getTime()));
    """.trimIndent()))

    @Test
    fun finiteFieldsWhoseArithmeticOverflowsStillProduceInvalidDates() = assertEquals("NaN,NaN", runIn("UTC", """
        [new Date(1e308, 0).getTime(), new Date(1970, 0, 1, 1e308).getTime()].join();
    """.trimIndent()))

    @Test
    fun extremeLocalAndUtcSettersProduceInvalidDates() {
        for (zone in listOf("UTC", "UTC+18:00", "UTC-18:00")) assertEquals("true", runIn(zone, """
            var methods = ['setMilliseconds', 'setSeconds', 'setMinutes', 'setHours',
              'setDate', 'setMonth', 'setFullYear', 'setYear', 'setUTCMilliseconds',
              'setUTCSeconds', 'setUTCMinutes', 'setUTCHours', 'setUTCDate',
              'setUTCMonth', 'setUTCFullYear'];
            var values = [1e20, -1e20, 1e308, -1e308], valid = true;
            for (var i = 0; i < methods.length; i++) for (var j = 0; j < values.length; j++) {
              var d = new Date(0), result = d[methods[i]](values[j]);
              valid = valid && isNaN(result) && isNaN(d.getTime());
            }
            String(valid);
        """.trimIndent()), zone)
    }

    @Test
    fun zoneOffsetsPreserveExactBoundariesAndRejectAdjacentValues() {
        val limit = 8640000000000000L
        val expected = "${-limit},${-limit + 1},NaN,${limit - 1},$limit,NaN"
        for ((zone, offset) in mapOf("UTC" to 0L, "UTC+18:00" to 64800000L, "UTC-18:00" to -64800000L)) {
            assertEquals(expected, runIn(zone, """
                [-8640000000000000, -8639999999999999, -8640000000000001,
                  8639999999999999, 8640000000000000, 8640000000000001].map(function (t) {
                    return new Date(1970, 0, 1, 0, 0, 0, t + $offset).getTime();
                  }).join();
            """.trimIndent()), zone)
        }
    }

    @Test
    fun ordinaryFieldNormalizationAndUtcClippingRemainIntact() = assertEquals(
        "1969-12-31T00:00:00.000Z|1971-01-01T00:00:00.000Z|1970-01-02T01:00:00.000Z|0|NaN|NaN",
        runIn("UTC", """
            [new Date(1970, 0, 0).toISOString(), new Date(1970, 12, 1).toISOString(),
             new Date(1970, 0, 1, 25).toISOString(), new Date(1970, 0, 1).getTime(),
             Date.UTC(1e308, 0), new Date(1e308).getTime()].join('|');
        """.trimIndent()),
    )

    @Test
    fun laterConversionErrorsStillPropagateBeforeClipping() = assertEquals("true,true", runIn("UTC", """
        var token = {}, arg = { valueOf: function () { throw token; } }, a = false, b = false;
        try { new Date(-1e20, 0, arg); } catch (e) { a = e === token; }
        try { new Date(0).setFullYear(-1e20, 0, arg); } catch (e) { b = e === token; }
        [a, b].join();
    """.trimIndent()))
}
