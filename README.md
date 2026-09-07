# KiteJS

A JavaScript engine for Kotlin Multiplatform, built on the interpreter from
[Mozilla Rhino](https://github.com/mozilla/rhino). It runs the same script the same way on every
target.

**[Documentation](docs/)** · [Getting started](docs/getting-started.md) ·
[Binding host objects](docs/host-objects.md) ·
[Differences from a browser](docs/differences.md)

```kotlin
KiteJs { instructionBudget = 5_000_000 }.use { js ->
    js.global.function<Double, Double, Double>("hypot") { a, b -> hypot(a, b) }
    js.global.obj("document") {
        property("title", "Untitled")
        getter("readyState") { "complete" }
    }
    println(js.evaluate("document.title + ':' + hypot(3, 4)").asString())
}
// Untitled:5
```

## What it does

Runs ES5.1 and most of ES2015 and later: `let` and `const`, arrow functions, template literals,
destructuring, `Symbol`, `Map`, `Set`, `WeakMap`, `WeakSet`, generators, `Promise`, `Proxy`,
`Reflect`, `BigInt`, typed arrays, optional chaining, and the full regular expression syntax
including named groups and lookbehind. It does not run classes, modules or `async`/`await`.

The engine is common Kotlin. There is no platform engine underneath, no native library to ship,
and no code generation at runtime. The parser, the regular expression engine, the date
arithmetic, the number formatting and the number parsing are all computed inside the engine, so
the same script gives the same answer everywhere.

Binding your own functions and objects uses lambdas and property references, not reflection, so
it behaves identically on every target too.

## Targets

JVM (bytecode 11), Android, iOS, macOS, JavaScript, WebAssembly, Linux (x64 and arm64) and
Windows.

## Building it

KiteJS is not published yet. Build it and include it locally:

```bash
git clone https://github.com/yuroyami/KiteJS.git
cd KiteJS
./gradlew :kitejs:assemble
```

```kotlin
// settings.gradle.kts
includeBuild("../KiteJS")
```

```kotlin
// build.gradle.kts
commonMain.dependencies {
    implementation("io.github.yuroyami:kitejs:0.0.1")
    implementation("io.github.yuroyami:kitejs-coroutines:0.0.1")  // optional
}
```

The second artifact puts the engine behind suspending functions: awaiting a promise, handing a
`Deferred` to a script, host functions that suspend, and cancellation that stops a running
script.

## Correctness

Every script runs through upstream Rhino as well, and the two answers have to match. On the last
full test262 run, 52,802 cases went through both engines and none of them disagreed. The same
52,802 cases then run on JavaScript, WebAssembly, iOS and macOS against the outcomes the JVM
recorded, so no target can quietly behave differently.

## Licence

MPL-2.0, because KiteJS is a derivative of [Mozilla Rhino](https://github.com/mozilla/rhino).
See [LICENSE](LICENSE) and [NOTICE](NOTICE).
