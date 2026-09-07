/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.reflect.KClass
import kotlin.time.Instant
import kotlinx.datetime.offsetAt

/**
 * The `Date` builtin.
 *
 * All the arithmetic is the spec's own and is computed here, so it gives the same answer on every
 * target. The one thing the engine cannot work out for itself is where daylight saving starts and
 * stops, so that comes from `Context.timeZone`, and the current time comes from `Context.clock`.
 */
internal class NativeDate private constructor() : IdScriptableObject() {

    private var date: Double = 0.0

    override val className: String
        get() = "Date"

    override fun getDefaultValue(hint: KClass<*>?): Any? =
        super.getDefaultValue(hint ?: ScriptRuntime.StringClass)

    internal val jsTimeValue: Double get() = date

    override fun fillConstructorProperties(ctor: IdFunctionObject) {
        addIdFunctionProperty(ctor, DATE_TAG, ConstructorId_now, "now", 0)
        addIdFunctionProperty(ctor, DATE_TAG, ConstructorId_parse, "parse", 1)
        addIdFunctionProperty(ctor, DATE_TAG, ConstructorId_UTC, "UTC", 7)
        super.fillConstructorProperties(ctor)
    }

    override fun initPrototypeId(id: Int) {
        if (id == SymbolId_toPrimitive) {
            initPrototypeMethod(DATE_TAG, id, SymbolKey.TO_PRIMITIVE, "[Symbol.toPrimitive]", 1, DONTENUM or READONLY)
            return
        }
        val arity: Int
        val s: String
        when (id) {
            Id_constructor -> { arity = 7; s = "constructor" }
            Id_toString -> { arity = 0; s = "toString" }
            Id_toTimeString -> { arity = 0; s = "toTimeString" }
            Id_toDateString -> { arity = 0; s = "toDateString" }
            Id_toLocaleString -> { arity = 0; s = "toLocaleString" }
            Id_toLocaleTimeString -> { arity = 0; s = "toLocaleTimeString" }
            Id_toLocaleDateString -> { arity = 0; s = "toLocaleDateString" }
            Id_toUTCString -> { arity = 0; s = "toUTCString" }
            Id_toSource -> { arity = 0; s = "toSource" }
            Id_valueOf -> { arity = 0; s = "valueOf" }
            Id_getTime -> { arity = 0; s = "getTime" }
            Id_getYear -> { arity = 0; s = "getYear" }
            Id_getFullYear -> { arity = 0; s = "getFullYear" }
            Id_getUTCFullYear -> { arity = 0; s = "getUTCFullYear" }
            Id_getMonth -> { arity = 0; s = "getMonth" }
            Id_getUTCMonth -> { arity = 0; s = "getUTCMonth" }
            Id_getDate -> { arity = 0; s = "getDate" }
            Id_getUTCDate -> { arity = 0; s = "getUTCDate" }
            Id_getDay -> { arity = 0; s = "getDay" }
            Id_getUTCDay -> { arity = 0; s = "getUTCDay" }
            Id_getHours -> { arity = 0; s = "getHours" }
            Id_getUTCHours -> { arity = 0; s = "getUTCHours" }
            Id_getMinutes -> { arity = 0; s = "getMinutes" }
            Id_getUTCMinutes -> { arity = 0; s = "getUTCMinutes" }
            Id_getSeconds -> { arity = 0; s = "getSeconds" }
            Id_getUTCSeconds -> { arity = 0; s = "getUTCSeconds" }
            Id_getMilliseconds -> { arity = 0; s = "getMilliseconds" }
            Id_getUTCMilliseconds -> { arity = 0; s = "getUTCMilliseconds" }
            Id_getTimezoneOffset -> { arity = 0; s = "getTimezoneOffset" }
            Id_setTime -> { arity = 1; s = "setTime" }
            Id_setMilliseconds -> { arity = 1; s = "setMilliseconds" }
            Id_setUTCMilliseconds -> { arity = 1; s = "setUTCMilliseconds" }
            Id_setSeconds -> { arity = 2; s = "setSeconds" }
            Id_setUTCSeconds -> { arity = 2; s = "setUTCSeconds" }
            Id_setMinutes -> { arity = 3; s = "setMinutes" }
            Id_setUTCMinutes -> { arity = 3; s = "setUTCMinutes" }
            Id_setHours -> { arity = 4; s = "setHours" }
            Id_setUTCHours -> { arity = 4; s = "setUTCHours" }
            Id_setDate -> { arity = 1; s = "setDate" }
            Id_setUTCDate -> { arity = 1; s = "setUTCDate" }
            Id_setMonth -> { arity = 2; s = "setMonth" }
            Id_setUTCMonth -> { arity = 2; s = "setUTCMonth" }
            Id_setFullYear -> { arity = 3; s = "setFullYear" }
            Id_setUTCFullYear -> { arity = 3; s = "setUTCFullYear" }
            Id_setYear -> { arity = 1; s = "setYear" }
            Id_toISOString -> { arity = 0; s = "toISOString" }
            Id_toJSON -> { arity = 1; s = "toJSON" }
            else -> throw IllegalArgumentException(id.toString())
        }
        initPrototypeMethod(DATE_TAG, id, s, arity)
    }

    override fun execIdCall(f: IdFunctionObject, cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        if (!f.hasTag(DATE_TAG)) return super.execIdCall(f, cx, scope, thisObj, args)

        when (f.methodId()) {
            ConstructorId_now -> return ScriptRuntime.wrapNumber(now(cx))
            ConstructorId_parse -> return ScriptRuntime.wrapNumber(date_parseString(cx, ScriptRuntime.toString(args, 0)))
            ConstructorId_UTC -> return ScriptRuntime.wrapNumber(jsStaticFunction_UTC(args))
            Id_constructor -> {
                // Called as a plain function, Date just prints the current time.
                if (thisObj != null) return date_format(cx, now(cx), Id_toString)
                return jsConstructor(cx, args)
            }
            Id_toJSON -> {
                val o = ScriptRuntime.toObject(cx, scope, thisObj)
                val tv = ScriptRuntime.toPrimitive(o, ScriptRuntime.NumberClass)
                if (tv is Number) {
                    val d = tv.toDouble()
                    if (d.isNaN() || d.isInfinite()) return null
                }
                val toISO = getProperty(o, "toISOString")
                if (toISO === Scriptable.NOT_FOUND) {
                    throw ScriptRuntime.typeErrorById("msg.function.not.found.in", "toISOString", ScriptRuntime.toString(o))
                }
                if (toISO !is Callable) {
                    throw ScriptRuntime.typeErrorById("msg.isnt.function.in", "toISOString", ScriptRuntime.toString(o), ScriptRuntime.toString(toISO))
                }
                val result = toISO.call(cx, scope, o, ScriptRuntime.emptyArgs)
                if (!ScriptRuntime.isPrimitive(result)) {
                    throw ScriptRuntime.typeErrorById("msg.toisostring.must.return.primitive", ScriptRuntime.toString(result))
                }
                return result
            }
            SymbolId_toPrimitive -> {
                val o = ScriptRuntime.toObject(cx, scope, thisObj)
                val arg0 = if (args.isNotEmpty()) args[0] else Undefined.instance
                val hint = if (arg0 is CharSequence) arg0.toString() else null
                val typeHint: KClass<*>? = when (hint) {
                    "string", "default" -> ScriptRuntime.StringClass
                    "number" -> ScriptRuntime.NumberClass
                    else -> null
                }
                if (typeHint == null) {
                    throw ScriptRuntime.typeErrorById("msg.invalid.toprimitive.hint", ScriptRuntime.toString(arg0))
                }
                return getDefaultValue(o, typeHint)
            }
        }

        // Everything below needs `this` to be a real Date.
        val realThis = ensureType<NativeDate>(thisObj, f.functionName)
        var t = realThis.date
        val id = f.methodId()

        when (id) {
            Id_toString, Id_toTimeString, Id_toDateString -> {
                if (!t.isNaN()) return date_format(cx, t, id)
                return js_NaN_date_str
            }
            Id_toLocaleString, Id_toLocaleTimeString, Id_toLocaleDateString -> {
                if (!t.isNaN()) return toLocale_helper(cx, t, id)
                return js_NaN_date_str
            }
            Id_toUTCString -> {
                if (!t.isNaN()) return js_toUTCString(t)
                return js_NaN_date_str
            }
            Id_toSource -> return "(new Date(" + ScriptRuntime.toString(t) + "))"
            Id_valueOf, Id_getTime -> return ScriptRuntime.wrapNumber(t)

            Id_getYear, Id_getFullYear, Id_getUTCFullYear -> {
                if (!t.isNaN()) {
                    if (id != Id_getUTCFullYear) t = LocalTime(cx, t)
                    t = YearFromTime(t).toDouble()
                    if (id == Id_getYear) {
                        if (cx.hasFeature(Context.FEATURE_NON_ECMA_GET_YEAR)) {
                            if (t in 1900.0..1999.0) t -= 1900
                        } else {
                            t -= 1900
                        }
                    }
                }
                return ScriptRuntime.wrapNumber(t)
            }
            Id_getMonth, Id_getUTCMonth -> {
                if (!t.isNaN()) {
                    if (id == Id_getMonth) t = LocalTime(cx, t)
                    t = MonthFromTime(t).toDouble()
                }
                return ScriptRuntime.wrapNumber(t)
            }
            Id_getDate, Id_getUTCDate -> {
                if (!t.isNaN()) {
                    if (id == Id_getDate) t = LocalTime(cx, t)
                    t = DateFromTime(t).toDouble()
                }
                return ScriptRuntime.wrapNumber(t)
            }
            Id_getDay, Id_getUTCDay -> {
                if (!t.isNaN()) {
                    if (id == Id_getDay) t = LocalTime(cx, t)
                    t = WeekDay(t).toDouble()
                }
                return ScriptRuntime.wrapNumber(t)
            }
            Id_getHours, Id_getUTCHours -> {
                if (!t.isNaN()) {
                    if (id == Id_getHours) t = LocalTime(cx, t)
                    t = HourFromTime(t).toDouble()
                }
                return ScriptRuntime.wrapNumber(t)
            }
            Id_getMinutes, Id_getUTCMinutes -> {
                if (!t.isNaN()) {
                    if (id == Id_getMinutes) t = LocalTime(cx, t)
                    t = MinFromTime(t).toDouble()
                }
                return ScriptRuntime.wrapNumber(t)
            }
            Id_getSeconds, Id_getUTCSeconds -> {
                if (!t.isNaN()) {
                    if (id == Id_getSeconds) t = LocalTime(cx, t)
                    t = SecFromTime(t).toDouble()
                }
                return ScriptRuntime.wrapNumber(t)
            }
            Id_getMilliseconds, Id_getUTCMilliseconds -> {
                if (!t.isNaN()) {
                    if (id == Id_getMilliseconds) t = LocalTime(cx, t)
                    t = msFromTime(t).toDouble()
                }
                return ScriptRuntime.wrapNumber(t)
            }
            Id_getTimezoneOffset -> {
                if (!t.isNaN()) t = (t - LocalTime(cx, t)) / msPerMinute
                return ScriptRuntime.wrapNumber(t)
            }

            Id_setTime -> {
                t = TimeClip(ScriptRuntime.toNumber(args, 0))
                realThis.date = t
                return ScriptRuntime.wrapNumber(t)
            }
            Id_setMilliseconds, Id_setUTCMilliseconds, Id_setSeconds, Id_setUTCSeconds,
            Id_setMinutes, Id_setUTCMinutes, Id_setHours, Id_setUTCHours,
            -> {
                t = makeTime(cx, t, args, id)
                realThis.date = t
                return ScriptRuntime.wrapNumber(t)
            }
            Id_setDate, Id_setUTCDate, Id_setMonth, Id_setUTCMonth, Id_setFullYear, Id_setUTCFullYear -> {
                t = makeDate(cx, t, args, id)
                realThis.date = t
                return ScriptRuntime.wrapNumber(t)
            }
            Id_setYear -> {
                var year = ScriptRuntime.toNumber(args, 0)
                if (year.isNaN() || year.isInfinite()) {
                    t = ScriptRuntime.NaN
                } else {
                    t = if (t.isNaN()) 0.0 else LocalTime(cx, t)
                    if (year in 0.0..99.0) year += 1900
                    val day = MakeDay(year, MonthFromTime(t).toDouble(), DateFromTime(t).toDouble())
                    t = MakeDate(day, TimeWithinDay(t))
                    t = internalUTC(cx, t)
                    t = TimeClip(t)
                }
                realThis.date = t
                return ScriptRuntime.wrapNumber(t)
            }
            Id_toISOString -> {
                if (!t.isNaN()) return js_toISOString(t)
                throw ScriptRuntime.rangeError(Messages.getMessageById("msg.invalid.date"))
            }
            else -> throw IllegalArgumentException(id.toString())
        }
    }

    override fun findPrototypeId(name: String): Int = when (name) {
        "constructor" -> Id_constructor
        "toString" -> Id_toString
        "toTimeString" -> Id_toTimeString
        "toDateString" -> Id_toDateString
        "toLocaleString" -> Id_toLocaleString
        "toLocaleTimeString" -> Id_toLocaleTimeString
        "toLocaleDateString" -> Id_toLocaleDateString
        "toUTCString" -> Id_toUTCString
        "toSource" -> Id_toSource
        "valueOf" -> Id_valueOf
        "getTime" -> Id_getTime
        "getYear" -> Id_getYear
        "getFullYear" -> Id_getFullYear
        "getUTCFullYear" -> Id_getUTCFullYear
        "getMonth" -> Id_getMonth
        "getUTCMonth" -> Id_getUTCMonth
        "getDate" -> Id_getDate
        "getUTCDate" -> Id_getUTCDate
        "getDay" -> Id_getDay
        "getUTCDay" -> Id_getUTCDay
        "getHours" -> Id_getHours
        "getUTCHours" -> Id_getUTCHours
        "getMinutes" -> Id_getMinutes
        "getUTCMinutes" -> Id_getUTCMinutes
        "getSeconds" -> Id_getSeconds
        "getUTCSeconds" -> Id_getUTCSeconds
        "getMilliseconds" -> Id_getMilliseconds
        "getUTCMilliseconds" -> Id_getUTCMilliseconds
        "getTimezoneOffset" -> Id_getTimezoneOffset
        "setTime" -> Id_setTime
        "setMilliseconds" -> Id_setMilliseconds
        "setUTCMilliseconds" -> Id_setUTCMilliseconds
        "setSeconds" -> Id_setSeconds
        "setUTCSeconds" -> Id_setUTCSeconds
        "setMinutes" -> Id_setMinutes
        "setUTCMinutes" -> Id_setUTCMinutes
        "setHours" -> Id_setHours
        "setUTCHours" -> Id_setUTCHours
        "setDate" -> Id_setDate
        "setUTCDate" -> Id_setUTCDate
        "setMonth" -> Id_setMonth
        "setUTCMonth" -> Id_setUTCMonth
        "setFullYear" -> Id_setFullYear
        "setUTCFullYear" -> Id_setUTCFullYear
        "setYear" -> Id_setYear
        "toISOString" -> Id_toISOString
        "toJSON" -> Id_toJSON
        "toGMTString" -> Id_toGMTString
        else -> 0
    }

    override fun findPrototypeId(key: Symbol): Int =
        if (SymbolKey.TO_PRIMITIVE == key) SymbolId_toPrimitive else 0

    companion object {
        private val DATE_TAG: Any = "Date"
        private const val js_NaN_date_str = "Invalid Date"

        internal fun init(scope: Scriptable, sealed: Boolean) {
            val obj = NativeDate()
            // The prototype itself is an invalid date.
            obj.date = ScriptRuntime.NaN
            obj.exportAsJSClass(MAX_PROTOTYPE_ID, scope, sealed)
        }

        // ---- The spec's own arithmetic -------------------------------------------------------

        private const val HalfTimeDomain = 8.64e15
        private const val HoursPerDay = 24.0
        private const val MinutesPerHour = 60.0
        private const val SecondsPerMinute = 60.0
        private const val msPerSecond = 1000.0
        private const val MinutesPerDay = HoursPerDay * MinutesPerHour
        private const val SecondsPerDay = MinutesPerDay * SecondsPerMinute
        private const val SecondsPerHour = MinutesPerHour * SecondsPerMinute
        private const val msPerDay = SecondsPerDay * msPerSecond
        private const val msPerHour = SecondsPerHour * msPerSecond
        private const val msPerMinute = SecondsPerMinute * msPerSecond

        private fun Day(t: Double): Double = floor(t / msPerDay)

        private fun TimeWithinDay(t: Double): Double {
            var result = t % msPerDay
            if (result < 0) result += msPerDay
            return result
        }

        private fun IsLeapYear(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

        /** Floating point on purpose: `floor((1968 - 1969) / 4)` has to be -1. */
        private fun DayFromYear(y: Double): Double =
            (365 * (y - 1970)) + floor((y - 1969) / 4.0) - floor((y - 1901) / 100.0) + floor((y - 1601) / 400.0)

        private fun TimeFromYear(y: Double): Double = DayFromYear(y) * msPerDay

        private fun YearFromTime(t: Double): Int {
            if (t.isInfinite() || t.isNaN()) return 0

            var y = floor(t / (msPerDay * 365.2425)) + 1970
            val t2 = TimeFromYear(y)

            // The guess used an average year length, so it is usually wrong within a few hours of
            // a year boundary.
            if (t2 > t) {
                y--
            } else {
                if (t2 + msPerDay * DaysInYear(y) <= t) y++
            }
            return y.toInt()
        }

        private fun DayFromMonth(m: Int, year: Int): Double {
            var day = m * 30
            if (m >= 7) {
                day += m / 2 - 1
            } else if (m >= 2) {
                day += (m - 1) / 2 - 1
            } else {
                day += m
            }
            if (m >= 2 && IsLeapYear(year)) ++day
            return day.toDouble()
        }

        private fun DaysInYear(year: Double): Double {
            if (year.isInfinite() || year.isNaN()) return ScriptRuntime.NaN
            return if (IsLeapYear(year.toInt())) 366.0 else 365.0
        }

        /** Month is 1-based here, unlike everywhere else. */
        private fun DaysInMonth(year: Int, month: Int): Int {
            if (month == 2) return if (IsLeapYear(year)) 29 else 28
            return if (month >= 8) 31 - (month and 1) else 30 + (month and 1)
        }

        private fun MonthFromTime(t: Double): Int {
            val year = YearFromTime(t)
            var d = (Day(t) - DayFromYear(year.toDouble())).toInt()

            d -= 31 + 28
            if (d < 0) return if (d < -28) 0 else 1

            if (IsLeapYear(year)) {
                if (d == 0) return 1 // 29 February
                --d
            }

            // d now counts days from 1 March.
            val estimate = d / 30
            val mstart = when (estimate) {
                0 -> return 2
                1 -> 31
                2 -> 31 + 30
                3 -> 31 + 30 + 31
                4 -> 31 + 30 + 31 + 30
                5 -> 31 + 30 + 31 + 30 + 31
                6 -> 31 + 30 + 31 + 30 + 31 + 31
                7 -> 31 + 30 + 31 + 30 + 31 + 31 + 30
                8 -> 31 + 30 + 31 + 30 + 31 + 31 + 30 + 31
                9 -> 31 + 30 + 31 + 30 + 31 + 31 + 30 + 31 + 30
                10 -> return 11 // late December
                else -> throw Kit.codeBug()
            }
            return if (d >= mstart) estimate + 2 else estimate + 1
        }

        private fun DateFromTime(t: Double): Int {
            val year = YearFromTime(t)
            var d = (Day(t) - DayFromYear(year.toDouble())).toInt()

            d -= 31 + 28
            if (d < 0) return if (d < -28) d + 31 + 28 + 1 else d + 28 + 1

            if (IsLeapYear(year)) {
                if (d == 0) return 29 // 29 February
                --d
            }

            val mdays: Int
            val mstart: Int
            when (d / 30) {
                0 -> return d + 1
                1 -> { mdays = 31; mstart = 31 }
                2 -> { mdays = 30; mstart = 31 + 30 }
                3 -> { mdays = 31; mstart = 31 + 30 + 31 }
                4 -> { mdays = 30; mstart = 31 + 30 + 31 + 30 }
                5 -> { mdays = 31; mstart = 31 + 30 + 31 + 30 + 31 }
                6 -> { mdays = 31; mstart = 31 + 30 + 31 + 30 + 31 + 31 }
                7 -> { mdays = 30; mstart = 31 + 30 + 31 + 30 + 31 + 31 + 30 }
                8 -> { mdays = 31; mstart = 31 + 30 + 31 + 30 + 31 + 31 + 30 + 31 }
                9 -> { mdays = 30; mstart = 31 + 30 + 31 + 30 + 31 + 31 + 30 + 31 + 30 }
                10 -> return d - (31 + 30 + 31 + 30 + 31 + 31 + 30 + 31 + 30) + 1 // late December
                else -> throw Kit.codeBug()
            }
            d -= mstart
            // The estimate was one month high, so step back.
            if (d < 0) d += mdays
            return d + 1
        }

        private fun WeekDay(t: Double): Int {
            var result = Day(t) + 4
            result %= 7
            if (result < 0) result += 7
            return result.toInt()
        }

        private fun now(cx: Context): Double = cx.clock()

        // ---- The two questions the engine asks the platform ------------------------------------

        /** January and July of a recent year, which is what pins down the standard offset. */
        private const val REFERENCE_JANUARY = 1704067200000L
        private const val REFERENCE_JULY = 1719792000000L

        /**
         * The zone's offset outside daylight time, in milliseconds. Daylight saving only ever adds,
         * so the smaller of the January and July offsets is the standard one in either hemisphere.
         */
        private fun rawOffset(cx: Context): Int {
            val cached = cx.rawTimeZoneOffsetMs
            if (cached != null) return cached
            val tz = cx.timeZone
            val jan = tz.offsetAt(Instant.fromEpochMilliseconds(REFERENCE_JANUARY)).totalSeconds
            val jul = tz.offsetAt(Instant.fromEpochMilliseconds(REFERENCE_JULY)).totalSeconds
            val raw = (if (jan <= jul) jan else jul) * 1000
            cx.rawTimeZoneOffsetMs = raw
            return raw
        }

        private fun offsetMsAt(cx: Context, t: Double): Int {
            val millis = t.coerceIn(-8.64e15, 8.64e15).toLong()
            return cx.timeZone.offsetAt(Instant.fromEpochMilliseconds(millis)).totalSeconds * 1000
        }

        /**
         * Whether the zone is on daylight time at [t].
         *
         * Comparing against the zone's offset today would be wrong: a zone that has changed its
         * standard offset since would then look permanently on daylight time. Africa/Algiers is the
         * example that found this, being UTC+0 in 1970 and UTC+1 now. So the standard offset is
         * taken from [t]'s own year, as the smaller of its January and July offsets, and daylight
         * time is when [t] sits above it. That reads correctly in the southern hemisphere too,
         * where the January offset is the larger one.
         */
        private fun inDaylightTime(cx: Context, t: Double): Boolean {
            if (t.isNaN() || t.isInfinite()) return false
            val year = YearFromTime(t).toDouble()
            val january = offsetMsAt(cx, MakeDate(MakeDay(year, 0.0, 1.0), 0.0))
            val july = offsetMsAt(cx, MakeDate(MakeDay(year, 6.0, 1.0), 0.0))
            val standard = if (january <= july) january else july
            return offsetMsAt(cx, t) > standard
        }

        /**
         * Upstream answers a whole hour whenever the zone is in daylight time, even for the few
         * zones that shift by half an hour. The port keeps that, since parity is the point.
         */
        private fun DaylightSavingTA(cx: Context, tIn: Double): Double {
            var t = tIn
            // Zone rules before 1970 are patchy, so upstream maps an early date onto a year whose
            // weekdays line up and asks about that instead.
            if (t < 0.0) {
                val year = EquivalentYear(YearFromTime(t))
                val day = MakeDay(year.toDouble(), MonthFromTime(t).toDouble(), DateFromTime(t).toDouble())
                t = MakeDate(day, TimeWithinDay(t))
            }
            return if (inDaylightTime(cx, t)) msPerHour else 0.0
        }

        /**
         * A year whose dates fall on the same weekdays. Only safe for working out daylight saving,
         * and even then not near a year boundary.
         */
        private fun EquivalentYear(year: Int): Int {
            var day = DayFromYear(year.toDouble()).toInt() + 4
            day %= 7
            if (day < 0) day += 7
            return if (IsLeapYear(year)) {
                when (day) {
                    0 -> 1984; 1 -> 1996; 2 -> 1980; 3 -> 1992; 4 -> 1976; 5 -> 1988; 6 -> 1972
                    else -> throw Kit.codeBug()
                }
            } else {
                when (day) {
                    0 -> 1978; 1 -> 1973; 2 -> 1985; 3 -> 1986; 4 -> 1981; 5 -> 1971; 6 -> 1977
                    else -> throw Kit.codeBug()
                }
            }
        }

        private fun LocalTime(cx: Context, t: Double): Double = t + rawOffset(cx) + DaylightSavingTA(cx, t)

        private fun internalUTC(cx: Context, t: Double): Double {
            val local = t - rawOffset(cx)
            return local - DaylightSavingTA(cx, local)
        }

        private fun HourFromTime(t: Double): Int {
            var result = floor(t / msPerHour) % HoursPerDay
            if (result < 0) result += HoursPerDay
            return result.toInt()
        }

        private fun MinFromTime(t: Double): Int {
            var result = floor(t / msPerMinute) % MinutesPerHour
            if (result < 0) result += MinutesPerHour
            return result.toInt()
        }

        private fun SecFromTime(t: Double): Int {
            var result = floor(t / msPerSecond) % SecondsPerMinute
            if (result < 0) result += SecondsPerMinute
            return result.toInt()
        }

        private fun msFromTime(t: Double): Int {
            var result = t % msPerSecond
            if (result < 0) result += msPerSecond
            return result.toInt()
        }

        private fun MakeTime(hour: Double, min: Double, sec: Double, ms: Double): Double =
            ((hour * MinutesPerHour + min) * SecondsPerMinute + sec) * msPerSecond + ms

        private fun MakeDay(yearIn: Double, monthIn: Double, date: Double): Double {
            var year = yearIn
            var month = monthIn
            year += floor(month / 12)
            month %= 12
            if (month < 0) month += 12

            val yearday = floor(TimeFromYear(year) / msPerDay)
            val monthday = DayFromMonth(month.toInt(), year.toInt())
            return yearday + monthday + date - 1
        }

        private fun MakeDate(day: Double, time: Double): Double = day * msPerDay + time

        private fun TimeClip(d: Double): Double {
            if (d.isNaN() || d == Double.POSITIVE_INFINITY || d == Double.NEGATIVE_INFINITY || abs(d) > HalfTimeDomain) {
                return ScriptRuntime.NaN
            }
            // Adding zero is not redundant: it turns -0.0 into +0.0, which is what TimeClip owes
            // the caller. `new Date(-0).getTime()` has to be +0.
            return if (d > 0.0) floor(d + 0.0) else ceil(d + 0.0)
        }

        /** UTC milliseconds for the given parts, with no 1900 correction. */
        private fun date_msecFromDate(
            year: Double,
            mon: Double,
            mday: Double,
            hour: Double,
            min: Double,
            sec: Double,
            msec: Double,
        ): Double = MakeDate(MakeDay(year, mon, mday), MakeTime(hour, min, sec, msec))

        private const val MAXARGS = 7

        private fun date_msecFromArgs(args: Array<Any?>): Double {
            val array = DoubleArray(MAXARGS)
            for (loop in 0 until MAXARGS) {
                if (loop < args.size) {
                    val d = ScriptRuntime.toNumber(args[loop])
                    if (d.isNaN() || d.isInfinite()) return ScriptRuntime.NaN
                    array[loop] = ScriptRuntime.toInteger(args[loop])
                } else {
                    array[loop] = if (loop == 2) 1.0 else 0.0 // the day defaults to 1
                }
            }
            // Two-digit years mean the twentieth century.
            if (array[0] >= 0 && array[0] <= 99) array[0] += 1900
            return date_msecFromDate(array[0], array[1], array[2], array[3], array[4], array[5], array[6])
        }

        private fun jsStaticFunction_UTC(args: Array<Any?>): Double {
            if (args.isEmpty()) return ScriptRuntime.NaN
            return TimeClip(date_msecFromArgs(args))
        }

        // ---- Parsing ---------------------------------------------------------------------------

        /**
         * The simplified ISO 8601 form the spec defines: `YYYY-MM-DD'T'HH:mm:ss.sss'Z'` or the same
         * with a `+hh:mm` offset. Read by a small state machine.
         */
        private fun parseISOString(cx: Context, s: String): Double {
            val ERROR = -1
            val YEAR = 0
            val MONTH = 1
            val DAY = 2
            val HOUR = 3
            val MIN = 4
            val SEC = 5
            val MSEC = 6
            val TZHOUR = 7
            val TZMIN = 8
            var state = YEAR
            val values = intArrayOf(1970, 1, 1, 0, 0, 0, 0, -1, -1)
            var timeSpecified = false
            var yearlen = 4
            var yearmod = 1
            var tzmod = 1
            var i = 0
            val len = s.length
            if (len != 0) {
                val c = s[0]
                if (c == '+' || c == '-') {
                    // Extended years.
                    i += 1
                    yearlen = 6
                    yearmod = if (c == '-') -1 else 1
                } else if (c == 'T' && cx.languageVersion < Context.VERSION_ES6) {
                    // Time-only forms left the spec, but SpiderMonkey still takes them.
                    i += 1
                    state = HOUR
                }
            }

            var broke = false
            while (state != ERROR) {
                if (state == MSEC) {
                    // Milliseconds are the odd one out: the second and third digits are optional.
                    var value = 0
                    var digitsFound = 0
                    while (i < len) {
                        val c = s[i]
                        if (c < '0' || c > '9') break
                        if (digitsFound < 3) {
                            value = 10 * value + (c - '0')
                            digitsFound++
                        }
                        i++
                    }
                    if (digitsFound == 0) {
                        state = ERROR
                        broke = true
                        break
                    }
                    if (digitsFound < 3) value *= if (digitsFound == 1) 100 else 10
                    values[state] = value
                    if (i == len) break // no zone at all is fine here
                } else {
                    val m = i + (if (state == YEAR) yearlen else 2)
                    if (m > len) {
                        state = ERROR
                        break
                    }
                    var value = 0
                    var bad = false
                    while (i < m) {
                        val c = s[i]
                        if (c < '0' || c > '9') {
                            state = ERROR
                            bad = true
                            break
                        }
                        value = 10 * value + (c - '0')
                        ++i
                    }
                    if (bad) {
                        broke = true
                        break
                    }
                    values[state] = value
                    if (i == len) {
                        if (state == HOUR || state == TZHOUR) state = ERROR
                        break
                    }
                }

                val c = s[i++]
                if (c == 'Z') {
                    values[TZHOUR] = 0
                    values[TZMIN] = 0
                    when (state) {
                        YEAR, MONTH, DAY, MIN, SEC, MSEC -> {}
                        else -> state = ERROR
                    }
                    break
                }

                state = when (state) {
                    YEAR, MONTH -> if (c == '-') state + 1 else if (c == 'T') HOUR else ERROR
                    DAY -> if (c == 'T') HOUR else ERROR
                    HOUR -> {
                        timeSpecified = true
                        if (c == ':') MIN else ERROR
                    }
                    TZHOUR -> {
                        // Non-standard: the minutes may follow without a colon.
                        if (c != ':') i -= 1
                        TZMIN
                    }
                    MIN -> if (c == ':') SEC else if (c == '+' || c == '-') TZHOUR else ERROR
                    SEC -> if (c == '.') MSEC else if (c == '+' || c == '-') TZHOUR else ERROR
                    MSEC -> if (c == '+' || c == '-') TZHOUR else ERROR
                    TZMIN -> ERROR
                    else -> state
                }
                if (state == TZHOUR) tzmod = if (c == '-') -1 else 1
            }

            if (state == ERROR || i != len || broke && state == ERROR) return ScriptRuntime.NaN
            if (state == ERROR) return ScriptRuntime.NaN

            val year = values[YEAR]
            val month = values[MONTH]
            val day = values[DAY]
            val hour = values[HOUR]
            val min = values[MIN]
            val sec = values[SEC]
            val msec = values[MSEC]
            val tzhour = values[TZHOUR]
            val tzmin = values[TZMIN]
            if (year > 275943 || // ceil(1e8/365) + 1970
                (month < 1 || month > 12) ||
                (day < 1 || day > DaysInMonth(year, month)) ||
                hour > 24 ||
                (hour == 24 && (min > 0 || sec > 0 || msec > 0)) ||
                min > 59 || sec > 59 || tzhour > 23 || tzmin > 59
            ) {
                return ScriptRuntime.NaN
            }

            var date = date_msecFromDate(
                (year * yearmod).toDouble(), (month - 1).toDouble(), day.toDouble(),
                hour.toDouble(), min.toDouble(), sec.toDouble(), msec.toDouble(),
            )
            if (tzhour == -1) {
                // The spec says UTC here, but every browser uses local time when a time was given.
                if (timeSpecified) date -= rawOffset(cx) + DaylightSavingTA(cx, date)
            } else {
                date -= (tzhour * 60 + tzmin) * msPerMinute * tzmod
            }

            if (date < -HalfTimeDomain || date > HalfTimeDomain) return ScriptRuntime.NaN
            return date
        }

        /** The loose formats, tried after ISO 8601 fails. Ported from jsdate.c, not from a locale. */
        private fun date_parseString(cx: Context, s: String): Double {
            val d = parseISOString(cx, s)
            if (!d.isNaN()) return d

            var year = -1
            var mon = -1
            var mday = -1
            var hour = -1
            var min = -1
            var sec = -1
            var c: Char
            var si: Char
            var i = 0
            var n: Int
            var tzoffset = -1.0
            var prevc = 0.toChar()
            var seenplusminus = false

            val limit = s.length
            while (i < limit) {
                c = s[i]
                i++
                if (c <= ' ' || c == ',' || c == '-') {
                    if (i < limit) {
                        si = s[i]
                        if (c == '-' && si in '0'..'9') prevc = c
                    }
                    continue
                }
                if (c == '(') {
                    // A parenthesised comment.
                    var depth = 1
                    while (i < limit) {
                        c = s[i]
                        i++
                        if (c == '(') {
                            depth++
                        } else if (c == ')') {
                            if (--depth <= 0) break
                        }
                    }
                    continue
                }
                if (c in '0'..'9') {
                    n = c - '0'
                    while (i < limit && s[i].also { c = it } in '0'..'9') {
                        n = n * 10 + (c - '0')
                        i++
                    }

                    if (prevc == '+' || prevc == '-') {
                        // A zone offset, which also lets a colon appear inside it.
                        seenplusminus = true
                        n = if (n < 24) n * 60 else n % 100 + n / 100 * 60
                        if (prevc == '+') n = -n // plus means east of GMT
                        if (tzoffset != 0.0 && tzoffset != -1.0) return ScriptRuntime.NaN
                        tzoffset = n.toDouble()
                    } else if (n >= 70 || (prevc == '/' && mon >= 0 && mday >= 0 && year < 0)) {
                        if (year >= 0) {
                            return ScriptRuntime.NaN
                        } else if (c <= ' ' || c == ',' || c == '/' || i >= limit) {
                            year = if (n < 100) n + 1900 else n
                        } else {
                            return ScriptRuntime.NaN
                        }
                    } else if (c == ':') {
                        if (hour < 0) hour = n else if (min < 0) min = n else return ScriptRuntime.NaN
                    } else if (c == '/') {
                        if (mon < 0) mon = n - 1 else if (mday < 0) mday = n else return ScriptRuntime.NaN
                    } else if (i < limit && c != ',' && c > ' ' && c != '-') {
                        return ScriptRuntime.NaN
                    } else if (seenplusminus && n < 60) {
                        // GMT-3:30 and friends.
                        if (tzoffset < 0) tzoffset -= n else tzoffset += n
                    } else if (hour >= 0 && min < 0) {
                        min = n
                    } else if (min >= 0 && sec < 0) {
                        sec = n
                    } else if (mday < 0) {
                        mday = n
                    } else {
                        return ScriptRuntime.NaN
                    }
                    prevc = 0.toChar()
                } else if (c == '/' || c == ':' || c == '+' || c == '-') {
                    prevc = c
                } else {
                    val st = i - 1
                    while (i < limit) {
                        c = s[i]
                        if (!((c in 'A'..'Z') || (c in 'a'..'z'))) break
                        i++
                    }
                    val letterCount = i - st
                    if (letterCount < 2) return ScriptRuntime.NaN

                    var index = 0
                    var wtbOffset = 0
                    while (true) {
                        val wtbNext = WORD_TABLE.indexOf(';', wtbOffset)
                        if (wtbNext < 0) return ScriptRuntime.NaN
                        if (WORD_TABLE.regionMatches(wtbOffset, s, st, letterCount, ignoreCase = true)) break
                        wtbOffset = wtbNext + 1
                        ++index
                    }
                    if (index < 2) {
                        // AM or PM: 12:30 AM is 00:30 and 12:30 PM is 12:30.
                        if (hour > 12 || hour < 0) {
                            return ScriptRuntime.NaN
                        } else if (index == 0) {
                            if (hour == 12) hour = 0
                        } else {
                            if (hour != 12) hour += 12
                        }
                    } else if ((index - 2).also { index = it } < 7) {
                        // A weekday name, which carries no information.
                    } else if ((index - 7).also { index = it } < 12) {
                        if (mon < 0) mon = index else return ScriptRuntime.NaN
                    } else {
                        index -= 12
                        tzoffset = when (index) {
                            0, 1, 2 -> 0.0 // gmt, ut, utc
                            3 -> 5.0 * 60 // est
                            4 -> 4.0 * 60 // edt
                            5 -> 6.0 * 60 // cst
                            6 -> 5.0 * 60 // cdt
                            7 -> 7.0 * 60 // mst
                            8 -> 6.0 * 60 // mdt
                            9 -> 8.0 * 60 // pst
                            10 -> 7.0 * 60 // pdt
                            else -> throw Kit.codeBug()
                        }
                    }
                }
            }
            if (year < 0 || mon < 0 || mday < 0) return ScriptRuntime.NaN
            if (sec < 0) sec = 0
            if (min < 0) min = 0
            if (hour < 0) hour = 0

            val msec = date_msecFromDate(
                year.toDouble(), mon.toDouble(), mday.toDouble(),
                hour.toDouble(), min.toDouble(), sec.toDouble(), 0.0,
            )
            if (tzoffset == -1.0) return internalUTC(cx, msec)
            return msec + tzoffset * msPerMinute
        }

        private const val WORD_TABLE =
            "am;pm;" +
                "monday;tuesday;wednesday;thursday;friday;saturday;sunday;" +
                "january;february;march;april;may;june;" +
                "july;august;september;october;november;december;" +
                "gmt;ut;utc;est;edt;cst;cdt;mst;mdt;pst;pdt;"

        // ---- Formatting -------------------------------------------------------------------------

        private fun date_format(cx: Context, tIn: Double, methodId: Int): String {
            var t = tIn
            val result = StringBuilder(60)
            val local = LocalTime(cx, t)

            // Tue Oct 31 2000 09:41:40 GMT-0800 (PST)
            if (methodId != Id_toTimeString) {
                appendWeekDayName(result, WeekDay(local))
                result.append(' ')
                appendMonthName(result, MonthFromTime(local))
                result.append(' ')
                append0PaddedUint(result, DateFromTime(local), 2)
                result.append(' ')
                var year = YearFromTime(local)
                if (year < 0) {
                    result.append('-')
                    year = -year
                }
                append0PaddedUint(result, year, 4)
                if (methodId != Id_toDateString) result.append(' ')
            }

            if (methodId != Id_toDateString) {
                append0PaddedUint(result, HourFromTime(local), 2)
                result.append(':')
                append0PaddedUint(result, MinFromTime(local), 2)
                result.append(':')
                append0PaddedUint(result, SecFromTime(local), 2)

                // Minutes from GMT, daylight saving included.
                val minutes = floor((rawOffset(cx) + DaylightSavingTA(cx, t)) / msPerMinute).toInt()
                // 510 minutes prints as 0830.
                var offset = (minutes / 60) * 100 + minutes % 60
                if (offset > 0) {
                    result.append(" GMT+")
                } else {
                    result.append(" GMT-")
                    offset = -offset
                }
                append0PaddedUint(result, offset, 4)

                // The equivalent year again, for the same reason DaylightSavingTA needs it.
                if (t < 0.0) {
                    val equiv = EquivalentYear(YearFromTime(local))
                    val day = MakeDay(equiv.toDouble(), MonthFromTime(t).toDouble(), DateFromTime(t).toDouble())
                    t = MakeDate(day, TimeWithinDay(t))
                }
                result.append(" (")
                result.append(zoneName(cx))
                result.append(')')
            }
            return result.toString()
        }

        /**
         * The short zone name upstream gets from the JDK's locale data. There is no such data in
         * common Kotlin, so the zone id stands in (D-45).
         */
        private fun zoneName(cx: Context): String = cx.timeZone.id

        private fun jsConstructor(cx: Context, args: Array<Any?>): Any {
            val obj = NativeDate()

            if (args.isEmpty()) {
                obj.date = now(cx)
                return obj
            }

            if (args.size == 1) {
                val value = args[0]
                if (value is NativeDate) {
                    obj.date = value.date
                    return obj
                }
                val v = ScriptRuntime.toPrimitive(value)
                val date = if (v is CharSequence) date_parseString(cx, v.toString()) else ScriptRuntime.toNumber(v)
                obj.date = TimeClip(date)
                return obj
            }

            var time = date_msecFromArgs(args)
            if (!time.isNaN() && !time.isInfinite()) time = TimeClip(internalUTC(cx, time))
            obj.date = time
            return obj
        }

        /**
         * The locale-sensitive formats. Upstream asks `java.time` with the default locale; this
         * uses the en-US patterns that produces, and ignores the locale argument (D-46).
         */
        private fun toLocale_helper(cx: Context, t: Double, methodId: Int): String {
            val local = LocalTime(cx, t)
            val es6 = cx.languageVersion >= Context.VERSION_ES6
            val sb = StringBuilder(40)

            val wantDate = methodId == Id_toLocaleString || methodId == Id_toLocaleDateString
            val wantTime = methodId == Id_toLocaleString || methodId == Id_toLocaleTimeString

            if (es6) {
                // The short forms: 1/2/24, 3:04 AM
                if (wantDate) {
                    sb.append(MonthFromTime(local) + 1).append('/').append(DateFromTime(local)).append('/')
                    append0PaddedUint(sb, twoDigitYear(YearFromTime(local)), 2)
                }
                if (wantDate && wantTime) sb.append(", ")
                if (wantTime) {
                    appendClockTime(sb, local, withSeconds = false)
                }
            } else {
                // The long forms: January 2, 2024 3:04:05 AM UTC
                if (wantDate) {
                    appendFullMonthName(sb, MonthFromTime(local))
                    sb.append(' ').append(DateFromTime(local)).append(", ").append(YearFromTime(local))
                }
                if (wantDate && wantTime) sb.append(' ')
                if (wantTime) {
                    appendClockTime(sb, local, withSeconds = true)
                    sb.append(' ').append(zoneName(cx))
                }
            }
            return sb.toString()
        }

        private fun twoDigitYear(year: Int): Int {
            val y = year % 100
            return if (y < 0) y + 100 else y
        }

        private fun appendClockTime(sb: StringBuilder, local: Double, withSeconds: Boolean) {
            val hour24 = HourFromTime(local)
            var hour = hour24 % 12
            if (hour == 0) hour = 12
            sb.append(hour).append(':')
            append0PaddedUint(sb, MinFromTime(local), 2)
            if (withSeconds) {
                sb.append(':')
                append0PaddedUint(sb, SecFromTime(local), 2)
            }
            sb.append(if (hour24 < 12) " AM" else " PM")
        }

        private fun js_toUTCString(date: Double): String {
            val result = StringBuilder(60)
            appendWeekDayName(result, WeekDay(date))
            result.append(", ")
            append0PaddedUint(result, DateFromTime(date), 2)
            result.append(' ')
            appendMonthName(result, MonthFromTime(date))
            result.append(' ')
            var year = YearFromTime(date)
            if (year < 0) {
                result.append('-')
                year = -year
            }
            append0PaddedUint(result, year, 4)
            result.append(' ')
            append0PaddedUint(result, HourFromTime(date), 2)
            result.append(':')
            append0PaddedUint(result, MinFromTime(date), 2)
            result.append(':')
            append0PaddedUint(result, SecFromTime(date), 2)
            result.append(" GMT")
            return result.toString()
        }

        private fun js_toISOString(t: Double): String {
            val result = StringBuilder(27)
            val year = YearFromTime(t)
            if (year < 0) {
                result.append('-')
                append0PaddedUint(result, -year, 6)
            } else if (year > 9999) {
                result.append('+')
                append0PaddedUint(result, year, 6)
            } else {
                append0PaddedUint(result, year, 4)
            }
            result.append('-')
            append0PaddedUint(result, MonthFromTime(t) + 1, 2)
            result.append('-')
            append0PaddedUint(result, DateFromTime(t), 2)
            result.append('T')
            append0PaddedUint(result, HourFromTime(t), 2)
            result.append(':')
            append0PaddedUint(result, MinFromTime(t), 2)
            result.append(':')
            append0PaddedUint(result, SecFromTime(t), 2)
            result.append('.')
            append0PaddedUint(result, msFromTime(t), 3)
            result.append('Z')
            return result.toString()
        }

        private fun append0PaddedUint(sb: StringBuilder, iIn: Int, minWidthIn: Int) {
            if (iIn < 0) throw Kit.codeBug()
            var i = iIn
            var minWidth = minWidthIn
            var scale = 1
            --minWidth
            if (i >= 10) {
                if (i < 1000 * 1000 * 1000) {
                    while (true) {
                        val newScale = scale * 10
                        if (i < newScale) break
                        --minWidth
                        scale = newScale
                    }
                } else {
                    // Kept apart so the check cannot overflow past ten billion.
                    minWidth -= 9
                    scale = 1000 * 1000 * 1000
                }
            }
            while (minWidth > 0) {
                sb.append('0')
                --minWidth
            }
            while (scale != 1) {
                sb.append(('0'.code + (i / scale)).toChar())
                i %= scale
                scale /= 10
            }
            sb.append(('0'.code + i).toChar())
        }

        private const val MONTHS = "JanFebMarAprMayJunJulAugSepOctNovDec"
        private const val DAYS = "SunMonTueWedThuFriSat"
        private val FULL_MONTHS = arrayOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December",
        )

        private fun appendMonthName(sb: StringBuilder, index: Int) {
            val at = index * 3
            for (i in 0 until 3) sb.append(MONTHS[at + i])
        }

        private fun appendWeekDayName(sb: StringBuilder, index: Int) {
            val at = index * 3
            for (i in 0 until 3) sb.append(DAYS[at + i])
        }

        private fun appendFullMonthName(sb: StringBuilder, index: Int) {
            sb.append(FULL_MONTHS[index])
        }

        // ---- The setters -------------------------------------------------------------------------

        private fun makeTime(cx: Context, date: Double, args: Array<Any?>, methodId: Int): Double {
            // A setter called with no arguments has to answer NaN, because the missing argument is
            // undefined and undefined is not a number.
            if (args.isEmpty()) return ScriptRuntime.NaN

            val maxargs: Int
            var local = true
            when (methodId) {
                Id_setUTCMilliseconds -> { local = false; maxargs = 1 }
                Id_setMilliseconds -> maxargs = 1
                Id_setUTCSeconds -> { local = false; maxargs = 2 }
                Id_setSeconds -> maxargs = 2
                Id_setUTCMinutes -> { local = false; maxargs = 3 }
                Id_setMinutes -> maxargs = 3
                Id_setUTCHours -> { local = false; maxargs = 4 }
                Id_setHours -> maxargs = 4
                else -> throw Kit.codeBug()
            }

            var hasNaN = false
            val numNums = if (args.size < maxargs) args.size else maxargs
            val nums = DoubleArray(4)
            for (i in 0 until numNums) {
                val d = ScriptRuntime.toNumber(args[i])
                if (d.isNaN() || d.isInfinite()) hasNaN = true else nums[i] = ScriptRuntime.toInteger(d)
            }

            if (hasNaN || date.isNaN()) return ScriptRuntime.NaN

            var i = 0
            val stop = numNums
            val hour: Double
            val min: Double
            val sec: Double
            val msec: Double
            val lorutime = if (local) LocalTime(cx, date) else date

            hour = if (maxargs >= 4 && i < stop) nums[i++] else HourFromTime(lorutime).toDouble()
            min = if (maxargs >= 3 && i < stop) nums[i++] else MinFromTime(lorutime).toDouble()
            sec = if (maxargs >= 2 && i < stop) nums[i++] else SecFromTime(lorutime).toDouble()
            msec = if (maxargs >= 1 && i < stop) nums[i++] else msFromTime(lorutime).toDouble()

            var result = MakeDate(Day(lorutime), MakeTime(hour, min, sec, msec))
            if (local) result = internalUTC(cx, result)
            return TimeClip(result)
        }

        private fun makeDate(cx: Context, date: Double, args: Array<Any?>, methodId: Int): Double {
            if (args.isEmpty()) return ScriptRuntime.NaN

            val maxargs: Int
            var local = true
            when (methodId) {
                Id_setUTCDate -> { local = false; maxargs = 1 }
                Id_setDate -> maxargs = 1
                Id_setUTCMonth -> { local = false; maxargs = 2 }
                Id_setMonth -> maxargs = 2
                Id_setUTCFullYear -> { local = false; maxargs = 3 }
                Id_setFullYear -> maxargs = 3
                else -> throw Kit.codeBug()
            }

            var hasNaN = false
            val numNums = if (args.size < maxargs) args.size else maxargs
            val nums = DoubleArray(3)
            for (i in 0 until numNums) {
                val d = ScriptRuntime.toNumber(args[i])
                if (d.isNaN() || d.isInfinite()) hasNaN = true else nums[i] = ScriptRuntime.toInteger(d)
            }
            if (hasNaN) return ScriptRuntime.NaN

            var i = 0
            val stop = numNums
            val lorutime: Double

            // An invalid date stays invalid unless the year is being set, which starts from zero.
            if (date.isNaN()) {
                if (maxargs < 3) return ScriptRuntime.NaN
                lorutime = 0.0
            } else {
                lorutime = if (local) LocalTime(cx, date) else date
            }

            val year = if (maxargs >= 3 && i < stop) nums[i++] else YearFromTime(lorutime).toDouble()
            val month = if (maxargs >= 2 && i < stop) nums[i++] else MonthFromTime(lorutime).toDouble()
            val day = if (maxargs >= 1 && i < stop) nums[i++] else DateFromTime(lorutime).toDouble()

            var result = MakeDate(MakeDay(year, month, day), TimeWithinDay(lorutime))
            if (local) result = internalUTC(cx, result)
            return TimeClip(result)
        }

        // The ids.
        private const val ConstructorId_now = -3
        private const val ConstructorId_parse = -2
        private const val ConstructorId_UTC = -1
        private const val Id_constructor = 1
        private const val Id_toString = 2
        private const val Id_toTimeString = 3
        private const val Id_toDateString = 4
        private const val Id_toLocaleString = 5
        private const val Id_toLocaleTimeString = 6
        private const val Id_toLocaleDateString = 7
        private const val Id_toUTCString = 8
        private const val Id_toSource = 9
        private const val Id_valueOf = 10
        private const val Id_getTime = 11
        private const val Id_getYear = 12
        private const val Id_getFullYear = 13
        private const val Id_getUTCFullYear = 14
        private const val Id_getMonth = 15
        private const val Id_getUTCMonth = 16
        private const val Id_getDate = 17
        private const val Id_getUTCDate = 18
        private const val Id_getDay = 19
        private const val Id_getUTCDay = 20
        private const val Id_getHours = 21
        private const val Id_getUTCHours = 22
        private const val Id_getMinutes = 23
        private const val Id_getUTCMinutes = 24
        private const val Id_getSeconds = 25
        private const val Id_getUTCSeconds = 26
        private const val Id_getMilliseconds = 27
        private const val Id_getUTCMilliseconds = 28
        private const val Id_getTimezoneOffset = 29
        private const val Id_setTime = 30
        private const val Id_setMilliseconds = 31
        private const val Id_setUTCMilliseconds = 32
        private const val Id_setSeconds = 33
        private const val Id_setUTCSeconds = 34
        private const val Id_setMinutes = 35
        private const val Id_setUTCMinutes = 36
        private const val Id_setHours = 37
        private const val Id_setUTCHours = 38
        private const val Id_setDate = 39
        private const val Id_setUTCDate = 40
        private const val Id_setMonth = 41
        private const val Id_setUTCMonth = 42
        private const val Id_setFullYear = 43
        private const val Id_setUTCFullYear = 44
        private const val Id_setYear = 45
        private const val Id_toISOString = 46
        private const val Id_toJSON = 47
        private const val SymbolId_toPrimitive = 48
        private const val MAX_PROTOTYPE_ID = SymbolId_toPrimitive

        /** An alias for toUTCString, kept for Annex B. */
        private const val Id_toGMTString = Id_toUTCString
    }
}
