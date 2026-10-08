# KiteJS Implementation Plan

> **For agentic workers:** execute tasks inline in the main session (superpowers:executing-plans style). Do NOT dispatch write-subagents: this workspace has a one-writer rule. Read-only scouting subagents are fine. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a JavaScript engine in pure common Kotlin, ported from Mozilla Rhino 1.9.1, that evaluates real ECMAScript identically to upstream on Android, iOS, JVM and JS.

**Architecture:** faithful port of Rhino's interpreter path: `TokenStream` (lexer) feeds `Parser` (AST), `IRFactory`/`CodeGenerator` lower the AST to Icode, `Interpreter` executes Icode against the runtime (`ScriptRuntime`, `ScriptableObject`, native objects). The JVM bytecode compiler is not ported; upstream's interpreted mode (what Rhino uses on Android) is the model. iOS forbids JIT, so an interpreter is also the only legal design.

**Tech stack:** Kotlin 2.4.10 KMP, Gradle 9.6.0, AGP 9.2.1 (`com.android.kotlin.multiplatform.library`), vanniktech publish, dokka. Runtime dependencies: none.

**Spec:** the pinned upstream source at `reference/rhino/rhino/src/main/java/org/mozilla/javascript/` (tag `Rhino1_9_1_Release`). Each port task names its upstream files; those files ARE the task's code spec. Feature coverage reference: https://mozilla.github.io/rhino/compat/engines.html

## Global constraints

- Everything engine lives in `kitejs/src/commonMain/kotlin/io/github/yuroyami/kitejs/`. Package mirrors upstream: `org.mozilla.javascript` maps to `io.github.yuroyami.kitejs`, subpackages `ast`, `regexp`, `json`, `typedarrays`, `dtoa`, `v8dtoa` keep their names.
- File mapping is 1:1 by name: `TokenStream.java` becomes `TokenStream.kt`. Same class names, method names and behavior. Deviations only where a JVM-ism forces one, via the substitution table below, and each deviation gets a row in the divergence ledger.
- One expect/actual in the whole engine: `WeakRef`, needed by `WeakMap` and `WeakSet` from Phase 5. There is no weak reference in the common standard library, so it cannot be avoided (D-52).
- `:kitejs` runtime classpath = kotlin-stdlib. Nothing else. `jvmTest` additionally carries `org.mozilla:rhino:1.9.1` as the differential-testing oracle; it never ships.
- Never ported: `optimizer/` (bytecode codegen), `xml/` + the 9 `ast/Xml*` files (E4X), `lc/` + `annotations/` + `FunctionObject` reflection binding (LiveConnect), `serialize/`, `commonjs/` (revisit after Phase 7), `debug/` beyond the interfaces the interpreter needs, Intl.
- Every ported file starts with the 3-line MPL header (same header upstream uses). License MPL-2.0, NOTICE credits Mozilla.
- Threading: v0 is single-thread confined (JS is single-threaded). Drop `synchronized`/`volatile` at port time; each dropped site gets a one-line `// KMP:` comment. No atomics until a real need appears.
- Comments: keep upstream's substantive comments, drop Javadoc boilerplate. New comments only for KMP deviations, 1-2 lines.
- Docs follow `#Kite/KITE.md`. No em dashes anywhere: code comments, docs, strings.
- TDD per task: port test lands with the file cluster, red first where practical, jvmTest green before moving on. Other targets compile-checked per phase, test-run per milestone.
- Commit per task, conventional style, no co-author trailers.

## How to execute a port task

Standing recipe; every port task below means exactly this:

1. Read the upstream `.java` file(s) fully.
2. Write the failing test first when the cluster has testable surface (lexer, parser, runtime); for pure data classes (AST nodes) the test comes at cluster level.
3. Port file by file, preserving structure. Apply the substitution table. Where Kotlin makes code strictly better with identical behavior (`when` for switch, `Enum.entries`, `data object`), take it; do not restructure logic.
4. Run `./gradlew :kitejs:jvmTest`.
5. Update `PORTING_STATUS.md`, tick the checkbox here, commit.

## KMP substitution table

| Upstream JVM-ism | Where it bites | KiteJS substitution |
|---|---|---|
| `java.io.Reader` source path | `TokenStream`, `Parser`, `Context.evaluateReader` | String-only source. Reader overloads are not ported (D-2) |
| `ThreadLocal` context stack | `Context.enter/exit`, `ContextFactory` | plain singleton slot, single-thread contract (D-3) |
| `synchronized`, `volatile`, `SlotMapContainer` thread-safe variant | `ScriptableObject`, `LazilyLoadedCtor`, caches | dropped under the single-thread contract (D-3) |
| `Character.isJavaIdentifierStart/Part` | lexer identifier scanning | own `ScriptRuntime.isJavaIdentifierStart/Part` built on `Char.category` (D-1) |
| `ResourceBundle` + `MessageFormat` (`Messages_*.properties`) | every error message via `ScriptRuntime.getMessage` | `Messages.kt`: Kotlin map of the English bundle, `{0}` substitution by hand. Keys ported on demand per phase (D-4) |
| `java.math.BigInteger` | BigInt literals in the lexer, `NativeBigInt`, arithmetic | Phase 0 stores digits + radix in a stub `KBigInt`; Phase 5 gives it real arithmetic (D-5) |
| `config/` `RhinoProperties` (system properties, env) | feature flags | ported `RhinoConfig` object resolves every flag to its compile-time default (D-6) |
| `java.util.TimeZone`, `System.currentTimeMillis` | `NativeDate`, `Date.now` | `Context.timeZone` is a kotlinx-datetime `TimeZone` (default `currentSystemDefault()`, tests inject `TimeZone.of("UTC")` or a `FixedOffsetTimeZone`), plus an injectable clock with a stdlib `Clock.System` default (P4.5) |
| `java.time` localized formatters, `Locale` | `Date.prototype.toLocale*` | fixed en-US patterns equal to upstream under `Locale.US`; other locales out of scope (P4.5) |
| `java.util.regex`, `Character.UnicodeScript`, `Character.getType(int)`, `Character.toUpperCase` | `UnicodeProperties`, `NativeRegExp` case folding | Kotlin range tables generated from the Unicode Character Database 15.0 by a Gradle task kept in the repo (P4.4) |
| `WeakHashMap` | `NativeWeakMap`, `NativeWeakSet`, the symbol registry | own weak-keyed map over the port's own `WeakRef` (P5.3); the registry is a plain map |
| `java.util.List` and `RandomAccess` views, `java.lang.reflect.Array` | `NativeArray`, `NativeTypedArrayView` | dropped (D-36 and the same rule for the typed views, P4.6) |
| `AtomicInteger`, `ConcurrentHashMap` | `NativeConsole` counters | plain fields under the single-thread contract (P7.2) |
| `java.util.jar.Manifest` | `ImplementationVersion` | a constant (P7.1) |
| `Double.doubleToLongBits` | dtoa, hashing | `Double.toBits()/toRawBits()`, exists in common |
| `String.format`, `Locale` | misc formatting | manual formatting; locale-sensitive behavior is out of scope |
| `Serializable`, `serialVersionUID`, `readObject` | almost every class | dropped |
| `ClassLoader`, reflection, `ClassCache` | LiveConnect, `FunctionObject` | cut; Phase 7 replaces host-object binding with a Kotlin DSL over the lambda architecture upstream already has |
| `IdentityHashMap` | `EqualObjectGraphs`, interpreter guards | small own map keyed by reference equality, only where actually needed (Phase 3) |
| `java.util` collections | everywhere | Kotlin stdlib collections |

## Divergence ledger

Living list. Every entry is a known, deliberate behavior or structure difference vs upstream.

- D-1: CLOSED by D-43. Identifier classification used to approximate the JVM with `Char.category`;
  it now reads the generated tables, so every target answers exactly what upstream answers.
- D-2: no `Reader`-based source input. String in, that is all.
- D-3: engine instances are single-thread confined. No shared-context multithreading.
- D-4: error messages exist in English only, and only the keys the ported code uses.
- D-5: `KBigInt` replaces `java.math.BigInteger`, which common Kotlin has no equal of. Sign and
  magnitude over base 2^32 limbs, with Karatsuba multiplication and Knuth division, written here
  rather than taken from a library (rule 4): the multiplatform candidates do sign-magnitude
  bitwise operations, and JavaScript needs two's complement over an infinite bit string, which is
  the harder half. `KBigIntOracleTest` checks every operation against `java.math.BigInteger`.
  It was a stub through phases 0 to 4, holding a literal's digits so the lexer had somewhere to
  put them; the real arithmetic landed in P5.1.
- D-6: feature flags are compile-time constants, not system properties.
- D-8: where upstream pairs a protected field with a public accessor that has extra behavior
  (`Node.type/lineno/column`, `AstNode.parent`, `Scope.parentScope`, `Loop.body`), the port keeps the
  public name as a property and the raw storage as a `*Field` sibling. Java subclasses that wrote the
  raw field write the `*Field` name here, so the field-versus-accessor distinction survives.
- D-9: `AstRoot` keeps its comments in an insertion-sorted list, not a `java.util.TreeSet`. Ordering
  and the drop-on-equal-position behavior match; the type in the public API is `List<Comment>`.
- D-10: `Jump.getFinally`, `getContinue` and `getDefault` become `finallyTarget`, `continueTarget`
  and `defaultTarget`, since `finally` and `continue` are Kotlin keywords.
- D-11: D-7 applies to symmetric accessor pairs. Where upstream's getter and setter disagree on
  nullability or semantics (`FunctionNode.getParams`/`setParams`, `TemplateLiteral.getElements`/
  `setElements`, `ScriptNode.getFunctions`), the port keeps them as methods.
- D-12: `ContinueStatement.getTarget` becomes `targetLoop`. Upstream's `Jump.target` is a public
  field, so a Java subclass can add an unrelated `getTarget`; in Kotlin both are properties and would
  collide.
- D-13: `BigIntLiteral.toSource` matches upstream only for decimal literals until Phase 5, because
  the `KBigInt` stub cannot convert a hex, octal or binary literal to its decimal digits (follows
  from D-5).
- D-14: RETIRED in P2.1. `NumberLiteral(Double)` used to derive its text from `Double.toString`,
  which differs between the JVM, JS and native. The Schubfach formatter is now ported, so
  `ScriptRuntime.numberToString` gives the same answer on every target. Only radix 10 is covered;
  the other radixes still wait for BigInt (D-5).
- D-15: the deprecated `getOptimizationLevel`/`setOptimizationLevel` pair on `CompilerEnvirons` and
  `Context` is not ported. There is no bytecode compiler, so `interpretedMode` is the only switch and
  the level would always read -1.
- D-16: E4X syntax is rejected rather than parsed. `xmlInitializer`, `attributeAccess`, `xmlElemRef`,
  the qualified-name branch of `propertyName` and the `DOTQUERY` branch of `memberExprTail` report
  "XML not available" and return an error node. `compilerEnv.xmlAvailable` keeps upstream's default
  of true so the surrounding control flow is unchanged.
- D-17: the parser does not catch stack overflow. Upstream turns a `StackOverflowError` on deeply
  nested input into a "too deep parser recursion" error; common Kotlin has no portable way to catch
  it, so such input fails with the platform's own stack error.
- D-18: `Context.reportError` always throws, because there is no runtime Context to route through
  until P3, and its no-position overload cannot recover a source position from the interpreter stack
  for the same reason. Both become faithful once the runtime Context lands.
- D-19: `IRFactory.createForIn` unwinds with a `ParserException` where upstream returns null on a bad
  for-in left side. Upstream's caller then dereferences that null, so in IDE mode it fails with a
  null pointer instead of abandoning the subtree. Outside IDE mode both behave the same, since
  `reportError` throws.
- D-20: `Scriptable.getDefaultValue` takes a `KClass<*>?` instead of a `java.lang.Class`, and
  `ScriptRuntime` keeps its type sentinels (`StringClass`, `NumberClass` and the rest) as `KClass`
  values. Common Kotlin has no `java.lang.Class`. The sentinels are only ever compared by identity,
  so the switch changes nothing an engine caller can observe.
- D-21: two contract members wait on code that is not ported yet. `Script.getDescriptor` returns a
  `JSDescriptor`, which is part of the descriptor layer landing in P3.6, and `Evaluator`'s
  `getDebuggableScript` belongs to the debugger surface, which is never ported. Both are recorded in
  the contract parity test's expected-difference list, so the test fails if either becomes stale.
- D-22: `SerializableCallable` and `SerializableConstructable` stay as names but carry nothing.
  Upstream uses them to mark a lambda as serializable; there is no serialization in this port, so
  they are empty markers over `Callable` and `Constructable`. They are kept because
  `LambdaFunction` and `LambdaConstructor` name them all over their public API.
- D-23: `ScriptRuntime.typeof` becomes `typeOf`, because `typeof` is a reserved word in Kotlin.
  Same reason as D-10. Two error messages that upstream fills with a Java class name use the Kotlin
  simple name instead, since common Kotlin has no `Class.getName`.
- D-24: `setExternalArrayData` defines the `length` property with a lambda getter, where upstream
  points it at a reflected `getExternalArrayLength` method. Both give a read-only, non-enumerable
  accessor property that reports the external array's length.
- D-25: the thread-safe half of the slot maps is not ported, which follows from D-3.
  `ThreadSafeEmbeddedSlotMap`, `ThreadSafeHashSlotMap`, `ThreadSafeCompoundOperationMap`,
  `LockAwareSlotMap`, `SlotMapOwner.ThreadedAccess` and the two thread-safe empty and single-entry
  maps are all gone. `Context.FEATURE_THREAD_SAFE_OBJECTS` is always false.
- D-26: `NativeFunction` is not ported. At this pin `JSFunction` extends `BaseFunction` directly and
  nothing in scope extends `NativeFunction`; it only serves the bytecode compiler's generated classes.
- D-27: `EqualObjectGraphs`, the structural graph comparison, is not ported. The `equals` overrides
  on `BoundFunction` and `IdFunctionObject` that used it are gone, so two bound functions with the
  same target and arguments compare by identity, as any other object does.
- D-28: `Context` drops class shutters, wrap factories, security controllers, class loaders, the
  debugger, property-change listeners and locale. The debugger's `DebuggableScript` interface is not
  ported either; `JSDescriptor` keeps the same members as plain methods. The continuations API
  (`captureContinuation`, `resumeContinuation`) is decided with the interpreter in P3.7.
- D-30: `Interpreter` dispatches with a `when` over the opcodes instead of upstream's table of one
  object per instruction. The cases hold the same code; only the dispatch differs.
- D-31: continuations are not ported: `NativeContinuation`, `ContinuationPending`,
  `Context.captureContinuation`, `resumeContinuation`, `executeScriptWithContinuations` and the
  `ContinuationJump` paths in the interpreter. They are a Rhino extension no ECMAScript program
  uses, and the generator machinery does not depend on them.
- D-32: Kotlin/JS cannot tell an `Int` from a `Double` at runtime: every number passes `is Int`
  there, so `NaN`, `Infinity` and `2.5` would take the Int fast paths in the arithmetic and get
  truncated. `ScriptRuntime.isInt` therefore also checks the value is a whole 32-bit number that
  is not `-0`, and every fast path that does integer work (`add`, `subtract`, `multiply`, the
  bitwise operators, `negate`, `toInt32`, `toIntegerOrInfinity`, `++`/`--`, `Object.fromEntries`)
  goes through it. The check is free on the JVM. The eval smoke test and the corpus slice run on
  every target to keep this honest; the corpus slice is what found it. One visible leftover: a
  message that prints a whole `Double` with Java's `Double.toString` (`new 5` says `5.0 is not a
  function` upstream and on the JVM) says `5` on JS, because the value cannot be told from an Int
  there.
- D-39: `Messages` formats number arguments the way `java.text.MessageFormat` does (thousands
  separators, three fraction digits with half-even rounding, no `.0` on a whole double, `∞` for an
  infinity), checked against the real `MessageFormat` by `MessageFormatOracleTest`. Without it a
  `Double` printed as `5.0` on one platform and `5` on another.
- D-33: `dtoa/DecimalFormatter` is written without `BigDecimal`. It expands the double into its
  exact decimal digits by hand (every double is a finite decimal) and rounds the digit string
  HALF_UP the way `MathContext` and `setScale` do. `DecimalFormatterOracleTest` compares it with
  upstream over a fixed corpus and 350 seeded random doubles for every digit count the
  JavaScript methods accept.
- D-33b: `Number.prototype.toString(radix)` for radixes other than 10 is upstream's
  `DToA.JS_dtobasestr`, moved to `dtoa/RadixFormatter` because the rest of `DToA` was replaced by
  `DecimalFormatter` (D-33). The algorithm is upstream's: it stops emitting digits as soon as what
  it has already printed reads back as the same double. It runs over `KBigInt`, which is why it
  waited for phase 5.
- D-34: `NativeObject` no longer implements `java.util.Map`; the `keySet`, `values`, `entrySet`
  and `containsKey` views are gone. They were a Java host convenience, not JavaScript behaviour.
  The global `isXMLName` is registered for parity but throws "XML is not available", since E4X is
  out of scope.
- D-35: `RhinoException` captures the interpreter frames itself (`Interpreter().captureStackInfo`)
  instead of asking `Context.createInterpreter()`; there is only one evaluator to ask.
- D-36: `NativeArray` no longer implements `java.util.List`; only `toArray()`, `size()`,
  `isEmpty()` and `get(Long)` stay, because the abstract operations use them. Same reasoning as
  D-34.
- D-37: `String.prototype.localeCompare` compares UTF-16 code units and answers -1, 0 or 1.
  Upstream uses a `java.text.Collator` at IDENTICAL strength with canonical decomposition, and
  common Kotlin has no collator. Mixed-case and accented comparisons differ from upstream until a
  Kotlin Multiplatform collation exists; the oracle only checks same-case ASCII.
- D-38: `String.prototype.normalize` checks the form name and returns the text unchanged.
  Upstream uses `java.text.Normalizer`; common Kotlin has no normalization tables. Combining
  sequences therefore stay as written. This is a real gap and stays listed in
  `PORTING_STATUS.md` until it is closed.
- D-40: the `Symbol.for` registry is a plain `HashMap<String, SymbolKey>`. Upstream uses a
  synchronized `WeakHashMap`, but the map holds the description strings itself, so no entry was
  ever collected and the two behave the same. The port is single-thread confined (D-3), so the
  synchronization has nothing to protect.
- D-41: `NativeIterator` drops `WrappedJavaIterator` and the `getJavaIterator` path. They wrap a
  `java.util.Iterator` or `Iterable` through the wrap factory, which is LiveConnect. `new
  Iterator(x)` on a script object behaves exactly as upstream. `NativeGenerator.resume` also drops
  upstream's reentrancy lock, since the port is single-thread confined (D-3).
- D-42: `Interpreter.execute` is split into `execute` and `executeCold`. One `when` over every
  opcode compiles to about 9000 bytecodes, and HotSpot silently refuses to JIT-compile any method
  over 8000, so the whole engine ran inside the JVM's own bytecode interpreter. Measured on a tight
  numeric loop: 10238ms before the split, 517ms after, against upstream's 375ms. Which opcodes sit
  in which half does not matter, only that both halves are under the limit. Behaviour is unchanged.
- D-43: the Unicode data the lexer and the regexp engine need is generated from JDK 21's own
  `java.lang.Character` by `tools/unicode/UnicodeTablesGenerator.java`, not parsed from the UCD
  files. Rhino asks `Character` these exact questions, and `Character` has its own answers that the
  UCD does not give directly (its `isWhitespace` is not Unicode White_Space, `digit(cp, 16)` accepts
  more than Hex_Digit, and unassigned code points are handled its own way). Reading the tables from
  the JDK makes the oracle exact by construction, and `UnicodeTablesOracleTest` walks all 1114112
  code points on every run to keep it that way. The Unicode version is still pinned: whatever JDK 21
  ships, which is 15.0.
- D-44: the regexp engine is constructed directly instead of being discovered through a service
  loader. Upstream uses `RegExpLoader` so a build can leave the regexp package out; there is one
  implementation here and no service loader in common Kotlin. The debug disassembler
  (`prettyPrintRE`, about 250 lines behind a system property) is not ported either, since common
  Kotlin has no system properties.
- D-45: the short zone name in `Date.prototype.toString` and in the pre-ES6 `toLocale*` formats is
  the zone id, not the abbreviation. Upstream reads CLDR through `java.time`; there is no such data
  in common Kotlin and no way to compute it. `new Date(0).toString()` therefore ends `(UTC)` where
  it would say `(CET)` under Europe/Berlin. Everything before that in the string, the offset
  included, is identical, and the oracle checks the whole string in a zone whose id is its own
  abbreviation.
- D-46: `Date.prototype.toLocaleString`, `toLocaleDateString` and `toLocaleTimeString` format with
  fixed en-US patterns and ignore their locale argument. Upstream asks `java.time` with the default
  locale, which is what an engine with no `Intl` is allowed to do. At ES6 and above the patterns
  print no zone name, so those answers are identical to upstream's under `Locale.US`.
- D-47: Kotlin/JS needs the `@js-joda/timezone` npm package and an eager reference to it, or
  `TimeZone.of("Europe/Berlin")` throws and only UTC and fixed offsets work. The dependency alone is
  not enough: nothing loads the package unless something references it, and the reference is dropped
  as dead code without `@EagerInitialization`. `DateZoneSliceTest` is what caught this.
- D-48: the byte reader hands back `Int` where upstream hands back `Byte` and `Short`, and a
  `Double` where it hands back a `Long`. Those are the number types the rest of the engine speaks,
  so a value read out of a typed array needs no further boxing. Script sees a number either way.
  The typed array views also drop the `java.util.List` face, same reasoning as D-34 and D-36.
- D-49: two message keys upstream's code uses but its properties file never defines,
  `msg.missing.argument` and `msg.typed.array.abstract.ctor`. Asking upstream for either raises a
  missing-resource error instead of returning text, so there is nothing to copy and the port writes
  its own wording. `MessageParityTest` pins that upstream still lacks them, so the port takes their
  text the moment upstream defines it. The second one also replaces a placeholder message upstream
  never meant to ship.
- D-50: a `getOwnPropertyDescriptor` trap may answer `undefined` for a property the target does
  not have. Upstream reads the target's descriptor without checking for null first and throws a
  `NullPointerException`; the port returns `undefined`, which is what the spec asks for and what
  every other engine does. `EvalOracleTest.getOwnPropertyDescriptorTrapMayReturnUndefined` pins
  both halves, so the port follows the moment upstream fixes it.
- D-51: the `construct` trap is handed a real JavaScript array of the arguments. Upstream passes
  the raw Java array and relies on Java interop to wrap it on the way into script, which this port
  does not have, so the raw array would simply fail to be a value script can hold. The `apply`
  trap already built a proper array upstream, so the two now agree.
  `EvalOracleTest.constructTrapGetsARealArray` pins both halves.
- D-52: `WeakRef` is the engine's one expect/actual. Upstream uses `java.util.WeakHashMap`,
  and the common standard library has no weak reference at all, so `WeakMap` and `WeakSet`
  cannot be built without one. The class and its four actuals live in the engine rather than in
  a shared library: the obvious candidate exposed a coroutines dependency to every consumer and
  would have fixed the target list to its own. The files came from KiteCore and keep their Apache
  2.0 header, which `NOTICE` records. `WeakRefTest` covers the contract on every
  target and `WeakRefGcTest` proves the reference is really weak on the JVM, the only target
  where a test can ask for a collection.
- D-53: `BigUint64Array` reads back the value that was written. Upstream masks with
  `0xffffffff`, a Java int literal that sign-extends once it is promoted to a long, so every
  element with its top bit set comes back wrong: writing `-1n` reads as `-4294967297n` instead of
  2^64 - 1. The branch is wrong for every value it exists to handle.
  `EvalOracleTest.bigUint64ArrayReadsBackWhatWasWritten` pins both halves.
- D-54: `KBigInt` does not extend `Number`, though upstream's `BigInteger` does. On Kotlin/JS
  `Number` is the JS primitive number type, so a class extending it fails `is Number` and throws
  on `as Number`; every bigint operation went down the mixed-operand path and raised a TypeError.
  The JVM and iOS never showed it, which is what the cross-target slice is for. So the runtime
  tells a bigint apart with `is KBigInt`, the binary numeric operators in `ScriptRuntime` take
  `Any?` rather than `Number`, `toNumeric` answers `Any`, and `numericToDouble` replaces the
  `Number.doubleValue()` calls that used to cover both. The one comparison overload that took two
  numbers is now `compareNumeric`, since both overloads would otherwise have the same signature.
- D-55: `WeakKeyMap` replaces `java.util.WeakHashMap`, which common Kotlin has no equivalent of.
  Keys are matched by identity rather than by `equals`, which is what JavaScript asks for and what
  `WeakHashMap` happened to give upstream because the key types never override it. `hashCode` only
  picks a bucket. There is no reference queue in common Kotlin, so collected entries are swept out
  as the map grows and whenever it is asked its size, rather than the moment the key dies.
- D-56: `Date.prototype[Symbol.toPrimitive]` is non-writable in the port, which is what the spec
  asks for and what `built-ins/Date/prototype/Symbol.toPrimitive/prop-desc.js` checks. Upstream
  leaves it writable and fails that test. This is the rare parity difference where the port is the
  more correct of the two; `Test262ParityTest` pins it, so if upstream ever fixes it the entry goes
  stale and the run says so.
- D-57: identifiers may start with any character the spec's `ID_Start` allows. Upstream asks the
  JDK's `Character.isJavaIdentifierStart`, which is Java's rule rather than JavaScript's, so it
  rejects characters test262 says are valid. The port uses its own generated tables (D-43) and
  passes the seven `language/identifiers/start-unicode-*` files upstream fails.
- D-58: eight test262 files that upstream fails and the port passes, mostly `Proxy` construct
  arguments and the BigInt typed array constructors. Nothing to do about them beyond recording
  them: `Test262ParityTest` asserts each still differs, so an upstream fix shows up as a stale
  entry rather than as silence.
- D-59: `Math.fround` does its own rounding to the nearest 32-bit float rather than going through
  `Double.toFloat()`. On Kotlin/JS that conversion does nothing at all, because JavaScript has only
  doubles, so `Math.fround` was answering its own argument. The rounding is written out over the
  bits, and `FroundOracleTest` checks it against the JVM's real conversion on the boundaries, on
  the ties, and on 200,000 seeded doubles. The test262 cross-target run is what found this.
- D-60: `String.prototype.toLowerCase` and its neighbours call Kotlin's `lowercase()`, which is the
  platform's own. That makes case conversion the one operation in the engine that is not computed
  here, and the one place the targets disagree: the JVM applies the conditional special casing for
  a Greek final sigma, and JS and Native do not. The JVM answer is upstream's, so the parity run is
  clean; `Test262SliceTest` lists the two files where the other targets differ. Putting this right
  means generating the full and conditional case mappings from the UCD, the way D-43 generated the
  classification tables, which is a job of its own.
- D-61: the host-object binding classes in the root package go the same way as `lc/`, because
  common Kotlin has no reflection to bind a host class with. That is `NativeJavaObject`,
  `NativeJavaClass`, `NativeJavaArray`, `NativeJavaList`, `NativeJavaMap`, `NativeJavaMethod`,
  `NativeJavaConstructor`, `NativeJavaPackage`, `NativeJavaTopPackage`, `JavaMembers`,
  `JavaMembers_jdk11`, `JavaAdapter`, `InterfaceAdapter`, `MemberBox`, `WrapFactory`,
  `ImporterTopLevel`, `JavaToJSONConverters`, `NullabilityDetector` and `ClassCache`. The binding
  DSL in P7.2 covers the same ground with lambdas and callable references.
- D-62: the class-loading and sandboxing classes are not ported: `DefiningClassLoader`,
  `GeneratedClassLoader`, `ClassShutter`, `SecurityController`, `SecurityUtilities`, `SecureCaller`,
  `PolicySecurityController` and `RhinoSecurityManager`. All of them exist to load and fence
  generated bytecode, and this port generates none.
- D-63: `NativeTypedArrayIterator` is folded into `NativeArrayIterator`. Upstream keeps a second
  iterator class so the typed-array one can notice a detached buffer; here that is one branch in
  `isDone` instead of a class.
- D-64: `ContextListener` is not ported. Upstream marks it deprecated, its two methods are never
  called by the runtime, and the interface it extends, `ContextFactory.Listener`, is ported and
  used.
- D-65: a write through a `Delegator` lands on the object behind it. Upstream forwards the write
  with the wrapper still named as the receiver, and that loops: the delegee does not own the
  property, so it bounces the write back to the receiver, which forwards it to the delegee again.
  Every write through an upstream `Delegator` ends in a `StackOverflowError`, whether it comes from
  a script (`wrapped.x = 1`, and even `wrapped.name = 'set'` for a property the delegee already
  has) or from a host call to `put`. Reads are unaffected and stay byte-identical to upstream. The
  port names the delegee as the receiver when the write was aimed at the wrapper, which is the one
  line that makes the class usable as the base for host wrappers, which is what P7 wants it for.
- D-66: `console.time` measures with `TimeSource.Monotonic` rather than `System.nanoTime`. It is
  the same monotonic clock idea, and it is the one common Kotlin has on every target.
- D-67: cancelling a coroutine cannot stop a running script on Kotlin/JS. The engine and the
  canceller share the one thread there, so while a script loops nothing else runs, and no signal
  can arrive. The interrupt hook still works on every target when the hook can decide for itself,
  such as a deadline it reads from a clock, because it is asked from inside the running script.
  The job-cancellation tests are therefore JVM tests.
- D-68: the engine reads decimal numbers with its own parser, not the platform's. Kotlin/Wasm's
  `String.toDouble` answers a neighbouring double for about one string in a hundred, mostly but
  not only subnormal ones: `5.65865417e-315` came back one unit in the last place low. Measured
  over 20,000 random strings, the JVM and Kotlin/JS were exact and Wasm was wrong 235 times. A
  number literal that means two different numbers on two targets is not one engine, so
  `dtoa/DecimalParser` does it here: a single exactly rounded multiply for the common case, and
  an exact `KBigInt` fraction rounded half to even for everything else.
  `DecimalParserOracleTest` checks it against the JVM over 250,000 random strings, every
  subnormal power of two, and 100,000 round trips through the port's own printer.
- D-69: `String.prototype.includes` clamps its start position to the string's length, which the
  spec asks for and the port was leaving to the platform. The JVM clamps inside `indexOf` and
  the other targets do not, so `"abc".includes("", 100)` was true on the JVM and false on Wasm.
  The test262 cross-target slice is what caught it.
- D-70: spreading a value with no `Symbol.iterator` into an array literal or an argument list
  throws a TypeError, as ECMAScript 2015, 12.2.5.2 and 12.3.6.1 ask through GetIterator and as
  every browser does. Upstream spreads a primitive or a plain object by its own ids, so `[...5]`
  has nine elements and `[...{ a: 1 }]` has one, and it spreads null and undefined as nothing.
  A real array whose iterator was deleted still spreads by its length, which is the one lenient
  path upstream's fallback was there for. `EvalOracleTest` pins both halves.
- D-71: assigning to a `const` throws a TypeError in any mode, as ECMAScript 2015, 8.1.1.1.5
  (SetMutableBinding) asks and every browser does. Upstream keeps the older Rhino `const`, which
  ignores the write outside strict mode and in strict code run by `eval`. A const in a frame is
  the only read-only variable there, so `SETVAR` and `++`/`--` throw when they meet one. A const
  held by a scope object carries a `CONST_BINDING` attribute bit, which is what tells it apart
  from a merely read-only property such as `NaN`, where a sloppy-mode write must still fail
  silently. `getAttributes` never reports the bit, so a host sees upstream's attributes. The
  message is the one upstream's strict mode already gives for the same write. A logical
  assignment to a name, `a ||= b`, now lowers to `a || (a = b)` as ES2021, 13.15.2 asks, where
  upstream writes either way; without that, a truthy const would throw on a write that never
  happens. The corpus keeps its logical assignments on properties, which lower as upstream does.
  `EvalOracleTest` pins both halves, and `Test262ParityTest` lists the six destructuring files
  that now pass only here, outside strict mode.
- D-72: a `for` head may declare its variable with `const`, as in `for (const x of xs)`,
  `for (const k in o)`, a destructuring head and `for (const i = s; ...)`. Upstream's parser
  rejects all of these. A let or const head now gives each iteration a binding of its own
  (ECMAScript 2015, 13.7.5.13), where upstream shares one binding across the loop, so a closure
  made in the body keeps the value of its own pass. The names move from the loop into a block
  around the body, which becomes a fresh scope object each time it is entered when the function
  keeps scope objects, and frame slots when it does not. Such a const is scoped to the loop, so
  it may shadow an outer name the way a let does, and it is bound afresh on every pass by two
  icodes of the port's own, `INITCONST` by name and `INITCONSTVAR` by frame slot, since
  `SETCONST` only ever binds a const once. A write to it in the body is a TypeError (D-71). A
  const in a for-in or for-of head with an initializer is a SyntaxError. `Test262ParityTest`
  lists the five files that pass only here.
- D-73: `Math`'s transcendental functions, the `**` operator and asm.js's maths library are
  `FdLibm`, a port of Sun's fdlibm 5.3 to common Kotlin, rather than `kotlin.math`, which hands
  them to the platform: the JVM's `Math` (intrinsics that may be a unit off in the last place),
  JavaScript's own, or the native libm. The same argument gave different digits on different
  targets, and `Math.exp(1)` was `2.7182818284590455` on the JVM on Apple silicon. The port follows
  V8's `ieee754.cc` where V8 left fdlibm 5.3: `exp(1)` is `Math.E`, `tanh` returns a tiny
  argument unchanged, `atan2` answers +-pi/2 whenever `|y/x|` exceeds 2^60, `cbrt` is FreeBSD's
  newer algorithm, and `acosh`, `asinh`, `atanh` and `log2` are FreeBSD's routines rather than
  upstream's formulas on top of `log`, which overflowed for `acosh(1e300)` and gave
  `Math.log2(8)` as `2.9999999999999996`. Against Node 22, 200,000 arguments for each function
  give the same bits. `pow` is the exception: it stays fdlibm's, which is what `StrictMath.pow`
  gives. V8 computes powers its own way and disagrees in the last bit for about one argument in
  a hundred, half of them integer powers, and on the integer powers checked exactly fdlibm's
  answer is the correctly rounded one and V8's is not.
  `FdLibmOracleTest` checks every routine against `StrictMath` over hundreds of thousands of
  arguments, `MathDigitsTest` checks V8's digits on every target, and `EvalOracleTest` and
  `Test262ParityTest` pin the four arguments and one file where upstream's answer was the
  wrong one.
- D-74: a `const` in a `{ ... }` block belongs to the block, as a `let` does, and is bound afresh
  each time its declaration runs (ECMAScript 2015, 13.3.1). Upstream makes every const one
  binding for the whole function that only its first initialization sets, so a const in a loop
  body kept the first pass's value: `for (...) { const k = i }` gave `k` as 0 on every pass. The
  declaration carries `FRESH_CONST_PROP` and binds through the `INITCONST` icodes of D-72, in a
  scope object of the block's own when the function keeps scope objects, so a closure keeps the
  binding of its pass. A var or function declared anywhere inside the block may not share the
  const's name, which each block tracks in `Scope.varNamesWithin`; an outer var or parameter of
  the same name is shadowed. A const directly in a function or script body, a loop or a switch is
  unchanged, which keeps the corpus on upstream's lowering. `Test262ParityTest` lists the
  test262 harness that parses native function source, which declares consts in its loops and
  now works here, with the two tests that use it.
- D-75: the descriptor of a property that holds a built-in not yet built, such as
  `Object.getOwnPropertyDescriptor(globalThis, 'JSON')`, builds it first, as reading the property
  does, so the descriptor's value is the built-in. Upstream's `LazyLoadSlot` inherits the plain
  slot's descriptor, which hands out the `LazilyLoadedCtor` placeholder itself, and anything a
  script does with that value, `typeof` included, throws a host error ("Invalid JavaScript value
  of type LazilyLoadedCtor"). It hit `Math`, `JSON`, `RegExp`, `Map`, `Set`, `Promise`, `Proxy`,
  `Reflect`, `BigInt`, `WeakMap`, `WeakSet`, the typed arrays, `ArrayBuffer`, `DataView` and
  `Array.prototype[Symbol.unscopables]` until something first read them. test262's
  `built-in-function-object.js` walks every intrinsic's descriptors; both engines still fail it
  with a Test262Error, upstream at its first intrinsic, where its copy of the harness trips over
  D-74, and the port only at RegExp's legacy accessors such as `$*`, whose getters print as
  `function get $*()`, as V8's `$&` does, which is not the NativeFunction syntax.
- D-76: a function declaration is not a statement, so as the body of a `while`, `do`, `for`,
  `for-in`, `for-of`, `with` or `if`, labelled or not, it is an early SyntaxError (ECMAScript
  2015, 13.6.1 and 13.7.1.1), and a labelled function is one too in strict code or when it is a
  generator (13.13.1). Annex B.3.2 and B.3.4 keep what browsers keep: in sloppy code a plain
  function may be labelled and may be the body of an `if`. Upstream accepts every one of these,
  as SpiderMonkey's old function statements did, and so does the port below `VERSION_ES6`. The
  message, "function declaration not directly within block", is the port's own key, modelled on
  upstream's "let declaration not directly within block". `Test262ParityTest` lists the 46 files
  that pass only here, one of them outside strict mode only, and `EvalOracleTest` pins both
  halves.
- D-77: a call is a point where the instruction observer is asked, as a branch is. Upstream
  only adds `INVOCATION_COST` at a call and asks the observer at branches and thrown
  exceptions, so code that calls without branching never reached it: a Promise reaction that
  queues the next one ran forever under any budget and any interrupt hook, and a straight-line
  `forEach` callback ran to the end of its array. The interpreter now asks at each call site,
  at each entry from native code (a callback, a reaction), and `Context.processMicrotasks`
  charges `MICROTASK_COST` and asks before each job, through `Context.addInstructionCount`.
  When the facade's budget or hook stops a script, `Context.discardMicrotasks` drops the jobs
  still queued, as V8's `MicrotaskQueue::RunMicrotasks` drops its queue on termination, so the
  abandoned chain does not resume on the next call. `runMicrotasks` and a host call into a
  `JsFunction` are each one call to the budget, as `evaluate` is, and the facade's running
  total is a `Long`. The observer stays off when no threshold is set, so the oracle and parity
  runs see no change.
- D-78: an accessor slot whose setter is a `FunctionSetter` of something that is not a function
  has no setter. `Object.defineProperty` keeps `set: undefined` as such a setter, and upstream's
  `AccessorSlot.setValue` ran it, which did nothing, so a strict write was dropped where ECMAScript
  2015, 9.1.9.1 throws a TypeError, as it does when `set` is left out. `setValue` now treats it as
  no setter: the strict write throws through `throwNoSetterException` and a sloppy one changes
  nothing. `EvalOracleTest` pins both halves and `AccessorWithoutSetterTest` runs the cases on
  every target; 18 strict-only test262 files now pass here and fail upstream, pinned in
  `Test262ParityTest` (#68).
- D-79: `GeneratorFunction` is built for every scope `initSafeStandardObjects` sets up, not only
  for a `TopLevel`, and it is parked on the scope as a value, as %GeneratorPrototype% is, rather
  than put on the global. Upstream built it in `TopLevel.cacheBuiltins` alone, so in a plain
  scope, which `initStandardObjects()` and the facade make, every generator function had a null
  prototype, no `call`, `apply` or `bind`, and was not an `instanceof Function`; in a `TopLevel`
  it put the constructor on the global as an enumerable `__GeneratorFunction`, which
  `Object.keys(globalThis)` and a `for`-`in` listed. `GeneratorFunctionPrototypeTest` runs the
  expression, declaration and method forms on every target, and `EvalOracleTest` pins the
  difference (#67).
- D-80: `Context.isStrictMode` answers the mode of the code that runs: the interpreter's current
  frame, whose descriptor says whether its code is strict, and the top call only when no frame
  runs. Upstream asked the current activation, which only a function that needs one pushes, so a
  strict callback called by sloppy code wrote to a frozen object quietly, and a sloppy function
  called from strict code threw. The `Function` constructor marks the frame that calls it as
  sloppy until it returns, in place of clearing the activation. Two readers of the same answer
  meant something else and now ask for it: an `arguments` object is mapped when its own function
  is sloppy, whoever reads it, and an indirect `eval` compiles its code sloppy unless the code
  says otherwise, where upstream made it strict whenever its caller was. `StrictModeFollowsTheCodeTest`
  runs each failed write in both directions, and `EvalOracleTest` pins the difference (#69).
- D-81: a Promise resolve function whose resolution has a `then` getter that throws rejects
  the promise with what was thrown, as ECMAScript 2015, 25.4.1.3.2 steps 8 and 9 ask and V8
  does. Upstream reads `then` outside any catch after setting `alreadyResolved`, so the error
  escapes the resolve function (into the executor, or into the reaction that returned the
  thenable) and the promise is never settled. `NativePromise.awaitValue` is the spec's Await
  for the host: a native promise is observed through `PerformPromiseThen` with no derived
  promise and without calling its `then`, and any other thenable has `then` read once and called
  from a microtask with fresh resolving functions, so nested promises and thenables are adopted
  and only one settlement counts. `onSettled` and the coroutine `await` sit on it.
- D-82: `WeakMap` and `WeakSet` keep their entries on the keys, not in the collection. Each
  key that the engine made (any `ScriptableObject`, or a symbol from `Symbol()`) carries a
  `WeakKeyTable` of the collections it is in, each held weakly, with what that collection holds
  for it. This is the inverted representation the WeakMap note in the spec (ECMAScript 2015,
  23.3) describes for a collector without ephemerons, the one Mozilla's ephemeron write-up
  attributes to Edge and the one the SES weak-map shim uses. Nothing in a collection leads to a
  key, so a value that refers back to its key, or entries whose values lead to each other's keys,
  go when nothing else holds the keys. Upstream keeps a `java.util.WeakHashMap`, whose values are
  strong and so pin any key they lead to for as long as the map lives. The costs: an entry of a
  collection that was collected first stays on a live key until that key is next looked up, which
  sweeps it out; a value that refers to its own collection keeps that collection alive while the
  key lives; and built-in symbols and host objects such as a `Delegator`, which have nowhere to
  keep a table, use a `WeakKeyMap` of the collection's own. On WebAssembly, where nothing is weak,
  an entry now goes with its key and a dropped collection stays reachable from its live keys,
  where before every entry stayed until the collection was dropped.
- D-83: `%TypedArray%.prototype` `at`, `toReversed`, `toSorted` and `with` follow ES2023 step
  by step: ValidateTypedArray first, so a detached view is a TypeError, then the length, then the
  conversions of what the script passed. `with` converts its replacement with the view's own
  conversion (ToBigInt on a bigint view), takes a missing one as undefined, and checks the index
  against the view as it is after the conversions. The copies come from TypedArrayCreateSameType,
  and the species default of `map`, `filter`, `slice` and `subarray` and the buffer behind a new
  view come from the realm's intrinsics, which `Intrinsics` records as each constructor is set up.
  Upstream skips the validation (`at` answers undefined, `toReversed` and `with` copy a detached
  view, `toSorted` throws a host ClassCastException), converts every replacement with ToNumber
  defaulting to 0, and finds the constructors through the global object.
- D-84: ToBigInt turns a Number away with a TypeError, as ECMAScript 2020, 7.1.13 says, so a
  Number cannot be written into a bigint view or passed to `BigInt.asIntN`. `BigInt()` keeps
  NumberToBigInt through `ScriptRuntime.bigIntFromValue`. Upstream's `toBigInt` converts an
  integral Number for every caller.
- D-85: a sort comparator that answers NaN means equal, as SortCompare says. Upstream maps NaN to
  greater with `Double.compare`, so a stable sort can reorder elements that a consistent
  comparator calls equal.
- D-86: `%TypedArray%.prototype.set` takes a missing source as undefined and puts any source that
  is not a typed array through ToObject, then reads each element with Get, as
  SetTypedArrayFromArrayLike says: `set()` is a TypeError a script can catch, `set('123')` writes
  1, 2 and 3, and an element the source inherits is copied. ToIntegerOrInfinity answers +0 for NaN
  and -0. Upstream indexes the missing argument, so `set()` escapes as a host
  ArrayIndexOutOfBoundsException, demands an object, and reads only own elements.
- D-87: `ArrayBuffer.prototype.slice` reads the length once, before its arguments are converted,
  and once the species constructor has run it checks that the result is attached, is not the
  source and is long enough, and then that the source is still attached, copying only the bytes
  the source still holds. `transfer` and `transferToFixedLength` are ArrayBufferCopyAndDetach: the
  new length goes through ToIndex before the detached check, and the copy is a plain ArrayBuffer
  of the method's own realm, so species is never read. Both default to the realm's own
  ArrayBuffer constructor rather than the global. Upstream checks the source only on entry, so a
  conversion or species constructor that detaches it escapes as a host NullPointerException, and
  it transfers through species, so a species answering the source returns the buffer it has just
  detached, a throwing `constructor` getter blocks the transfer, and a species constructor that
  calls `transfer` recurses until the host stack overflows. It also rejects `transfer(-0.5)`,
  which ToIndex makes 0, and checks for detachment before converting the length.
- D-88: Typed arrays are integer-indexed exotic objects (ECMAScript 2024, 10.4.5). Every
  property name that is a CanonicalNumericIndexString stays on the element path, valid index or
  not: a read of an invalid one is undefined without asking the prototype, `in` is false, a write
  converts the value for the element type first and then ignores the index (also through a
  prototype chain, where a valid index is written onto the receiver), a valid element cannot be
  deleted (a TypeError in strict code), and `ScriptableObject.getBase` stops at the view through a
  new `endsLookup` hook so the lookup never continues past it. Elements have data descriptors that
  are writable, enumerable and configurable, `defineOwnProperty` refuses an invalid index, a
  non-configurable, non-enumerable, read-only or accessor definition, and converts a value only
  once the index is known to be valid, and both `getIds` overloads list the live indices followed
  by the ordinary keys. `Object.defineProperty` and `Object.defineProperties` convert the key
  before the descriptor and throw a TypeError when a definition is refused, rather than ignoring
  the false. `setIntegrityLevel` sends the spec's partial descriptors (`{configurable: false}`,
  and `{configurable: false, writable: false}` for frozen data properties) through each object's
  public [[DefineOwnProperty]], so proxies and exotic objects see them and a refusal throws; a
  String object answers for its characters by comparing against their fixed descriptors, and an
  arguments object gives a live argument its slot before the definition is checked and ends the
  mapping only when `writable` is present and false. A computed property made plain by a partial
  descriptor keeps its computed value. Because those partial descriptors reach ordinary objects
  too, the non-configurable check compares only the fields a descriptor has, so sealing or
  freezing twice works; the descriptor reader is ToPropertyDescriptor, asking HasProperty before
  each read in the spec's order and making the flags booleans; and an array that becomes
  non-extensible or gets a read-only length leaves dense mode, refuses an index at or past that
  length (a TypeError in strict code), and grows its length only for an element that was really
  created. Sealing a non-empty typed array is therefore a TypeError,
  as the spec requires since ES2021 made elements configurable; V8 still seals it. Upstream
  falls through to ordinary lookup for every invalid index, lists either the elements or the
  ordinary keys, has no element descriptors, narrows a fractional index to an int, ignores a
  refused definition, defines descriptors with every missing field defaulted, converts a
  computed property to null, reads a missing `enumerable` or `value` as false or undefined when
  checking a non-configurable property, reads descriptor fields without HasProperty, and lets a
  frozen or non-extensible array keep growing through its dense fast paths.
- D-89: Reflect and the proxy property traps follow the spec's internal methods with a receiver.
  `AbstractEcmaObjectOperations` gains [[Get]], [[Set]] (OrdinarySet with the spec's receiver
  steps, CreateDataProperty on a receiver without the property, and a proxy receiver asked through
  its own traps), [[Delete]] on the object alone, and a define that answers false instead of
  throwing. `Reflect.get`, `set`, `has`, `deleteProperty`, `getOwnPropertyDescriptor` and
  `defineProperty` run ToPropertyKey on the key (so an omitted key is `"undefined"` and `-1` or
  `0.5` stay names), then call those operations with the caller's receiver; `Reflect.set` answers
  whether the write was made, refusing on a read-only property, an accessor without a setter or a
  non-object receiver rather than on a non-configurable one; `Reflect.defineProperty` lets a
  throwing key, descriptor or trap propagate; `Reflect.construct` requires an object argument
  list. A proxy's `get` and `set` traps receive the receiver (the object the lookup started from),
  a trapless proxy runs its target's [[Get]] and [[Set]] with that receiver, a false `set` or
  `deleteProperty` answer is a TypeError in strict code, `delete` takes the proxy's answer instead
  of asking `has` afterwards, `ScriptableObject.getBase` stops at a proxy through `endsLookup` (so
  neither a missed read nor a write consults the proxy's own prototype or `has` trap), and the
  get and set invariants compare with SameValue. A typed array that is the target of such a set
  keeps its element semantics when it is the receiver and defers to OrdinarySet otherwise. A
  trapless proxy's define answers false where its target refuses, instead of letting the target
  throw. Because a proxy now answers a missed read with undefined rather than NOT_FOUND, the
  object environment of a `with` statement stops reading names to find them: HasBinding is
  HasProperty followed by the @@unscopables check (`with ([]) { keys }` no longer finds
  Array.prototype.keys), GetBindingValue asks a proxy HasProperty again and reads a binding that
  vanished as undefined or a strict ReferenceError, name calls, `typeof`, `++` and `--` and
  assignment binding all go through it, and strict SetMutableBinding throws a ReferenceError for
  a binding the right-hand side deleted. Catch and block scopes also sit behind a NativeWith, but
  their binding objects have no prototype, so @@unscopables costs them one missed lookup. A
  compound assignment still resolves the name twice (BINDNAME, then NAME), which only a proxy can
  tell, through one extra `has` and @@unscopables read; changing that means a new IR shape.
  `copyWithin` asks HasProperty for each element instead of taking undefined for a hole, and
  ArraySetLength checks the definition again after converting an object `value`, whose
  `valueOf` may have made the length read-only, and answers false.
  Upstream ignores the receiver in `Reflect.get` and `Reflect.set` (writing straight onto it, so
  an inherited setter is shadowed and a frozen prototype is ignored), answers true for every
  write, converts a numeric key through ToIndex, reads an omitted key as missing, deletes along
  the prototype chain, constructs without an argument list, turns every engine TypeError in
  `Reflect.defineProperty` into false (a proxy invariant violation included) while accepting a
  getter that is not callable, hands traps the proxy as receiver, never calls a `set`
  trap when the receiver differs, ignores false trap answers, reads through a trapless proxy with
  the target as `this`, calls `has` and `getPrototypeOf` traps the spec never reaches, finds
  `with` bindings by reading them, ignores @@unscopables, recreates a binding deleted before a
  strict assignment, and deletes a present undefined element in the generic `copyWithin` loop. RegExp's `source` and flag
  properties stay upstream's own data properties, so `Reflect.get(RegExp.prototype, 'source', re)`
  still reads `''`; that is a structural difference outside this entry.
- D-90: `IdScriptableObject.PrototypeValues.getNames` lists a prototype id named by a symbol as
  that symbol. Upstream added the symbol's `toString()`, so `Reflect.ownKeys` of
  `RegExp.prototype` and `Date.prototype` listed strings such as `"Symbol(Symbol.match)"` that
  name no property, `Object.getOwnPropertySymbols` left the symbol methods out, a descriptor read
  of a listed key answered undefined, and `Object.freeze` reached the string and crashed the host
  with a `NullPointerException`. `IdScriptableObject.defineOwnProperty` redefines such an id in
  place, as it does a named one; upstream sent a symbol key to the slot map, which does not hold
  it, so a non-extensible prototype refused the change as a new property and an extensible one
  got a second copy over the id. `OwnSymbolKeysTest` checks every key of the built-ins on every
  target, and `EvalOracleTest` pins the difference (#76).
- D-91: [[Construct]] takes a newTarget, IsConstructor is exact, and proxies, enumeration, JSON and
  [[SetPrototypeOf]] follow the spec's internal methods. `AbstractEcmaObjectOperations` gains
  IsConstructor (only functions with a [[Construct]]: not arrows, methods, accessors, generators,
  built-in non-constructors or the revoke function, but `Symbol` and `BigInt`, and a bound function
  or proxy when its target is one), Construct(F, args, newTarget), GetPrototypeFromConstructor and
  GetFunctionRealm. `BaseFunction` has a newTarget-aware `construct` that makes the object and then
  gives it newTarget's `prototype`, or the same intrinsic's prototype in newTarget's realm when that
  is not an object (found by comparing the constructor with its realm's built-ins, so
  GeneratorFunction is found too); a script function makes `this` from newTarget before its body
  runs (`new.target` itself does not parse yet), `Object` ignores its argument, a bound function
  passes its target on in place of itself, and a proxy hands newTarget to its `construct` trap or
  forwards it. `Reflect.construct`, species lookups, `Array.from` and `of` and promise capabilities
  use IsConstructor. A proxy's [[GetOwnProperty]] runs the trap's answer through
  ToPropertyDescriptor and CompletePropertyDescriptor and checks it against the target (a
  non-configurable answer for a missing or configurable property, a non-writable one for a writable
  property, a non-object answer); [[OwnPropertyKeys]] compares keys as property keys, rejects
  duplicates and returns the trap's list in the trap's order; [[GetPrototypeOf]] requires an object
  or null and checks a non-extensible target; [[SetPrototypeOf]] is a trap of its own whose false
  answer stands; and a proxy's ids are filtered through its own [[GetOwnProperty]], with indices as
  ints, so `Object.keys`, for-in and JSON see what the getOwnPropertyDescriptor trap reports.
  Objects list integer keys up to 2^32 - 2 first, in ascending order, then strings, then symbols, in
  creation order. `Object.assign`, `entries`, `values` and object spread ask [[GetOwnProperty]] for
  each key just before reading it, in one pass over [[OwnPropertyKeys]] with symbols in their place,
  and `assign` throws when Set is refused and overwrites an array target's elements.
  `hasOwnProperty`, `Object.hasOwn` (whose `length` is 2) and `propertyIsEnumerable` convert the key
  first and ask a proxy its getOwnPropertyDescriptor trap. OrdinarySetPrototypeOf answers false
  instead of throwing, stops its cycle walk at a proxy, and refuses any new prototype for the
  realm's Object.prototype (an immutable prototype exotic object); `Object.setPrototypeOf`,
  `Reflect.setPrototypeOf`, the `__proto__` accessor and `o.__proto__ = v` all go through it, the
  getter throws a TypeError for an undefined `this` instead of crashing, and the setter does nothing
  for a primitive or a non-object value. A built-in function's prototype set to null stays null.
  JSON.parse keeps `-0`; the reviver walk is InternalizeJSONProperty, reading with [[Get]],
  recognising arrays with IsArray (a proxy for one too) to LengthOfArrayLike, writing back with
  CreateDataProperty and [[Delete]], and passing keys as strings; JSON.stringify reads `toJSON`
  once, serialises a proxy for an array as an array, passes keys to the replacer as strings, and
  builds the property list of an array replacer from every index with [[Get]]. Only a call to the
  plain name `eval` is a direct eval, so `this.eval(src)` and another realm's `other.eval(src)` run
  in the eval function's own global. Upstream constructs Map, Set, Promise, typed arrays and the
  other lambda constructors with a newTarget by throwing, gives arrays, regexps and plain objects
  the wrong prototype, accepts arrows, methods and generators as constructors, ignores a proxy's
  setPrototypeOf answer and a getOwnPropertyDescriptor trap's invalid descriptor, answers
  `Reflect.ownKeys` with the target's keys for a non-extensible target, wrongly rejects a frozen
  array target's index keys, lets `Object.keys` of a proxy list non-enumerable keys and throw on a
  symbol, crashes `JSON.stringify` with a ClassCastException on a proxy listing a symbol, leaves an
  array target's elements alone in `Object.assign`, asks `has` where the spec asks for a descriptor,
  puts integer keys from 2^31 in creation order, lets Object.prototype take a new prototype,
  re-defaults a built-in function's null prototype, parses `-0` as `0`, reads `toJSON` twice,
  serialises a proxy for an array as an object, and makes `this.eval` a direct eval.
- D-92: `instanceof` is InstanceofOperator. `ScriptRuntime.instanceOf` looks up the target's
  `Symbol.hasInstance`, calls it with the target as `this` and the left side, primitive or not, as
  its argument, and answers ToBoolean of the result; a value that is neither undefined, null nor
  callable is a TypeError, and only a target without one must be callable and is asked
  OrdinaryHasInstance (`ScriptRuntime.ordinaryHasInstance`), which for a bound function is
  `instanceof` of its target. `Function.prototype[Symbol.hasInstance]` is a `KnownBuiltInFunction`
  so finding it goes straight to OrdinaryHasInstance without a call. `Scriptable.hasInstance` stays
  the hook a host object answers through once no method was found, and `ScriptableObject`'s
  default is now OrdinaryHasInstance for something callable and a TypeError otherwise; the legacy
  `StopIteration` still answers false for a primitive. Upstream asked a function its prototype
  chain whatever it defined, answered false for a primitive left side before looking, called an
  object's method with the target itself as the argument, passed over a method that could not be
  called, read a bound function's target `prototype` instead of asking the target, and crashed
  with a host ClassCastException in `Function.prototype[Symbol.hasInstance]` for a bound function
  whose target was not a script function (#75).
- D-93: The array, map, set, string, regexp string and generator iterator prototypes inherit one
  %IteratorPrototype% per realm, which `ES6Iterator.iteratorPrototype` makes the first time one of
  them asks and parks on the top scope. It is an ordinary object whose one property is
  `[Symbol.iterator]`, of length 0, answering its `this`, and the prototypes no longer hold a copy
  each. %GeneratorFunction.prototype%'s `prototype` is read-only and hidden, and %GeneratorPrototype%
  has a `constructor` pointing back at it, so `ES6Generator.init` leaves the sealing of
  %GeneratorPrototype% to `BaseFunction.initAsGeneratorFunction`, which adds that link. Upstream
  gave every iterator prototype Object.prototype as its prototype and its own
  `[Symbol.iterator]` of length 1, made `prototype` an ordinary enumerable property, and gave
  %GeneratorPrototype% no `constructor`. The iterator helpers of ES2025 (`Iterator.prototype.map`
  and the rest) are not part of this: the global `Iterator` is still Rhino's legacy one (#74).
- D-94: Every global object keeps its realm's intrinsics. `TopLevel.Intrinsics` holds the original
  constructors and error constructors, taken from the globals once the standard objects are in,
  in a field of a `TopLevel` and as an associated value of any other global object, and a scope
  that has none uses those of the global it inherits from, as a scope made per request on top of
  a shared one does. `ScriptRuntime.initSafeStandardObjects` makes a `TopLevel` when it is given
  no scope, as `Context.initStandardObjects` documents, so the global the `KiteJs` facade and
  `initStandardObjects()` make is one, and `Object.prototype.toString.call(this)` there answers
  `[object global]`, as Node does. `Map.groupBy` builds its Map with the constructor it belongs to,
  and `Promise.any` and `Error.captureStackTrace` build their errors with `newNativeError`.
  Upstream cached the intrinsics for a `TopLevel` only and made a plain `NativeObject` when given
  no scope, so in the global most embedders get a replaced `String`, `Object`, `Array` or
  `TypeError` changed the prototype of every primitive, literal and engine error that followed,
  and `Map.groupBy`, `Promise.any` and `Error.captureStackTrace` built through the global binding
  and crashed the host with a ClassCastException once a script replaced it, `TopLevel` or not
  (#77).
- D-95: Class syntax (ECMAScript 2015, 14.5; ECMAScript 2022, 15.7), which upstream has none of.
  The parser makes a `ClassNode` whose constructor, methods, accessors, field initializers and
  static blocks are functions of their own, with `extends`, `super` calls and properties,
  `new.target`, computed keys, public and private fields, private methods and accessors, `#x in
  obj`, static fields and static blocks, and the early errors of a class body (a duplicate or
  special constructor, a static `prototype`, a field named `constructor`, `super()` outside a
  derived constructor, `arguments` in an initializer, an undeclared or duplicate private name, a
  `delete` of one). `IRFactory` lowers it to a `CLASS` node, and the code generator to
  `CLASS_PRIVATE_NAMES`, `CLASS_BEGIN`, `CLASS_CTOR`, one `CLASS_ELEMENT` per element and
  `CLASS_END`, which `ClassRuntime` runs: ClassDefinitionEvaluation with the heritage checks, the
  prototype chain, a read-only `prototype`, methods and accessors defined non-enumerable with
  their home object, fields and private methods recorded on the constructor, static elements run
  in order and the inner binding initialized last, with a temporal dead zone of its own until
  then. A class constructor throws when called, and its [[Construct]] makes `this` from
  newTarget's prototype and puts the fields on it for a base class, or leaves `this` to `super()`
  for a derived one, through a `ThisBinding` the constructor's arrow functions and eval code
  share, and checks the returned value when the frame exits. A default derived constructor passes
  its arguments on without the array iterator. Private names are fresh per class evaluation, an
  object's private elements are a map on `ScriptableObject`, and `#x in obj`, reads, writes,
  calls, `++`, compound and logical assignment all go through them with the TypeErrors the spec
  asks for. Class bodies are strict. Eval code in a field initializer keeps the initializer's
  rules (no `arguments`, no `super()`), through `CompilerEnvirons.inClassFieldInitializer`.
  Making class syntax pass test262 also fixed parser and runtime defects upstream shares with
  plain functions: NamedEvaluation of anonymous functions and classes in destructuring defaults
  and parameter defaults; a method's source text is its whole definition (its raw source bounds
  start at the name, its node keeps upstream's position, which the parser oracle compares); a
  strict function has
  no own `arguments` or `arity`; ArrayBuffer[Symbol.species]; no `constructor` on a generator's
  prototype; a redefinable function `length`; a reserved word spelled with an escape is no
  keyword, fine as a property name and an error as an identifier; getters take no parameter and
  setters exactly one; `in` is allowed between brackets, braces and backquotes in a `for` head;
  a rest parameter may be a pattern and takes no default; "use strict" needs a simple parameter
  list; ZWNJ and ZWJ are identifier parts and format-control characters are kept from ES2015 on;
  `yield 1, yield 2` and `f(yield 1)` need no parentheses and a yield's operand is one
  AssignmentExpression; `yield *` may continue on the next line and `yield` before a line break
  and `*` is no `yield*`; a `let`, a `var` and a block function clash across blocks the way V8
  reports it; `get` before a line break and `*` is a field; a BigInt key names its digits in an
  object literal and a pattern, and a pattern reads a number key as its string form and a string
  key as an element, so `{ 1.5: a }` and `{ '0': a }` find their properties. The redeclaration checks still let a
  sloppy block function and a `var` share a name (Annex B). Lexical bindings other than a class's
  inner binding still have no temporal dead zone (docs/differences.md). The test262 parity test
  runs `language/statements/class`, `language/expressions/class` and
  `language/expressions/new.target`, which upstream's properties file skips; what still fails
  there is async code (#12), arrow rest parameters (#71) and gaps plain functions share, each
  filed as an issue of its own: rest elements, rest properties and computed keys in patterns
  (#81), iterator closing (#83), the parameter
  scope (#84), block-scoped functions (#85), errors made in the catching realm (#88) and
  WeakRef (#89). The
  error messages the class syntax and these early errors need are the port's own, worded after
  V8's, and the one upstream key whose check widened (`msg.default.args.use.strict`) keeps its
  text beside a new `msg.use.strict.non.simple`.
- D-96: for-in follows EnumerateObjectProperties. `ScriptRuntime.enumNext` reads a proxy's
  keys, all its string keys and not only the enumerable ones, through its [[GetOwnProperty]] as
  the loop reaches each: a key without a property is skipped, and one with a property is
  remembered and handed out when it is enumerable, with no `has` call. An ordinary object keeps
  its enumerable keys and the own-property check. Every own key of an object the loop has passed
  hides a prototype's property of the same name, enumerable or not, and so does a key handed out
  and deleted afterwards; the loop reads a passed object's full key list only once it reaches a
  prototype with keys of its own. `__iterator__` is looked up only below ES6. Upstream checked
  each key with `has`, which a proxy answers from its target, so a key the target lacked was
  dropped, remembered only the enumerable keys of the objects it passed, and asked every object
  for `__iterator__` (#90).
- D-97: Async functions and `await` (ECMAScript 2017, 14.7 and 25.5), which upstream has none
  of. `async` and `await` stay names to the token stream and the parser reads them by context:
  `async` before `function` on the same line starts an async function expression or declaration,
  `async x =>` and `async (...) =>` an async arrow (the parenthesized form parses as a call first
  and becomes the parameter list once `=>` follows, so a spread becomes the rest parameter and a
  trailing comma after it is an error), and `async` before a method name on the same line an
  async method, static or not, in a class or an object literal. `await` is the operator inside an
  async function body, an error in its parameters (default values, patterns and an async arrow's
  parameters included) and a name everywhere else; class field initializers read it as a name
  and static blocks reserve it, as V8 does. The early errors are the spec's: `await` as a binding
  or label inside, `yield` inside, `await x ** y`, `async constructor`, `async get` and `set`, an
  async declaration as the body of an `if` or a label, a line break before `=>`, duplicate arrow
  parameters (which upstream let every arrow have) and "use strict" with a non-simple parameter
  list. The body compiles the way a generator's does, with one `Icode_AWAIT` per `await` that
  suspends the frame like a yield, and `AsyncFunctionDriver` runs it: the call creates the
  promise, runs the body to its first `await`, and each `await` waits through the spec's Await
  (PromiseResolve with %Promise% and the promise's own reactions, so one tick per `await` and
  the interleaving V8 shows) and resumes the frame from the microtask queue inside a top call of
  its own, with the value or with the reason thrown in at the `await`. A return resolves the
  promise and a throw rejects it, before the first `await` and in a parameter default too, since
  an async function's defaults run in its body, not before the generator object the way a
  generator's do. The frame ends with a completion value instead of a StopIteration, and its
  function declarations are made when the frame starts. %AsyncFunction% and %AsyncFunction.prototype%
  are realm intrinsics, the prototype of every async function, which has no `prototype` and no
  [[Construct]], and the `AsyncFunction` constructor compiles its source the way `Function` does.
  The test262 harness runs tests flagged `async` by capturing `print`, draining the microtask
  queue after the script and reading `$DONE`'s verdict, on both engines, and the parity test runs
  `built-ins/AsyncFunction`, `language/expressions/async-function`, `language/expressions/await`
  and `language/statements/async-function`, which upstream's properties file skips. `for await`
  and async generators (ECMAScript 2018) are a follow-up of their own (#91), and until then
  `async function*` and `async *m()` are a SyntaxError that says so. The call's own activation
  steps aside while the body first runs, which enters it again, or the activation became its own
  parent and the caller's was lost. Whether `function` follows `async` on the same line is read
  from the end of the name, since a name's `tokenEnd` sits one character further on, past a line
  break too, which made `async` then a line break then a function declaration an async one.
  Running the async tests of test262 also turned up defects upstream shares with plain code: a
  generator's `return` inside a try with a finally computed its value after the finally blocks
  and outside the block scopes, so it now stores the value first and ends through
  `Icode_GENERATOR_RETURN_RESULT`, and a yield hands its value back without the frame result
  slot the stored value lives in; a list with a default, a pattern or a rest parameter, and a
  method's or an arrow's, binds no name twice; a "use strict" body makes the function's own name
  and parameters strict code; two functions of one block share a name only when both are plain
  and the code sloppy; a `let` declaration ends the way any statement does; and a parenthesized
  name or property is a target in an assignment pattern, a property there takes a default, and a
  parenthesized literal is no pattern. A finally block entered on the way out keeps, beside the
  address it returns to, the result that was pending when it started, and puts it back when it
  ends normally, as V8 gives each try-finally a result register of its own: upstream kept one
  result slot per frame, so a `return` inside a finally that a `break` or a caught throw then
  abandoned replaced the return already pending, in a plain function too (the generators passed
  the SpiderMonkey test only because they computed the value late), and an eval of
  `try { 7 } finally { 8 }` answered 8. JavaScript 1.8's expression closures stay plain
  functions' own: a generator or an async function without a braced body is a SyntaxError, as
  SpiderMonkey made it. `let` as a name in sloppy code is a gap upstream shares, filed as #92.
- D-98: a `let` without an initializer binds `undefined` each time its declaration runs
  (ECMAScript 2015, 13.3.1.4). Upstream skips a binding with no initializer when it lowers a
  declaration, which is right for `var`, so a `let x;` in a loop body kept what the previous pass
  left in it. A `let` below the function or script body, in a block, a loop body or a switch,
  carries `RESET_LET_PROP` and lowers to an assignment of `undefined`; one directly in the body
  runs once per call or script and keeps upstream's lowering, and the corpus with it.
  `LetWithoutInitializerTest` pins it.
- D-99: `Number.prototype.toLocaleString` and `BigInt.prototype.toLocaleString` print what
  `Intl.NumberFormat("en-US", options)` prints, on both engines, where upstream's are `toString`
  and read the locale argument as a radix, so `(1).toLocaleString("en-US")` threw. The formatter
  is `EnUsNumberFormat` in kitejs-api: ECMA-402's option reading (InitializeNumberFormat and
  SetNumberFormatDigitOptions, in order, with their coercions and errors), FormatNumericToString
  and PartitionNumberPattern over en-US's patterns. It rounds the shortest decimal digits of a
  double, as ICU does, not its exact binary value. The locale argument is not read, there is no
  `Intl` global, and `numberingSystem` is validated and ignored. The currency and unit strings
  in `EnUsNumberData` are generated from ICU by `tools/intl/generate-en-us-number-data.mjs`,
  which also writes the 1,699 cases `EnUsNumberFormatTest` checks against ICU's output. Rhino
  reaches it from `LocaleNumbers`; QuickJS-ng, whose own `toLocaleString` is `toString`, from
  its prelude, through a proxy of a host function so the method still prints as native code
  and the errors stay catchable. `LocaleNumbersContract` holds both engines to it.
- D-100: `Number::toString` prints the fewest digits that identify the double, as ECMAScript
  2015, 7.1.12.1 step 5 asks, for the tiny subnormals too: `Number.MIN_VALUE` is `5e-324`.
  `DoubleFormatter` is the Schubfach port OpenJDK's `Double.toString` uses, whose specification
  keeps at least two digits, so for eight subnormals (1, 2, 10, 12, 14, 16, 18 and 20 times
  MIN_VALUE) upstream printed two, `4.9e-324` among them. A two-digit result now gives way to a
  one-digit neighbour in the rounding interval, the closer one, or the even one on a tie.
  `JavaNumbers`, which prints a double the way Java does, keeps Java's digits through
  `toStringWithJavaDigits`. `DoubleFormatterTest` checks 900 doubles, every subnormal up to 300
  times MIN_VALUE among them, against what V8 prints (`tools/dtoa/generate-v8-number-strings.mjs`).
- D-101: an `if` runs its condition under the line of the condition's leftmost operand that has
  one. Upstream takes the line of the condition's first child even when that child has none, and
  a property read never has one, so in `if (item.p === "x")` an error took the line of the
  statement before, in `lineNumber` and in the stack. `ConditionLineNumberTest` pins it.
- D-102: `Date.prototype` and `RegExp.prototype` are ordinary objects (ECMAScript 2015, 20.3.4
  and 21.2.5): `[object Object]` to `Object.prototype.toString`, and their methods throw a
  TypeError on them, where upstream made them an invalid Date and a RegExp of the empty pattern.
  Each stays the object the methods are defined on, marked `isPrototype`, so its `className` is
  `Object`, a method's receiver check refuses it, and the RegExp one has no `lastIndex` and no
  pattern; `NativeRegExp.isInstance` tells a RegExp with a pattern from it where the fast paths
  and `RegExpImpl` used to ask `is NativeRegExp`. `source`, `flags`, `global`, `ignoreCase`,
  `multiline`, `dotAll`, `sticky` and `unicode` move from data properties of every RegExp to the
  spec's accessors on the prototype, which answer `(?:)` and undefined on the prototype itself;
  `source` escapes line terminators and gives `(?:)` for the empty pattern, `flags` is generic and
  reads each flag property in the spec's order (`/a/yu.flags` is `uy`), and `toString` is the
  spec's generic `/source/flags`. The RegExp constructor follows IsRegExp: `RegExp(x)` with no
  flags hands back an x whose `constructor` is RegExp, and a pattern IsRegExp counts but that is
  no RegExp lends its `source` and `flags`. `startsWith`, `endsWith` and `includes` refuse what
  IsRegExp counts, before converting it. `PrototypesAreOrdinaryTest` checks 36 cases against V8.
- D-103: an arrow function takes a rest parameter, a name or a pattern, after its other
  parameters or alone (ECMAScript 2015, 14.2). The parser reads a parenthesized expression first
  and makes it the parameter list once `=>` follows, so `...` there failed as an expression and
  upstream rejected every such arrow. `parenExpr` now takes a trailing `... target`, the cover
  grammar's form, which has to close the parentheses and be followed by `=>` on the same line,
  and `arrowFunctionParams` turns it into the rest parameter. A default on it, a parameter or a
  trailing comma after it, and a target that is not a name or a pattern are SyntaxErrors.
  `ArrowRestParameterTest` checks 21 cases against V8.
- D-104: `Float16Array`, and `getFloat16` and `setFloat16` on `DataView.prototype`
  (ECMAScript 2025), which upstream has none of. The element is IEEE 754 binary16, encoded by
  `typedarrays/Float16`, which rounds a double to the nearest half, ties to even, in one step:
  through a float it would round twice and miss some ties. `Math.f16round` uses the same code in
  place of upstream's port. `Float16Test` reads every one of the 65,536 halves back to its bits
  and rounds 2,259 doubles, the midpoints between halves and a double either side of each among
  them, as V8 does; `Float16ArrayTest` checks the API against V8, except for one sort that Node
  22's experimental Float16Array gets wrong, where the spec's order is pinned.
- D-105: in ES6 and later, a for-in destructuring head receives the key string, using the same
  pattern lowering as other destructuring statements. Upstream uses JavaScript 1.7's `[key,
  value]` enumeration for every array pattern and rejects object patterns and arrays of any
  other length (#82). `IRFactory.createForIn` keeps that pair form and its length restriction
  only below ES6. `for each` and `for-of` still enumerate values. `ForInEnumerationTest` checks
  assignment and binding patterns, per-iteration lexical scopes, and that enumerating keys
  never reads property values.
- D-106: object `uneval`/`toSource` reads own descriptors instead of invoking getters, and
  reconstructs methods and accessor pairs as source (#103). Parser-recorded parameter-list
  offsets let it replace a computed or renamed method's original key with its current property
  key without replaying key expressions. Accessors requiring function expressions, including
  arrow getters, are restored through `Object.defineProperty`. Ordinary function and arrow data
  values keep expression syntax. `UnevalObjectTest` checks round trips and serialization effects.
- D-107: in ES6 and later, `yield` is an identifier in sloppy code outside generator
  functions (#86). The scanner uses the current function's grammar context; strict directives
  are checked even when the next token was scanned before the directive took effect. Generator
  expressions reserve their own name, ordinary function expressions reset that restriction,
  and yield expressions are rejected in generator parameters. Explicit pre-ES6 versions retain
  implicit generators. `YieldIdentifierTest` pins 52 modern cases against Node 26.10.0 and checks
  the legacy boundary; the token-position oracle records the deliberate identifier token.
- D-108: non-ISO Date parsing accepts year-first dates with a space before the time,
  one-digit month/day fields, named-month fractional seconds, and month-plus-year strings
  (#112). The fallback uses the browser two-digit-year pivot (00–49 become 2000–2049), without
  changing numeric constructors or Date.UTC. Standard ISO parsing still runs first, preserving
  UTC date-only versus local date-time rules. Year-first calendar and offset bounds remain
  checked, and fractional digits are truncated to milliseconds without overflow. This is a
  compatibility policy for implementation-defined formats, not a change to the ISO contract.
  `DateParseCompatibilityTest` checks 48 V8-controlled strings in three zones, parse/constructor
  agreement, standard round trips, invalid-date rejection and numeric-constructor year rules.
- D-109: Function.prototype has the ES6 restricted caller/arguments accessors, whose getter
  and setter share a non-extensible ThrowTypeError intrinsic per realm (#87). Strict and other
  unmapped arguments objects reuse their function's realm intrinsic across contexts and foreign
  calls. Non-simple parameters (defaults, rest or patterns) do not alias arguments elements.
  Modern methods, arrows, generators, async functions, bound functions and native built-ins have
  no own legacy arity/arguments properties; bound functions inherit their target's prototype.
  Sloppy ordinary functions retain legacy arguments/arity and a null caller sentinel. Compiled
  asm exports retain the source function's own and inherited strictness. Function descriptors
  record the compiler language version, rather than leaving it at zero, so the layout follows
  the code's version. Explicit pre-ES6 layouts remain available. RestrictedFunctionPropertiesTest
  exercises descriptor attributes, realm identity, mutation, function kinds and asm parity.
- D-110: Date reads the configured zone's actual offset at each instant (#49), rather than
  combining a fixed 2024 base offset with synthetic one-hour daylight time or an equivalent year.
  Local constructors, offset-free ISO and fallback parsing, and every local setter resolve civil
  fields with kotlinx-datetime's compatible disambiguation (#50): the earlier overlap instant and
  the pre-gap offset. GMT formatting reads the same actual offset. Calendar overflow is still
  normalized by the engine, and extreme local values are rejected before zone conversion while
  values just outside TimeClip may resolve inside it. DateTimeZoneRulesTest pins Node 26.10
  controls for historical, half-hour and date-line changes, nine gaps/overlaps, parsing and every
  local setter. DateZoneSliceTest now records the compatible overlap result; DateRangeTest retains
  exact positive/negative range boundaries and extreme-field checks. Both web engines share
  @js-joda/timezone 2.23.0, the newest version compatible with kotlinx-datetime's core 3.2.0;
  this replaces the 2020 database that still predicted Apia daylight time after its abolition.
- D-111: Typed array constructors dispatch ordinary objects through a single GetMethod of
  Symbol.iterator and IteratorToList, or through ToLength/indexed reads when that method is absent,
  null or undefined (#43). TypedArray.from uses the same path for arrays and typed arrays, honors
  overridden iterators, and exhausts iteration before construction/mapping/conversion. It validates
  the constructor and mapper before inspecting the source and validates the resulting live typed
  array and its length. Iterator objects/results and next are checked, without eagerly reading
  return or closing an exhausted iterator. Typed-array copy constructors bypass custom iterators,
  reject detached sources and mixed content types even at length zero, and preserve same-type raw
  bits (including NaN payloads). Standard array iterators read inherited elements and advance before
  a getter may throw. TypedArrayInputTest pins 27 Node controls for these effects and exceptions;
  the parity ledger records the exact observed constructor/from improvements over upstream.
- D-112: Bare name/value calls carry undefined (#70), and OrdinaryCallBindThis uses the source
  function's own strictness and realm in both JSFunction.call and the interpreter's fast path.
  Strict functions retain null/undefined; sloppy functions substitute their own global, and
  arrows retain lexical this. Property and with-environment calls retain their base receiver.
  Array/typed-array iteration, reduction and flatMap, Map/Set forEach, Promise executors and
  regexp replacement callbacks pass the specified receiver instead of the caller's global.
  Error.captureStackTrace obtains its realm from its native function, so an extracted call still
  uses the original Error intrinsic. Constant folding of conditional/logical expressions keeps
  GetValue semantics, preventing accidental method receivers, with bindings or direct eval.
  BareCallThisTest pins Node controls and exercises host calls, foreign realms, async/generator
  functions, asm parity and the explicit old undefined/null compatibility feature. Primitive
  receiver boxing (#78) and Reflect.apply's argument conversion (#47) remain separate issues.
- D-113: In modern sloppy code, let is a contextual name rather than an unconditional keyword
  (#92). Non-consuming lookahead recognizes lexical declarations only in declaration contexts
  and for heads, across comments, line terminators, escapes and Unicode identifiers. Expression
  statements retain the negative lookahead for let [, and unparenthesized/unescaped let heads
  remain forbidden in for-of. Relational uses, for-in, ordinary bindings, parameters, labels,
  shorthand properties and function names work as names. Lexical bindings cannot be named let;
  strict code and a function's own strict directive reject identifier uses. Pre-ES6 modes retain
  legacy let expressions/blocks. New let diagnostics validate only matched binding names, avoiding extra
  diagnostics from a stale keyword string. LetIdentifierTest pins 135 Node evaluation/syntax
  controls and checks the legacy extension; lexer position and diagnostic oracle tests document
  the exact modern changes while retaining their other comparisons.
- D-114: ArraySetLength deletes own indices in descending order, including hidden and large
  indices (#80). A non-configurable element stops deletion, restores length to that index plus
  one, and still applies a requested writable:false. Reflect.set/defineProperty receive the
  boolean refusal; Object.defineProperty and strict assignments throw. Length descriptors perform
  ToUint32 then ToNumber on the original value and validate the descriptor after those coercions.
  Array mutation methods use Set(..., true) and DeletePropertyOrThrow independently of caller
  strictness, preserve partial mutations and exception identity, and observe HasProperty before
  Get in sparse reverse/shift/unshift/splice/sort paths. Dense mutations check attributes and the
  indexed prototype chain, and splice/copyWithin recheck after user code can reshape or freeze
  the array. Splice constructs its species with the deletion count after argument coercion and
  sets both lengths even for no arguments. Species works for proxy arrays, and definitions on
  species results report refusal. Copy methods define their own elements rather than invoking
  inherited setters. ArrayMutationTest pins 210 Node controls for rollback, coercion, generic
  receivers, frozen arrays, prototypes, proxies, species and copying; the JVM oracle records the
  two corrected sloppy frozen/read-only push results explicitly.
- D-7: JavaBean accessors become Kotlin properties across the whole port (getString() becomes .string, and `Parser.CurrentPositionReporter` declares properties, not get-methods). Upstream's constructor overload trios collapse into constructors with default arguments. Call sites adapt mechanically at port time.

- D-115: PerformEval creates a fresh declarative scope for every eval's lexical declarations
  (#99). Strict eval uses it as its variable environment too; sloppy eval declares into the
  caller's variable environment after validating every conflict and global descriptor. Eval
  closures retain that scope. Block scopes lowered to NativeWith carry declarative-environment
  metadata, so eval rejects intervening lexical conflicts without treating object `with` bindings
  as declarations. Name lookup and assignment preserve `with` unscopables across eval scopes.
  Direct eval preserves null/undefined this, arguments, new.target, super and private names;
  optional eval is indirect, and only the caller realm's intrinsic eval is direct. Lexical-scope
  metadata does not change shared bytecodes. EvalEnvironmentTest pins 125 fixed Node controls;
  the eval_scoping.js oracle records the corrected strict-var isolation result explicitly.
- D-116: Non-simple parameters initialize sequentially in a declarative parameter environment
  before body declarations exist (#84). Later/self bindings enforce the temporal dead zone for
  reads, writes and typeof. Ordinary functions create unmapped arguments before defaults, while
  arrows retain lexical arguments. Sloppy parameter eval has a separate variable environment,
  so var declarations cannot redeclare formal parameters but can retain other bindings for
  closures. Where parameters contain expressions, body var/function bindings use a child
  environment and copy like-named parameter values as FunctionDeclarationInstantiation requires.
  Generator defaults run before the generator is returned; async initialization errors reject
  the promise. Three appended icodes fetch raw argument values, initialize bindings without
  bypassing temporal-dead-zone checks in user code, and enter the function body once. Generator
  creation captures the stack after parameter calls rather than their last intermediate stack.
  The exact destructuring.js/spread.js fixtures
  and two handwritten parameter sources compare shared declaration metadata instead of asserting
  the upstream parameter lowering; other bytecodes and enclosing scripts still compare normally.
  ParameterEnvironmentTest pins 140 fixed Node controls across defaults, closures, eval, arguments,
  patterns, rest, arrows, methods, constructors, generators and async functions. The parity
  producer now runs default-arg feature tests instead of excluding them.

- D-117: Engine errors retain the realm of the execution context that raises them (#88).
  Interpreter frames keep their realm through calls, unwinding, generators and async resumes;
  built-in calls, optimized apply/call paths and native accessor callbacks temporarily enter
  their own realm and restore the previous one. Bound/proxy target calls enter the target's
  realm, while revoked-proxy and invariant errors use the context performing the operation.
  Derived-constructor return checks run after removing the constructor context, in the caller's
  realm. Promise reaction and thenable jobs capture GetFunctionRealm when queued, use the
  current realm if it throws, and enter that saved realm when invoked. Resolving functions and
  proxy argument arrays created in a job belong to its realm. Catch clauses, host conversion,
  async rejection and Promise reactions share one
  intrinsic error object per EcmaError, preserving its message, stack and throwing realm even
  when global error bindings are replaced. ToObject rejects both undefined representations;
  call receiver conversion still keeps undefined distinct from null. ErrorRealmTest pins 99
  fixed Node/V8 controls, and ErrorRealmHostTest covers native accessors, host reentry and
  retained error identity after unwinding.

- D-118: IdFunctionObject tracks [[Call]] versus [[Construct]] explicitly, restoring that
  invocation kind across reentry and throws (#134). Its constructor callback still receives
  null for compatibility, while an ordinary call can also receive null without being treated
  as construction. NativeDate uses the invocation kind: Date.call/apply/bind/Reflect.apply with
  null return a current-time string and ignore argument coercion; construction creates the
  Date from its arguments. The legacy Call extension also requires a construction invocation.
  DateInvocationTest pins 70 fixed Node/V8 controls for receivers, binding, proxies, newTarget,
  coercion and reentry; IdInvocationTest checks callback compatibility and kind restoration.

## Phases

| Phase | Deliverable | Done when |
|---|---|---|
| P0 | Scaffold + lexer | `TokenStreamTest` green on jvm; iOS and JS targets compile |
| P1 | AST + Parser | DONE. `toSource()`, positions and error parity with upstream on the corpus (jvm oracle) |
| P2 | IR generator | DONE. The corpus lowers to an IR tree identical to upstream's, before and after the transform pass. Code generation moved to P3, see that block |
| P3 | Interpreter + core runtime | eval oracle: identical results vs upstream on the arithmetic/string/object/array/function/closure/control-flow/exception corpus |
| P4 | Symbol, iterators and generators, Map and Set, RegExp with generated Unicode tables, Date over kotlinx-datetime time zones with an injectable clock, typed arrays, Promise, Proxy and Reflect | extended oracle green on the JVM, the slice green on JS and iOS, every deviation ledgered |
| P5 | Real BigInt (own arithmetic, no dependency), radix printing, BigInt typed views, WeakMap and WeakSet over the port's own `WeakRef` | BigInt oracle against `java.math.BigInteger` green, weak semantics tests green |
| P6 | test262 parity harness (both engines per test), a common test262 runner over kotlinx-io for iOS and Node, native tests executed, V8 differential report | zero unexplained parity differences; the common runner green on every target |
| P7 | The Java-shaped leftovers cleaned, `explicitApi()`, the Kotlin facade and host-binding DSL, console, `kitejs-coroutines`, EPUB-shaped demo | facade tests green on every target; coroutine artifact green on JVM, JS and iOS |
| P8 | macOS and Wasm targets, CI, docs site, README and POM to KITE.md, Maven Central, KiteVersions | artifacts resolve from Central on every target; site live; CI green |

Rolling-wave rule: before starting a phase, expand its block below into per-file checkbox tasks sized like P0's (one cluster, one test cycle, one commit).

---

### P0: Scaffold + lexer (this session)

**Files (as landed):**
- Create: whole Gradle scaffold (settings, root build, module build, toml, properties, wrapper, LICENSE, NOTICE, README, PORTING_STATUS, this plan)
- Create: `kitejs/src/commonMain/kotlin/io/github/yuroyami/kitejs/Token.kt` (from `Token.java`, 748 lines)
- Create: `.../kitejs/Kit.kt` (subset of `Kit.java`: `codeBug`, `xDigitToInt`; grows on demand)
- Create: `.../kitejs/Messages.kt` (English keys the lexer uses, D-4)
- Create: `.../kitejs/config/RhinoConfig.kt` (compile-time flag defaults, D-6)
- Create: `.../kitejs/Characters.kt` (java.lang.Character substitution, D-1)
- Create: `.../kitejs/ScriptRuntime.kt` (lexer slice: `isJSLineTerminator`, `isStrWhiteSpaceChar`, `stringToNumber` family, `getMessageById`; grows in P3)
- Create: `.../kitejs/KBigInt.kt` (digit-string stub, D-5)
- Create: `.../kitejs/Context.kt` (language-version surface only; grows in P3)
- Create: `.../kitejs/CompilerEnvirons.kt` (minus initFromContext/ideEnvirons/security fields)
- Create: `.../kitejs/ErrorReporter.kt`, `DefaultErrorReporter.kt` (minus forEval), `RhinoException.kt` + `EvaluatorException.kt` (shells), `ast/IdeErrorReporter.kt`
- Create: `.../kitejs/Parser.kt` (Phase 0 slice: constructors, error/warning plumbing, `CurrentPositionReporter`; parsing methods arrive in P1)
- Create: `.../kitejs/TokenStream.kt` (from `TokenStream.java`, 2559 lines; Reader path and XML methods cut)
- Test: `commonTest/.../TokenTest.kt`, `TokenStreamTest.kt`; `jvmTest/.../TokenParityTest.kt`, `org/mozilla/javascript/KeywordParityTest.kt`, `org/mozilla/javascript/PositionParityTest.kt`

**Interfaces produced (P1 relies on):**
- `object Token` with upstream's exact numeric values (verified against the upstream jar; Icode offsets in P2 depend on them).
- `internal class TokenStream(parser: Parser, sourceString: String, lineno: Int)` exposing `getToken(): Int`, `string`, `number`, `bigInt`, `tokenBeg/tokenEnd/tokenColumn/tokenStartLineno/lineno`, `readRegExp`/`readAndClearRegExpFlags`, `readTemplateLiteral`/`rawString`, `getAndResetCurrentComment`, `getLine(position, linep)`. Surface follows upstream under the D-7 property convention.
- Error reporting goes through the real `Parser` Phase 0 slice (no invented interface): `addError`/`addWarning`/`reportError` plus `compilerEnv`, `inUseStrictDirective`, `calledByCompileFunction`, `currentPos`.

**Steps:**

- [x] Scaffold compiles (`./gradlew help`)
- [x] **Write failing `TokenTest`:** `Token.typeToName(Token.LP)` returns `"LP"`, boundary constants match upstream's documented values
- [x] **Port `Token.kt`, test passes** (plus jvmTest `TokenParityTest`: every constant and the whole `typeToName` range equal the upstream jar)
- [x] **Write `TokenStreamTest` suite.** Representative cases (full suite covers each lexer region):

```kotlin
@Test fun numbers() {
    assertTokens("42 3.14 .5 1e3 0x1F 0o17 0b101",
        NUMBER to 42.0, NUMBER to 3.14, NUMBER to 0.5, NUMBER to 1000.0,
        NUMBER to 31.0, NUMBER to 15.0, NUMBER to 5.0)
}
@Test fun stringsAndEscapes() {
    assertStrings(""" "a\n" 'bA' `t${'$'}{x}u` """, "a\n", "bA") // template parts asserted separately
}
@Test fun keywordsVsNames() { assertTokens("let letx if ifx", LET, NAME, IF, NAME) }
@Test fun regexVsDivision() {
    // after a value, / is DIV; after an operator, / starts a regexp
    assertTokenSequence("a / b", NAME, DIV, NAME)
    assertRegExpAfter("a = /b+/g", flags = "g", body = "b+")
}
@Test fun asiSignals() { /* peekTokenOrEOL sees EOL between lines */ }
@Test fun comments() { /* line + block comments skipped, token positions correct */ }
@Test fun bigIntLiteral() { /* 123n lexes to BIGINT, stub payload "123" */ }
```

- [x] **Port support files** (`Kit.kt`, `Messages.kt`, `config/RhinoConfig.kt`, `Characters.kt`, `ScriptRuntime.kt` lexer slice, `KBigInt.kt`, error/reporter/Context/CompilerEnvirons/Parser slices)
- [x] **Port `TokenStream.kt`**
- [x] **`./gradlew :kitejs:jvmTest` green** (53 tests, including the three upstream-oracle parity tests; `PositionParityTest` compares every token's code, boundaries, column, line and value against the upstream jar over a 22-source corpus)
- [x] **Cross-target check:** iosSimulatorArm64 and JS compile, `jsNodeTest` green, `assembleAndroidMain` builds the AAR
- [x] **Update `PORTING_STATUS.md` (lexer row), commit**

### P1: AST + Parser

**Upstream files:** `Node.java` (1297), `ast/` minus the 9 `Xml*` files (70 files, 8499 lines),
`Parser.java` (5210), `CompilerEnvirons.java` completion. Total port surface: ~15000 upstream lines.

**Why the tasks are grouped the way they are:** the `ast/` package is one connected graph, not
a tree. Starting from `Node`, the transitive dependency closure is 25 classes wide, so those 25
plus `Node.kt` are the smallest set that compiles. The other 45 files then fall into three
dependency waves, each of which compiles once the wave before it has landed. Task boundaries
follow those waves, so every commit leaves the module compiling and jvmTest green.

**Port conventions for this phase (on top of the global rules):**
- D-7 everywhere: `getX()/setX()` become Kotlin properties, `isX()` becomes `val x`. Upstream
  constructor overload chains collapse into one constructor with default arguments.
- `AstNode.toSource(depth)` and `makeIndent` are ported verbatim. They are the P1 oracle, so any
  cosmetic drift there fails the phase.
- Upstream `Node.java` needs `java.math.BigInteger` for `getBigInt`. It takes `KBigInt` (D-5).
- `Node` implements `Iterable<Node>`. Kotlin keeps that, with the same eager-snapshot iterator
  semantics upstream documents.

#### P1.1: Node and the AST core spine

- [x] Port `Node.kt` (1297) plus the 25-class closure, all in one commit because nothing smaller
      compiles: `ast/NodeVisitor.kt`, `AstNode.kt` (623), `Jump.kt`, `Symbol.kt`, `Scope.kt` (240),
      `ScriptNode.kt` (353), `FunctionNode.kt` (568), `AstRoot.kt`, `Name.kt`, `NumberLiteral.kt`,
      `StringLiteral.kt`, `TemplateLiteral.kt`, `TemplateCharacters.kt`, `RegExpLiteral.kt`,
      `Comment.kt`, `Block.kt`, `EmptyExpression.kt`, `ExpressionStatement.kt`, `ReturnStatement.kt`,
      `InfixExpression.kt`, `PropertyGet.kt`, `Loop.kt`, `ForInLoop.kt`, `ArrayComprehension.kt`,
      `ArrayComprehensionLoop.kt`
- [x] `ScriptRuntime.escapeString` added, since `StringLiteral.toSource` needs it
- [x] Tests (`commonTest`): `NodeTest` (child list add/remove/replace, `Iterable`, prop list
      get/set/remove, `getLineno/getColumn`), `AstNodeTest` (absolute/relative position, `getParent`
      chain, `visit` order, `toSource` on the literals ported here), `ScopeTest` (symbol table,
      `getDefiningScope`, `joinScopes`)
- [x] Test (`jvmTest`): `AstCoreOracleTest`, a differential test against the upstream jar. Both
      sides build the same hand-made tree; `toSource`, position math, line-number fallback, child
      list surgery, symbol bookkeeping and `escapeString` must match exactly. Added beyond the
      original P1.1 list because the oracle is available at this level and catches design mistakes
      before they reach the parser.
- [x] `./gradlew :kitejs:jvmTest` green (107 tests, was 53)

#### P1.2: AST wave 1 (leaf nodes)

- [x] Port 29 files (2563 lines), all depending only on the P1.1 core:
      `AbstractObjectProperty`, `Assignment`, `BigIntLiteral`, `BreakStatement`,
      `ComputedPropertyKey`, `ConditionalExpression`, `ContinueStatement`, `DoLoop`, `ElementGet`,
      `EmptyStatement`, `ErrorNode`, `FunctionCall`, `GeneratorExpressionLoop`,
      `GeneratorMethodDefinition`, `IdeErrorReporter` (replaces the P0 stub), `IfStatement`,
      `KeywordLiteral`, `Label`, `ParenthesizedExpression`, `ParseProblem`, `Spread`, `SwitchCase`,
      `TaggedTemplateLiteral`, `ThrowStatement`, `UnaryExpression`, `UpdateExpression`, `WhileLoop`,
      `WithStatement`, `Yield`. `IdeErrorReporter` already landed in P0 unchanged.
- [x] Test (`commonTest`): `AstSourceTest` builds each node by hand and asserts `toSource()` text,
      so the renderers run on every target, not just the JVM
- [x] Test (`jvmTest`): `AstLeafOracleTest`, the differential test for these node types against the
      upstream jar
- [x] jvmTest green (151 tests), `jsNodeTest` green (113 tests), iOS compiles

#### P1.3: AST waves 2 and 3 (containers)

- [x] Port 16 files (2018 lines): wave 2 is `ErrorCollector`, `GeneratorExpression`,
      `LabeledStatement`, `ObjectProperty`, `SpreadObjectProperty`, `SwitchStatement`; wave 3 is
      `ArrayLiteral`, `CatchClause`, `DestructuringForm`, `ForLoop`, `LetNode`, `NewExpression`,
      `ObjectLiteral`, `TryStatement`, `VariableDeclaration`, `VariableInitializer`
- [x] Test (`commonTest`): `AstContainerSourceTest`, the cross-target renderer check
- [x] Test (`jvmTest`): `AstContainerOracleTest`, the differential test against the upstream jar
- [x] jvmTest green (181 tests), `jsNodeTest` green (128 tests), iOS compiles. The `ast` package is
      closed: all 70 non-E4X node types are ported, verified by a file-by-file comparison against
      the upstream directory.

#### P1.4: CompilerEnvirons completion

- [x] Fill the P0 slice: `ideEnvirons()`, the `ErrorCollector` wiring, `activationNames`,
      the accessors `Parser` reads. `initFromContext` still waits for P3 (Context is a shell).
      Cross-checked the 14 `compilerEnv.*` members `Parser.java` actually touches: all present.
- [x] Test: `CompilerEnvironsTest` on the defaults and the ide preset
- [x] jvmTest green (186 tests)

#### P1.5: Parser

- [x] Port `Parser.kt` (5210 upstream lines, 4988 Kotlin). One commit because a half-ported parser
      does not compile. Cut at port time: the `Reader` overloads (D-2) and the E4X methods
      (`xmlInitializer`, `attributeAccess`, `xmlElemRef`, the `XmlPropRef` branch of `propertyName`
      and the `DOTQUERY` branch of `memberExprTail`), which now report "XML not available" (D-16).
      `getPropKey` waits for P2: it needs `ScriptRuntime.getIndexObject`, and only IRFactory calls it.
- [x] `Messages.kt` grows the 100 parser keys, copied from upstream's properties file. The
      `MessageFormat` shim gained real single-quote handling, so `'{'` renders as a bare brace.
- [x] Test: `ParserSmokeTest` in commonTest, covering declarations, precedence, functions, control
      flow, literals, ES6 syntax, try/catch, switch, labels, regexp-vs-division, comment recording,
      error reporting, IDE-mode error collection, single-use enforcement, scopes and source bounds
- [x] Test: `jvmTest/MessageParityTest`, which checks every ported message key against the upstream
      bundle with zero, one and two arguments
- [x] jvmTest green (204 tests), `jsNodeTest` green (149 tests), iOS compiles

#### P1.6: Oracle parity and corpus

- [x] Built `kitejs/src/jvmTest/resources/corpus/*.js`, 30 files, one construct family each:
      literals, operators, precedence, assignments, update expressions, functions, closures, arrow
      functions, control flow, `for-in`/`for-of`, labels, `switch`, `try/catch/finally`, objects,
      arrays, member access, optional chaining, template literals, destructuring, spread and rest,
      generators, `let`/`const`, comments in odd places, ASI edge cases, plain statements, strict
      mode, unicode identifiers, nested scopes, getters and setters, computed keys
- [x] Test `jvmTest/ParserOracleTest`. For every corpus file the port and the upstream jar must
      agree on: the rendered source at ES6 and at VERSION_DEFAULT, the accept-or-reject decision,
      the recorded comment list, and every node's type, position, length and line number
- [x] Test `jvmTest/ParserErrorParityTest`. 60 malformed sources produce the same problems in the
      same order with the same text, offset and length, both in IDE mode through `ErrorCollector`
      and in throwing mode. Strict-mode warnings are compared too
- [x] Cross-target check: iosSimulatorArm64 and iosArm64 compile, `jsNodeTest` green (149 tests)
- [x] Updated `PORTING_STATUS.md`, committed

**Done when:** corpus parity green on both language versions, error parity green, all targets
compile. **All green: 214 jvmTest, 149 jsNodeTest.**

Two corpus notes worth keeping. Rhino 1.9.1 accepts spread in array literals, object literals and
rest parameters, but rejects it in call arguments (`f(...a)`), in `new` arguments and in an arrow
function's parameter list. Those forms are out of the corpus because upstream cannot parse them,
not because the port cannot. And many corpus files are invalid at VERSION_DEFAULT; the oracle
turns that into a test of its own by requiring both parsers to reject them.

### P2: IR generator

**Upstream files:** `IRFactory.java` (2644), `NodeTransformer.java` (567), `Icode.java` (378),
`DecompilerFlag.java` (17), and the number-formatting trio `dtoa/MathUtils.java` (791),
`dtoa/Decimal.java` (305), `dtoa/DoubleFormatter.java` (253).

**Scope correction, made when P2 was expanded.** The original block listed `CodeGenerator` and
`InterpreterData` here. They cannot land in this phase. In Rhino 1.9.1 the code generator is
`CodeGenerator<T extends ScriptOrFn<T>> extends Icode` and it returns a `JSDescriptor<T>`, so it is
generic over a descriptor layer (`JSDescriptor`, `JSCode`, `JSFunction`, `ScriptOrFn`) whose root
type is `JSFunction extends BaseFunction`. That is the P3 object model. It also reads
`Interpreter.EXCEPTION_*` constants and calls `ScriptRuntime.checkRegExpProxy`. Porting it now would
mean stubbing the runtime and rewriting it in P3, so the code generator, `InterpreterData`,
`CodeGenUtils` and the descriptor layer move to the front of P3, next to `Interpreter` itself.
`ConstProperties` moves with them: it is a runtime interface over `Scriptable`, not an IR concern.

What stays in P2 is the half that is genuinely independent: lowering the AST to the `Node` IR.

**Runtime surface this phase adds.** `IRFactory` and `NodeTransformer` need exactly six things that
do not exist yet: `ScriptRuntime.emptyArgs`, `isSpecialProperty`, `toInt32`, `indexFromString`,
`getIndexObject` and `numberToString`, plus `NativeObject`'s two magic property names and
`Context.reportError`. Everything else they touch is already ported.

**Oracle:** upstream `IRFactory` is public, with a public constructor and a public `transformTree`,
so the differential test runs the same corpus through both and compares the resulting IR trees. The
comparison does not use `Node.toStringTree`, which is gated behind upstream's `rhino.printTrees`
system property. jvmTest walks both trees through the public API instead (type, string and number
values, property list, line and column, children) and compares the dumps, which is both reachable
and more precise about what is being asserted.

#### P2.1: Number formatting

- [x] Port `dtoa/MathUtils.kt` (791), `dtoa/Decimal.kt` (305) and `dtoa/DoubleFormatter.kt` (253).
      All three are pure arithmetic with no imports at all, so they cross to common Kotlin untouched.
- [x] Grow `ScriptRuntime` with `numberToString(d, base)` for base 10 and `toString(d)`.
      `DToA.JS_dtobasestr` handles the other radixes and needs `BigInteger`, so it waits for P5 with
      `KBigInt`; `dtoa/DecimalFormatter` needs `BigDecimal` and is only used by `NativeNumber`, so it
      waits for its own phase.
- [x] This retires ledger entry D-14: number-to-string no longer goes through `Double.toString`, so
      it stops varying between the JVM, JS and native.
- [x] Test `jvmTest/NumberFormatOracleTest`: compare against upstream `ScriptRuntime.numberToString`
      over the boundary values (zero, negative zero, NaN, both infinities, the subnormal edge, the
      largest and smallest finite doubles, the integer-exact range, the exponent-notation switch
      points) plus a large deterministic random sample of bit patterns
- [x] Test `commonTest/NumberFormatTest`: the same boundary values, so the formatter is exercised on
      every target
- [x] jvmTest green (229 tests)

#### P2.2: Icode constants and the runtime helper slice

- [x] Port `Icode.kt` (378) and `DecompilerFlag.kt` (17). `Icode` is a plain constant table with a
      name lookup and no dependencies; its values sit below `Token.EOF`, which is why P0 pinned the
      token numbering.
- [x] Grow `ScriptRuntime` with `emptyArgs`, `isSpecialProperty`, `toInt32`, `indexFromString` and
      `getIndexObject`. This also unblocks `Parser.getPropKey`, which P1 deferred for exactly this
      reason, so port it now and drop the deferral note.
- [x] Create `NativeObject.kt` holding only `PROTO_PROPERTY` and `PARENT_PROPERTY`; the class grows
      into the real object in P3, the same way `ScriptRuntime` and `Context` have been growing.
- [x] Grow `Context` with the static `reportError` pair. The no-position overload cannot recover a
      source position from the interpreter stack yet, so it reports an unknown position until P3
      (ledger entry).
- [x] Test `jvmTest/IcodeParityTest`: every Icode constant and the whole `bytecodeName` range equal
      the upstream jar, the same way `TokenParityTest` pins the token numbering
- [x] Test `jvmTest/RuntimeHelperParityTest`: `toInt32`, `indexFromString` and `getIndexObject`
      compared against upstream over a wide value sample, plus 150000 random doubles for `toInt32`
- [x] `toInt32` needs `v8dtoa/DoubleConversion` (89 lines, BSD, from V8), so that landed too
- [x] jvmTest green (237 tests)

#### P2.3: IRFactory

- [x] Port `IRFactory.kt` (2644), including its nested `AstNodePosition` helper. One commit: the
      transform methods form one recursive cluster and a half-ported file does not compile.
      Cut at port time: the eight `Xml*` transform methods, which follow D-16 and report
      "XML not available".
- [x] Test `commonTest/IRFactorySmokeTest`: a handful of scripts lowered to IR with the expected
      node types, so the transform runs on every target
- [x] Test `jvmTest/IRFactoryOracleTest` landed here rather than in P2.5, because it is what proves
      the port: the whole corpus lowers to an IR tree identical to upstream's at both language
      versions, and the function tables match too
- [x] jvmTest green (256 tests), `jsNodeTest` green, iOS compiles

Bug the oracle caught: upstream's `Parser.destructuringObject` guards on `ts != null` and leaves the
position at zero when the token stream was never started, which is exactly the case when IRFactory
drives destructuring through a parser it made but never ran. The port called `lineNumber()`
unconditionally and produced -1. `lateinit` had hidden the guard; `this::ts.isInitialized` restores it.

#### P2.4: NodeTransformer

- [x] Port `NodeTransformer.kt` (567). It is independent of `IRFactory` despite the comment that
      mentions it, and it needs only `Kit.codeBug`, `ScriptRuntime.getIndexObject` and
      `Context.reportError`.
- [x] Test `commonTest/NodeTransformerSmokeTest`: break and continue become gotos, declarations
      become stores, local names resolve to variable slots unless an activation is needed, strict
      mode rewrites stores, a return inside try jsrs to the finally, `typeof o.p` stops warning,
      generator resumption points are recorded, and a let block becomes a `with` under activation
- [x] Test `jvmTest/NodeTransformerOracleTest`: the whole corpus, transformed on both sides and
      compared node by node, at both language versions and again in strict mode. The script tree and
      every nested function are compared, since the transform runs per function and the flattening
      decision differs between them
- [x] jvmTest green (271 tests)

#### P2.5: Close out

The two oracle tests landed with the code they check, in P2.3 and P2.4, which is where a failure is
easiest to act on. What is left here is the shared plumbing and the cross-target pass.

- [x] `jvmTest/IrDump`: the shared tree renderer both oracles use. It walks the public API rather
      than `Node.toStringTree`, which upstream gates behind the `rhino.printTrees` system property
      read once at class-init time
- [x] Cross-target check: iosSimulatorArm64 and iosArm64 compile, `jsNodeTest` green (185 tests)
- [x] Update `PORTING_STATUS.md`, commit

**Done when:** the whole corpus lowers to IR and survives the transform pass with a tree identical
to upstream's, on both language versions, and every target compiles. **All green: 271 jvmTest,
185 jsNodeTest.**

### P3: Interpreter + core runtime

**Size.** Roughly 35000 upstream lines, the largest phase by a wide margin. The clusters below are
ordered so each one compiles on top of the last.

**The structural fact that shapes this phase.** P1 and P2 could each be checked end to end as soon
as they landed, because the parser and the IR generator are self-contained. Nothing evaluates until
almost all of P3 is in place: the interpreter needs the object model, the object model needs the
runtime conversions, and the code generator needs the descriptor layer. So the eval oracle only
becomes possible at P3.9. Until then every cluster is checked the strongest way it can be:

- Pure functions are compared against upstream directly (P3.1 does this for the whole conversion
  surface, and it is a large surface).
- Data structures are exercised through their own API and compared against the upstream class
  (the slot maps, the property machinery).
- Everything else gets structural tests, and the eval oracle at the end is what really proves it.

That is worth stating plainly: between P3.2 and P3.8 the test suite gets weaker per line of code
than it was in P1 and P2, and P3.9 is where the guarantee comes back.

#### P3.1: Values and the conversion surface

- [x] Port `Undefined.kt` (151), `UniqueTag.kt` (73), `ConsString.kt` (107). Self-contained value
      types with no dependency on the object model.
- [x] Grow `ScriptRuntime` with the conversion surface that needs no Scriptable: `toNumber(String)`
      and its string-scanning helpers, `toInteger(double)`, `toInt32(double)`, `toUint32(double)`,
      `toIndex`, plus the numeric predicates. 54 of upstream's static methods take only primitives
      and strings, and they are the ones ported here.
- [x] `DToA.JS_dtobasestr` stays out: it needs arbitrary-precision integers and waits for P5 with
      `KBigInt`, so `numberToString` keeps handling radix 10 only.
- [x] Test `jvmTest/ConversionOracleTest`: every ported conversion compared against upstream over a
      wide sample, including the string-to-number edge cases (whitespace, signs, hex, octal, binary,
      infinity, empty, trailing junk) and the full double range for the integer conversions
- [x] Test `commonTest/ConversionTest`: the same edge cases on every target
- [x] jvmTest green

#### P3.2: Contracts

- [x] Port the interfaces the rest of the runtime is written against: `Scriptable.kt` (292),
      `SymbolScriptable.kt`, `Callable.kt`, `Constructable.kt`, `Function.kt`, `Evaluator.kt`,
      `Script.kt`, `RefCallable.kt`, `Ref.kt`, `ConstProperties.kt` (moved here from P2),
      `Symbol.kt`, `Wrapper.kt`.
- [x] `IdFunctionCall.kt` moves to P3.4. Its one method takes an `IdFunctionObject`, which is a
      P3.4 class, so it cannot compile here.
- [x] These are declarations, so the check is that they compile and that their member sets match
      upstream. Test `jvmTest/ContractParityTest` compares each interface by reflection: method
      names, arity, parameter shapes, return shapes and the interfaces it extends. Two members are
      known-absent and listed with a reason (D-21); a second test fails if either entry goes stale.
- [x] jvmTest green

#### P3.3: The object model

**Why P3.3 and P3.4 are now one cluster.** The plan used to split the property machinery from the
function objects. That split does not compile. `ScriptableObject` names `BaseFunction`, `TopLevel`,
`LambdaFunction` and `LambdaConstructor` in its public static helpers, and `TopLevel` names
`BaseFunction` back, so the object base and the function base are one strongly connected group.
Kotlin cannot add methods to a class in a later file, so the group has to land together. This is the
same kind of scope error the plan already hit with `CodeGenerator` in P2, found the same way: by
listing what each file actually names before writing any of it.

The reflection half of `ScriptableObject` (`defineClass`, the annotation scan, `MemberBox`,
`FunctionObject`) is LiveConnect and never gets ported, so a large part of the file drops out with
it. `Delegator` goes the same way.

Three commits, each of which compiles on its own.

- [x] Commit 1, the standalone support types: `SymbolKey.kt` (83), `ExternalArrayData.kt` (28),
      `Initializable.kt` (12), `SerializableCallable.kt` (11), `SerializableConstructable.kt` (9).
      `JavaScriptException` and `LazilyLoadedCtor` were meant to be here too, but the first needs
      `RhinoException.recordErrorOrigin` and a `NativeError`, and the second calls
      `ScriptableObject.addLazilyInitializedValue`, so both move to commit 2.
- [x] Commit 2, the core: `Slot.kt` (145), `AccessorSlot.kt` (289), `BuiltInSlot.kt` (201),
      `LambdaSlot.kt` (74), `LambdaAccessorSlot.kt` (167), `LazyLoadSlot.kt` (38), `SlotMap.kt` (94),
      `CompoundOperationMap.kt` (103), `EmbeddedSlotMap.kt` (318), `HashSlotMap.kt` (100),
      `SlotMapOwner.kt` (375), `ScriptableObject.kt` (3345), `BaseFunction.kt` (828),
      `LambdaFunction.kt` (124), `KnownBuiltInFunction.kt` (44), `LambdaConstructor.kt` (473),
      `TopLevel.kt` (266), `JavaScriptException.kt` (118), `LazilyLoadedCtor.kt` (174). The
      thread-safe slot maps, the lock-aware map and the thread-safe compound operation map are all
      dropped under D-3.
- [x] Commit 3, the id-function machinery: `IdFunctionObject.kt` (133), `IdFunctionCall.kt` (17),
      `IdScriptableObject.kt` (1019), `BoundFunction.kt` (113). `NativeFunction.kt` is not ported,
      under D-26.
- [x] `Arguments.kt` and `NativeCall.kt` moved to P3.6 and landed there. Both hold a `JSFunction`,
      which is part of the descriptor layer.
- [x] Test `jvmTest/ScriptableObjectOracleTest`: define, redefine, delete, enumerate and seal
      properties on both sides and compare the observable results, including attributes, symbol
      keys, constants, descriptors and prototype chain lookup. This also drives the slot maps,
      which is why the separate `SlotMapOracleTest` in the old plan is gone: the maps are not
      reachable from outside `ScriptableObject`, and their public behaviour is exactly what this
      test compares. A 20000 step random sequence and a 2500 property object cross every point
      where the map changes shape.
- [x] The map's change of shape is not visible through that comparison, since every shape keeps
      insertion order. A separate white-box test asserts the shape directly, so a regression in the
      promotion rule is still caught. This was found by a negative control: breaking the promotion
      threshold did not fail any comparison test.
- [x] Test `commonTest/ObjectModelTest`: the same behaviour on every target, including a 20000 step
      random sequence checked against an ordinary `LinkedHashMap`.
- [x] jvmTest green

#### P3.4: The id-function machinery and the forward references from P3.3

- [x] `IdFunctionObject.kt`, `IdFunctionCall.kt`, `IdScriptableObject.kt`, `BoundFunction.kt`.
- [x] `NativeFunction` is not ported. Nothing in scope extends it: at this pin `JSFunction` extends
      `BaseFunction` directly, and `NativeFunction` only exists for the bytecode compiler's generated
      classes (D-26).
- [x] `EqualObjectGraphs` is not ported, so the `equals` overrides on `BoundFunction` and
      `IdFunctionObject` that used it are gone (D-27).
- [x] `BaseFunction.init`, `initAsGeneratorFunction`, `apply`, `call`, `bind`, `toString`,
      `toSource` and the live `arguments` object are filled in.
- [x] Test `jvmTest/IdScriptableObjectOracleTest`: the same small class, written against each
      side's `IdScriptableObject`, driven through instance ids, prototype ids, attributes, deletion,
      method calls and constructor export, compared step by step.
- [x] Every site left waiting on a later phase is filled. `NativeSymbol` in `SymbolKey.equals`,
      `ScriptRuntime.isSymbol` and `IdScriptableObject` landed in P4.1; `NativeError` in
      `JavaScriptException` and the external-array `put` in P3.8; the generator prototype in
      `BaseFunction.setupDefaultPrototype` and `initAsGeneratorFunction` in P4.2. No `TODO(P...)`
      marker is left in the source.

#### P3.5: Context

- [x] `Context.kt` replaces the shell, with `ContextFactory.kt`, `ContextAction.kt`,
      `WrappedException.kt`, `RegExpProxy.kt` and `ScriptStackElement.kt`. Cut: class shutters,
      wrap factories, security controllers, class loaders, the debugger, property-change listeners,
      locale and the E4X hooks (D-28). The time zone waits for the `Date` decision in P4.
- [x] `Context.enter`/`exit` use a plain singleton slot rather than a `ThreadLocal`, under D-3.
- [x] `reportError` and `reportRuntimeError` route through the context's reporter again. The
      source-position fallback that walked the Java stack is gone for good (D-18 stays for that
      half); the interpreter half becomes real in P3.7.
- [x] `ScriptRuntime` grows the top-call machinery: `doTopCall`, `hasTopCall`, `getTopCallScope`,
      `typeErrorThrower`, `applyOrCall`, `toObject`, `toObjectOrNull`, `newObject`,
      `getArrayElements` and `call`.
- [x] The descriptor layer moved up from P3.6, because `Context` and `BaseFunction` name it:
      `ScriptOrFn.kt`, `JSCode.kt` (with `JSCodeExec` and `JSCodeResume`), `JSScript.kt`,
      `JSFunction.kt`, `JSDescriptor.kt`, `NativeCall.kt`, `Arguments.kt`, `InterpreterData.kt`,
      `CodeGenUtils.kt`. The debugger's `DebuggableScript` interface is not ported; `JSDescriptor`
      has the same members as plain methods (D-28).
- [x] `Interpreter.kt` exists as a shell implementing `Evaluator`: the create methods and the stack
      capture are real, `compile` waits for P3.6 and `interpret` for P3.7. Both are `TODO()` calls,
      not silent stubs.
- [x] Test `commonTest/ContextTest`: enter and exit counting, `call`, feature flags by version,
      sealing, factory listeners, microtask ordering, and error routing through the reporter.
- [x] jvmTest green

#### P3.6: Code generation

Moved here from P2, because `CodeGenerator` is generic over `ScriptOrFn<T>` and returns a
`JSDescriptor<T>` whose root type `JSFunction` extends `BaseFunction`, and because it reads
`Interpreter`'s exception-table constants directly.

- [x] The descriptor layer and `InterpreterData` landed in P3.5, since `Context` needs them.
- [x] Port `CodeGenerator.kt` (1971).
- [x] Test `jvmTest/IcodeOracleTest`: generate icode for the whole corpus on both sides and compare
      the byte arrays, the string and number pools, the exception tables, the frame sizes, the
      literal ids and the nested-function tables, recursively. This is the phase 2 promise finally
      kept, and it is a strong check: the icode array is exactly what the interpreter executes. A
      second test does the same over 57 short hand-written sources that hit the paths a corpus of
      whole programs skips past. Both passed on the first run; a negative control (emitting ADD for
      string concatenation) fails both.
- [x] Upstream's `CodeGenerator` and `InterpreterData` are package-private, so the test reads the
      upstream result through the public `Script.getDescriptor()` and reflection on the fields.
- [x] jvmTest green

#### P3.7: Interpreter

- [x] Port `Interpreter.kt` (5106): the call-frame machine, the generator resumption path and the
      exception unwinding. With it: `NativeWith.kt`, `SpecialRef.kt`, `NewLiteralStorage.kt`,
      `IteratorLikeIterable.kt`, the iterator and generator name shells, `setIntegrityLevel`, and
      the ~90 `ScriptRuntime` functions the interpreter calls (property access, names and scopes,
      arithmetic, equality, comparison, enumeration, activations, catch scopes and literals).
- [x] Upstream dispatches through a table of one object per opcode; this port uses a `when` over
      the same opcodes, which is what older Rhino did (D-30). Continuations are not ported (D-31).
- [x] Test `jvmTest/EvalOracleTest`: scripts run on both engines and the last expression's value,
      or the thrown error's name and message, has to match. The first batch of 220 scripts covers
      arithmetic, coercion, strings, templates, functions, closures, control flow, objects,
      prototypes, `new`, `with`, `try`/`finally`, getters and setters, optional chaining and the
      engine's own error messages. It passed on the first run except for eight scripts that need
      the standard objects, which join the P3.8 batch.
- [x] Test `commonTest/EvalSmokeTest`: 60 of the same scripts with fixed expectations, run on every
      target.
- [x] jvmTest green

#### P3.8: Natives wave 1

Part 1, the objects everything else hangs off:

- [x] `dtoa/DecimalFormatter.kt` gets a hand-written exact decimal path instead of `BigDecimal`
      (D-33), checked against upstream by `jvmTest/DecimalFormatterOracleTest`.
- [x] `NativeError.kt` (459) with `NativeCallSite.kt`; `JavaScriptException` fills in its
      `NativeError` branch; `RhinoException` now captures the script stack (D-35), so `e.stack`
      renders the same frames as upstream.
- [x] `NativeObject.kt` (1085) gets its constructor and every `Object.*` and
      `Object.prototype.*` method (D-34 drops the `java.util.Map` view). `NativeGlobal.kt` (761)
      replaces the shell: `eval` (recognised by type), `parseInt`, `parseFloat`, `isNaN`,
      `isFinite`, `escape`, `unescape`, the URI functions, `uneval`, `NaN`, `Infinity`, `undefined`,
      `globalThis` and the seven error constructors. `NativeBoolean.kt`, `NativeNumber.kt`,
      `NativeMath.kt`, `NativeScript.kt`, `ScriptRuntimeES6.kt`, the rest of
      `AbstractEcmaObjectOperations.kt`, the `ES6Iterator` base and `NativeStringIterator`.
- [x] `ScriptRuntime`: `initSafeStandardObjects` registers everything in upstream's order, with the
      objects still to come marked `TODO(P3.8)` and `TODO(P4)` at their slots; `toObject` wraps
      numbers and booleans; `same`, `uneval`, `defaultObjectToSource`, `loadFromIterable`,
      `toInteger`, `toLength`, `toIntegerOrInfinity`, `getTopLevelProp` and the `(args, index)`
      overloads.
- [x] `EvalOracleTest` now runs both engines with `initStandardObjects()`. Seven new groups (about
      420 scripts in all) cover Object, Function, Error, the globals, Boolean, Number, Math and
      Script, plus 60 more engine-error scripts. `EvalSmokeTest` runs a slice on every target.
- [x] `Number.prototype.toString(radix)` for a radix other than 10 still throws until the phase 5
      BigInt lands (`DToA.JS_dtobasestr` is bignum arithmetic).

Part 2, the collections and text:

- [x] `NativeArray.kt` (2573) with `ArrayLikeAbstractOperations.kt` (455) and
      `NativeArrayIterator.kt` (D-36 drops the `java.util.List` view); `Context.newArray`,
      `ScriptRuntime.isArrayLike`, `getArrayElements`, `sameZero` and the dense-storage hook in
      `__defineGetter__` are filled in. About 300 array scripts match upstream on the first run,
      including the dense/sparse switch, `length` truncation, species, subclassing, holes,
      destructuring and `for...of`. The scripts that look at `Symbol.iterator`,
      `Symbol.unscopables` and `Symbol.species` wait for phase 4.
- [x] `NativeString.kt` (1495) with `AbstractEcmaStringOperations.kt` (330); `toObject`,
      `toCharSequence` and `NativeArray.getLengthProperty` know strings. `localeCompare` and
      `normalize` lose their Java library support (D-37, D-38). The regexp-driven methods compile
      but go through the `RegExpProxy`, which phase 4 installs.
- [x] `NativeJSON.kt` (601) with `json/JsonParser.kt` (414), registered lazily like `Math`; the
      LiveConnect branches (Java maps, collections, arrays) are gone.
- [x] About 190 string scripts and 130 JSON scripts match upstream on the first run.
- [x] jvmTest green

#### P3.9: Eval oracle

- [x] `kitejs/src/jvmTest/resources/eval/*.js`: 47 whole programs, each ending in the expression
      that is its result. Closures, recursion, sorting, string processing, prototypes, destructuring,
      default and rest parameters, template literals and tagged templates, control flow, exceptions,
      accessors and property attributes, coercion tables, number formatting and parsing, JSON round
      trips, `this` binding, hoisting and scope, modules by IIFE, enumeration order, Unicode strings,
      array holes, higher-order functions, linked lists, trees, stacks and queues, matrices, primes,
      `arguments`, deep equality, a pretty printer, engine error messages, sort stability, eval
      scoping, optional chaining, exponent and bitwise corners, `Object.assign` and spread,
      array-likes, wrapper objects, a tokenizer, string building, logical assignment and optional
      catch bindings. Rhino 1.9.1 has no class syntax, so the corpus uses constructor functions.
- [x] `EvalOracleTest.corpusMatchesUpstream` runs every file on both engines and compares the
      rendered result or the thrown error. Passed on the first run. With the one-liner groups the
      oracle now covers about 1900 scripts.
- [x] `commonTest/EvalCorpusSlice.kt`: twenty of the programs with the answer upstream gives,
      generated from the corpus; `commonTest/EvalCorpusTest` runs them on every target, and
      `jvmTest/EvalCorpusSliceOracleTest` fails if a recorded answer stops being upstream's or a
      program stops matching its file.
- [x] Cross-target check, `PORTING_STATUS.md` updated, committed.

**Done when:** the eval corpus produces identical results and identical errors on both engines, on
the JVM, and the smoke slice passes on every other target.

### Rules for the rest of the port: Kotlin first, multiplatform first

P0 to P3 proved the method: port file by file, keep upstream's names, check every cluster
against the upstream jar, run a slice on every target. The remaining phases keep that method
and add the rules below. They decide every "how" question that comes up from here on.

1. **One engine, every platform.** Regular expressions, dates, number printing and Unicode
   classification are computed by KiteJS's own code. Nothing is delegated to the platform's
   regex engine, `java.time`, `Foundation` or the host's `RegExp`. The platforms disagree with
   each other and with Rhino; the JVM oracle and the cross-target slice only mean something when
   the same code produces the answer on JVM, JS, iOS and Wasm. `DecimalFormatter` (D-33) and
   `JavaNumbers` (D-39) are the pattern.
2. **Platform seams are properties on `Context`, with sensible defaults.** The time zone is a
   kotlinx-datetime `TimeZone` (default `currentSystemDefault()`), the clock a stdlib
   `Clock.System`, the console printer `println`, the random source the stdlib one. A test
   injects a fixed clock and `TimeZone.of("UTC")` or a fixed offset; an embedder injects
   whatever zone it wants. Nothing in the engine reads the platform directly.
3. **Unicode data comes from the Unicode Character Database, generated into Kotlin.** A Gradle
   task kept in the repo (`build-logic`) reads the UCD files and writes range tables into
   `commonMain`. The Unicode version is pinned to the one JDK 21 ships (15.0), so the JVM
   oracle stays exact and JS, Native and Wasm agree with it. `Char.category` and
   `uppercaseChar()` differ per platform; the tables do not.
4. **The core artifact depends on the Kotlin stdlib and kotlinx-datetime (time zones, from
   P4.5). Nothing else.** Every candidate library was weighed:
   a regex library or the platform's own regex breaks parity, no multiplatform library carries
   the Unicode data the regex engine needs, JSON is already ported and oracle-checked, and
   arbitrary-precision integers are written here with an exhaustive oracle rather than taken
   from a library whose rounding and bitwise corners would need checking anyway. Coroutines
   ship as the separate `kitejs-coroutines` artifact so a consumer who does not want them
   never sees them. kotlinx-io is a test-scope dependency only (P6).
5. **Three checks per cluster, no exceptions.** The JVM oracle against the upstream jar, the
   cross-target slice with recorded upstream answers (JS on Node and the iOS simulator now,
   macOS and Wasm from P8), and a negative control that proves the test is live. From P6 on,
   test262 parity is the fourth.
6. **Kotlin idioms at the surface, upstream shapes inside.** Engine internals keep Rhino's
   class and method names: that is what makes a port task checkable against its source file.
   The Kotlin shows in properties instead of accessors (D-7), explicit backing fields,
   `Enum.entries`, `data object`, sealed hierarchies, value classes and context parameters at
   the public API, and `explicitApi()` on the module. The embedding facade in P7 is where the
   library feels like Kotlin.
7. **Single-thread confinement stays, and becomes explicit.** One engine per thread (D-3).
   The coroutine artifact provides a dedicated single-thread dispatcher; the core never
   synchronises anything.
8. **Every phase close-out updates `PORTING_STATUS.md` and the README status paragraph.** The
   README claims only what the committed code does (KITE.md rule 5). The README is stale today
   ("scaffold and lexer") and gets its first truthful rewrite at the P4 close-out, its full
   rewrite in P8.

What is left, by size. Everything else in the upstream tree is LiveConnect, security,
thread-safe maps, continuations, E4X or the bytecode compiler, all excluded by the ledger.

| Cluster | Upstream lines | Phase |
|---|---|---|
| `regexp/` (9 files) | 6396 | P4 |
| `typedarrays/` (19 files, two of them BigInt views) | 4002 | P4, P5 |
| `NativeDate` | 2038 | P4 |
| `NativeProxy`, `NativeReflect` | 1770 | P4 |
| `NativePromise`, `UnhandledRejectionTracker` | 1073 | P4 |
| `Hashtable`, `NativeMap`, `NativeSet`, `NativeCollectionIterator` | 1443 | P4 |
| `NativeSymbol`, `ES6Generator`, `NativeGenerator`, `NativeIterator` | 1134 | P4 |
| `NativeWeakMap`, `NativeWeakSet` | 281 | P5 |
| `NativeBigInt`, the BigInt half of `DToA`, `KBigInt` arithmetic | ~700 | P5 |
| `NativeConsole` | 386 | P7 |
| `Delegator`, `ImplementationVersion` | 349 | P7 |

### P4: Language completeness wave

Nine clusters, in dependency order. Each is one commit, checked the three ways above. The
`ScriptRuntime.initSafeStandardObjects` registration order is upstream's; every cluster fills
its own `TODO(P4)` slot there.

#### P4.1: Symbol

- [x] Port `NativeSymbol.kt` (233): the constructor, `Symbol.for` and `Symbol.keyFor`, the
      well-known symbols as constructor properties, `description`, `toString`, `valueOf`,
      `Symbol.prototype[Symbol.toPrimitive]`, and the `typeof` answer. The global registry is a
      plain `HashMap<String, SymbolKey>`; upstream's `WeakHashMap` on interned strings never
      collected anything anyway (ledger entry when landed).
- [x] Fill the marked sites: `SymbolKey.equals` and `hashCode`, the two `IdScriptableObject`
      lookups, `ScriptRuntime.isSymbol`, `ScriptRuntime.toObject` (a `SymbolKey` wraps into a
      `NativeSymbol`), `ScriptRuntime.typeOf`.
- [x] `TopLevel.Builtins.Symbol` cached; `Symbol.iterator`, `Symbol.species`,
      `Symbol.unscopables`, `Symbol.toStringTag` and `Symbol.hasInstance` become reachable from
      script. The array and string one-liners removed in P3.8 for that reason return to the
      oracle.
- [x] Oracle: symbol identity, registry round trips, symbols as property keys (define, get,
      delete, `getOwnPropertySymbols`, `JSON.stringify` skipping them), coercion errors
      (`+Symbol()`, template literal, `Symbol() == Symbol()`), the well-known symbols on the
      objects that own them, `Object.prototype.toString` reading `Symbol.toStringTag`.
- [x] jvmTest, jsNodeTest, iOS compile green; commit.

#### P4.2: Iterators and generators

- [x] Port `NativeIterator.kt` (245) as the real class: the legacy `__iterator__` protocol,
      `StopIteration`, `Iterator()` constructor, `NativeIterator.init`. The shell object from
      P3.7 goes away.
- [x] Port `NativeGenerator.kt` (235): the pre-ES6 generator object (`send`, `next`, `throw`,
      `close`, `__iterator__`), `GeneratorClosedException` stays where P3.7 put it.
- [x] Port `ES6Generator.kt` (421): `next`, `return`, `throw`, the `yield*` delegation with
      `YieldStarResult` from P3.7, the state machine and the prototype wiring through
      `BaseFunction.initAsGeneratorFunction`.
- [x] Fill `Interpreter.generatorCreate`: an `ES6Generator` at `VERSION_ES6` and above, a
      `NativeGenerator` below, exactly as upstream chooses.
- [x] Oracle: generator functions with `yield`, `yield*` over arrays, strings and other
      generators, early `return` and `throw` into a suspended generator, `finally` on close,
      `for...of` and spread over generators, destructuring from a generator, infinite generators
      taken with a counter, generator methods in object literals, arguments and `this` inside
      generators, `Symbol.iterator` returning `this`, custom iterables with `next` and `return`,
      iterator closing on `break`, and the legacy protocol at `VERSION_1_8`.
- [x] Corpus programs: a lazy pipeline (map, filter, take over a generator), a tree walker that
      yields in order, a fibonacci generator.
- [x] Green on all targets; commit.

#### P4.3: Map and Set

- [x] Port `Hashtable.kt` (327): upstream's insertion-ordered table with the linked entry list
      that keeps iteration correct while entries are added or deleted mid-loop. Kotlin's
      `LinkedHashMap` cannot do that, so the class is ported, not replaced. Key normalisation:
      `-0` becomes `+0`, `NaN` equals `NaN`, a `KBigInt` key compares by value (the BigInt
      arithmetic arrives in P5; the hook is written now).
- [x] Port `NativeMap.kt` (278), `NativeSet.kt` (762, includes the set algebra methods:
      `union`, `intersection`, `difference`, `symmetricDifference`, `isSubsetOf`,
      `isSupersetOf`, `isDisjointFrom`) and `NativeCollectionIterator.kt` (76) for `keys`,
      `values`, `entries` of both. `Map.groupBy` lands with them (the abstract operation from
      P3.8 already takes `KEY_COERCION.COLLECTION`).
- [x] `ScriptRuntime.loadFromIterable` (P3.8) feeds the constructors; `Symbol.species` on both
      constructors through `ScriptRuntimeES6.addSymbolSpecies`.
- [x] Oracle: insertion order, `size`, key coercion corners (`-0`, `NaN`, objects by identity,
      strings vs numbers), deleting and adding during `forEach` and `for...of`, `Map` from an
      iterable of pairs and the error on a bad entry, `Set` from a string, chaining `set`, the
      set algebra against arrays and against set-like objects with `size`, `has` and `keys`,
      `Object.prototype.toString` tags, `JSON.stringify(new Map())`.
- [x] Green on all targets; commit.

#### P4.4: Regular expressions

The largest cluster. Rhino's regex engine is a bytecode compiler plus a backtracking matcher
written in plain Java with no `java.util.regex` inside, so it ports verbatim and gives the same
semantics on every target (rule 1). The only platform pieces are Unicode classification and
simple case mapping, which come from the generated tables (rule 3).

- [x] `build-logic/unicode`: a Gradle task `generateUnicodeTables` that reads pinned UCD 15.0.0
      files checked into `build-logic/unicode/ucd/` (`UnicodeData.txt`, `Scripts.txt`,
      `PropList.txt`, `DerivedCoreProperties.txt`, `PropertyValueAliases.txt`) and writes
      `commonMain/.../regexp/UnicodeTables.kt`: general category ranges for all planes, script
      ranges, the binary properties upstream supports (`Alphabetic`, `ASCII`, `Case_Ignorable`,
      `ASCII_Hex_Digit`, `Hex_Digit`, `ID_Continue`, `ID_Start`, `Lowercase`, `Uppercase`,
      `White_Space`), and the simple upper and lower case mappings. Sorted `IntArray` ranges
      with binary search; the generated file is committed and has a header naming the UCD
      version and the task that made it.
- [x] Port `UnicodeProperties.kt` (445) on top of the tables. `Character.UnicodeScript`,
      `Character.getType(int)`, `Character.isAlphabetic` and `Character.digit` all become table
      lookups. The property-name parser drops its `java.util.regex.Pattern` for a hand-written
      scan of `name` or `name=value`.
- [x] Port `SubString.kt` (34), `RegExpImpl.kt` (784, the `RegExpProxy` implementation:
      `match`, `search`, `replace`, `replaceAll`, `split`, the `$1`-style substitutions reusing
      `AbstractEcmaStringOperations`), `NativeRegExpCtor.kt` (116, the constructor with the
      legacy static properties `RegExp.$1`, `input`, `lastMatch`, `leftContext`,
      `rightContext`), `NativeRegExpCallable.kt` (29), `NativeRegExpInstantiator.kt` (26),
      `NativeRegExpStringIterator.kt` (101, for `matchAll`), and `RegExpLoaderImpl` as
      `ScriptRuntime.registerRegExp`.
- [x] Port `NativeRegExp.kt` (4849) in three commits that each compile: the compiler
      (`parseTerm`, `parseAlternative`, the emit pass and the `RENode` tree), the matcher
      (`matchRegExp`, `executeREBytecode`, the backtrack stack, `simpleMatch`, the class-set
      matching with `RECharSet`), and the object surface (`exec`, `test`, `compile`,
      `toString`, `Symbol.match`, `Symbol.matchAll`, `Symbol.replace`, `Symbol.search`,
      `Symbol.split`, the `flags`, `source`, `global`, `ignoreCase`, `multiline`, `sticky`,
      `unicode` and `hasIndices` accessors, `lastIndex`). Case-insensitive matching uses the
      generated simple case mapping instead of `Character.toUpperCase` (ledger entry).
- [x] `String.prototype.match`, `matchAll`, `search`, `replace`, `replaceAll` and `split` with a
      pattern argument (P3.8 left them going through the proxy) come alive without changes.
- [x] Oracle, compared as rendered `exec` results (`index`, `input`, `groups`, every capture,
      `lastIndex` before and after): literal and constructed patterns, every flag, sticky and
      global iteration, named groups and `$<name>` replacement, backreferences, lookahead and
      lookbehind, lazy and possessive quantifier corners, character classes and escapes, Unicode
      escapes with and without `u`, surrogate pairs under `u`, `\p{...}` for every supported
      property and the errors for unsupported ones, `split` with captures and limits, `replace`
      with a function and with every `$` pattern, `Symbol.split` on a custom object, `RegExp`
      called on a `RegExp`, `RegExp.prototype.toString` escaping, the legacy statics,
      `String.raw` with regexps, and the syntax errors upstream throws.
- [x] Corpus programs: a tokenizer, a template engine, a CSV parser, a log parser, a URL
      parser, an email validator table. The slice adds a regex program with recorded answers so
      JS and iOS prove the same matcher.
- [x] Cross-target smoke for case folding: a list of the code points where platforms disagree
      (`ſ`, `İ`, `ı`, `ẞ`, the Greek sigma forms, Cherokee), matched with
      `i` on every target against upstream's recorded answers.
- [x] Green on all targets; commit.

#### P4.5: Date

- [x] Add `kotlinx-datetime` to `commonMain` (version in `libs.versions.toml`, one that
      supports wasmJs; on JS and Wasm it reads zone data from the host's `Intl`, on Apple from
      Foundation, on the JVM from `java.time`). `Context.timeZone: TimeZone`, default
      `TimeZone.currentSystemDefault()`, settable like upstream's `Context.setTimeZone`.
      Local offsets now come directly from `offsetAt` at the represented instant and local civil
      fields resolve through `LocalDateTime.toInstant` (D-110). The short zone name for the `zzz`
      pattern is the zone id (kotlinx-datetime has no abbreviations; ledger entry, and the oracle runs with a
      zone whose id equals its abbreviation, such as `UTC`, for the formats that print it).
- [x] `Context.clock`: a `() -> Double` of epoch milliseconds, default `Clock.System` from the
      stdlib (opt in if the API is still marked experimental at 2.4). `Date.now()`,
      `new Date()` and the tests use it; tests pin it.
- [x] Port `NativeDate.kt` (2038). The date arithmetic (`MakeDay`, `MakeTime`, `YearFromTime`,
      `WeekDay`, the 64 static helpers) is pure and ports verbatim. `LocalTZA` and
      `DaylightSavingTA` read `Context.timeZone`. `date_parseString` and `date_format` port
      verbatim. The four `toLocale*` methods format with fixed en-US patterns that equal what
      upstream produces under `Locale.US` (`MMMM d, yyyy h:mm:ss a z` below ES6, the short
      localized forms at ES6 and above); other locales and the `Intl` object are out of scope
      (ledger entry, listed in `PORTING_STATUS.md` as a limitation).
- [x] `NativeDate.init` in `initSafeStandardObjects`; `TopLevel.Builtins.Date`;
      `JSON.stringify` of a date through `toJSON`; `Date.prototype[Symbol.toPrimitive]`.
- [x] Oracle with the same zone on both sides (`cx.setTimeZone(TimeZone.getTimeZone(id))`
      upstream, `cx.timeZone = TimeZone.of(id)` here; both read the same IANA database on the
      JVM) and a fixed clock, for `UTC`, a fixed offset, `Europe/Berlin` and
      `America/New_York`: constructors from millis, strings and components,
      `Date.UTC`, `Date.parse` on ISO 8601, RFC 2822 style and the loose formats upstream
      accepts, every getter and setter including the overflow rules, `toString`, `toUTCString`,
      `toISOString` (and its RangeError), `toDateString`, `toTimeString`, `toJSON`,
      `getTimezoneOffset`, `getYear` and `setYear`, invalid dates everywhere, the
      millisecond limits, leap years and the year-zero corners.
- [x] The cross-target slice adds a Date program with `UTC` and with `Europe/Berlin` across a
      daylight-saving switch, with upstream's recorded answers, so the JS, iOS and Wasm zone
      data are checked against the JVM's, not assumed.
- [x] Green on all targets; commit.

#### P4.6: Typed arrays, ArrayBuffer and DataView

- [x] Port `ByteIo.kt` (184) and `Conversions.kt` (59): the little- and big-endian packing
      over `ByteArray`, floats through `Float.toBits` and `Double.toBits`, the
      `Uint8Clamped` rounding. Pure.
- [x] Port `NativeArrayBuffer.kt` (355), `NativeArrayBufferView.kt` (82),
      `NativeTypedArrayIterator.kt` (83), then `NativeTypedArrayView.kt` (1567) without its
      `java.util.List` and `RandomAccess` views (same reasoning as D-36) and without
      `java.lang.reflect.Array`; keep `ExternalArrayData` (P3.3 already has it).
- [x] Port the nine numeric views (`Int8`, `Uint8`, `Uint8Clamped`, `Int16`, `Uint16`,
      `Int32`, `Uint32`, `Float32`, `Float64`, about 110 lines each) and `NativeDataView.kt`
      (422). `BigInt64Array` and `BigUint64Array` wait for P5.
- [x] `NativeArrayIterator.isDone` gets its detached-array check (the `TODO(P4)` from P3.8);
      `ArrayLikeAbstractOperations.iterativeMethod` and `reduceMethodWithLength` serve the
      typed views as upstream intends.
- [x] Register the lazy constructors in `initSafeStandardObjects` under the same version gate
      as upstream (`VERSION_ES6`, or `VERSION_1_8` with `FEATURE_V8_EXTENSIONS`).
- [x] Oracle: buffers shared between views, offsets and lengths and their RangeErrors,
      element conversion for every type including NaN, infinity, negative zero and clamping,
      `set` from arrays and from overlapping views, `subarray` sharing memory, `slice` copying,
      `copyWithin`, `fill`, `sort` with and without comparator, `indexOf` and `includes` on
      NaN, `join`, `toString`, `from` and `of`, `Symbol.species`, iteration, `DataView`
      get and set for every type in both endiannesses with byte offsets, `byteLength`,
      `byteOffset`, `BYTES_PER_ELEMENT`, `Object.prototype.toString` tags, and the
      `FEATURE_LITTLE_ENDIAN` default.
- [x] Green on all targets; commit.

#### P4.7: Promise

- [x] Port `UnhandledRejectionTracker.kt` (77) and wire `Context.unhandledPromiseTracker`;
      the identity-keyed map from P3 replaces `IdentityHashMap`.
- [x] Port `NativePromise.kt` (996): constructor, `then`, `catch`, `finally`, `resolve`,
      `reject`, `all`, `allSettled`, `any` with `AggregateError` (P3.8 has it), `race`,
      `withResolvers`, the thenable job, `Symbol.species`, and the microtask reactions through
      `Context.enqueueMicrotask` (P3.5 has the queue; `doTopCall` already drains it).
- [x] Oracle, compared through log arrays written by the callbacks: ordering of `then` chains
      against synchronous code, nested promises, thenables that call back twice, `resolve` with
      itself, rejection with a non-error, `finally` pass-through, every combinator on empty,
      mixed and rejecting inputs, unhandled rejections reported through the tracker, and the
      `async` test262-style `$DONE` pattern. Note for readers: upstream has no `async`
      functions, so `await` does not exist in either engine.
- [x] Corpus programs: a promise-based task queue, a retry-with-backoff simulation driven by a
      manual clock, `Promise.all` over a fake fetch table.
- [x] Green on all targets; commit.

#### P4.8: Proxy and Reflect

- [x] Open the `ScriptableObject` hooks a proxy overrides where they are still final:
      `getTypeOf`, `getDeclarationScope`, the `Symbol` overloads of `get`, `put`, `has` and
      `delete`, `preventExtensions`, `isExtensible`, `defineOwnProperty` and
      `getOwnPropertyDescriptor`. All of them were already open, so nothing had to change.
- [x] Port `NativeProxy.kt` (1373) with `NativeProxyFunction`, every trap, the invariant checks
      and their TypeErrors, `Proxy.revocable`, and the two `TODO(P4)` sites
      (`AbstractEcmaObjectOperations.isConstructor`, `NativeArray.js_isArray`).
- [x] Port `NativeReflect.kt` (397): the thirteen static methods over the abstract operations
      from P3.8 (`createListFromArrayLike` gains its proxy-aware callers).
- [x] Oracle: every trap called and not called, trap return values coerced, invariant
      violations, proxies as prototypes, `in` and `delete` through proxies, `for...in` and
      `Object.keys` through `ownKeys`, function and constructor proxies with `apply` and
      `construct`, revocation, `Array.isArray(proxy)`, `typeof` of a proxied function, and
      every `Reflect` method against plain objects and against proxies. Two places where
      upstream cannot be matched are pinned as tests instead (D-50, D-51).
- [x] Green on all targets; commit.

#### P4.9: Close-out

- [x] `initSafeStandardObjects` has no `TODO(P4)` left; `TopLevel.cacheBuiltins` finds every
      builtin; `ScriptRuntime` has no `TODO("... phase 4")` left (grep is the check). The last
      one was the detached typed array check in `NativeArrayIterator`.
- [x] The corpus gains at least twelve programs that use the new builtins together. Thirteen
      landed, all of them proxy or reflection driven and all of them leaning on the earlier
      phases (regexps, generators, `Map` and `Set`, `JSON`, symbols). The slice takes six.
- [x] The eval smoke test on every target gets one line per new builtin.
- [x] `PORTING_STATUS.md` rows for each builtin; README status paragraph rewritten to the truth
      ("evaluates ES5 and most of ES2015 minus classes and modules, on JVM, Android, iOS and
      JS"); the P4 ledger entries reviewed against the code. Commit.

**Done when:** the extended oracle is green on the JVM, the slice is green on JS and iOS, the
smoke test covers every new builtin on every target, and every deviation is a ledger entry.

### P5: BigInt and weak collections

#### P5.1: KBigInt

- [x] Replace the stub with a real immutable arbitrary-precision integer in
      `commonMain/.../KBigInt.kt`: sign and magnitude as an `IntArray` of base 2^32 limbs. Operations
      in the order the engine needs them: `compareTo`, `add`, `subtract`, `multiply`
      (schoolbook, Karatsuba above a threshold), `divideAndRemainder` (Knuth algorithm D),
      `remainder`, `pow`, `shiftLeft`, `shiftRight`, `and`, `or`, `xor`, `not` (two's
      complement semantics like `java.math.BigInteger`), `negate`, `abs`, `signum`,
      `bitLength`, `toString(radix)`, `parse(text, radix)`, `toDouble` (correctly rounded),
      `fromDouble`, `toLong` and `toInt` (low bits), `asIntN` and `asUintN`, `equals` and
      `hashCode`. Zero third-party dependency (rule 4). `mod`, `testBit` and `longValueExact`
      came along too, since P5.2 needs them.
- [x] Test `jvmTest/KBigIntOracleTest`: every operation against `java.math.BigInteger` on a
      seeded corpus of operands (small, limb-boundary, thousands of bits, negative, zero) and
      every radix from 2 to 36; algebraic checks (`(a*b)/b == a`, `a - a == 0`, shifts against
      multiplication) as negative controls. `commonTest/KBigIntTest` runs a slice on every
      target. The slice is not a formality here: Kotlin/JS has no 64-bit integer, so every
      `Long` and `ULong` in the division loops is emulated with a pair of Ints.
- [x] Commit.

#### P5.2: BigInt in the language

- [x] Port `NativeBigInt.kt` (151): the `BigInt` function, `asIntN`, `asUintN`, `toString`,
      `toLocaleString`, `valueOf`, `Symbol.toStringTag`; `toObject` wraps a `KBigInt`.
- [x] Fill the `TODO(P5)` sites: `ScriptRuntime.toBigInt` from strings and numbers with the
      RangeErrors, the mixed-type TypeErrors in the arithmetic (P3.7 already routes `KBigInt`
      operands), `typeof`, equality and relational comparison between BigInt and Number and
      String, `NativeJSON` (`msg.json.cant.serialize` and `toJSON`), `Hashtable` key
      normalisation, unary minus and `**`, the lexer's `123n` literal path (`KBigInt.parse`
      exists since P0), `Number(bigint)` and `parseInt(bigint)`.
- [x] Port the BigInt half of `DToA` (`JS_dtobasestr`, about 150 lines over `KBigInt`), so
      `Number.prototype.toString(radix)` works for every radix, as `dtoa/RadixFormatter`
      (D-33b). The radix one-liners removed in P3.8 return to the oracle, plus a sweep of every
      radix against a spread of fractions, which is what reaches the last-digit tie-break.
- [x] Port `NativeBigIntArrayView.kt` (19), `NativeBigInt64Array.kt` (123) and
      `NativeBigUint64Array.kt` (130), and register them. All three live in one
      `typedarrays/BigIntViews.kt`, the way the other views share `NumericViews.kt`.
- [x] Oracle: literals, every operator on BigInt and the TypeErrors for mixing, comparisons
      across types, `BigInt("0x10")` and the parsing rules, `asIntN` and `asUintN` wrapping,
      `toString` in every radix, `JSON.stringify` error and `toJSON` escape, BigInt keys in
      `Map` and `Set`, the typed views, `Number.prototype.toString(2)` and `(16)`,
      `Object.is(0n, -0n)`, and `BigInt.prototype.toString.call(1)` errors.
- [x] Commit.

#### P5.3: WeakMap and WeakSet

- [x] `WeakRef` lives in the engine, not in a dependency. KiteCore exposes
      `kotlinx-coroutines-core` with `api`, so depending on it would put coroutines in every
      consumer's classpath, which rule 4 exists to prevent, and would cap KiteJS's targets at
      KiteCore's. The expect class and its four actuals were copied in instead, keeping their
      Apache 2.0 header, along with `WeakRefTest` on every target and `WeakRefGcTest` on the
      JVM (D-52).
- [x] `WeakKeyMap.kt`: a small weak-keyed map over `WeakRef`, keyed by identity hash, that
      drops cleared entries when it grows or is asked its size. Upstream's `WeakHashMap` has no
      multiplatform equivalent, so this is the port's own (D-55).
- [x] Port `NativeWeakMap.kt` (150) and `NativeWeakSet.kt` (131) on top of it, with the
      non-object key TypeErrors.
- [x] Tests: functional semantics on every target, plus about 50 oracle scripts; `WeakKeyMapGcTest`
      on the JVM holds keys, drops them, forces a collection and shows the entries go, which is the
      only place the weakness can be tested at all.
- [x] Commit.

#### P5.4: Close-out

- [x] No `TODO(P5)` left; `PORTING_STATUS.md` rows for BigInt, WeakMap and WeakSet; the
      README status paragraph updated. Commit.

**Done when:** the BigInt oracle and the weak semantics tests are green, and the radix printer
matches upstream on the P3.8 one-liners.

### P6: Conformance harness

The goal is not a pass rate. The goal is parity: for every test262 file upstream runs, the
port passes exactly when upstream passes. Every difference becomes a fix or a ledger entry.

- [x] `tools/fetch-test262.sh`: shallow-fetches `tc39/test262` at the commit upstream pins
      (`3fd4ec27f1798ebecafc73b354a45dcdda9bde29`) into `reference/test262/` (gitignored, next
      to the Rhino source). CI runs it before the conformance job.
- [x] `kitejs/src/jvmTest/resources/test262.properties`: a copy of upstream's file (6949
      lines), unchanged, so the same skip list and expected-failure list apply.
- [x] `jvmTest/Test262ParityTest.kt`: a Kotlin port of the parts of upstream's
      `Test262SuiteTest` that matter. It reads the properties file, parses each test's YAML
      front matter (`includes`, `flags`, `features`, `negative`), loads the harness files
      (`assert.js`, `sta.js`, `compareArray.js`, `propertyHelper.js`, `doneprintHandle.js` and
      the rest of `harness/`), applies upstream's unsupported-feature list (`class`,
      `async-functions`, `async-iteration`, `default-arg`, `new.target`, `object-rest`,
      `regexp-dotall`, `regexp-unicode-property-escapes`, `Temporal` and the others), and runs
      each test through both engines in interpreted mode at the language version upstream
      uses. It asserts three things per test: the port's outcome equals upstream's outcome, an
      expected failure in the properties file fails upstream (the copy is not stale), and a
      test not listed passes upstream.
- [x] The run is a separate Gradle task (`test262Parity`), not part of `jvmTest`: two engines
      over tens of thousands of files takes minutes. CI runs it nightly and on release
      branches; a developer runs it before a phase close-out.
- [x] Every parity difference gets a fix or a ledger entry with the test path; the ledger
      entry is the only acceptable way to leave a difference in place.
- [x] `commonTest/Test262Runner.kt` over `kotlinx-io` (test scope only): the same properties
      parsing, front-matter parsing and harness loading as the JVM runner, reading the fetched
      `reference/test262` tree from disk on the JVM, on the iOS simulator and on Node (the path
      arrives through a generated constant or an environment variable). It compares the port's
      outcome per test with the outcome the JVM parity run recorded into
      `build/test262/expectations.json`, so every target runs the same files the JVM runs, not
      a capped slice. Browser and Wasm-browser targets, which have no file system, skip it
      with a visible message; Wasm on Node runs it. Nothing generated is committed.
- [x] Native tests actually run: `iosSimulatorArm64Test` joins the default check (today the
      iOS target only compiles), and `macosArm64Test` once P8 adds the target.
- [x] V8 differential report: when `jsNodeTest` runs, a reporter evaluates the eval corpus in
      Node's own engine as well and prints where KiteJS and V8 disagree. Informational only:
      upstream Rhino itself disagrees with V8 (no classes, no modules), so this cannot be a
      gate, but it shows the reader where the engine stands against a modern one.
- [ ] Optional, after the above: upstream's 149 Mozilla `jstests` through the same runner. Not
      done: test262 already covers the same ground and the parity mechanism is what mattered.

**Done when:** `test262Parity` reports zero unexplained differences, the common runner matches
the JVM's recorded outcomes on JS, iOS and (after P8) macOS and Wasm, and `PORTING_STATUS.md`
states the pass count per folder, taken from the run, not typed by hand.

### P7: Kotlin-first API

Two halves. The first cleans the engine's Kotlin without changing behaviour. The second adds
the facade an embedder actually uses. The engine classes stay public but are documented as the
engine-level API; the facade is the recommended one.

#### P7.1: The Java-shaped leftovers (behaviour unchanged)

Measured today: 498 `getX()`/`setX()`/`isX()` functions, 863 `!!`, 308 raw `Array<Any?>`
argument lists. The sweep applies the house Kotlin idioms where they fit, in one commit per
package, with the full suite green after each.

- [x] Accessors that are not JavaScript operations become properties. 92 of the 130 zero-argument
      `getX()`/`isX()` functions are now properties, `getArity()`, `getLength()` and
      `getFunctionName()` among them; `getPrototypeProperty` already was one. Sixteen turned out to
      be a private field plus an accessor that only widened its visibility or its type, and those
      collapsed into one member. `getDefaultValue` stays (it is the `[[DefaultValue]]` hook),
      `get(name, start)` and friends stay (they are the object protocol), `isEmpty()` stays (it is
      the collection idiom), the lexer calls that advance a position stay, and so do the three
      readers that throw or build something rather than answer a stored value.
- [x] Explicit backing fields: checked and not applied. The feature solves a public read type
      that differs from the internal write type, and no property in the port has that shape. Every
      `xField` is either read and written at the same type, or backs a getter with real logic that
      a backing field cannot express, or is a lazily allocated collection that reads back as a
      shared empty list when null, which an explicit field would force to allocate on every node.
      The `NewLiteralStorage` four turned out to have no accessor pair at all.
- [x] `Enum.entries` was already used everywhere; `.values()` appears nowhere in the module. The
      interpreter's three loop sentinels are `data object`s now, which is where the idiom fits: a
      sealed hierarchy whose members carry no data. `UniqueTag` and `Undefined` deliberately stay
      as they are. Both override `equals` in ways a `data object` would undo: `Undefined` treats
      its two instances as equal, and `UniqueTag.toString` prints the identity hash the way
      upstream's does. Guard conditions in `when` and non-local `break` found no site where they
      read better than what is already there; the interpreter's one labelled break is a real
      labelled break out of the dispatch loop, not a Java workaround.
- [x] Nullability, with the target changed and the reason recorded. Nineteen redundant sites
      the compiler itself flags are gone: fourteen `!!`, three safe calls and six casts that were
      never needed. The remaining thousand are not noise. They sit in the parser, the IR builder,
      the code generator and the interpreter, where upstream declares a Java field nullable and
      every reader knows it is set by then. Getting under a hundred means changing the node model
      so those invariants are types, which is a deep change to the one part of the port that must
      stay readable against upstream, for no behaviour gained. Commenting the other nine hundred
      would be the kind of comment noise this project does not want. What is worth doing when the
      node model is next touched: `Node.firstChild`, `Node.next` and `Parser.currentScriptOrFn`
      alone account for a quarter of them.
- [x] `explicitApi()` on both modules. 2,223 declarations took an explicit visibility and 344
      took an explicit return type; three of the inferred types were wrong and the compiler caught
      all three. Then the decision itself: measured against upstream, seven classes were public
      here that upstream keeps package-private, and they are now `internal` (`Block`,
      `NativeBoolean`, `NativeError`, `NativeMath`, `NativeNumber`, `NativeScript`,
      `NativeString`). Everything else in the port's public surface matches a class upstream also
      publishes, which is what a faithful port should look like.
- [x] The identifier classification in the lexer and `ScriptRuntime.isJavaIdentifierStart`
      moved onto the generated Unicode tables in P4.4 (D-43, D-57). Nothing in `commonMain` asks
      the platform to classify a character any more.
- [x] `ImplementationVersion` becomes a constant (`Context.IMPLEMENTATION_VERSION`, read by
      `Context.implementationVersion`); `Delegator` is ported (289) as the base for host wrappers,
      with the write path fixed under D-65 because upstream's overflows the stack.
- [x] Short KDoc on every public class, object and interface: 22 were missing one and now have
      it, so the count is zero. Not applied to every public function and constant, on purpose.
      `Token` alone has 196 public constants whose names already say what they are, and a line of
      prose on each would make the file harder to read, not easier. The rule used instead: a type
      always gets a line, a function gets one when its name does not already answer the question,
      and a run of related constants gets a section comment rather than one comment each.

#### P7.2: The facade

A new package `io.github.yuroyami.kitejs.api` with a handful of types. Reflection-free,
expect/actual-free, and usable from Swift and JavaScript through the normal Kotlin exports.

```kotlin
val js = KiteJs {
    languageVersion = LanguageVersion.ES6
    timeZone = TimeZone.of("Europe/Berlin")
    clock = { fixedMillis }
    console = ConsolePrinter.Stdout
    instructionBudget = 5_000_000
}

js.global.function("log") { args -> println(args.joinToString(" ")) }
js.global.function<Double, Double, Double>("hypot") { a, b -> sqrt(a * a + b * b) }
js.global.obj("document") {
    property("title", "Untitled")
    getter("readyState") { "complete" }
    function<String, JsValue>("getElementById") { id -> host.lookup(id) }
}

val result: JsValue = js.evaluate("document.title + ':' + hypot(3, 4)")
println(result.asString())       // "Untitled:5"
js.runMicrotasks()
js.close()
```

- [x] `KiteJs`: the engine handle. Builds a `Context` and a global scope from a `KiteJsConfig`
      DSL; `evaluate`, `compile` (a reusable `JsScript`), `global`, `runMicrotasks`, `close`,
      `valueOf`, `newObject`, `newArray`; implements `AutoCloseable` so `use { }` works. One
      instance at a time, and opening a second while one is live says so rather than quietly
      sharing the first one's context.
- [x] `JsValue`: a value class over the engine's `Any?` with `isUndefined`, `isNull`,
      `isNullish`, `asBoolean()`, `asDouble()`, `asInt()`, `asLong()`, `asString()`, `asBigInt()`,
      `asObject()`, `asArray()`, `asFunction()` and their `OrNull` twins, `toKotlin()` (deep, and
      a cycle stops at the object that closed it), and `typeOf`. A `when` over `JsValue.type`
      is exhaustive.
- [x] `JsObject`, `JsArray`, `JsFunction`: thin wrappers with `operator get` and `set`, `keys`,
      `has`, `delete`, `call`, `invoke`, `callOn`, `construct`, `bind`, and conversion to and from
      Kotlin collections. No new object model; they wrap `Scriptable`, `NativeArray` and
      `Function`. A call the host starts is wrapped in a top call, so it sets up the same top
      scope a script would; without that `bind` cannot even build its type-error thrower.
- [x] `Converters`: the fixed table Kotlin to JS (`Int`, `Long` (a BigInt outside the safe
      range), `Double`, `Float`, `Short`, `Byte`, `Boolean`, `Char`, `String`, `CharSequence`,
      `List`, `Array`, the primitive arrays, `Map`, `Set`, `Unit`, `null`, `JsValue`, `JsObject`)
      and back, plus `register` for user types. Typed `function<A, B, R>` overloads resolve
      arguments through it at call time, with reified types and no reflection.
- [x] Host binding DSL: `obj { }`, `function`, `method`, `property`, `constant`, `getter`,
      `setter`, `accessor`, `constructor`, a `PropertyFlags` for writable, enumerable and
      configurable, and a `bind(name, instance) { property(Kotlin::prop); method(...) }` form
      built on callable references (`KProperty1.get` needs no reflection library). Context
      parameters were not used: the DSL receiver is the object being built, which is the thing
      the lambda is about, and the engine reaches the lambdas through the entered context.
- [x] `JsException` hierarchy (sealed): `JsError` (a thrown script value, with `value`,
      `name`, `errorMessage` and `scriptStack: List<JsStackFrame>`), `JsSyntaxError` (with file,
      line, column and line source), `JsEngineError` (closed, misused, or out of budget). The
      engine's `RhinoException` family stays underneath; the facade translates at the boundary.
- [x] Instruction budget: `ContextFactory.observeInstructionCount` backs
      `KiteJsConfig.instructionBudget`; exceeding it throws `JsEngineError` naming the budget.
      Each call gets the budget again. This is how an embedder stops a runaway script on every
      target.
- [x] `NativeConsole.kt` (386) ported with a `ConsolePrinter` fun interface, a `Level` enum,
      the format specifiers (`%s`, `%d`, `%i`, `%f`, `%o`, `%O`, `%c`, `%%`) formatted by hand,
      plain counters instead of `AtomicInteger` and `TimeSource.Monotonic` instead of
      `System.nanoTime` (D-66). Installed by the facade when a printer is configured;
      `ConsolePrinters` has ready-made ones for stdout and for collecting into a list.
- [x] Tests: `commonTest/FacadeTest`, 39 tests on every target covering each DSL form,
      conversions in both directions, exceptions, the budget, the console and the config;
      `jvmTest/FacadeEquivalenceTest` runs the whole eval corpus plus 56 scripts through both the
      facade and `evaluateString` and compares, proving no semantic layer was added. Three
      negative controls confirm all four of its cases are live.

#### P7.3: The coroutine artifact

- [x] A new Gradle module `kitejs-coroutines` depending on `kitejs` and
      `kotlinx-coroutines-core`. `asyncKiteJs()` builds an engine on a dispatcher that runs one
      thing at a time; `AsyncKiteJs` has `onEngine`, `evaluate`, `compile`, `evaluateAwaiting`,
      `await` (a rejection arrives as the `JsError` it carried), `deferredToPromise` and
      `suspendFunction`, which hands the script an ordinary function answering a promise.
      Cancellation goes through a new `KiteJsConfig.interruptWhen` hook that the instruction
      observer asks, so cancelling the caller stops a running script. The hook chains: whatever
      the caller set is still asked. `newPromise()` in the facade gives the host a promise it
      settles itself, built by evaluating a five-line script rather than reaching into the
      engine.
- [x] Tests with `kotlinx-coroutines-test`: 16 in `commonTest` on every target for ordering,
      promises settled from either side, a script awaiting a host call, exceptions crossing both
      ways, and a deadline stopping a runaway script. Cancelling from another coroutine needs a
      second thread, which Kotlin/JS has none of, so those three tests are `jvmTest` and run on
      the real clock (D-67). A negative control confirms the hook is what stops the script:
      without it the run never finishes.

#### P7.4: The EPUB-shaped demo

- [x] `commonTest/api/EpubScriptingDemoTest`: a fake `document` with `getElementById`,
      `querySelector`, `querySelectorAll`, `addEventListener` and elements with `textContent`,
      `className` and `style`, plus `navigator.epubReadingSystem` with `name`, `version` and
      `hasFeature`, all bound through the facade and nothing else. A chapter script registers a
      `DOMContentLoaded` handler, rewrites the title, counts paragraphs and installs a click
      handler that keeps its own closed-over state across clicks; the test asserts the DOM it
      touched. Also covers a chapter script that throws (the engine keeps working) and one that
      never returns (the budget stops it).

**Done when:** the facade tests are green on every target, both modules compile with
`explicitApi()`, every redundant null assertion the compiler can find is gone, and the coroutine
artifact passes its tests on JVM, JS and iOS. The original wording asked for the `!!` count to be
under a hundred; that target was dropped with the reasoning recorded above, because reaching it
means reshaping the node model rather than fixing anything.

### P8: Widen and publish

- [x] Targets: `macosArm64`, `wasmJs` (browser and Node), `linuxX64`, `linuxArm64` and
      `mingwX64`. The three new native targets needed no code at all, since they share the same
      `WeakRef` actual the Apple targets use. Wasm needed its own: it has no weak primitive, so
      it holds strongly and reports `isWeakSupported` as false, and it needs the same npm zone
      database Kotlin/JS does. `macosArm64Test` and `wasmJsNodeTest` run the whole suite,
      including the 52,802-case test262 slice, and the slice is what found two real bugs (D-68).
      `mingwX64Test` and `linuxX64Test` run in CI, since a host can only test itself.
- [x] CI in `.github/workflows`: `ci.yml` runs three jobs on every push, a Linux one (JVM, JS
      on Node, Wasm on Node, Linux native, `assemble` and `check`, which carries the ABI check), a
      macOS one (macOS and the iOS simulator) and a Windows one, since a host can only test
      itself. `test262.yml` fetches the suite and runs the parity check nightly, keeping the
      per-folder summary as an artifact. `docs.yml` builds the MkDocs site and the Dokka
      reference and deploys both to Pages. No `release.yml`: nothing is published yet.
- [x] JVM bytecode target 11 on both modules, compiled with the 21 toolchain and
      `-Xjdk-release=11` so a newer standard library method cannot slip in. The upstream jar
      stays a test-only dependency. ABI validation is on for both modules.
- [x] Documentation site: `mkdocs.yml` and seven pages under `docs/`, with the shared Kite
      theme copied in by `_kite-docs/sync.sh`. Getting started, evaluating scripts, binding host
      objects, promises and coroutines, dates and time zones, limits and safety, and one page of
      differences from a browser written as limitations the reader will hit. Every claim on that
      page was checked against the running engine rather than remembered, which is how the
      missing spread-in-a-call turned up. `dokkaGenerate` covers both modules. No em dashes, no
      ledger vocabulary, British spelling throughout.
- [x] README rewritten: what it does, the targets, a working quickstart, the docs links in the
      first screen, and provenance named once in the opening sentence and once under Licence.
      `POM_DESCRIPTION` rewritten for both modules to say what the code does and what it does
      not. The status paragraph and the links to `PORTING_STATUS.md` and `KITEJS_IMPL.md` are
      gone; those two stay as maintainer documents.
- [ ] Publish `io.github.yuroyami:kitejs:0.1.0` (and `kitejs-coroutines`) to Maven
      Central through vanniktech, with the shared POM values in `gradle.properties` and the
      MPL-2.0 licence and NOTICE attribution intact. Held back on purpose: everything the
      publish needs is in place and nothing has been pushed anywhere.
- [ ] Register KiteJS in KiteVersions (`repos.txt`) so the shared versions sync into
      `gradle/libs.versions.toml`. Waits for the publish, since it touches another repository.
- [x] Announce nothing the code cannot support. The README, the site and both POM descriptions
      say what runs and name what does not: no classes, no modules, no async and await, no `Intl`,
      and the three places an answer differs from a browser's.

**Done when:** the artifacts resolve from Maven Central in a fresh project on every declared
target, the site is live and linked from the README, and CI is green on the default check. The
first of those waits for the publish, which is deliberately not done.

## Self-review notes (kept with the plan)

- Type consistency: `Token` values must keep upstream's exact ints (P2 Icode depends on the numbering, Icode values sit below `Token.EOF`).
- The lexer error-reporter interface is the one place P0 invents structure ahead of P1; P1's `Parser` must implement it, and if upstream's coupling is too tight the interface dissolves back into a direct `Parser` reference at P1 (note it in the ledger either way).
- EPUB conformance in KitePDF does NOT wait for this project: EPUB 3.3 scripting is optional for reading systems. KiteJS unlocks scripted books later, through a DOM binding layer that belongs to KitePDF.
