# Getting started

## Add the dependency

KiteJS is on Maven Central, one artifact per engine. Add the one you want, or both:

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.yuroyami:kitejs-rhino:0.3.0")    // KiteJs(Rhino)
            implementation("io.github.yuroyami:kitejs-quickjs:0.3.0")  // KiteJs(QuickJs)
        }
    }
}
```

[Choosing an engine](engines.md) says which one fits what. The examples in these pages use Rhino,
and run unchanged on QuickJS unless they use one of Rhino's own settings.

To build from source instead, clone the repository and add `includeBuild("../KiteJS")` to
`settings.gradle.kts`.

If you also want the suspending API, add the second artifact:

```kotlin
implementation("io.github.yuroyami:kitejs-coroutines:0.3.0")
```

## Run a script

```kotlin
import io.github.yuroyami.kitejs.api.KiteJs
import io.github.yuroyami.kitejs.rhino.Rhino

KiteJs(Rhino).use { js ->
    val answer = js.evaluate("1 + 1")
    println(answer.asInt())   // 2
}
```

`KiteJs` holds an engine and a global scope. Build it, use it, close it. `use { }` closes it for
you, including when the block throws.

On JavaScript and Wasm, QuickJS compiles a WebAssembly module before its first engine opens, so
call `QuickJs.load()` once and await it first. Everywhere else, and for Rhino, there is nothing to
load.

## One thread per engine

An engine belongs to the thread that opened it, the same way JavaScript itself is single
threaded: only that thread can use it or close it. Rhino also allows one open engine per thread,
and opening a second while the first is still open throws a `JsEngineError` that says so, so
close the first one or keep both inside their own `use { }` blocks that do not overlap. QuickJS
has no such limit, and one thread can hold several of its engines at once.

If you need the engine from suspending code, use `kitejs-coroutines`. It gives each engine a
thread of its own and moves every call onto that thread, so callers never overlap. See
[Promises and coroutines](promises-and-coroutines.md).

## Configure it

Everything is optional and has a working default:

```kotlin
KiteJs(Rhino) {
    languageVersion = LanguageVersion.ES6
    timeZone = TimeZone.of("Europe/Berlin")
    clock = { fixedMillis }
    console = ConsolePrinters.stdout
    instructionBudget = 5_000_000
    sealBuiltins = true
}.use { js -> /* ... */ }
```

These work on both engines:

| Setting | What it does |
|---|---|
| `timeZone` | The zone `Date` reads local time in |
| `clock` | Where `Date.now()` reads the time from, in epoch milliseconds |
| `console` | Where `console.log` goes. Leave it unset and there is no `console` |
| `instructionBudget` | How many instructions one call may run before the engine gives up |
| `interruptWhen` | Asked now and then while a script runs. Answer `true` to stop it |
| `sealBuiltins` | Makes the built-ins read only, so a script cannot replace `Array.prototype.push` |

These are Rhino's own:

| Setting | What it does |
|---|---|
| `languageVersion` | Which JavaScript the engine speaks. `LATEST` by default |
| `safeBuiltins` | Leaves out the built-ins a sandbox does not want |
| `littleEndian` | The byte order of typed array views. True by default, as in every browser |
| `asmJs` | Whether `"use asm"` modules are compiled. True by default |

And these are QuickJS's own:

| Setting | What it does |
|---|---|
| `memoryLimit` | Bytes the engine may allocate before a call ends with `JsEngineError`. 0, no limit, by default |
| `maxStackSize` | Bytes of native stack a script may use before recursion throws `RangeError`. 512 KiB by default |
