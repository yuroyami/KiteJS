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
| Support slice (Kit, Messages, Characters, ErrorReporter, exceptions, Context version surface) | 🟡 | Only what the lexer and parser need; each file grows in its own later phase. Every error message carried so far is verified against the upstream bundle |
| CompilerEnvirons | ✅ | Complete except `initFromContext`, which needs a real Context (phase 3). Security controllers and the deprecated optimization level are out of scope |
| AST node hierarchy | ✅ | All 70 node types ported. Every one is checked against the upstream jar: rendered source, positions, line-number fallback, child-list surgery and symbol tables all match. E4X node types are out of scope |
| Parser | ✅ | Matches upstream on a 30 file construct corpus: same rendered source, same node positions and line numbers, same recorded comments, same accept-or-reject decision. Error reporting matches too, over 60 malformed sources, in both collecting and throwing modes. E4X syntax is rejected rather than parsed |
| Number formatting (Schubfach) | ✅ | `ScriptRuntime.numberToString` at radix 10 matches upstream on 300000 sampled values plus every boundary case. Same answer on every target. Other radixes wait for BigInt |
| Icode constants | ✅ | All 89 values equal the upstream table, checked by reflection. The icode range and the bytecode token range still do not overlap |
| IR generator (IRFactory) | ✅ | The whole corpus lowers to an IR tree identical to upstream's at both language versions: same node types, values, positions, property slots and function tables. E4X transforms are out of scope |
| IR transform pass (NodeTransformer) | ✅ | The whole corpus transforms to a tree identical to upstream's at both language versions and in strict mode, script tree and every nested function compared |
| Runtime conversions (ScriptRuntime, string to number and back) | 🟡 | The whole primitive conversion surface is ported and matches upstream: `toNumber(String)` over 25000 strings, `toInteger` and `toUint32` over 160000 doubles, `escapeString` over 40000 cases, and format-then-parse round trips. The conversions that need a Scriptable wait for the object model |
| Object model (ScriptableObject, the slot family, property descriptors) | ✅ | Matches upstream through the public property API: a 20000 step random sequence of put, get, delete, define and attribute changes, a 2500 property object that crosses every point where the property table changes shape, plus attributes, symbol keys, constants, sealing, extensibility, descriptors and prototype chain lookup. The reflection half (defineClass and the annotation scan) is out of scope |
| Function objects (BaseFunction, LambdaFunction, LambdaConstructor, TopLevel, IdScriptableObject, BoundFunction, JSFunction) | ✅ | The id-based native object machinery matches upstream through a class written against both sides. `Function.prototype` (`apply`, `call`, `bind`, `toString`) and the `Function` constructor are in; the generator prototype waits for phase 4 |
| Context and ContextFactory | ✅ | Enter and exit, feature flags, sealing, listeners, microtasks, error routing and the compile entry points. Single-thread confined: one context slot instead of one per thread. Class shutters, wrap factories, security controllers and the debugger are out of scope |
| Descriptor layer (JSDescriptor, JSScript, NativeCall, Arguments, InterpreterData) | ✅ | The compiled form of a script or function and its activation record. Exercised once the interpreter runs |
| Runtime contracts (Scriptable, Function, Callable, Script, Evaluator, Ref, Symbol) | ✅ | Every interface has the same members as upstream: names, arity, parameter and return shapes, and the interfaces it extends, all compared by reflection. Two members are known-absent and listed with a reason |
| Value types (Undefined, UniqueTag, ConsString) | ✅ | Same behaviour as upstream, including the lazy rope that repeated string concatenation builds |
| Icode generator (CodeGenerator) | ✅ | The whole corpus and 57 hand-written sources compile to icode identical to upstream's: same bytes, string and number pools, exception tables, frame sizes and nested function tables |
| Interpreter | ✅ | Runs the icode. 220 scripts match upstream: arithmetic, coercion, strings, templates, functions, closures, control flow, objects, prototypes, `new`, `with`, `try`/`finally`, getters and setters, optional chaining and the engine's own error messages. Generator objects and continuations are out until phase 4 and never, respectively |
| Standard objects, wave 1 (Object, Function, Error and the error kinds, the global functions, Boolean, Number, Math, Script) | ✅ | Registered by `initStandardObjects()` in upstream's order. About 420 oracle scripts match upstream, including `Number.prototype.toFixed`, `toExponential` and `toPrecision`, which use a hand-written exact decimal path instead of BigDecimal. `Number.prototype.toString(radix)` with a radix other than 10 waits for the phase 5 BigInt |
| Array | ✅ | Dense and sparse storage, every `Array.prototype` method through `with` and `toSpliced`, the iterators, species and subclassing. About 300 oracle scripts match upstream |
| String | ✅ | The wrapper object, indexing, every `String.prototype` method and the statics. About 190 oracle scripts match upstream. Two gaps: `localeCompare` is code-unit order, not collation, and `normalize` returns its input unchanged (common Kotlin has neither a collator nor normalization tables). `match`, `search`, `matchAll` and the regexp forms of `split` and `replace` wait for the phase 4 regexp engine |
| Eval oracle corpus | ✅ | 47 whole programs under `jvmTest/resources/eval` run on both engines with the same answers; twenty of them, with upstream's recorded answers, run on JVM, JS and iOS |
| JSON | ✅ | `JSON.parse` with reviver and `JSON.stringify` with replacer and indentation. About 130 oracle scripts match upstream |
| Symbol | ✅ | The constructor, the registry (`Symbol.for`, `Symbol.keyFor`), `description`, the wrapper object `Object(sym)` makes, and all thirteen well-known symbols. About 110 oracle scripts match upstream, including symbols as property keys and every coercion error |
| Iterators and generators | ✅ | `ES6Generator` at ES6 and `NativeGenerator` below it, the legacy `Iterator` and `StopIteration`, `yield` and `yield*` with delegation, `return` and `throw` into a suspended generator. About 90 oracle scripts plus 16 at language version 1.8 match upstream, and three whole programs run on JVM, JS and iOS. Like upstream, breaking out of a `for...of` does not run the generator's `finally`; an explicit `return()` does |
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
