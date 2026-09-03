# KiteJS engine status

This file lists the support level for every part of the engine. For the task-level
plan, read [KITEJS_IMPL.md](KITEJS_IMPL.md).

## Legend

| Mark | Meaning |
|---|---|
| ✅ | Ported and tested |
| 🟡 | Partly ported: read the note |
| ⛔ | Planned, no code yet |
| 🚫 | Out of scope, with the reason |

## Engine

| Cluster | Status | Notes |
|---|---|---|
| Lexer (Token, TokenStream) | ✅ | Token-by-token identical to upstream on the parity corpus (codes, boundaries, columns, line numbers, values). E4X tokenizer methods not ported; BigInt literals lex into a stub value until Phase 5 |
| Phase 0 support slice (Kit, Messages, Characters, CompilerEnvirons, ErrorReporter, exceptions, Context version surface, Parser error plumbing) | 🟡 | Only what the lexer needs; each file grows in its own later phase |
| AST + Parser | ⛔ | Phase 1 |
| IR + bytecode generator (Icode) | ⛔ | Phase 2 |
| Interpreter + core runtime (Object, Function, Array, String, Number, Boolean, Math, JSON, errors) | ⛔ | Phase 3 |
| RegExp engine | ⛔ | Phase 4 |
| Date | ⛔ | Phase 4, needs a timezone decision |
| Map, Set, Symbol, iterators, generators, typed arrays, Promise | ⛔ | Phase 4 |
| WeakMap, WeakSet | ⛔ | Phase 5, via KiteCore WeakRef |
| BigInt | ⛔ | Phase 5, needs a big-integer implementation |
| test262 conformance harness | ⛔ | Phase 6 |
| Kotlin embedding API (host objects without reflection) | ⛔ | Phase 7 |
| JVM bytecode compiler | 🚫 | JIT and runtime codegen are impossible on iOS and pointless for this port; upstream's interpreted mode is the model |
| E4X (XML syntax) | 🚫 | Deprecated language extension, dead in the wild |
| LiveConnect (Java interop) | 🚫 | Reflection-based JVM interop has no meaning in common Kotlin; Phase 7 replaces it with a Kotlin DSL |
| Intl | 🚫 | Upstream barely supports it; would need ICU-scale data |

## Language level

Upstream Rhino 1.9.1 implements complete ES5.1 plus a large part of ES2015+
(destructuring, spread, Symbol, Map/Set, template literals, generators, Promise,
optional chaining). It does not implement ES2015 classes or async/await. The port
inherits exactly this coverage; extending it comes after parity, not before.
