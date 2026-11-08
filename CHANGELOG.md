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
  own depth, because native code cannot catch a stack overflow (#139).
- Rhino: `toLowerCase`, `toUpperCase` and their locale variants use the engine's own case tables
  from the Unicode Character Database 16.0, SpecialCasing included, with the final sigma rule, so
  every target gives one answer. They came from the platform, which disagreed on the final sigma
  and, on the JVM, had no Garay letters (#136).
