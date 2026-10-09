# KiteJS

A JavaScript engine for Kotlin Multiplatform, with the same behaviour on every target, and two
engines to choose from behind one API.

- **Rhino** is Mozilla Rhino's interpreter, ported to common Kotlin. There is no native library to
  ship and no code generation at runtime: the parser, the regular expressions, the date arithmetic
  and the big integer arithmetic are all computed in Kotlin, so a script gives the same answer on
  Android, iOS, the JVM, the browser and a server.
- **QuickJS** is QuickJS-ng 0.17.0, the C engine, compiled into each target. It runs newer
  JavaScript, it is several times faster, and it can cap the memory a script takes.

```kotlin
KiteJs(Rhino).use { js ->
    println(js.evaluate("[1, 2, 3].map(n => n * 2).join()").asString())
}
// 2,4,6
```

Swap `Rhino` for `QuickJs` and nothing else changes. [Choosing an engine](engines.md) compares
the two.

## What it runs

On Rhino, complete ES5.1 plus most of ES2015 and later: `let` and `const`, arrow functions,
template literals, destructuring, spread, classes, `Symbol`, `Map`, `Set`, `WeakMap`, `WeakSet`,
generators, `Promise`, async functions and `await`, `Proxy`, `Reflect`, `BigInt`, typed arrays,
async generators and `for await`, optional chaining, and the full regular expression syntax
including named groups and lookbehind. It does not run modules. See
[Differences from a browser](differences.md) for the full list and what to write instead.

On QuickJS, ES2023 and most of what came after, async generators and `for await` included.

## Targets

Both engines run on every one of these.

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
- [Choosing an engine](engines.md): Rhino or QuickJS, and what each one costs.
- [Evaluating scripts](evaluating.md): reading results, catching errors, compiling once.
- [Binding host objects](host-objects.md): giving a script your own functions and data.
- [Promises and coroutines](promises-and-coroutines.md): awaiting a promise from Kotlin.
- [Dates and time zones](dates-and-time-zones.md): making `Date` behave predictably.
- [Limits and safety](limits.md): stopping a script that will not return.
- [Differences from a browser](differences.md): what is missing and what to write instead.
- [API reference](https://yuroyami.github.io/KiteJS/api/)

## Licence

MPL-2.0. KiteJS is a Kotlin port of [Mozilla Rhino](https://github.com/mozilla/rhino), so the
upstream licence carries over. QuickJS-ng, which kitejs-quickjs carries, keeps its MIT licence.
See `LICENSE` and `NOTICE` in the repository.
