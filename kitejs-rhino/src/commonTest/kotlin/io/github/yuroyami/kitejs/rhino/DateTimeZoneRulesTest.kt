/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** Fixed Node 26.10 controls; timezone rules and compatible disambiguation are authoritative. */
class DateTimeZoneRulesTest {
    private data class Transition(val zone: String, val fields: String, val instant: String)
    private val transitions = listOf(
        Transition("America/New_York", "2024,2,10,2,30", "2024-03-10T07:30:00.000Z"),
        Transition("America/New_York", "2024,10,3,1,30", "2024-11-03T05:30:00.000Z"),
        Transition("Europe/Berlin", "2024,2,31,2,30", "2024-03-31T01:30:00.000Z"),
        Transition("Europe/Berlin", "2024,9,27,2,30", "2024-10-27T00:30:00.000Z"),
        Transition("Australia/Lord_Howe", "2024,9,6,2,15", "2024-10-05T15:45:00.000Z"),
        Transition("Australia/Lord_Howe", "2024,3,7,1,45", "2024-04-06T14:45:00.000Z"),
        Transition("Pacific/Apia", "2011,11,30,12,0", "2011-12-30T22:00:00.000Z"),
        Transition("Europe/Dublin", "2024,9,27,1,30", "2024-10-27T00:30:00.000Z"),
        Transition("Europe/Dublin", "2024,2,31,1,30", "2024-03-31T01:30:00.000Z"),
    )

    private fun run(zone: String, source: String): String {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            cx.timeZone = TimeZone.of(zone)
            return ScriptRuntime.toString(cx.evaluateString(cx.initStandardObjects(), source, "zone-rules.js", 1, null))
        } finally {
            Context.exit()
        }
    }

    @Test
    fun constructorsChooseTheEarlierOverlapAndMoveForwardAcrossGaps() {
        for (case in transitions) assertEquals(case.instant,
            run(case.zone, "new Date(${case.fields}).toISOString()"), case.toString())
    }

    @Test
    fun offsetFreeIsoAndFallbackParsingUseTheSameResolver() {
        for (case in transitions) assertEquals("${case.instant}|${case.instant}", run(case.zone, """
            var fields = [${case.fields}];
            var civil = new Date(Date.UTC.apply(null, fields)).toISOString().slice(0,-1);
            [new Date(civil).toISOString(), new Date(civil.replace('T',' ')).toISOString()].join('|');
        """), case.toString())
    }

    @Test
    fun everyLocalSetterUsesCompatibleDisambiguation() {
        for (case in transitions) assertEquals(List(8) { case.instant }.joinToString("|"), run(case.zone, """
            var a = [${case.fields}], y=a[0], m=a[1], day=a[2], h=a[3], min=a[4];
            var out = [], d;
            d=new Date(y,m,day); d.setHours(h,min); out.push(d.toISOString());
            d=new Date(y,m,day); d.setMinutes(h*60+min); out.push(d.toISOString());
            d=new Date(y,m,day); d.setSeconds((h*60+min)*60); out.push(d.toISOString());
            d=new Date(y,m,day); d.setMilliseconds((h*60+min)*60000); out.push(d.toISOString());
            d=new Date(y,m,day-1,h,min); d.setDate(day); out.push(d.toISOString());
            d=new Date(y,m-1,day,h,min); d.setMonth(m,day); out.push(d.toISOString());
            d=new Date(y-1,m,day,h,min); d.setFullYear(y,m,day); out.push(d.toISOString());
            d=new Date(y-1,m,day,h,min); d.setYear(y); out.push(d.toISOString());
            out.join('|');
        """), case.toString())
    }

    @Test
    fun localGettersUseHistoricalHalfHourAndDateLineOffsets() {
        val cases = listOf(
            Triple("Africa/Algiers", "1970-01-01T00:00:00Z", "1970,0,1,0,0,0,0"),
            Triple("Africa/Algiers", "2024-01-01T00:00:00Z", "2024,0,1,1,0,0,-60"),
            Triple("Australia/Lord_Howe", "2024-01-01T00:00:00Z", "2024,0,1,11,0,0,-660"),
            Triple("Australia/Lord_Howe", "2024-07-01T00:00:00Z", "2024,6,1,10,30,0,-630"),
            Triple("Pacific/Apia", "1970-01-01T00:00:00Z", "1969,11,31,13,0,0,660"),
            Triple("Pacific/Apia", "2011-12-30T09:59:59Z", "2011,11,29,23,59,59,600"),
            Triple("Pacific/Apia", "2011-12-30T10:00:00Z", "2011,11,31,0,0,0,-840"),
            Triple("Asia/Kathmandu", "1985-12-31T00:00:00Z", "1985,11,31,5,30,0,-330"),
            Triple("Asia/Kathmandu", "1986-01-01T00:00:00Z", "1986,0,1,5,45,0,-345"),
            Triple("Africa/Casablanca", "2024-03-20T00:00:00Z", "2024,2,20,0,0,0,0"),
            Triple("Africa/Casablanca", "2024-04-20T00:00:00Z", "2024,3,20,1,0,0,-60"),
            Triple("Europe/Dublin", "2024-01-01T00:00:00Z", "2024,0,1,0,0,0,0"),
            Triple("Europe/Dublin", "2024-07-01T00:00:00Z", "2024,6,1,1,0,0,-60"),
        )
        // Windows reads zone history from the registry, which starts in the 2000s for most zones.
        // A host with the full IANA history must match every control; another must match its own rules.
        val fullHistory = TimeZone.of("Asia/Kathmandu").offsetAt(Instant.parse("1985-12-31T00:00:00Z")).totalSeconds == 19_800
        for ((zone, instant, expected) in cases) assertEquals(if (fullHistory) expected else hostFields(zone, instant), run(zone, """
            var d = new Date('$instant');
            [d.getFullYear(),d.getMonth(),d.getDate(),d.getHours(),d.getMinutes(),d.getSeconds(),d.getTimezoneOffset()].join(',');
        """), "$zone $instant")
    }

    private fun hostFields(zone: String, instant: String): String {
        val tz = TimeZone.of(zone)
        val at = Instant.parse(instant)
        val local = at.toLocalDateTime(tz)
        val offset = -tz.offsetAt(at).totalSeconds / 60
        return "${local.year},${local.month.number - 1},${local.day},${local.hour},${local.minute},${local.second},$offset"
    }

    @Test
    fun printedGmtOffsetsUseTheRepresentedInstant() {
        for ((zone, instant, offset) in listOf(
            Triple("Australia/Lord_Howe", "2024-01-01T00:00:00Z", "GMT+1100"),
            Triple("Australia/Lord_Howe", "2024-07-01T00:00:00Z", "GMT+1030"),
            Triple("Pacific/Apia", "1970-01-01T00:00:00Z", "GMT-1100"),
            Triple("Pacific/Apia", "2024-01-01T00:00:00Z", "GMT+1300"),
        )) assertEquals("$offset|$offset", run(zone, """
            var d = new Date('$instant');
            [d.toString().match(/GMT[+-][0-9]{4}/)[0],d.toTimeString().match(/GMT[+-][0-9]{4}/)[0]].join('|');
        """), "$zone $instant")
    }

    @Test
    fun utcAndExplicitOffsetsIgnoreLocalTransitions() {
        for (case in transitions) assertEquals("1710037800000,1710037800000,1710037800000,1710028800000", run(case.zone, """
            [Date.UTC(2024,2,10,2,30), Date.parse('2024-03-10T02:30:00Z'),
             Date.parse('2024-03-10T03:30:00+01:00'), Date.parse('2024-03-10')].join(',');
        """), case.zone)
    }
}
