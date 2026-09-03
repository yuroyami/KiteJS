# KiteJS

A JavaScript engine for Kotlin Multiplatform, ported from Mozilla Rhino. The whole
engine lives in common Kotlin: no platform engines, no binary blobs, no JIT.

Full guide: [docs/](docs/) (placeholder for now).

## Status

Pre-alpha. The project scaffold and the lexer exist. Nothing here evaluates
JavaScript yet. [PORTING_STATUS.md](PORTING_STATUS.md) tracks what works,
[KITEJS_IMPL.md](KITEJS_IMPL.md) is the implementation plan.

## License

MPL-2.0, because KiteJS is a derivative of [Mozilla Rhino](https://github.com/mozilla/rhino).
See [LICENSE](LICENSE) and [NOTICE](NOTICE).
