/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.TimeZone

/**
 * One Date program run in three zones, straddling both daylight saving switches of 2024, with the
 * answers upstream Rhino gives on the JVM.
 *
 * Every platform ships its own copy of the IANA database. Running this on JS, iOS and Wasm is what
 * shows they agree with the JVM's, rather than assuming it.
 */
class DateZoneSliceTest {

    private val expected: Map<String, String> = mapOf(
        "UTC" to "2024:2:31:0:30:0:0|2024:2:31:1:30:0:0|2024:2:31:2:30:0:0|2024:9:27:0:30:0:0|2024:9:27:1:30:0:0|2024:9:27:2:30:0:0|2024:0:15:12:0:1:0|2024:6:15:12:0:1:0|1970:0:1:0:0:4:0|1969:11:31:0:0:3:0|2000:0:1:0:0:6:0|2038:0:1:0:0:5:0|0|0|0|0|0|0|0|0|0|0|0|0|2024-07-15T12:00:00.000Z|Mon, 15 Jul 2024 12:00:00 GMT|1721044800000|1705320000000|1721044800000",
        "Europe/Berlin" to "2024:2:31:1:30:0:-60|2024:2:31:3:30:0:-120|2024:2:31:4:30:0:-120|2024:9:27:2:30:0:-120|2024:9:27:2:30:0:-60|2024:9:27:3:30:0:-60|2024:0:15:13:0:1:-60|2024:6:15:14:0:1:-120|1970:0:1:1:0:4:-60|1969:11:31:1:0:3:-60|2000:0:1:1:0:6:-60|2038:0:1:1:0:5:-60|0|0|0|3600000|0|0|0|0|0|0|0|0|2024-07-15T12:00:00.000Z|Mon, 15 Jul 2024 12:00:00 GMT|1721037600000|1705316400000|1721037600000",
        "America/New_York" to "2024:2:30:20:30:6:240|2024:2:30:21:30:6:240|2024:2:30:22:30:6:240|2024:9:26:20:30:6:240|2024:9:26:21:30:6:240|2024:9:26:22:30:6:240|2024:0:15:7:0:1:300|2024:6:15:8:0:1:240|1969:11:31:19:0:3:300|1969:11:30:19:0:2:300|1999:11:31:19:0:5:300|2037:11:31:19:0:4:300|0|0|0|0|0|0|0|0|0|0|0|0|2024-07-15T12:00:00.000Z|Mon, 15 Jul 2024 12:00:00 GMT|1721059200000|1705338000000|1721059200000",
    )

    private val program = "\nvar out = [];\n// Straddles the spring forward and the autumn fall back.\nvar stamps = [\n  Date.UTC(2024, 2, 31, 0, 30), Date.UTC(2024, 2, 31, 1, 30), Date.UTC(2024, 2, 31, 2, 30),\n  Date.UTC(2024, 9, 27, 0, 30), Date.UTC(2024, 9, 27, 1, 30), Date.UTC(2024, 9, 27, 2, 30),\n  Date.UTC(2024, 0, 15, 12, 0), Date.UTC(2024, 6, 15, 12, 0),\n  0, -86400000, 946684800000, 2145916800000\n];\nfor (var i = 0; i < stamps.length; i++) {\n  var d = new Date(stamps[i]);\n  out.push([d.getFullYear(), d.getMonth(), d.getDate(), d.getHours(), d.getMinutes(), d.getDay(), d.getTimezoneOffset()].join(':'));\n}\n// Round trips through the local-time constructor.\nfor (var j = 0; j < stamps.length; j++) {\n  var e = new Date(stamps[j]);\n  var f = new Date(e.getFullYear(), e.getMonth(), e.getDate(), e.getHours(), e.getMinutes(), e.getSeconds());\n  out.push(String(f.getTime() - stamps[j]));\n}\nout.push(new Date(Date.UTC(2024, 6, 15, 12, 0)).toISOString());\nout.push(new Date(Date.UTC(2024, 6, 15, 12, 0)).toUTCString());\nout.push(String(Date.parse('2024-07-15T12:00:00')));\nout.push(String(Date.parse('2024-01-15T12:00:00')));\nout.push(String(Date.parse('Jul 15 2024 12:00:00')));\nout.join('|');\n"

    private fun runIn(zoneId: String): String {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            cx.timeZone = TimeZone.of(zoneId)
            val scope = cx.initStandardObjects()
            return ScriptRuntime.toString(cx.evaluateString(scope, program, "date.js", 1, null))
        } finally {
            Context.exit()
        }
    }

    @Test
    fun zoneRulesMatchUpstream() {
        val failures = mutableListOf<String>()
        for ((zoneId, want) in expected) {
            val got = runIn(zoneId)
            if (got != want) failures.add("$zoneId\n  want: $want\n  got:  $got")
        }
        assertEquals(emptyList(), failures, "zone rules differ from the ones upstream sees")
    }
}
