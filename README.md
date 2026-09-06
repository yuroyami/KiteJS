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
answers cannot quietly differ off the JVM.

Still missing: the test262 conformance harness, and a Kotlin API for handing your own objects to
script. [PORTING_STATUS.md](PORTING_STATUS.md) tracks
what works, [KITEJS_IMPL.md](KITEJS_IMPL.md) is the implementation plan.

## License

MPL-2.0, because KiteJS is a derivative of [Mozilla Rhino](https://github.com/mozilla/rhino).
See [LICENSE](LICENSE) and [NOTICE](NOTICE).
