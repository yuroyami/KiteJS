# KiteJS

A JavaScript engine for Kotlin Multiplatform, with two engines behind one API:
[Mozilla Rhino](https://github.com/mozilla/rhino)'s interpreter ported to common Kotlin, and
[QuickJS-ng](https://github.com/quickjs-ng/quickjs) compiled into each target. Either one runs the
same script the same way on every target.

<sub>AI tools wrote much of the code, tests and documentation in this project, under the
direction and review of its author.</sub>

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
`Symbol`, `Map`, `Set`, generators, `Promise`, async functions, async generators and `for await`,
iterator helpers, `Proxy`, `Reflect`, `BigInt`, `WeakRef`, typed arrays over resizable and shared
buffers with `Atomics`, optional chaining and the full regular expression syntax. It does not run
modules.

**QuickJS** is QuickJS-ng 0.17.0, the C engine, compiled in: a static library on Kotlin/Native, a
JNI library inside the jar, a JNI library per ABI on Android (in the `kitejs-quickjs-android-natives`
jar that comes with it), and a WebAssembly module on JavaScript and Wasm. It runs
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
    implementation("io.github.yuroyami:kitejs-rhino:0.8.0")       // KiteJs(Rhino)
    implementation("io.github.yuroyami:kitejs-quickjs:0.8.0")     // KiteJs(QuickJs)
    implementation("io.github.yuroyami:kitejs-coroutines:0.8.0")  // optional
}
```

With the Kotlin Toolchain (`module.yaml`):

```yaml
dependencies:
  - io.github.yuroyami:kitejs-rhino:0.8.0
  - io.github.yuroyami:kitejs-quickjs:0.8.0
  - io.github.yuroyami:kitejs-coroutines:0.8.0
```

On JavaScript and Wasm, add the npm package `@js-joda/timezone` too, or only UTC and fixed offsets
work in `Date` ([Dates and time zones](https://yuroyami.github.io/KiteJS/dates-and-time-zones/)).

The last artifact puts either engine behind suspending functions: awaiting a promise, handing a
`Deferred` to a script, host functions that suspend, and cancellation that stops a running
script. The `kitejs` artifact from earlier releases still works and brings Rhino with it, along
with the old `KiteJs { }`, which now opens Rhino and is deprecated in favour of `KiteJs(Rhino) { }`.

On JavaScript and Wasm, QuickJS has to compile its WebAssembly module before the first engine
opens: call `QuickJs.load()` once and await it. Everywhere else it returns at once.

## Correctness

Both engines run one contract suite, on every target, that pins down how the API behaves.

Rhino's test262 parity run (`python3 tools/test262.py parity`) compares a pinned test262 corpus
with upstream Rhino, recording intentional differences separately. Agreement includes tests both
engines fail; it is a compatibility check, not a claim that every case conforms to ECMAScript.

Corpus replay is separate from the ordinary test suite and must be requested with
`python3 tools/test262.py replay`. Missing or mismatched inputs fail that requested run. The
dedicated workflow replays one versioned artifact across its platform matrix and records executed
counts and known differences per target. Coverage is established by those run results; the regular platform tests
alone do not establish corpus coverage. See [Contributing](CONTRIBUTING.md#tests-and-test262-coverage) for the commands.

## Building

KiteJS builds with the [Kotlin Toolchain](https://github.com/JetBrains/kotlin-toolchain). The
`kotlin` script in the root downloads it on the first run, with the JDK, Kotlin/Native and Node that
it needs.

```sh
./kotlin build                       # every platform this machine can build
./kotlin test -p jvm -p android      # the tests on the platforms you name
python3 tools/web-tests/run.py       # the JavaScript and Wasm tests, in Node
./kotlin check abi klibAbi           # the public API against the dumps in each module's api/ folder
./kotlin do dokka && ./kotlin do dokkaSite   # the API reference, in build/dokka/html
```

Name the platforms for `./kotlin test`. Without `-p` it also runs the Wasm tests in headless Chrome,
and there only kitejs-api passes: the other modules import the npm package `@js-joda/timezone`,
which the toolchain cannot add to its browser page. `./kotlin check abi klibAbi` needs a build of
every platform first.

kitejs-quickjs compiles C code, so it needs [zig](https://ziglang.org) on the `PATH`, and for Android
an NDK in `ANDROID_NDK_HOME` or in the Android SDK. [Contributing](CONTRIBUTING.md#run-the-tests) has the details.

## Licence

MPL-2.0, because KiteJS is a derivative of [Mozilla Rhino](https://github.com/mozilla/rhino).
QuickJS-ng, which kitejs-quickjs carries in `kitejs-quickjs/native/quickjs`, keeps its MIT licence.
See [LICENSE](LICENSE) and [NOTICE](NOTICE).
