# KiteJS

A JavaScript engine for Kotlin Multiplatform, ported from Mozilla Rhino. The whole
engine lives in common Kotlin: no platform engines, no binary blobs, no JIT.

Full guide: [docs/](docs/) (placeholder for now).

## Status

Pre-alpha, but it runs. KiteJS evaluates ES5 and most of ES2015 (minus classes and
modules) on the JVM, Android, iOS and JS. That includes regular expressions, dates,
typed arrays, promises, generators, `Map` and `Set`, symbols, `Proxy`, `Reflect`, `BigInt` and
the weak collections.
Everything is checked against upstream Rhino: the same script runs on both engines and
the answers have to match, and a slice of whole programs runs on every target so the
answers cannot quietly differ off the JVM. On the last full test262 run, 52,802 cases went
through both engines and none of them disagreed.

## Using it

```kotlin
KiteJs { instructionBudget = 5_000_000 }.use { js ->
    js.global.function<Double, Double, Double>("hypot") { a, b -> hypot(a, b) }
    js.global.obj("document") {
        property("title", "Untitled")
        getter("readyState") { "complete" }
    }
    println(js.evaluate("document.title + ':' + hypot(3, 4)").asString())
}
```

Host functions, properties, accessors and constructors are bound with lambdas and property
references, so nothing here uses reflection and it works the same on every target. The budget
stops a script that will not return.

`kitejs-coroutines` is a second artifact that puts the engine behind suspending functions:
`await` on a JavaScript promise, a Kotlin `Deferred` handed to a script as a promise, host
functions that suspend, and cancellation that actually stops a running script.

[PORTING_STATUS.md](PORTING_STATUS.md) tracks what works,
[KITEJS_IMPL.md](KITEJS_IMPL.md) is the implementation plan.

## License

MPL-2.0, because KiteJS is a derivative of [Mozilla Rhino](https://github.com/mozilla/rhino).
See [LICENSE](LICENSE) and [NOTICE](NOTICE).
