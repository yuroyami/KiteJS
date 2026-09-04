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
- D-7: JavaBean accessors become Kotlin properties across the whole port (getString() becomes .string, and `Parser.CurrentPositionReporter` declares properties, not get-methods). Upstream's constructor overload trios collapse into constructors with default arguments. Call sites adapt mechanically at port time.

## Phases

| Phase | Deliverable | Done when |
|---|---|---|
| P0 | Scaffold + lexer | `TokenStreamTest` green on jvm; iOS and JS targets compile |
| P1 | AST + Parser | DONE. `toSource()`, positions and error parity with upstream on the corpus (jvm oracle) |
| P2 | IR generator | DONE. The corpus lowers to an IR tree identical to upstream's, before and after the transform pass. Code generation moved to P3, see that block |
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

- [ ] Port `Undefined.kt` (151), `UniqueTag.kt` (73), `ConsString.kt` (107). Self-contained value
      types with no dependency on the object model.
- [ ] Grow `ScriptRuntime` with the conversion surface that needs no Scriptable: `toNumber(String)`
      and its string-scanning helpers, `toInteger(double)`, `toInt32(double)`, `toUint32(double)`,
      `toIndex`, plus the numeric predicates. 54 of upstream's static methods take only primitives
      and strings, and they are the ones ported here.
- [ ] `DToA.JS_dtobasestr` stays out: it needs arbitrary-precision integers and waits for P5 with
      `KBigInt`, so `numberToString` keeps handling radix 10 only.
- [ ] Test `jvmTest/ConversionOracleTest`: every ported conversion compared against upstream over a
      wide sample, including the string-to-number edge cases (whitespace, signs, hex, octal, binary,
      infinity, empty, trailing junk) and the full double range for the integer conversions
- [ ] Test `commonTest/ConversionTest`: the same edge cases on every target
- [ ] jvmTest green

#### P3.2: Contracts

- [ ] Port the interfaces the rest of the runtime is written against: `Scriptable.kt` (292),
      `SymbolScriptable.kt`, `Callable.kt`, `Constructable.kt`, `Function.kt`, `Evaluator.kt`,
      `Script.kt`, `RefCallable.kt`, `IdFunctionCall.kt`, `Ref.kt`, `ConstProperties.kt`
      (moved here from P2), `Wrapper.kt` if the pin has one.
- [ ] These are declarations, so the check is that they compile and that their member sets match
      upstream. Test `jvmTest/ContractParityTest` compares each interface's method names and arity
      against the upstream class by reflection.
- [ ] jvmTest green

#### P3.3: The property machinery

- [ ] Port `Slot.kt` (145), `AccessorSlot.kt` (289), `BuiltInSlot.kt` (201), `LambdaSlot.kt` (74),
      `SlotMap.kt` (94), `EmbeddedSlotMap.kt` (318), `HashSlotMap.kt` (100), `SlotMapOwner.kt` (375).
      The thread-safe slot map variant is dropped under D-3.
- [ ] Port `ScriptableObject.kt` (3345), the base of every JavaScript object.
- [ ] Test `jvmTest/SlotMapOracleTest`: drive both the upstream slot maps and the ported ones through
      the same long random sequence of put, get, remove, iterate and compaction, then compare the
      resulting key order and contents. Slot maps switch representation as they grow, so the
      sequence has to cross those thresholds.
- [ ] Test `jvmTest/ScriptableObjectOracleTest`: define, redefine, delete, enumerate and seal
      properties on both sides and compare the observable results, including attribute handling and
      prototype chain lookup
- [ ] jvmTest green

#### P3.4: Function objects

- [ ] Port `BaseFunction.kt` (828), `NativeFunction.kt` (128), `Arguments.kt` (369),
      `NativeCall.kt` (154), `BoundFunction.kt` (113), `LambdaFunction.kt` (124),
      `LambdaConstructor.kt` (473), `IdFunctionObject.kt` (133), `IdScriptableObject.kt` (1019).
- [ ] Structural tests only at this point: the eval oracle in P3.9 is what really exercises them.
- [ ] jvmTest green

#### P3.5: Context

- [ ] Port `Context.kt` (2865) properly, replacing the phase 0 shell, and `ContextFactory.kt` (532)
      in its minimal form. Cut: class shutters, wrap factories, security controllers, the
      `ClassLoader` and reflection paths, and the debugger surface beyond what the interpreter needs.
- [ ] `Context.enter`/`exit` use a plain singleton slot rather than a `ThreadLocal`, under D-3.
- [ ] This retires D-18: `Context.reportError` can route through a real error reporter again, and
      `getSourcePositionFromStack` becomes implementable once the interpreter lands in P3.7.
- [ ] jvmTest green

#### P3.6: Code generation

Moved here from P2, because `CodeGenerator` is generic over `ScriptOrFn<T>` and returns a
`JSDescriptor<T>` whose root type `JSFunction` extends `BaseFunction`, and because it reads
`Interpreter`'s exception-table constants directly.

- [ ] Port the descriptor layer: `ScriptOrFn.kt` (21), `JSCode.kt` (31), `JSDescriptor.kt` (404),
      `JSFunction.kt` (226), `CodeGenUtils.kt`, and whatever `JSCodeExec`/`JSCodeResume` turn out to
      be at the pin.
- [ ] Port `InterpreterData.kt` (177) and `CodeGenerator.kt` (1971).
- [ ] Test `jvmTest/IcodeOracleTest`: generate icode for the whole corpus on both sides and compare
      the byte arrays, the string and number pools, the exception tables and the nested-function
      tables. This is the phase 2 promise finally kept, and it is a strong check: the icode array is
      exactly what the interpreter executes.
- [ ] jvmTest green

#### P3.7: Interpreter

- [ ] Port `Interpreter.kt` (5106): the call-frame machine, `ContinuationJump`, the generator
      resumption path and the microtask hooks.
- [ ] jvmTest green

#### P3.8: Natives wave 1

- [ ] Port the number formatting that phase 2 deferred: `dtoa/DecimalFormatter.kt` needs
      `BigDecimal`, so `NativeNumber.toFixed`, `toExponential` and `toPrecision` either wait for P5
      or get a hand-written decimal path; decide and record it in the ledger when the file is reached.
- [ ] Port the errors: `RhinoException.kt` (387) properly, `EcmaError.kt`, `EvaluatorException.kt`,
      `NativeError.kt` (459), `ScriptStackElement.kt`, `JavaScriptException.kt`.
- [ ] Port `TopLevel.kt` (266), `LazilyLoadedCtor.kt` (174), `NativeObject.kt` (1085, replacing the
      phase 2 stub), `NativeFunction` prototype wiring, `NativeGlobal.kt` (761), `NativeArray.kt`
      (2573), `NativeString.kt` (1495), `NativeNumber.kt` (310), `NativeBoolean.kt` (90),
      `NativeMath.kt` (621), `NativeJSON.kt` (601) with `json/JsonParser.kt` (414).
- [ ] jvmTest green

#### P3.9: Eval oracle

- [ ] Build `kitejs/src/jvmTest/resources/eval/*.js`, a corpus of scripts whose last expression is
      the result: arithmetic, string operations, object and array manipulation, function calls and
      closures, control flow, exceptions, prototype chains, coercion corners, and the ECMAScript
      edge cases that separate a correct engine from a plausible one
- [ ] Test `jvmTest/EvalOracleTest`: run each script through `org.mozilla.javascript.Context` and
      through the port, then compare the string form of the result and the name and message of any
      thrown error
- [ ] Test `commonTest/EvalSmokeTest`: a small slice of the same corpus, so evaluation is proven to
      work on every target rather than only on the JVM
- [ ] Cross-target check, update `PORTING_STATUS.md`, commit

**Done when:** the eval corpus produces identical results and identical errors on both engines, on
the JVM, and the smoke slice passes on every other target.

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
