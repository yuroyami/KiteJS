# Defects in upstream Rhino

KiteJS is a port of Mozilla Rhino 1.9.1, and porting it line by line turns up places where Rhino
itself is wrong. This page collects them so they can go back upstream as issues or pull requests.
Each entry was reproduced against the `org.mozilla:rhino:1.9.1` jar in interpreted mode at
`VERSION_ES6`, which is what the oracle tests run, and names the ledger entry in
[KITEJS_IMPL.md](KITEJS_IMPL.md) where the port's fix is described. The Java class named under
"Where" is the upstream class the KiteJS file of the same name was ported from.

Entries are added as they are found and marked once they are reported or fixed upstream.

## Spec conformance

### Spreading a value that is not iterable does not throw (D-70)

`[...5]` has nine elements, `[...{ a: 1 }]` has one and `[...null]` is empty; every browser throws
a TypeError, as ECMAScript 2015, 12.2.5.2 and 12.3.6.1 ask through GetIterator. Rhino spreads a
primitive or a plain object by its own ids and treats null and undefined as nothing.

- Where: `NewLiteralStorage.spreadArray` and the argument spread in `Interpreter`.
- Test: `EvalOracleTest.spreadingSomethingThatIsNotIterableThrows`.

### Assigning to a const is ignored outside strict mode (D-71)

`const c = 1; c = 2` leaves `c` at 1 without an error in sloppy code, and in strict code run by
`eval`. ECMAScript 2015, 8.1.1.1.5 (SetMutableBinding) throws a TypeError in any mode.

- Where: `ScriptRuntime.setName`, `Interpreter` (`SETVAR` and the increment and decrement ops).
- Test: `EvalOracleTest.assigningToAConstThrows`; six test262 destructuring files.

### Logical assignment to a name always writes (D-71)

`a ||= b` is lowered to `a = a || b`, so it writes even when it short circuits: a setter on the
global runs three times for `x ||= 2; x &&= 0; x ??= 3` where it should run once. ES2021,
13.15.2 writes only when the left side does not short circuit.

- Where: `IRFactory`, the lowering of `Token.ASSIGN_OR`, `ASSIGN_AND` and `ASSIGN_NULLISH` on a
  name.
- Test: `EvalOracleTest.logicalAssignmentToANameWritesOnlyWhenItMust`.

### A for head rejects const, and a let head shares one binding (D-72)

`for (const x of xs)`, `for (const k in o)` and `for (const i = 0; ...)` are syntax errors. A
`let` head makes one binding for the whole loop, so closures made in the body all see the last
value, where ECMAScript 2015, 13.7.5.13 gives each iteration a binding of its own.

- Where: `Parser.forLoopInit`, `IRFactory.createForIn` and `createFor`.
- Test: `ForConstTest`; five test262 files under `language/statements/for-in`, `for-of` and
  `language/block-scope`.

### A const in a block is one binding for the whole function (D-74)

`for (var i = 0; i < 3; i++) { const k = i * 2; r.push(k) }` pushes 0 three times: a const is
declared once per function and only its first initialization takes, so a const in a loop body
keeps the first pass's value. ECMAScript 2015, 13.3.1 scopes it to its block and binds it each
time the declaration runs. test262's `nativeFunctionMatcher.js` harness declares consts in its
loops, so every test that includes it fails upstream for this reason alone.

- Where: `Parser.defineSymbol` and the const lowering in `NodeTransformer` and `CodeGenerator`.
- Test: `BlockConstTest`; three test262 files.

### A property descriptor of a lazily loaded global leaks the placeholder (D-75)

`typeof Object.getOwnPropertyDescriptor(globalThis, 'JSON').value` throws "Invalid JavaScript
value of type org.mozilla.javascript.LazilyLoadedCtor" until something has read `JSON`. The same
goes for `Math`, `RegExp`, the collections, `Promise`, `Proxy`, `Reflect`, `BigInt`, the typed
arrays, `ArrayBuffer`, `DataView` and `Array.prototype[Symbol.unscopables]`.

- Where: `LazyLoadSlot`, which inherits `Slot.getPropertyDescriptor` and so reads the raw value
  without initializing it the way `getValue` does.
- Test: `EvalOracleTest.aDescriptorOfALazyGlobalHoldsTheBuiltIn`.

### A function declaration as the body of a loop or an if is accepted (D-76)

`while (0) function f() {}`, `for (;0;) l: function f() {}`, `for (var x of []) l: function f() {}`
and `if (1) l: function f() {}` are early SyntaxErrors (ECMAScript 2015, 13.7.1.1 and 13.6.1),
and so are `l: function* g() {}`, `if (1) function* g() {}` and, in strict code,
`if (1) function f() {}` and `l: function f() {}`. Annex B.3.2 and B.3.4 allow only a plain
function, labelled or as the body of an `if`, in sloppy code. Rhino accepts all of them; V8
rejects all of them. 46 test262 files fail for this.

- Where: `Parser`, the bodies of `ifStatement`, `whileLoop`, `doLoop`, `forLoop` and
  `withStatement`, and the labelled statement in `nameOrLabel`.
- Test: `EvalOracleTest.aFunctionDeclarationIsNotTheBodyOfALoop`.

### A strict write to an accessor with set: undefined is dropped (D-78)

`Object.defineProperty(o, 'z', { get: f, set: undefined })` gives an accessor with no setter,
the same as leaving `set` out (ECMAScript 2015, 6.2.4.5), so a write to `o.z` in strict code is a
TypeError (9.1.9.1 and 6.2.3.2), on `o` and on any object that inherits `z`. Rhino keeps the
explicit `undefined` as a setter, runs it as nothing and returns, so the strict write is dropped
without an error; with `set` left out it throws. Web IDL bindings define read-only attributes
with `set: undefined`. 18 strict-only test262 files fail for this, among them
`language/expressions/assignment/11.13.1-2-s.js` and the `lgcl-*-assignment-operator-no-set`
files.

- Where: `AccessorSlot.setValue`, which asks only whether a setter object is there, and its
  `FunctionSetter`, which ignores a target that is not a function.
- Test: `EvalOracleTest.aStrictWriteToAnAccessorWithSetUndefinedThrows`.

### A generator function in a plain scope has a null prototype (D-79)

ECMAScript 2015, 14.4.13 and 25.2.3 give a generator function %GeneratorFunction.prototype% as
its prototype, whose own prototype is `Function.prototype`. Rhino builds that object and its
constructor only when the scope is a `TopLevel`, so in the scope `initStandardObjects()` returns
a generator function, however it is written, has a null prototype: `call`, `apply` and `bind`
are missing, `g instanceof Function` is false, and code that calls a generator through `call`,
as an iterable helper does, throws `Cannot find function call`. In a `TopLevel` the constructor
is put on the global as `__GeneratorFunction`, writable, enumerable and configurable, so
`Object.keys(globalThis)` and a `for`-`in` over the global list a name no script defined.

- Where: `ScriptRuntime.initSafeStandardObjects`, which builds the constructor through
  `TopLevel.cacheBuiltins` alone, and `BaseFunction.initAsGeneratorFunction`, which puts it on
  the global.
- Test: `EvalOracleTest.aGeneratorFunctionInheritsFromTheGeneratorFunctionPrototype`.

### Strict mode at run time follows the caller, not the code that runs (D-80)

ECMAScript 2015, 10.2.1 makes strictness a property of code: a failed write in strict code throws
a TypeError, and the same write in sloppy code does nothing, whoever called the code. Rhino asks
the current activation, which the interpreter pushes only for a function that needs one, and the
top call. So a strict callback without an activation runs as its caller does: called by a sloppy
function, its write to a frozen object, to an accessor without a setter, to a non-extensible
object, or its `delete` of a permanent property, passes quietly, and a sloppy function called by
strict code throws on the same writes. The same answer decides two other things it should not: an
`arguments` object is mapped or not by the mode of the code reading it rather than of its own
function, and an indirect `eval` called from strict code compiles strict code, where the spec
makes indirect eval code strict only by its own directive. Strict eval code from sloppy code runs
as sloppy.

- Where: `Context.isStrictMode`, which reads `currentActivationCall` and `isTopLevelStrict`;
  `Arguments.sharedWithActivation`, which asks the context; and `NativeGlobal.js_eval`, which
  compiles through `evalSpecial` with the caller's mode.
- Test: `EvalOracleTest.strictModeIsTheModeOfTheCodeThatRuns`.
### A `then` getter that throws leaves the promise pending for good (D-81)

`new Promise(function (r) { r({ get then() { throw 'boom' } }) })` never settles, and the call to
`r` throws `'boom'` back at the executor. The same goes for a reaction that returns such an
object: the promise `then` made for it stays pending. ECMAScript 2015, 25.4.1.3.2 steps 8 and 9
reject the promise with what was thrown, and the resolve function returns normally; V8 does so.

- Where: `NativePromise.ResolvingFunctions.resolve`, which marks the promise resolved and then
  reads `then` with nothing around it to catch the error.
- Test: `EvalOracleTest.aThrowingThenGetterRejectsThePromise`.

### A getOwnPropertyDescriptor trap answering undefined crashes (D-50)

A Proxy whose `getOwnPropertyDescriptor` trap returns `undefined` for a property the target lacks
throws a `NullPointerException` from the engine; the spec allows that answer and every engine
returns `undefined`.

- Where: `NativeProxy`, the trap's result check, which reads the target's descriptor without a
  null check.
- Test: `EvalOracleTest.getOwnPropertyDescriptorTrapMayReturnUndefined`; three test262 files.

### The construct trap receives a Java array (D-51)

The `construct` trap's argument list is the raw `Object[]`, which only Java interop turns into
something script can use; the `apply` trap already builds a real array.

- Where: `NativeProxy`, the construct path.
- Test: `EvalOracleTest.constructTrapGetsARealArray`.

### BigUint64Array reads back the wrong value for the top half (D-53)

Writing `-1n` reads back as `-4294967297n` instead of 2^64 - 1. The conversion masks with
`0xffffffff`, an int literal that sign-extends when promoted to long, so every element with its
top bit set is wrong.

- Where: `NativeBigUint64Array`, the read conversion.
- Test: `EvalOracleTest.bigUint64ArrayReadsBackWhatWasWritten`; three test262 files.

### Date.prototype[Symbol.toPrimitive] is writable (D-56)

The spec makes it non-writable, and test262's `prop-desc.js` for it fails.

- Where: `NativeDate`, where the property is defined.

### Identifiers follow Java's rules, not ID_Start (D-57)

The tokenizer asks `Character.isJavaIdentifierStart`, so characters the spec's `ID_Start` allows
are rejected. Seven `language/identifiers/start-unicode-*` test262 files fail.

- Where: `TokenStream`, the identifier start check.

### Proxy construct arguments and BigInt typed array conversions (D-58)

Eight more test262 files that upstream fails and the port passes, listed in
`Test262ParityTest`: `Proxy/construct/call-parameters.js` and `arguments-realm.js`, the
`bigint-tobiguint64.js` trio (the D-53 mask again), `TypedArrayConstructors/from/nan-conversion.js`
and `new-instance-from-sparse-array.js`, and a destructuring evaluation-order test.

### Typed array views are big-endian (D-58)

Views read and write in big-endian order where every other engine uses the platform's order,
which is little-endian everywhere that matters, so a test that copies between two views of
different types over one buffer fails.

- Where: the typed array element conversions, which go through `ByteIo` in big-endian order.

## Runtime defects

### A write through a Delegator overflows the stack (D-65)

Every write through a `Delegator`, from a script or from a host call to `put`, ends in a
`StackOverflowError`: the write is forwarded with the wrapper still named as the receiver, the
delegee does not own the property and bounces it back to the receiver, and so on.

- Where: `Delegator.put`, which should name the delegee as the receiver when the write was aimed
  at the wrapper.

### Two message keys are missing from the properties file (D-49)

`msg.missing.argument` and `msg.typed.array.abstract.ctor` are used by the code but never
defined, so asking for either raises a missing-resource error instead of a message.

- Where: `Messages.properties`.
- Test: `MessageParityTest` pins that upstream still lacks them.

### The instruction observer never hears of code that only calls (D-77)

`ContextFactory.observeInstructionCount` is asked only at branches and thrown exceptions; a call
adds `INVOCATION_COST` to the count without checking it. A Promise reaction that queues the next
one, `function spin() { Promise.resolve().then(spin) }`, therefore runs forever whatever the
threshold, and so does any other work made of calls without branches, such as a straight-line
callback handed to `forEach` over a large array. `Context.processMicrotasks` drains the queue
without a checkpoint either.

- Where: `Interpreter`, the call ops (`doCall`, `doCallSpecial`, `doNew` and the spread forms),
  `Interpreter.interpret` for calls from native code, and `Context.processMicrotasks`.

## Maths accuracy

### log2, acosh, asinh and atanh are formulas on top of log (D-73)

`Math.log2(8)` is `2.9999999999999996`, because it is computed as `log(x) * LOG2E`, and
`Math.acosh(1e300)` is `Infinity`, because `x * x` overflows inside the formula. fdlibm's and
FreeBSD's routines, which V8 uses, are exact for powers of two and do not overflow.

- Where: `NativeMath`.
- Test: `EvalOracleTest.mathIsFdlibmAsV8HasIt`; `built-ins/Math/log2/log2-basicTests.js`.
