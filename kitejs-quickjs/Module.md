# Module kitejs-quickjs

The QuickJS-ng engine, version 0.17.0, bound to the KiteJS API. Open it with `KiteJs(QuickJs) { }`
and use it the way the `kitejs-api` module describes; `QuickJsConfig` adds what is QuickJS's own,
a memory limit and a stack size.

QuickJS is C, compiled into each target: a static library through cinterop on Kotlin/Native, a JNI
library inside the jar on the JVM and inside the AAR on Android, and a WebAssembly module embedded
in the code on JavaScript and Wasm. On those last two the module has to be compiled before the
first engine opens, so call `QuickJs.load()` once and await it; on every other target it returns
at once.
