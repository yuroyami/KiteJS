# Choosing an engine

KiteJS has two engines behind one API. You pick one when you open it, and everything after that,
the values, the host bindings, the promises and the errors, works the same way on both:

```kotlin
KiteJs(Rhino) { instructionBudget = 5_000_000 }.use { js -> js.evaluate("1 + 1") }
KiteJs(QuickJs) { instructionBudget = 5_000_000 }.use { js -> js.evaluate("1 + 1") }
```

Each engine is its own artifact, so an app only carries the one it uses:

```kotlin
commonMain.dependencies {
    implementation("io.github.yuroyami:kitejs-rhino:0.6.0")    // KiteJs(Rhino)
    implementation("io.github.yuroyami:kitejs-quickjs:0.6.0")  // KiteJs(QuickJs)
}
```

## The short version

| | Rhino | QuickJS |
|---|---|---|
| What it is | Mozilla Rhino's interpreter, ported to common Kotlin | QuickJS-ng 0.17.0, the C engine, compiled into each target |
| Language | ES5.1 and most of ES2015 and later | ES2023 and most of what came after, including async generators and `for await` |
| Native code | None | A static library, a JNI library or a WebAssembly module, depending on the target |
| Size it adds | Kotlin code only | About 1 MB of native code per platform, about 360 KB gzipped on the web |
| Speed | An interpreter written in Kotlin | An interpreter written in C, between 3 and 12 times faster on the JVM on loops, strings, objects, recursion and sorting |
| Engines per thread | One at a time | As many as you like |
| Its own settings | `languageVersion`, `safeBuiltins`, `maxCallDepth`, `maxHostCallDepth` | `memoryLimit`, `maxStackSize` |

Pick **Rhino** when you want no native code anywhere in your app, when every byte of download
matters more than speed, or when you rely on something only Rhino has, such as
[asm.js reports](asm-js.md). Pick **QuickJS** when scripts use newer syntax, when they do real
work and speed matters, or when you want a hard cap on the memory a script can take.

## Settings both engines share

`timeZone`, `clock`, `console`, `instructionBudget`, `interruptWhen` and `sealBuiltins` mean the
same on both. One caveat: each engine counts the instruction budget in steps of its own, so the
same number is not the same amount of work on Rhino and on QuickJS. Pick a budget by measuring the
scripts you expect on the engine you ship.

## Rhino recursion limits

```kotlin
KiteJs(Rhino) {
    maxCallDepth = 10_000    // nested script frames; 0 disables this limit
    maxHostCallDepth = 64    // nested host calls and interpreter reentry; must be positive
}
```

These are the defaults. A script that exceeds either limit gets a `RangeError` with the message
`Maximum call stack size exceeded`, and the engine remains usable. A script can catch a frame
or interpreter-reentry error; a recursion through a Kotlin callback reaches the host as a
`JsError`, following the host-exception rules.

Getters and built-in callbacks can reenter the interpreter on the platform's native stack,
so they count toward `maxHostCallDepth` too. Its practical maximum depends on the platform and
the callbacks. Script frames remain counted across those reentries. Setting `maxCallDepth` to
zero leaves the host-call limit in place.

## QuickJS

### Loading it on JavaScript and Wasm

On the web the engine is a WebAssembly module, and the browser compiles one asynchronously. Call
`QuickJs.load()` once, before the first engine opens, and await it:

```kotlin
suspend fun start() {
    QuickJs.load()
    KiteJs(QuickJs).use { js -> /* ... */ }
}
```

On every other target `load()` returns at once, so common code can call it everywhere. Opening an
engine on the web before loading throws a `JsEngineError` that says so.

### Memory and the stack

```kotlin
KiteJs(QuickJs) {
    memoryLimit = 64L * 1024 * 1024   // bytes the engine may allocate, 0 for no limit
    maxStackSize = 1024L * 1024       // bytes of native stack a script may use, 512 KiB by default
}
```

A script that goes past `memoryLimit` ends the call with a `JsEngineError`, even when the script
tries to catch it, and the engine stays usable afterwards. A script that recurses past
`maxStackSize` gets a `RangeError`, which it can catch like any other.

On JavaScript and WebAssembly, the browser's own stack is about 1 MiB and runs out first, so
`maxStackSize` is 256 KiB at most there. That is about 800 levels of plain calls, 200 nested
parentheses in a source, and 50 calls that pass through a host function. Each call into a host
function counts 4 KiB against the limit, for the host's own frames. A host function that itself
uses much more stack than the KiteJS frames around it can still run the browser out first.

### Where the native code comes from

- **Kotlin/Native** (iOS, macOS, Linux, Windows): linked into your binary as a static library.
- **JVM**: the jar carries a JNI library for Linux on x64 and arm64, macOS on x64 and arm64, and
  Windows on x64, and loads the one for the machine it runs on. The library has to be a file to
  load, so the first process copies it to a folder under the temporary folder that only the user
  can write, named for the library's SHA-256. Later processes load that file again, which macOS
  then does not check a second time. For any other platform, build the library yourself from the
  module's `native` folder and set the system property `kitejs.quickjs.library` to its path.
- **Android**: the AAR carries a JNI library for arm64-v8a, armeabi-v7a, x86_64 and x86, aligned
  for 16 KB pages, and keep rules so R8 leaves the classes it calls alone.
- **JavaScript and Wasm**: a WebAssembly module embedded in the code, loaded by `QuickJs.load()`.

### Android host tests

Android host tests (`testAndroidHostTest`) run on the desktop JVM, where the Android libraries of
the AAR do not load. The JVM artifact of `kitejs-quickjs` carries a library for each desktop
platform, and the loader finds the one for the machine on the classpath. Add that artifact to the
classpath of the host tests:

```kotlin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

val quickJsDesktop: Configuration by configurations.creating {
    isCanBeConsumed = false
    attributes {
        attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    }
}
dependencies { quickJsDesktop("io.github.yuroyami:kitejs-quickjs:0.6.0") }

tasks.withType<Test>().matching { it.name == "testAndroidHostTest" }.configureEach {
    val desktop = quickJsDesktop
    inputs.files(desktop).withPropertyName("quickJsDesktop")
    // The Android plugin sets the classpath late, so add the jar when the task starts.
    doFirst { classpath += desktop }
}
```

An app that runs its tests on a device or an emulator does not need this.
