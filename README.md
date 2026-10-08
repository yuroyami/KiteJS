# KiteJS

A JavaScript engine for Kotlin Multiplatform, with two engines behind one API:
[Mozilla Rhino](https://github.com/mozilla/rhino)'s interpreter ported to common Kotlin, and
[QuickJS-ng](https://github.com/quickjs-ng/quickjs) compiled into each target. Either one runs the
same script the same way on every target.

**[Documentation](https://yuroyami.github.io/KiteJS/)** ·
[Getting started](https://yuroyami.github.io/KiteJS/getting-started/) ·
[Choosing an engine](https://yuroyami.github.io/KiteJS/engines/) ·
[Binding host objects](https://yuroyami.github.io/KiteJS/host-objects/) ·
[Differences from a browser](https://yuroyami.github.io/KiteJS/differences/) ·
[API reference](https://yuroyami.github.io/KiteJS/api/)

```kotlin
KiteJs(QuickJs) { instructionBudget = 5_000_000 }.use { js ->
    js.global.function<Double, Double, Double>("hypot") { a, b -> hypot(a, b) }
    js.global.obj("document") {
        property("title", "Untitled")
        getter("readyState") { "complete" }
    }
    println(js.evaluate("document.title + ':' + hypot(3, 4)").asString())
}
// Untitled:5
```

Swap `QuickJs` for `Rhino` and nothing else changes: the values, the host bindings, the promises
and the errors behave the same on both.

## Two engines

**Rhino** is common Kotlin all the way down. There is no native library to ship and no code
generation at runtime, and the parser, the regular expressions, the date arithmetic and the
number formatting are all computed inside the engine. It runs ES5.1 and most of ES2015 and later:
`let` and `const`, arrow functions, destructuring, classes with private and static members,
`Symbol`, `Map`, `Set`, generators, `Promise`, async functions, `Proxy`, `Reflect`, `BigInt`, typed
arrays, optional chaining and the full regular expression syntax. It does not run modules, async
generators or `for await`.

**QuickJS** is QuickJS-ng 0.17.0, the C engine, compiled in: a static library on Kotlin/Native, a
JNI library inside the jar and the AAR, and a WebAssembly module on JavaScript and Wasm. It runs
ES2023 and most of what came after, it was between 3 and 12 times faster than Rhino on the JVM in
our measurements, and it can cap the memory a script takes. It adds about 1 MB of native code per
platform, about 360 KB gzipped on the web.

[Choosing an engine](https://yuroyami.github.io/KiteJS/engines/) compares them in full.

Binding your own functions and objects uses lambdas and property references, not reflection, so
it behaves identically on every target and on both engines.

## Targets

JVM (bytecode 11), Android, iOS, macOS, JavaScript, WebAssembly, Linux (x64 and arm64) and
Windows, for both engines.

## Installing it

KiteJS is on Maven Central. Add the engine you want, or both:

```kotlin
// build.gradle.kts
commonMain.dependencies {
    implementation("io.github.yuroyami:kitejs-rhino:0.6.0")       // KiteJs(Rhino)
    implementation("io.github.yuroyami:kitejs-quickjs:0.6.0")     // KiteJs(QuickJs)
    implementation("io.github.yuroyami:kitejs-coroutines:0.6.0")  // optional
}
```

The last artifact puts either engine behind suspending functions: awaiting a promise, handing a
`Deferred` to a script, host functions that suspend, and cancellation that stops a running
script. The `kitejs` artifact from earlier releases still works and brings Rhino with it, along
with the old `KiteJs { }`, which now opens Rhino and is deprecated in favour of `KiteJs(Rhino) { }`.

On JavaScript and Wasm, QuickJS has to compile its WebAssembly module before the first engine
opens: call `QuickJs.load()` once and await it. Everywhere else it returns at once.

## Correctness

Both engines run one contract suite, on every target, that pins down how the API behaves.

Rhino's dedicated `:kitejs-rhino:test262Parity` task compares a pinned test262 corpus with upstream
Rhino, recording intentional differences separately. Agreement includes tests both engines fail;
it is a compatibility check, not a claim that every case conforms to ECMAScript.

Corpus replay is separate from the ordinary test suite and must be requested with
`-Ptest262Replay`. Missing corpus data or expectations fail that requested run. Cross-platform
test262 replay is being wired into CI in [#31](https://github.com/yuroyami/KiteJS/issues/31);
the regular platform tests alone do not establish corpus coverage.

## Licence

MPL-2.0, because KiteJS is a derivative of [Mozilla Rhino](https://github.com/mozilla/rhino).
QuickJS-ng, which kitejs-quickjs carries in `kitejs-quickjs/native/quickjs`, keeps its MIT licence.
See [LICENSE](LICENSE) and [NOTICE](NOTICE).
