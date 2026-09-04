# KiteJS Implementation Plan

> **For agentic workers:** execute tasks inline in the main session (superpowers:executing-plans style). Do NOT dispatch write-subagents: this workspace has a one-writer rule. Read-only scouting subagents are fine. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a JavaScript engine in pure common Kotlin, ported from Mozilla Rhino 1.9.1, that evaluates real ECMAScript identically to upstream on Android, iOS, JVM and JS.

**Architecture:** faithful port of Rhino's interpreter path: `TokenStream` (lexer) feeds `Parser` (AST), `IRFactory`/`CodeGenerator` lower the AST to Icode, `Interpreter` executes Icode against the runtime (`ScriptRuntime`, `ScriptableObject`, native objects). The JVM bytecode compiler is not ported; upstream's interpreted mode (what Rhino uses on Android) is the model. iOS forbids JIT, so an interpreter is also the only legal design.

**Tech stack:** Kotlin 2.4.10 KMP, Gradle 9.6.0, AGP 9.2.1 (`com.android.kotlin.multiplatform.library`), vanniktech publish, dokka. Runtime dependencies: none.

**Spec:** the pinned upstream source at `reference/rhino/rhino/src/main/java/org/mozilla/javascript/` (tag `Rhino1_9_1_Release`). Each port task names its upstream files; those files ARE the task's code spec. Feature coverage reference: https://mozilla.github.io/rhino/compat/engines.html

## Global constraints

- Everything engine lives in `kitejs/src/commonMain/kotlin/io/github/yuroyami/kitejs/`. Package mirrors upstream: `org.mozilla.javascript` maps to `io.github.yuroyami.kitejs`, subpackages `ast`, `regexp`, `json`, `typedarrays`, `dtoa`, `v8dtoa` keep their names.
- File mapping is 1:1 by name: `TokenStream.java` becomes `TokenStream.kt`. Same class names, method names and behavior. Deviations only where a JVM-ism forces one, via the substitution table below, and each deviation gets a row in the divergence ledger.
- Zero expect/actual until Phase 5 (KiteCore's `WeakRef` hides the only one we need inside its own artifact).
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
| `java.util.TimeZone` | `NativeDate` | Phase 4 decision: kotlinx-datetime vs minimal own table. Leaning kotlinx-datetime (kotlinx tier is acceptable, same as KiteCore's coroutines) |
| `Double.doubleToLongBits` | dtoa, hashing | `Double.toBits()/toRawBits()`, exists in common |
| `String.format`, `Locale` | misc formatting | manual formatting; locale-sensitive behavior is out of scope |
| `Serializable`, `serialVersionUID`, `readObject` | almost every class | dropped |
| `ClassLoader`, reflection, `ClassCache` | LiveConnect, `FunctionObject` | cut; Phase 7 replaces host-object binding with a Kotlin DSL over the lambda architecture upstream already has |
| `IdentityHashMap` | `EqualObjectGraphs`, interpreter guards | small own map keyed by reference equality, only where actually needed (Phase 3) |
| `java.util` collections | everywhere | Kotlin stdlib collections |

## Divergence ledger

Living list. Every entry is a known, deliberate behavior or structure difference vs upstream.

- D-1: identifier start/part classification approximates the JVM's `isJavaIdentifier*` with `Char.category`. Exotic identifiers may differ; the oracle corpus includes unicode identifier cases to measure this.
- D-2: no `Reader`-based source input. String in, that is all.
- D-3: engine instances are single-thread confined. No shared-context multithreading.
- D-4: error messages exist in English only, and only the keys the ported code uses.
- D-5: BigInt is a stub until Phase 5 (literals lex and store, arithmetic throws).
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
- D-14: `NumberLiteral(Double)` derives its source text from `Double.toString`, whose output differs
  between the JVM, JS and native. Phase 3 replaces it with the ported DToA, which is platform
  independent. Only the `NumberLiteral(Double)` constructor is affected; a literal parsed from source
  keeps the original token text.
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
- D-7: JavaBean accessors become Kotlin properties across the whole port (getString() becomes .string, and `Parser.CurrentPositionReporter` declares properties, not get-methods). Upstream's constructor overload trios collapse into constructors with default arguments. Call sites adapt mechanically at port time.

## Phases

| Phase | Deliverable | Done when |
|---|---|---|
| P0 | Scaffold + lexer | `TokenStreamTest` green on jvm; iOS and JS targets compile |
| P1 | AST + Parser | DONE. `toSource()`, positions and error parity with upstream on the corpus (jvm oracle) |
| P2 | IR + Icode generator | Icode generation completes on the corpus without error; dump comparison vs upstream where accessible |
| P3 | Interpreter + core runtime | eval oracle: identical results vs upstream on the arithmetic/string/object/array/function/closure/control-flow/exception corpus |
| P4 | RegExp, Date, collections, iterators, generators, typed arrays, Promise, template runtime | extended oracle corpus green |
| P5 | WeakMap/WeakSet via KiteCore, real BigInt | weak semantics tests + BigInt oracle slice green |
| P6 | test262 subset harness | parity with upstream's curated `test262.properties` pass list, deltas ledgered |
| P7 | Kotlin embedding API | host objects and functions definable from Kotlin without reflection; EPUB-shaped demo (bind a fake `document`) |
| P8 | Widen targets + publish | wasmJs/macOS/Linux/mingw enabled, docs site, Maven Central, KiteVersions registration |

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

### P2: IR + Icode generator

**Upstream files:** `IRFactory.java` (2644), `NodeTransformer.java`, `CodeGenerator.java` (1971), `InterpreterData` + Icode constant definitions (inside `Interpreter.java`/`InterpreterCodegen` region), `Icode` helpers, `ConstProperties`, `DecompilerFlag`.

**Oracle:** upstream's Icode dumper is not public; jvmTest reaches it with the same-package trick (a test file declared in package `org.mozilla.javascript` sees package-private members on the upstream jar). If the trick fails on some member, fall back to asserting the port's own dump is stable and that P3 eval parity catches codegen bugs (it does, Icode feeds straight into eval results).

**Done when:** whole corpus lowers to Icode without exceptions; dumps compared where reachable.

### P3: Interpreter + core runtime

**Upstream files (clusters, each its own task at expansion time):**
1. Contracts: `Scriptable`, `SymbolScriptable`, `Callable`, `Constructable`, `Function`, `Evaluator`, `Script`, `RefCallable`, `IdFunctionCall`.
2. Values: `Undefined`, `UniqueTag`, `ConsString`, `ScriptRuntime.java` (6189, the big one, minus java-interop paths), `AbstractEcmaObjectOperations`, `AbstractEcmaStringOperations`, `ArrayLikeAbstractOperations`, `CompoundOperationMap`, `EqualObjectGraphs`.
3. Object model: `Slot`, `AccessorSlot`, `BuiltInSlot`, `LambdaSlot`, `EmbeddedSlotMap`, `HashSlotMap`, `SlotMapContainer` (single-thread variant only), `ScriptableObject.java` (3345), `NativeObject`, `TopLevel`, `LazilyLoadedCtor`.
4. Functions: `BaseFunction`, `NativeFunction`, `Arguments`, `NativeCall`, `BoundFunction`, `LambdaFunction`, `LambdaConstructor`, `IdFunctionObject`, `IdScriptableObject`.
5. Numbers to strings: `DToA.java`, `dtoa/`, `v8dtoa/` (BSD parts, LICENSE already covers).
6. Context: `Context.java` (trimmed: no class shutters, no wrap factories, no security controllers), `ContextFactory` (minimal), `CompilerEnvirons` finalized.
7. Natives wave 1: `NativeGlobal`, `NativeArray`, `NativeString`, `NativeNumber`, `NativeBoolean`, `NativeMath`, `json/` + `NativeJSON`, errors (`RhinoException` with own stack capture, `EcmaError`, `EvaluatorException`, `NativeError`, `ScriptStackElement`).
8. `Interpreter.java` (5106): CallFrame machine, `ContinuationJump`, `ArrayLikeAbstractOperations` hooks, microtask hooks.

**Oracle:** `EvalOracleTest` (jvmTest): `org.mozilla.javascript.Context.enter().evaluateString(...)` vs `kitejs` eval on the same script, compare `ScriptRuntime.toString` of results plus thrown error names/messages. Corpus grows to a few hundred scripts.

**Done when:** eval corpus parity green on jvm; iOS/JS targets compile; smoke eval test runs in commonTest on every target.

### P4: Language completeness wave

`regexp/` (9 files; `UnicodeProperties` is data-heavy, port verbatim), `NativeDate` (+timezone decision recorded in ledger), `Hashtable`, `NativeMap/Set` + iterators (`NativeMapIterator`, ...), `NativeSymbol`, `ES6Iterator`/`IteratorLikeIterable`/generator machinery (`ES6Generator`; legacy `NativeGenerator` only if the interpreter requires it), `typedarrays/`, `NativePromise` + `Context` microtask queue, template literal runtime, `NativeReflect`/`NativeProxy` if present at pin.

**Done when:** extended oracle corpus green (regex semantics compared via `exec` result arrays, Date via fixed-offset zones injected in tests).

### P5: Weak collections + BigInt

- Add `io.github.yuroyami:kitecore` dependency (first and only runtime dep besides stdlib; record the version in the toml with a comment).
- `NativeWeakMap`, `NativeWeakSet` over KiteCore `WeakRef`.
- `KBigInt` gets real arithmetic (add/sub/mul/divmod/pow/shift/compare/parse/toString radix). Port `java.math.BigInteger`'s algorithms or a minimal school-book + Karatsuba implementation; benchmark is irrelevant at this stage, correctness parity with upstream BigInt oracle slice is the bar.

### P6: Conformance harness

Port the harness idea from upstream `tests/`: run the test262 subset listed in upstream's `test262.properties` on jvm, three-way (upstream jar, KiteJS jvm, and later KiteJS native/js via generated expectations). Parity with upstream's own pass list is the goal; every delta becomes a ledger entry or a fix.

### P7: Embedding API

Kotlin-first, reflection-free host binding on top of `LambdaFunction`/`LambdaConstructor` (upstream 1.9 already moved builtins to lambdas, which is exactly the KMP-friendly path):

```kotlin
val engine = KiteJs { languageVersion = ES6 }
engine.global.defineFunction("log") { args -> println(args.joinToString(" ")) }
val doc = engine.global.defineObject("document") {
    function("getElementById") { args -> hostLookup(args[0]) }
}
engine.evaluate("log(1 + 2)")
```

Exact DSL shape decided then; the constraint is zero reflection and zero expect/actual. EPUB demo: bind a minimal fake `document` + `navigator.epubReadingSystem` and run a real scripted-EPUB snippet. (The real DOM binding lives in KitePDF, not here.)

### P8: Widen + publish

- Enable `wasmJs`, `macosArm64/X64`, `linuxX64/Arm64`, `mingwX64` (pure code, expected zero blockers; KiteCore already ships these).
- `_kite-docs/sync.sh`, mkdocs + dokka site, README rewritten per KITE.md against what the code actually does, `POM_DESCRIPTION` rewritten truthfully.
- Publish `io.github.yuroyami:kitejs` to Central via vanniktech.
- Register in KiteVersions.
- Announce nothing the code cannot support.

## Self-review notes (kept with the plan)

- Type consistency: `Token` values must keep upstream's exact ints (P2 Icode depends on the numbering, Icode values sit below `Token.EOF`).
- The lexer error-reporter interface is the one place P0 invents structure ahead of P1; P1's `Parser` must implement it, and if upstream's coupling is too tight the interface dissolves back into a direct `Parser` reference at P1 (note it in the ledger either way).
- EPUB conformance in KitePDF does NOT wait for this project: EPUB 3.3 scripting is optional for reading systems. KiteJS unlocks scripted books later, through a DOM binding layer that belongs to KitePDF.
