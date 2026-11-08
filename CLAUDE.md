# KiteJS

Read `CONTRIBUTING.md` first. It holds the ground rules, the test commands and the test262 commands.

Open work lives in GitHub Issues. There is no private planning file.

## Gotchas

Things a previous change taught the hard way. One line each. Delete a line when it stops being true.

- Kotlin/JS cannot tell an `Int` from a `Double` at runtime, because every number passes `is Int`. An integer fast path must go through `ScriptRuntime.isInt`, or `NaN`, `Infinity` and `2.5` get truncated on JS (50a5ba5).
- Kotlin/JS and Wasm load the `@js-joda/timezone` data only through an eager reference marked `@EagerInitialization`. Without it, only UTC and fixed offsets work. `DateZoneSliceTest` catches this (8990c58).
- Kotlin/Wasm's `String.toDouble` returns a neighbouring double for about one string in a hundred. Script numbers go through `dtoa/DecimalParser`, never through the platform parser (624cdca).
- Wasm has no weak reference. `WeakRef` holds strongly there and reports `isWeakSupported` as false, so a test that needs a real collection runs on the JVM only (624cdca).
- The JVM parity run cannot see a platform difference. Only the test262 replay on JS, Wasm and the native targets finds one (5e1882c).
