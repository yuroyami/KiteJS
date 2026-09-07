# KiteJS

A JavaScript engine for Kotlin Multiplatform, with the same behaviour on every target.

The engine is written in common Kotlin. There is no platform engine underneath, no native
library to ship, and no code generation at runtime. The parser, the regular expression engine,
the date arithmetic and the big integer arithmetic are all computed here, so a script gives the
same answer on Android, iOS, the JVM, the browser and a server.

```kotlin
KiteJs().use { js ->
    println(js.evaluate("[1, 2, 3].map(function (n) { return n * 2 }).join()").asString())
}
// 2,4,6
```

## What it runs

Complete ES5.1, plus most of ES2015 and later: `let` and `const`, arrow functions, template
literals, destructuring, spread, `Symbol`, `Map`, `Set`, `WeakMap`, `WeakSet`, generators,
`Promise`, `Proxy`, `Reflect`, `BigInt`, typed arrays, optional chaining, and the full regular
expression syntax including named groups and lookbehind.

It does not run ES2015 classes, modules, or `async`/`await`. See
[Differences from a browser](differences.md) for the full list and what to write instead.

## Targets

| Target | Tested |
|---|---|
| JVM (bytecode 11) | yes |
| Android | yes |
| iOS (device and simulator) | yes |
| macOS | yes |
| JavaScript (browser and Node) | yes |
| WebAssembly (browser and Node) | yes |
| Linux (x64 and arm64) | yes |
| Windows (x64) | yes |

## Where to go next

- [Getting started](getting-started.md): add the dependency and run your first script.
- [Evaluating scripts](evaluating.md): reading results, catching errors, compiling once.
- [Binding host objects](host-objects.md): giving a script your own functions and data.
- [Promises and coroutines](promises-and-coroutines.md): awaiting a promise from Kotlin.
- [Dates and time zones](dates-and-time-zones.md): making `Date` behave predictably.
- [Limits and safety](limits.md): stopping a script that will not return.
- [Differences from a browser](differences.md): what is missing and what to write instead.
- [API reference](https://yuroyami.github.io/KiteJS/api/)

## Licence

MPL-2.0. KiteJS is a Kotlin port of [Mozilla Rhino](https://github.com/mozilla/rhino), so the
upstream licence carries over. See `LICENSE` and `NOTICE` in the repository.
