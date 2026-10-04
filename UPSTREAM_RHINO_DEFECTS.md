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

## Maths accuracy

### log2, acosh, asinh and atanh are formulas on top of log (D-73)

`Math.log2(8)` is `2.9999999999999996`, because it is computed as `log(x) * LOG2E`, and
`Math.acosh(1e300)` is `Infinity`, because `x * x` overflows inside the formula. fdlibm's and
FreeBSD's routines, which V8 uses, are exact for powers of two and do not overflow.

- Where: `NativeMath`.
- Test: `EvalOracleTest.mathIsFdlibmAsV8HasIt`; `built-ins/Math/log2/log2-basicTests.js`.

## Shared with KiteJS, not fixed yet

### A function declaration as the body of a loop or a labelled if is accepted

`while (0) function f() {}`, `for (;0;) l: function f() {}`, `for (var x of []) l: function f() {}`
and `if (1) l: function f() {}` are early SyntaxErrors (ECMAScript 2015, 13.7.1.1, 13.6.1 and
B.3.4, which allows only a plain function declaration as the body of an `if`). Neither engine
reports them; V8 rejects all four.

- Where: `Parser`, the statement parsing of loop and `if` bodies.
