# Getting started

## Add the dependency

KiteJS is on Maven Central:

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.yuroyami:kitejs:0.1.0")
        }
    }
}
```

To build from source instead, clone the repository and add `includeBuild("../KiteJS")` to
`settings.gradle.kts`.

If you also want the suspending API, add the second artifact:

```kotlin
implementation("io.github.yuroyami:kitejs-coroutines:0.1.0")
```

## Run a script

```kotlin
import io.github.yuroyami.kitejs.api.KiteJs

KiteJs().use { js ->
    val answer = js.evaluate("1 + 1")
    println(answer.asInt())   // 2
}
```

`KiteJs` holds an engine and a global scope. Build it, use it, close it. `use { }` closes it for
you, including when the block throws.

## One engine at a time

An engine belongs to one thread, the same way JavaScript itself is single threaded. Opening a
second engine while the first is still open throws a `JsEngineError` that says so. Close the first
one, or keep both inside their own `use { }` blocks that do not overlap.

If you need the engine from suspending code, use `kitejs-coroutines`. It owns a dispatcher that
runs one thing at a time and moves every call onto it, so callers never overlap. See
[Promises and coroutines](promises-and-coroutines.md).

## Configure it

Everything is optional and has a working default:

```kotlin
KiteJs {
    languageVersion = LanguageVersion.ES6
    timeZone = TimeZone.of("Europe/Berlin")
    clock = { fixedMillis }
    console = ConsolePrinters.stdout
    instructionBudget = 5_000_000
    sealBuiltins = true
}.use { js -> /* ... */ }
```

| Setting | What it does |
|---|---|
| `languageVersion` | Which JavaScript the engine speaks. `LATEST` by default |
| `timeZone` | The zone `Date` reads local time in |
| `clock` | Where `Date.now()` reads the time from, in epoch milliseconds |
| `console` | Where `console.log` goes. Leave it unset and there is no `console` |
| `instructionBudget` | How many instructions one call may run before the engine gives up |
| `interruptWhen` | Asked now and then while a script runs. Answer `true` to stop it |
| `safeBuiltins` | Leaves out the built-ins a sandbox does not want |
| `sealBuiltins` | Makes the built-ins read only, so a script cannot replace `Array.prototype.push` |
