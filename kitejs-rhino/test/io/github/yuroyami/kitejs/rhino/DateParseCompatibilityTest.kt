/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.TimeZone

/** Non-ISO compatibility expectations recorded with Node 26.10.0 in each selected zone. */
class DateParseCompatibilityTest {
    private val inputs = listOf(
        "2024-01-15 10:30",
        "2024-01-15 10:30:00",
        "2024-1-5",
        "Jan 2024",
        "March 31, 2013 23:59:59.9999",
        "March 31, 2013 23:59:59.1",
        "March 31, 2013 23:59:59.12",
        "March 31, 2013 23:59:59.9999 GMT",
        "March 31, 2013 23:59:59.125 GMT+0230",
        "02/12/26",
        "01/01/49",
        "01/01/50",
        "1/2/99",
        "01/01/00",
        "Jan 15, 26",
        "15 Jan 49",
        "Jan 15, 2024",
        "15 Jan 2024",
        "January 2024",
        "2024-01-15",
        "2024-01",
        "2024",
        "2024-01-15T10:30:00",
        "2024-01-15T10:30:00Z",
        "2024-01-15T10:30:00+02:30",
        "Mon, 15 Jan 2024 10:30:00 GMT",
        "2024/01/15 10:30",
        "01/15/2024 10:30 PM",
        "2024-01-15 10:30:00.12345",
        "2024-01-15 10:30:00Z",
        "2024-01-15 10:30:00+02:30",
        "2024-1-5 1:02:03.4",
        "2024-01-15 24:00:00",
        " 2024-01-15 ",
        "2024-01-15 10:30:00-0530",
        "2024-13-01 10:30",
        "2024-00-01 10:30",
        "2024-01-32 10:30",
        "2024-01-15 25:00",
        "2024-01-15 24:00:00.001",
        "2024-01-15 10:60",
        "2024-01-15 10:30:60",
        "2024-01-15 10:30:00+00:60",
        "2024-1-5T10:30:00",
        "March 31, 2013 23:59:59.",
        "2024-01-15 10:30:00.123garbage",
        "Jan",
        "Jan 2024 garbage",
    )

    private val expected = mapOf(
        "UTC" to "1705314600000|1705314600000|1704412800000|1704067200000|1364774399999|1364774399100|1364774399120|1364774399999|1364765399125|1770854400000|2493072000000|-631152000000|915235200000|946684800000|1768435200000|2494281600000|1705276800000|1705276800000|1704067200000|1705276800000|1704067200000|1704067200000|1705314600000|1705314600000|1705305600000|1705314600000|1705314600000|1705357800000|1705314600123|1705314600000|1705305600000|1704416523400|1705363200000|1705276800000|1705334400000|NaN|NaN|NaN|NaN|NaN|NaN|NaN|NaN|NaN|NaN|NaN|NaN|NaN",
        "Europe/Berlin" to "1705311000000|1705311000000|1704409200000|1704063600000|1364767199999|1364767199100|1364767199120|1364774399999|1364765399125|1770850800000|2493068400000|-631155600000|915231600000|946681200000|1768431600000|2494278000000|1705273200000|1705273200000|1704063600000|1705276800000|1704067200000|1704067200000|1705311000000|1705314600000|1705305600000|1705314600000|1705311000000|1705354200000|1705311000123|1705314600000|1705305600000|1704412923400|1705359600000|1705273200000|1705334400000|NaN|NaN|NaN|NaN|NaN|NaN|NaN|NaN|NaN|NaN|NaN|NaN|NaN",
        "America/New_York" to "1705332600000|1705332600000|1704430800000|1704085200000|1364788799999|1364788799100|1364788799120|1364774399999|1364765399125|1770872400000|2493090000000|-631134000000|915253200000|946702800000|1768453200000|2494299600000|1705294800000|1705294800000|1704085200000|1705276800000|1704067200000|1704067200000|1705332600000|1705314600000|1705305600000|1705314600000|1705332600000|1705375800000|1705332600123|1705314600000|1705305600000|1704434523400|1705381200000|1705294800000|1705334400000|NaN|NaN|NaN|NaN|NaN|NaN|NaN|NaN|NaN|NaN|NaN|NaN|NaN",
    )

    private fun inZone(zone: String, block: (Context, ScriptableObject) -> Unit) {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            cx.timeZone = TimeZone.of(zone)
            block(cx, cx.initStandardObjects())
        } finally {
            Context.exit()
        }
    }

    private fun evaluate(cx: Context, scope: Scriptable, source: String): String =
        ScriptRuntime.toString(cx.evaluateString(scope, source, "date-parse.js", 1, null))

    @Test
    fun browserFormatsAndStringConstructorAgreeInThreeZones() {
        val failures = mutableListOf<String>()
        for ((zone, values) in expected) inZone(zone) { cx, scope ->
            for ((index, want) in values.split('|').withIndex()) {
                scope.put("input", scope, inputs[index])
                val got = evaluate(cx, scope, "String(Date.parse(input)) + '/' + String(new Date(input).getTime())")
                if (got != "$want/$want") failures += "$zone ${inputs[index]}: wanted $want/$want, got $got"
            }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun invalidCalendarDatesAndOutOfRangeZonesStayInvalid() = inZone("UTC") { cx, scope ->
        for (input in listOf("2024-02-30", "2024-02-30 10:30", "2023-2-29", "2024-01-15 10:30:00+24:00")) {
            scope.put("input", scope, input)
            assertEquals("NaN/NaN", evaluate(cx, scope, "String(Date.parse(input)) + '/' + String(new Date(input).getTime())"), input)
        }
    }

    @Test
    fun fractionalDigitsAreTruncatedWithoutRoundingOrOverflow() = inZone("UTC") { cx, scope ->
        for (fraction in listOf("000999999999999999999999", "0".repeat(1000) + "9")) {
            scope.put("input", scope, "March 31, 2013 23:59:59.$fraction GMT")
            assertEquals("1364774399000", evaluate(cx, scope, "String(Date.parse(input))"))
        }
    }

    @Test
    fun numericConstructorsKeepTheirSpecifiedYearRule() = inZone("UTC") { cx, scope ->
        assertEquals("1926,1949,1950,1999", evaluate(cx, scope,
            "[26,49,50,99].map(function(y){return new Date(y,0,1).getFullYear()}).join(',')"))
        assertEquals("1926,1949,1950,1999", evaluate(cx, scope,
            "[26,49,50,99].map(function(y){return new Date(Date.UTC(y,0,1)).getUTCFullYear()}).join(',')"))
    }

    @Test
    fun standardStringRoundTripsStillWork() {
        for (zone in expected.keys) inZone(zone) { cx, scope ->
            assertEquals("true", evaluate(cx, scope, """
                [0, 1705320000000, 1721044800000].every(function(t) {
                    var d = new Date(t);
                    return Date.parse(d.toString()) === t && Date.parse(d.toUTCString()) === t &&
                        Date.parse(d.toISOString()) === t;
                });
            """), zone)
        }
    }
}
