# Changelog

The notes of each release are also on the [releases page](https://github.com/yuroyami/KiteJS/releases).

## Unreleased

- Build. KiteJS now builds with the Kotlin Toolchain (`./kotlin`) instead of Gradle, with Kotlin
  2.4.21 (up from 2.4.20) and Android compileSdk 37.2 (up from 36). The group, the version and the
  klib names do not change. What changes for consumers:
  - Android: the QuickJS JNI libraries moved out of the `kitejs-quickjs` AAR into a new artifact,
    `io.github.yuroyami:kitejs-quickjs-android-natives`, which `kitejs-quickjs` depends on for
    Android. The Android Gradle plugin packages them into the app as before, aligned for 16 KB
    pages. The R8 keep rules for the JNI classes moved into that jar as well.
  - JavaScript and Wasm: the libraries no longer declare the npm package `@js-joda/timezone`. Add
    it to your build, or a named time zone throws in `Date`
    ([Dates and time zones](docs/dates-and-time-zones.md)).
  - The Wasm artifacts are `<artifact>-wasmjs` instead of `<artifact>-wasm-js`. Gradle finds them
    through the module metadata, so only a direct dependency on the old ID breaks.
  - The root POM of each library depends on its `-jvm` artifact, so a plain Maven project can use
    the root artifact.
  - The AAR keeps its classes in `libs/<artifact>-jvm.jar` instead of `classes.jar`. It has no
    `aar-metadata.properties`, and its manifest sets `targetSdkVersion` to the compileSdk.
  - The javadoc jars are empty, and the root artifact has none. The API reference is on the
    documentation site.
  - The sources jars of kitejs-quickjs for JavaScript and Wasm no longer contain the generated
    `QuickJsWasm.kt`, the engine's WebAssembly module in base64.
  - The Kotlin/Native platforms have no `-metadata.jar` of their own. The klibs of shared code are in
    the root metadata jar.
  - The POM has no `inceptionYear`.
  - `./kotlin build` links no Apple frameworks (`tools/apple_frameworks.py` does) and no JavaScript
    distribution of each library. Neither was published.
- Rhino: deeply nested source ends with the engine's error "Too deep recursion while parsing" on
  every target, as upstream reports it, instead of the host's stack overflow. The parser counts its
  own depth, because native code cannot catch a stack overflow (#139). On Windows it also asks the
  stack probe, so the error comes before the 1 MB main thread runs out.
- Rhino: the parser and the compiler passes after it take less native stack for each level of
  nesting. 200 nested blocks overflowed the 1 MB main thread of a Windows program in a debug build
  (#139). In a linuxX64 debug build a level of blocks went from 6.9 KB to 592 bytes in the node
  transformer and from 3.3 KB to 416 bytes in the code generator, and a level of parentheses from
  10.6 KB to 6.5 KB in the parser, so the depth test now passes there with 768 KB of stack.
- Rhino: `toLowerCase`, `toUpperCase` and their locale variants use the engine's own case tables
  from the Unicode Character Database 16.0, SpecialCasing included, with the final sigma rule, so
  every target gives one answer. They came from the platform, which disagreed on the final sigma
  and, on the JVM, had no Garay letters (#136).
- Rhino: `String.prototype.normalize` gives the four Unicode normalization forms from the engine's
  own Unicode 16.0 tables, and passes the whole NormalizationTest.txt conformance file. It used to
  return its input unchanged. `localeCompare` puts both strings in NFC first, so canonically
  equivalent strings compare equal (#135).
- Rhino: a `let`, `const` or class binding in a function or a block has its temporal dead zone,
  so reading, assigning or `typeof` before its declaration throws a `ReferenceError`, as in a
  browser, where it used to read `undefined`. Each entry to a block, a loop pass or a switch
  starts it again. A script's top-level bindings, which live on the global object, still have
  none (#137).
- QuickJS: a `FinalizationRegistry` holds its unregister token weakly, as ECMA-262 asks. It held the
  token strongly, so the common `registry.register(o, value, o)` kept every `o` alive and its
  callback never ran.
- QuickJS: the handles of wrappers that the collector took are freed in time that grows with their
  number. They waited in a list copied on each one added, so 200,000 found in one collection held
  the engine's next call for 30 seconds, and a chapter that made DOM nodes on a timer slowed down
  as it ran (yuroyami/KitePDF#885).
- Both engines: `Array.prototype.join`, `reverse`, `fill` and `copyWithin` on an object that
  claims a length of `2**53 - 1` now reach the instruction budget and the interrupt hook while
  they loop. QuickJS asks the hook from these loops, and Rhino counts each step, so one such call
  no longer holds its thread past every deadline.
