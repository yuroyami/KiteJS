# Dates and time zones

## Two settings decide what `Date` does

```kotlin
KiteJs {
    timeZone = TimeZone.of("Europe/Berlin")
    clock = { 1_700_000_000_000.0 }
}.use { js ->
    js.evaluate("new Date().toISOString()").asString()   // 2023-11-14T22:13:20.000Z
    js.evaluate("new Date(0).getHours()").asDouble()     // 1.0
}
```

`timeZone` is the zone local time is read in. `clock` answers the current time in epoch
milliseconds. Both default to the system's, which is what you want in production and never what
you want in a test.

## Pin the clock in tests

A script that reads the time gives a different answer every run. Fix the clock and it stops.

```kotlin
val fixed = 1_700_000_000_000.0
KiteJs { clock = { fixed }; timeZone = TimeZone.UTC }.use { js ->
    assertEquals("2023-11-14", js.evaluate("new Date().toISOString().slice(0, 10)").asString())
}
```

A `clock` is a lambda, so it can move:

```kotlin
var now = 0.0
KiteJs { clock = { now } }.use { js ->
    js.evaluate("var t0 = Date.now()")
    now += 5000
    js.evaluate("Date.now() - t0").asDouble()   // 5000.0
}
```

## The arithmetic is the engine's own

Leap years, month lengths, the day of the week, the ISO week, `Date.UTC`, `Date.parse` and every
getter and setter are computed inside the engine. They do not go through a platform date library,
so they give the same answer everywhere.

The one thing the engine cannot compute is which offset a zone used on a given day. Those rules
change by political decision and have to come from a database. KiteJS reads them through
kotlinx-datetime.

### JavaScript and WebAssembly need one npm package

On those two targets the zone database is a separate npm package, `@js-joda/timezone`. KiteJS
declares it, so a normal Gradle build picks it up. Without it a named zone throws and only UTC
and fixed offsets work.

If you bundle the output yourself, make sure the package survives tree shaking. KiteJS holds an
eager reference to it for exactly this reason.

## Two gaps worth knowing

`toString` prints the zone's id where a browser prints its abbreviation:

```
KiteJS:  Tue Nov 14 2023 23:13:20 GMT+0100 (Europe/Berlin)
Chrome:  Tue Nov 14 2023 23:13:20 GMT+0100 (Central European Standard Time)
```

`toLocaleString`, `toLocaleDateString` and `toLocaleTimeString` use fixed en-US patterns and
ignore their locale argument. Formatting a date for a user is better done in Kotlin, where you
have the platform's formatter, than in the script.
