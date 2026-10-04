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

### The ES2023 typed array methods skip receiver validation (D-83)

`at`, `toReversed`, `toSorted` and `with` never call ValidateTypedArray. On a view whose buffer
was transferred, `at` answers undefined, `toReversed` and `with` return copies, and `toSorted`
sorts `undefined` values with a cast to `Number`, so a host `ClassCastException` escapes the
script's `try`. `with` also converts its replacement with ToNumber whatever the view, so a bigint
view rejects `3n`, and it defaults a missing replacement to 0 where the spec has undefined, which
is NaN on a float view. Every copy is made through the global binding of the constructor, so a
script that reassigns `Uint8Array` or `ArrayBuffer` changes what these methods return.

- Where: `NativeTypedArrayView.js_at`, `js_toReversed`, `js_toSorted` and `js_with`, which make
  their copy with `cx.newObject(scope, getClassName(), ...)`, and `ScriptRuntime.getExistingCtor`
  wherever a built-in looks up an intrinsic.
- Test: `EvalOracleTest.theCopyingTypedArrayMethodsValidateTheirReceiver`.

### ToBigInt converts a Number (D-84)

`ScriptRuntime.toBigInt` converts an integral Number to a bigint, which is what `BigInt()` does
but not what ToBigInt does: the spec throws a TypeError for every Number. So
`a[0] = 1` on a `BigInt64Array`, `fill(3)` and `BigInt.asIntN(8, 1)` all succeed where V8 throws.

- Where: `ScriptRuntime.toBigInt`; the Number branch belongs in `NativeBigInt`'s constructor.
- Test: `EvalOracleTest.aNumberIsNotABigInt`.

### A comparator answering NaN reorders a stable sort (D-85)

SortCompare treats a comparator result of NaN as +0, but the comparator built for `sort` maps it
through `Double.compare(d, 0.0)`, which makes NaN greater. With a consistent comparator that
answers NaN for equal elements, `(x, y) => x.k === y.k ? NaN : x.k - y.k`, forty elements are
enough to come out in a different order from V8's, breaking the stability the spec requires.

- Where: `ArrayLikeAbstractOperations.getSortComparatorFromArguments`.
- Test: `EvalOracleTest.aComparatorAnsweringNaNMeansEqual`.

### TypedArray set with no source crashes, and set takes only objects (D-86)

`set()` reads `args[0]` without checking the length, so a host `ArrayIndexOutOfBoundsException`
escapes the script's `try`. A source that is not a typed array has to be an object, where the spec
puts it through ToObject: `set('123')` is a TypeError instead of writing 1, 2 and 3, and `set(5)`
is a TypeError instead of writing nothing. The elements are then read with `get` on the source
itself, so an element it inherits from its prototype is skipped.

- Where: `NativeTypedArrayView.js_set` (the prototype method) and `setRange`.
- Test: `EvalOracleTest.typedArraySetTakesAnyArrayLike`.

### ArrayBuffer slice crashes on a detached source, and transfer goes through species (D-87)

`slice` checks that the buffer is attached only on entry, so a `valueOf` on `start` or `end`, a
`constructor` or species getter, or the species constructor itself, any of which may detach it,
leaves the copy dereferencing a null array, and a host `NullPointerException` escapes the script's
`try`. `transfer` and `transferToFixedLength` build their copy through the species constructor,
where ArrayBufferCopyAndDetach allocates a plain `%ArrayBuffer%`: a species answering the source
makes `transfer` return the very buffer it has just detached, a `constructor` getter that throws
blocks a transfer that should succeed, and a species constructor that calls `transfer` recurses
until a host `StackOverflowError`. The detached check also comes before the new length is
converted instead of after, a small negative length such as `-0.5` is a RangeError where ToIndex
makes it 0, and the default constructor of both comes from `TopLevel.getBuiltinCtor`, which in any
scope that is not a `TopLevel` is whatever the global `ArrayBuffer` holds.

- Where: `NativeArrayBuffer.js_slice`, `js_transfer`, `js_transferToFixedLength` and
  `validateNewByteLength`.
- Test: `EvalOracleTest.arrayBufferSliceRechecksAndTransferSkipsSpecies`.

### Typed arrays are not integer-indexed exotic objects (D-88)

`NativeTypedArrayView` handles only property names that convert to a valid element index. Any
other canonical numeric name, such as `'-1'`, `'1.5'`, `'NaN'` or `'-0'`, falls through to the
ordinary path, so a read finds the prototype's property of that name, `in` answers from the
prototype, and a write skips the value conversion, so a BigInt view silently accepts a Number and
a `valueOf` never runs. `defineOwnProperty` narrows a fractional index to an int, so
`Object.defineProperty(a, '0.5', {value: 9})` overwrites element 0, and defining `'NaN'` reports
success. There is no `getOwnPropertyDescriptor` for elements, so `Object.getOwnPropertyDescriptor`
answers undefined for a real element, `getIds()` returns only the indices while the compound
`getIds` used by `Reflect.ownKeys` returns only the ordinary keys, and a valid element deleted in
strict code reports success.

- Where: `NativeTypedArrayView.get`, `has`, `put`, `delete`, `getIds` and `defineOwnProperty`.
- Test: `EvalOracleTest.typedArraysAreIntegerIndexedExoticObjects`.

### A refused definition is ignored, and seal and freeze send full descriptors (D-88)

`Object.defineProperty` and `Object.defineProperties` drop the boolean that `defineOwnProperty`
returns, so a proxy `defineProperty` trap answering false, or an exotic object refusing a
definition, leaves the script believing it succeeded, and the key is converted after the
descriptor instead of before. `setIntegrityLevel` builds a complete descriptor for every key and
calls the protected four-argument `defineOwnProperty`, so a proxy's trap is skipped altogether,
missing fields take defaults the spec never asks for, and a trap answering false does not throw.
When such a descriptor turns a computed (`LambdaSlot`) property into a plain one, `setSlotValue`
stores the descriptor's absent value as null, so
`Object.defineProperty(Error, 'stackTraceLimit', {writable: false})` loses the limit.

- Where: `NativeObject.js_defineProperty`, `ScriptableObject.defineOwnProperties`,
  `AbstractEcmaObjectOperations.setIntegrityLevel` and `ScriptableObject.setSlotValue`.
- Test: `EvalOracleTest.typedArraysAreIntegerIndexedExoticObjects`.

### A non-configurable property rejects descriptors that leave fields out (D-88)

`checkPropertyChangeForSlot` compares `enumerable` and `value` whether or not the descriptor has
them, reading a missing one as false or undefined. On an enumerable non-configurable property,
`Object.defineProperty(o, 'x', {value: 1})` with the same value, `{}`, or `{set: undefined}` on an
accessor all throw where ValidateAndApplyPropertyDescriptor accepts them. The descriptor reader
`DescriptorInfo(ScriptableObject)` is not ToPropertyDescriptor either: it reads each field with a
bare get, without HasProperty, so a proxy whose `get` trap answers undefined for a missing `get`
reads as data and accessor at once and throws; it reads the fields in its own order; and it keeps
the raw values of the three flags, so `{enumerable: 1}` fails `isCompatiblePropertyDescriptor`
against `true`, which makes a proxy's `defineProperty` invariant check throw for a valid answer.

- Where: `ScriptableObject.checkPropertyChangeForSlot` and `ScriptableObject.DescriptorInfo`.
- Test: `IntegrityLevelTest.partial_descriptors_compare_only_the_fields_they_have` (common).

### Arrays keep growing after they are frozen or their length is read-only (D-88)

The dense fast path of `push` and `unshift` appends without asking whether the array may grow, and
`Object.preventExtensions` leaves a dense array dense, so `push` still appends to it. An empty
frozen array only escaped by accident, because freezing redefined its length with a value.
`NativeArray.put` grows the length after `super.put` even when the write was refused, so writing
past the end of a non-extensible array changes its length, and a read-only length does not stop an
index past it being created. `defineOwnProperty` grows the length before defining the element,
whatever the length's attributes and whether or not the definition then fails, so
`Object.defineProperty(a, 3, {value: 4})` succeeds on a three element array with a read-only
length.

- Where: `NativeArray.put`, `NativeArray.defineOwnProperty`, `js_push` and `js_unshift`.
- Test: `IntegrityLevelTest.arrays_stop_growing_once_they_may_not` (common).

### Reflect.set writes onto the receiver and always answers true (D-89)

`Reflect.set` puts the value straight onto the receiver with `Scriptable.put` and returns true
whatever happened, so it answers true for a frozen object, a non-extensible one, an accessor
without a setter, and an inherited read-only property, which it even shadows with a new own
property (`Reflect.set(Object.create(Object.freeze({x: 1})), 'x', 2)` creates `x`). Writing to the
receiver directly also skips an inherited setter, which `Reflect.set(Object.create({set x(v)
{...}}), 'x', 5)` never calls, and a proxy target's `set` trap, which is not called at all when a
separate receiver is given. With a separate receiver, a non-configurable but writable property
refuses the write, where OrdinarySet only refuses a read-only one. A primitive or undefined
receiver throws a TypeError instead of answering false, `Reflect.set(o, 'x')` with no value throws
a host `ArrayIndexOutOfBoundsException`, and strict calling code turns a refused write into a
TypeError.

- Where: `NativeReflect.set`.
- Test: `EvalOracleTest.reflectRunsTheTargetsInternalMethodsWithTheReceiver`;
  `ReflectTest` (common).

### Reflect.get ignores its receiver and indexes numeric keys (D-89)

`Reflect.get` never passes its third argument on, so a getter runs with the target as `this`, and
`Reflect.get(Map.prototype, 'size', map)` throws. A numeric key goes through ToIndex, so
`Reflect.get(o, -1)` is a RangeError and `Reflect.get(o, 0.5)` reads `o[0]`, while the string `'1'`
on an array is not treated as an index and reads as missing. A key whose `Symbol.toPrimitive`
returns a symbol is a TypeError. `Reflect.get`, `Reflect.has`, `Reflect.deleteProperty` and
`Reflect.getOwnPropertyDescriptor` treat an omitted key as no property at all instead of the string
`"undefined"`, and `Reflect.has(o, 1)` is false for an object with the property `'1'`.

- Where: `NativeReflect.get`, `has`, `deleteProperty` and `getOwnPropertyDescriptor`.
- Test: `EvalOracleTest.reflectRunsTheTargetsInternalMethodsWithTheReceiver`;
  `ReflectTest` (common).

### Reflect.deleteProperty deletes along the prototype chain (D-89)

`Reflect.deleteProperty` uses `ScriptableObject.deleteProperty`, which finds the object in the
chain that holds the property and deletes it there, so `Reflect.deleteProperty({}, 'toString')`
removes `Object.prototype.toString`. A proxy's `deleteProperty` trap answering false is reported as
a success, and the `delete` operator in strict code ignores that false too.

- Where: `NativeReflect.deleteProperty`, `NativeProxy.delete`.
- Test: `EvalOracleTest.reflectRunsTheTargetsInternalMethodsWithTheReceiver`.

### Reflect.construct and Reflect.defineProperty skip required errors (D-89)

`Reflect.construct(F)` with no argument list, or with `undefined` or `null`, constructs with no
arguments, where CreateListFromArrayLike requires an object. `Reflect.defineProperty` catches every
`EcmaError` and answers false, so a TypeError the engine raises, such as a proxy invariant
violation or `null.x` inside a trap, disappears, and it accepts a descriptor whose `get` is not
callable.

- Where: `NativeReflect.construct` and `defineProperty`.
- Test: `EvalOracleTest.reflectRunsTheTargetsInternalMethodsWithTheReceiver`;
  `ReflectTest` (common).

### Proxy traps get the wrong receiver and false answers are ignored (D-89)

A proxy's `get` trap is always handed the proxy as receiver, even when the read started on an
object that inherits from it, and its `set` trap gets the proxy too, never the receiver the
assignment started from; an assignment to an object whose prototype is a proxy with a `set` trap
never reaches the trap and creates an own property instead. A `set` trap answering false does not
throw in strict code. A proxy without traps reads its target with the target as `this`, so a
getter on the target sees the target rather than the proxy. Because `getBase` asks `has` on every
object in the chain, an assignment that reaches a proxy calls its `has` trap, and a missed read
goes on to the proxy's own prototype, calling a `getPrototypeOf` trap that the spec never reaches. The `delete` operator asks `has` after deleting to learn the result, which calls the
`has` trap again. The message for a `deleteProperty` invariant violation prints the literal text
`' + name + '` in place of the key.

- Where: `NativeProxy.get`, `put`, `delete`, `checkDeleteInvariants`,
  `ScriptableObject.getBase` and `ScriptRuntime.deleteObjectElem`.
- Test: `EvalOracleTest.reflectRunsTheTargetsInternalMethodsWithTheReceiver`;
  `ReflectTest.proxy_traps_receive_the_receiver_and_their_answer_counts` (common).

### with ignores @@unscopables and finds bindings by reading them (D-89)

The object environment of a `with` statement decides whether it holds a name by reading the
property and comparing with NOT_FOUND, never asking HasProperty or looking at @@unscopables. So
`with ([]) { keys }` finds `Array.prototype.keys` instead of an outer `keys`, an object whose
@@unscopables lists `x` still captures reads, writes and `x++`, a proxy's `has` trap is never
consulted (one answering false still binds the name if its `get` trap answers anything). A strict assignment to a binding that
the right-hand side deleted recreates the property instead of throwing a ReferenceError
(`with (scope) { (function () { 'use strict'; x = (delete scope.x, 2) })() }`).

- Where: `ScriptRuntime.nameOrFunction`, `bind`, `typeofName`, `nameIncrDecr` and
  `strictSetName`.
- Test: `WithEnvironmentTest` (common); test262 `language/statements/with`.

### copyWithin takes undefined for a hole (D-89)

The generic loop of `Array.prototype.copyWithin` reads each source element and deletes the target
when the value is NOT_FOUND or undefined, so a present `undefined` is deleted rather than copied
(`Array.prototype.copyWithin.call({0: undefined, 1: 1, length: 2}, 1, 0)` leaves no `1`), and it
never asks HasProperty, so a proxy's `has` trap is not called. Dense arrays take a fast path that
copies correctly.

- Where: `NativeArray.js_copyWithin`.
- Test: `ReflectTest.copy_within_asks_has_property` (common).

### RegExp.prototype flags and source are data properties (D-89)

`source`, `flags`, `global`, `ignoreCase`, `multiline`, `sticky`, `unicode` and `dotAll` are own
data properties of every RegExp instance and of `RegExp.prototype`, not accessors on the prototype
as ECMAScript 2015, 21.2.5 defines them, so `Object.getOwnPropertyNames(/a/)` lists all of them
where browsers list only `lastIndex`. `Object.getOwnPropertyDescriptor(RegExp.prototype,
'source')` has a value instead of a `get`, and `Reflect.get(RegExp.prototype, 'source', /xy/)`
reads `''` where every browser reads `'xy'`. KiteJS keeps upstream's structure for now.

- Where: `NativeRegExp` (the instance ids `Id_source`, `Id_global` and the rest).

### The own keys of RegExp.prototype and Date.prototype are strings, not symbols (D-90)

ECMAScript 2015, 9.1.12 lists the keys of an object's properties, so a property named by a symbol
is listed as that symbol. For the symbol methods of `RegExp.prototype` (`Symbol.match`,
`Symbol.matchAll`, `Symbol.replace`, `Symbol.search`, `Symbol.split`) and
`Date.prototype[Symbol.toPrimitive]`, Rhino lists the string `"Symbol(Symbol.match)"` and so on:
`Reflect.ownKeys` answers keys that name no property, so a descriptor read of each answers
undefined, `Object.getOwnPropertySymbols` leaves the methods out, and `Object.freeze` of either
prototype ends in a `NullPointerException`. A prototype whose symbol keys sit in its slot map,
such as `Array.prototype`, is right.

A definition of one of those methods misses it too: `IdScriptableObject.defineOwnProperty`
redefines a prototype id in place only for a string key, and sends a symbol to the slot map, which
does not hold it. Once `Object.preventExtensions` ran, `Object.defineProperty(RegExp.prototype,
Symbol.split, { writable: false })` throws "Cannot add properties to this object because extensible
is false", and before it ran the definition adds a second property over the method.

- Where: `IdScriptableObject.PrototypeValues.getNames`, which adds `name.toString()` for a key
  that is a `Symbol`, and `IdScriptableObject.defineOwnProperty`, which looks up a prototype id
  for a `CharSequence` key only.
- Test: `EvalOracleTest.theOwnKeysOfAPrototypeNameItsSymbolMethodsBySymbols`.
### Reflect.construct with a newTarget throws or gives the wrong prototype (D-91)

`Reflect.construct(Map, [], Object)` throws "The constructor for Map may not be invoked as a
function", and so do Set, Promise, the typed arrays and every other constructor built on
`LambdaConstructor`. Where it does not throw, the object does not always take newTarget's
`prototype`: `Reflect.construct(Array, [], N)`, `Reflect.construct(RegExp, ['a'], N)` and
`Reflect.construct(Object, [], N)` are not instances of `N`. A proxy's `construct` trap receives the
proxy as newTarget even when `Reflect.construct(P, [], Array)` names another one. ECMAScript 2015,
9.1.13 (OrdinaryCreateFromConstructor) and 9.1.14 (GetPrototypeFromConstructor) take the prototype
from newTarget, or from the same intrinsic in newTarget's realm when its `prototype` is not an
object.

- Where: `NativeReflect.construct`, `BaseFunction`, `LambdaConstructor`, `NativeProxy`.
- Test: `EvalOracleTest.constructProxiesEnumerationAndJsonFollowTheInternalMethods`;
  `ConstructAndProxyInternalsTest` (common).

### Arrows, methods and generators are accepted as constructors (D-91)

`Reflect.construct(function () {}, [], () => {})` succeeds, and so does a newTarget that is a
method, a getter or a generator function; IsConstructor (ECMAScript 2015, 7.2.4) is false for all
of them, so each is a TypeError. Rhino treats every `Function` as a constructor.

- Where: `NativeReflect.construct`, `AbstractEcmaObjectOperations` (speciesConstructor).
- Test: `EvalOracleTest.constructProxiesEnumerationAndJsonFollowTheInternalMethods`;
  `ConstructAndProxyInternalsTest` (common).

### A proxy's getOwnPropertyDescriptor and ownKeys answers are not checked (D-91)

A getOwnPropertyDescriptor trap's answer is read for `value` and the three flags only, so an
accessor it reports comes back as a data property holding undefined. The trap may report a
non-configurable property the target does not have, or a descriptor whose `get` is not callable,
without an error; ECMAScript 2015, 9.5.5 runs the
answer through ToPropertyDescriptor and IsCompatiblePropertyDescriptor and throws a TypeError for
both. The ownKeys checks compare the target's keys as they are stored, so the index `0` of a frozen
array never matches the `'0'` the trap must return and
`Reflect.ownKeys(new Proxy(Object.freeze([1]), { ownKeys: () => ['length', '0'] }))` throws "proxy
can't skip a non-configurable property '0'". For a non-extensible target the checks pass and then
the target's own keys are returned in place of the trap's, so the trap's order is lost.

- Where: `NativeProxy.getOwnPropertyDescriptor` and `getIds`.
- Test: `EvalOracleTest.constructProxiesEnumerationAndJsonFollowTheInternalMethods`;
  `ConstructAndProxyInternalsTest` (common).

### Enumerating a proxy skips its getOwnPropertyDescriptor trap (D-91)

`Object.keys` of a proxy lists every key its ownKeys trap returns, including those the
getOwnPropertyDescriptor trap reports as non-enumerable, and throws "The object is not a string" on
a symbol among them. `JSON.stringify` of such a proxy crashes with a host
`ClassCastException: SymbolKey cannot be cast to Number`. `Object.assign`, `Object.entries`,
`Object.values` and object spread read each key without asking for its descriptor first, where
EnumerableOwnProperties and CopyDataProperties (ECMAScript 2017, 7.3.21 and 7.3.25) ask
[[GetOwnProperty]] for each key just before reading it, and `Object.assign` runs the strings and
then the symbols as two passes. `hasOwnProperty` and `propertyIsEnumerable` on a proxy call its
`has` trap, so `propertyIsEnumerable` answers true for an inherited property. `Object.assign` does
not throw when a proxy's set trap or an accessor without a setter refuses the write, and leaves an
array target's existing elements alone: `Object.assign([1, 2, 3], [4])` is `[1, 2, 3]`.

- Where: `NativeProxy.getIds`, `NativeObject` (`keys`, `assign`, `entries`, `values`,
  `hasOwnProperty`, `propertyIsEnumerable`), `NewLiteralStorage.spreadObject`, `NativeJSON.jo`.
- Test: `EvalOracleTest.constructProxiesEnumerationAndJsonFollowTheInternalMethods`;
  `ConstructAndProxyInternalsTest` (common).

### hasOwnProperty converts the key after `this`, and misses a symbol from ToPrimitive (D-91)

`Object.prototype.hasOwnProperty.call(undefined, key)` throws before it converts `key`, where
ECMAScript 2015, 19.1.3.2 runs ToPropertyKey first. A key whose `Symbol.toPrimitive` returns a
symbol is a TypeError in `hasOwnProperty`, `propertyIsEnumerable` and `Object.hasOwn` instead of
finding the symbol property. `Object.hasOwn.length` is 1; it is 2.

- Where: `NativeObject` (`hasOwnProperty`, `propertyIsEnumerable`, `hasOwn`).
- Test: `EvalOracleTest.constructProxiesEnumerationAndJsonFollowTheInternalMethods`;
  `ConstructAndProxyInternalsTest` (common).

### Integer keys from 2^31 are listed in creation order (D-91)

`Object.keys({ b: 1, 2147483648: 1, a: 1 })` is `b,2147483648,a`. OrdinaryOwnPropertyKeys
(ECMAScript 2015, 9.1.12) lists every array index, up to 2^32 - 2, first and in ascending order, so
it is `2147483648,b,a`. Rhino sorts only the keys that fit an `int`.

- Where: `ScriptableObject.KEY_COMPARATOR`.
- Test: `EvalOracleTest.constructProxiesEnumerationAndJsonFollowTheInternalMethods`;
  `ConstructAndProxyInternalsTest` (common).

### A proxy's setPrototypeOf answer is ignored, and Object.prototype takes a new prototype (D-91)

`Object.setPrototypeOf(proxy, {})` and `proxy.__proto__ = {}` succeed when the proxy's
setPrototypeOf trap answers false, and `Reflect.setPrototypeOf` answers true; both are a TypeError
or false per ECMAScript 2015, 9.5.2 and 19.1.2.18. `Object.setPrototypeOf(Object.prototype,
Object.create(null))` changes the prototype of Object.prototype, which is an immutable prototype
exotic object (ECMAScript 2016, 9.4.7) whose prototype can only stay null. The cycle check walks on
through a proxy in the new chain, calling its getPrototypeOf trap, where OrdinarySetPrototypeOf
stops at the proxy. The `__proto__` getter called with an undefined `this` crashes with a host
`ClassCastException` instead of throwing a TypeError. Setting a built-in function's prototype to
null does not stick: the next read puts Function.prototype back.

- Where: `NativeObject` (`setPrototypeOf`, the `__proto__` accessor), `NativeReflect`,
  `NativeProxy`, `SpecialRef`, `IdFunctionObject.prototype`.
- Test: `EvalOracleTest.constructProxiesEnumerationAndJsonFollowTheInternalMethods`;
  `ConstructAndProxyInternalsTest` (common).

### JSON.parse turns -0 into 0, and the reviver walk uses the wrong operations (D-91)

`1 / JSON.parse('-0')` is `Infinity`; the number is `-0`. The reviver gets array indices as
numbers rather than strings, reads only own properties (an element deleted and then inherited is
missed), and writes its results back with `put`, which calls a setter the reviver defined on the
holder and writes into an array the reviver froze, and deletes with the calling code's
strictness, so a refused delete throws in strict code. InternalizeJSONProperty (ECMAScript 2015,
24.3.1.1) uses CreateDataProperty, which replaces a configurable accessor and silently fails on a
frozen holder, and [[Delete]], whose false answer is ignored.

- Where: `json.JsonParser`, `NativeJSON.walk`.
- Test: `EvalOracleTest.constructProxiesEnumerationAndJsonFollowTheInternalMethods`;
  `ConstructAndProxyInternalsTest` (common).

### JSON.stringify reads toJSON twice and serializes a proxy for an array as an object (D-91)

`JSON.stringify` asks HasProperty for `toJSON` and then reads it twice, so a `toJSON` getter runs
twice where SerializeJSONProperty (ECMAScript 2015, 24.3.2.1) reads it once. A proxy for an array
is serialized as an object, `{"0":1,"1":2}`, where IsArray looks through a proxy; an array replacer
given as a proxy is ignored, and a hole in a replacer array does not read the prototype. Array
indices are passed to a replacer function as numbers.

- Where: `NativeJSON.str`, `ja` and `stringify`.
- Test: `EvalOracleTest.constructProxiesEnumerationAndJsonFollowTheInternalMethods`;
  `ConstructAndProxyInternalsTest` (common).

### A property call named eval is a direct eval (D-91)

`var x = 'global'; function f() { var x = 'local'; return this.eval('x') } f()` answers `local`,
and `this.eval('var v = 1')` inside a function declares `v` in the function. Only a call whose
callee is the plain name `eval` is a direct eval (ECMAScript 2015, 12.3.4.1), so `this.eval` and
another realm's `other.eval` run in the global scope of the eval function's own realm; V8 answers
`global`.

- Where: `IRFactory.createCallOrNew`.
- Test: `EvalOracleTest.constructProxiesEnumerationAndJsonFollowTheInternalMethods`;
  `ConstructAndProxyInternalsTest` (common).

### instanceof ignores Symbol.hasInstance on a function, and on a primitive left side (D-92)

ECMAScript 2015, 12.9.4: `V instanceof target` gets the target's `@@hasInstance` and, when it is
not undefined, answers ToBoolean of calling it with the target as `this` and V as the argument.
Rhino only asks for it on a plain object, and gets it wrong there too:

- `function F() {} Object.defineProperty(F, Symbol.hasInstance, { value: function (v) { return v === 1 } }); [1 instanceof F, ({}) instanceof F]`
  answers `false,false`; V8 answers `true,false`. A function's own method is never read, nor is
  a getter for it, so a throwing getter does not throw.
- A primitive left side answers false before the method is looked up:
  `2 instanceof { [Symbol.hasInstance]: function (v) { return v === 2 } }` answers `false`.
- On a plain object the method is called with the target itself as its argument in place of the
  left side: `var o = {}; o[Symbol.hasInstance] = function (v) { return v.a === 1 }; ({a: 1}) instanceof o`
  answers `false`.
- A method that is not callable is passed over instead of throwing a TypeError:
  `1 instanceof { [Symbol.hasInstance]: 1 }` answers `false`.
- A bound function reads its target's `prototype` instead of asking `instanceof` of the target, so
  the target's own method is skipped: with `F` as above, `1 instanceof F.bind()` answers `false`.

- Where: `ScriptRuntime.instanceOf`, which answers false for a primitive and otherwise calls
  `Scriptable.hasInstance`; `BaseFunction.hasInstance`, which walks the chain;
  `ScriptableObject.hasInstance`, which passes `this` as the argument; `BoundFunction.hasInstance`.
- Test: `EvalOracleTest.instanceofAsksSymbolHasInstance`; `InstanceofTest` (common).

### Function.prototype[Symbol.hasInstance] crashes on a bound built-in (D-92)

`Function.prototype[Symbol.hasInstance].call(Array.bind(), [])` ends in a host
`ClassCastException` (`LambdaConstructor cannot be cast to JSFunction`), and so does a function
bound twice, whose target is a `BoundFunction`. OrdinaryHasInstance (ECMAScript 2015, 7.3.19)
answers `instanceof` of the bound target, so V8 answers `true`. For a bound script function it
reads the target's `prototype` rather than asking the target, so with `F` defining its own
`Symbol.hasInstance` as above, `Function.prototype[Symbol.hasInstance].call(F.bind(), 1)` answers
`false` where V8 answers `true`.

- Where: `BaseFunction.js_hasInstance`, which casts `BoundFunction.getTargetFunction()` to
  `JSFunction`.
- Test: `EvalOracleTest.instanceofAsksSymbolHasInstance`; `InstanceofTest` (common).

### The iterator prototypes inherit Object.prototype, with no %IteratorPrototype% (D-93)

ECMAScript 2015, 25.1.2 defines %IteratorPrototype%, an ordinary object whose one property is
`[Symbol.iterator]`, a function of length 0 that returns its `this`; %ArrayIteratorPrototype%,
%MapIteratorPrototype%, %SetIteratorPrototype%, %StringIteratorPrototype%,
%RegExpStringIteratorPrototype% and %GeneratorPrototype% all inherit it. In Rhino each of them
inherits Object.prototype and holds a `[Symbol.iterator]` of its own, of length 1 for the first
five, so `Object.getPrototypeOf(Object.getPrototypeOf([][Symbol.iterator]())) === Object.prototype`
is `true` and a method added for every iterator has no one object to go on. V8 answers `false`.

The generator prototypes are half linked as well: %GeneratorFunction.prototype%'s `prototype` is
an ordinary writable, enumerable property, so `Object.keys` of it lists `prototype`, where
ECMAScript 2015, 25.2.3.2 makes it read-only and hidden, and %GeneratorPrototype% has no
`constructor` (25.3.1.1), so it inherits Object's.

- Where: `ES6Iterator.init` and `ES6Generator.init`, which set `getObjectPrototype(scope)` as the
  prototype and define `SymbolKey.ITERATOR` on each; `BaseFunction.initAsGeneratorFunction`, which
  uses `putProperty` for `prototype`.
- Test: `EvalOracleTest.theIteratorPrototypesInheritIteratorPrototype`; `IteratorPrototypeTest`
  (common).

### Replacing a global constructor changes what the engine makes (D-94)

An object literal is made from %Object.prototype% (ECMAScript 2015, 12.2.6.7), an array literal
from %ArrayPrototype% (9.4.2.2), a primitive's wrapper by ToObject from the intrinsic prototype
(7.1.13), and an error the engine throws from its intrinsic kind. Rhino keeps the original
constructors for that only in a `TopLevel`, and `Context.initStandardObjects()` makes a plain
`NativeObject` for its global, so there every one of them reads the global binding a script may
have replaced:

- `var SP = String.prototype; String = function () {}; (Object.getPrototypeOf('a') === SP) + ' ' + typeof ''.trim`
  answers `false undefined`; V8 answers `true function`.
- After `Object = function () {}`, `Object.getPrototypeOf({}) === Object.prototype` as saved before
  is `false`, and after `Array = function () {}`, `[].join` is undefined.
- After `TypeError = function () {}`, the error `null.x` throws is not an instance of the saved
  `TypeError` and has no `message`.

Three built-ins build through the global binding even in a `TopLevel`, and cast what it gives
them, so a replaced global crashes the host with a `ClassCastException`:

- `var M = Map; Map = function () {}; M.groupBy([1], function () { return 'k' })` casts to
  `NativeMap`.
- `var cst = Error.captureStackTrace; Error = function () {}; cst({})` casts to `NativeError`.
- `AggregateError = function () {}; Promise.any([])` casts to `NativeError` in its rejection.

- Where: `ScriptRuntime.initSafeStandardObjects`, which makes a `NativeObject` for a null scope
  and caches the intrinsics only when the scope is a `TopLevel`; `NativeMap.jsGroupBy`,
  `NativeError.js_captureStackTrace` and `NativePromise`'s `Promise.any`, which call
  `cx.newObject(scope, name)`.
- Test: `EvalOracleTest.replacedGlobalsDoNotReachWhatTheEngineMakes`; `RealmIntrinsicsTest`
  (common).

### A reserved word spelled with an escape is taken as the keyword (D-95)

ECMAScript 2015, 11.6.2 makes a reserved word a keyword only when it is spelled without escapes;
spelled with one it is an IdentifierName, good as a property name and an early error wherever an
identifier is expected. Rhino reads `\u0069f` as `if` itself, so `({ \u0069f: 1 })` is a syntax
error, `n\u0065w.target` and `for (x \u006ff y)` are accepted as `new.target` and `of`, and
`var \u0069f`, `tru\u0065` as a name, `g\u0065t x() {}` as a getter and an escaped `yield` in a
generator all parse. V8 answers the opposite in every case.

- Where: `TokenStream.getToken`, which looks an escaped word up in the keyword table like any
  other.
- Test: `SyntaxConformanceTest.escaped_keywords` (common).

### Format-control characters are dropped from the source (D-95)

ECMAScript 3, 7.1 removed format-control characters (Unicode category Cf) before scanning;
ECMAScript 5 keeps them: ZWNJ and ZWJ are identifier parts, any of them may appear in strings,
templates, regular expressions and comments, and one anywhere else is an error. Rhino still drops
them outside strings and templates, so `var a\u200D = 5; a` followed by a literal ZWJ reads two
different names (the escape keeps the character, the literal loses it), `/a<ZWJ>b/.source` has
two characters instead of three, and a stray `1<LRM>;` parses.

- Where: `TokenStream.getChar`, which skips `isJSFormatChar` characters at every language
  version.
- Test: `SyntaxConformanceTest.format_control_characters` (common).

### Accessors take any parameter list (D-95)

A getter takes no parameter and a setter exactly one, which may not be a rest parameter
(ECMAScript 2015, 14.3.1). Rhino accepts `({ get x(a) {} })`, `({ set x() {} })`,
`({ set x(a, b) {} })` and `({ set x(...a) {} })`; V8 throws a SyntaxError for each.

- Where: `Parser.methodDefinition`, which marks the function a getter or setter without looking
  at its parameters.
- Test: `SyntaxConformanceTest.accessor_parameters` (common).

### A for head refuses `in` between brackets (D-95)

Only the bare expression of a `for (init; ...)` head loses the `in` operator; array and object
literals, computed keys, element accesses and template substitutions take it back (ECMAScript
2015, 12.2, where each is `[+In]`). Rhino turns it off for everything but parentheses, call
arguments and the middle of a conditional, so `for (var o = { [a in b]: 1 }; ;)`,
`for (var a = [x in y]; ;)` and `for (var v = o[k in o]; ;)` are syntax errors.

- Where: `Parser`, whose `inForInit` flag only `parenExpr`, `argumentList` and `condExpr`
  reset.
- Test: `SyntaxConformanceTest.in_between_brackets_in_a_for_head` (common).

### A rest parameter cannot be a pattern, and may have a default (D-95)

ECMAScript 2016, 14.1 lets a rest parameter be a binding pattern, as in `function f(...[a, b])`,
which Rhino rejects with "missing formal parameter". It also accepts `function f(...a = [])`,
which has no grammar, and lets a body with `"use strict"` follow a parameter list that has a
pattern or a rest parameter, where 14.1.2 wants a simple list; Rhino only checks for defaults.

- Where: `Parser.parseFunctionParams` and the directive prologue of `Parser.parseFunctionBody`.
- Test: `SyntaxConformanceTest.rest_parameters_and_simple_lists` (common).

### A yield takes a whole Expression and wants parentheses in a list (D-95)

The operand of a yield is one AssignmentExpression (ECMAScript 2015, 14.4), so
`yield 1, yield 2` yields twice and `[...g()]` of `function* g() { yield 1, 2 }` is `[1]`. Rhino
parses the operand as a comma Expression, and keeps JavaScript 1.7's rule that a yield after a
comma or in an argument list be parenthesized, so `f(yield 1)` and `yield 1, yield 2` are syntax
errors. `yield *` followed by a line break loses its operand and throws a TypeError at run time,
and `yield` then a line break then `* 1` is taken as `yield* 1` where it is an early error.

- Where: `Parser.returnOrYield`, `Parser.expr` and `Parser.argumentList`.
- Test: `SyntaxConformanceTest.yield_operands` (common).

### Redeclarations across blocks go unreported (D-95)

A `let`, `const` or class conflicts with a `var` of its own scope, with a `var` declared in any
block inside it and with a parameter, and a function declared in a block is lexical there
(ECMAScript 2015, 13.2.1.1, 13.15.1 and 14.1.2). Rhino reports none of `{ var f; let f; }`,
`{ { var f; } let f; }`, `let f; { var f; }`, `{ function f() {} var f; }`,
`try {} catch (e) { let e; }`, `for (let x of []) { var x; }` or `function f(a) { let a; }`.

- Where: `Parser.defineSymbol`, which compares a name with the one scope that defines it.
- Test: `SyntaxConformanceTest.declaration_clashes` (common).

### An anonymous function in a destructuring default stays nameless (D-95)

NamedEvaluation gives an anonymous function, arrow, generator or class the name of the binding
it initializes, in a pattern's default as much as in a plain initializer (ECMAScript 2015,
12.14.5.2 and 13.3.3.6). Rhino names `var f = function () {}` but not
`var [f = function () {}] = []`, `var { g = () => 1 } = {}` or a parameter default, whose `name`
is the empty string.

- Where: `IRFactory`, whose name inference runs for plain initializers only.
- Test: `SyntaxConformanceTest.named_evaluation_in_patterns` (common).

### ArrayBuffer has no Symbol.species, and generator prototypes have a constructor (D-95)

`ArrayBuffer[Symbol.species]` is undefined, where ECMAScript 2015, 24.1.3.3 defines a getter
returning `this`. A generator function's `prototype` gets a `constructor` property like an
ordinary function's, which 25.2.4.2 leaves out. And `Object.defineProperty(f, 'length',
{ value: 5 })` succeeds on a function without changing what `f.length` answers.

- Where: `NativeArrayBuffer.init`; `BaseFunction.setupDefaultPrototype`; `BaseFunction`'s
  `length` slot, which has no setter.
- Test: `SyntaxConformanceTest.built_ins` (common).

### A method's source text starts at its parenthesis, and strict functions own `arguments` (D-95)

`Function.prototype.toString` of a method, getter, setter or generator method of an object
literal answers the text from its parameter list on, as in `() { return 1; }`, where ECMAScript
2019, 19.2.3.5 wants the whole MethodDefinition, `m() { return 1; }` or `get x() { ... }`. And a
strict function has own `arguments` and `arity` properties like a sloppy one, where ECMAScript
2015, 16.1 forbids an own `arguments` on a strict function.

- Where: `Parser.methodDefinition`, which records the function's source start at the
  parenthesis; `BaseFunction.createProperties`, which asks the context rather than the function
  whether it is strict.
- Test: `ClassSyntaxTest.source_text` (common).

### Number, string and BigInt keys go wrong in literals and patterns (D-95)

A BigInt literal as a key names the property its decimal digits spell (ECMAScript 2020,
12.2.6.5), so `({ 1n: true })[1]` is `true`. Rhino crashes the host with a
`NullPointerException` in its code generator on `{ 9n: true }`, and calls `Kit.codeBug` on
`var { 1n: a } = o`. A destructuring pattern also reads a number key cut to an int, so
`var { 1.5: a } = { '1.5': 7, 1: 3 }` binds 3, and reads a string key by name, so
`var { '0': a } = [5]` misses the array element and binds `undefined`.

- Where: `Parser.getPropKey`, which has no case for `BigIntLiteral`; `Parser.destructuringObject`,
  which builds `GETELEM` with `number.toInt()` and `GETPROP` for a string.
- Test: `SyntaxConformanceTest.literal_keys` (common).

### Calling undefined names a Java object in the message (D-95)

`[1].map()`, or any call whose callee the engine reports by value rather than by name, throws
`TypeError: org.mozilla.javascript.Undefined@255316f2 is not a function, it is undefined.`: the
message helper is printed with the Java object's own `toString`, hash and all, instead of as
`undefined`.

- Where: `ScriptRuntime.notFunctionError(Object, Object)`, which formats the helper with
  `toString` rather than `ScriptRuntime.toString`.
- Test: `EvalOracleTest.arrayBuiltin` (JVM), which compares the two messages once the Java name is
  mapped back.
### for-in drops a proxy's keys, and a non-enumerable property hides nothing (D-96)

for-in walks an object by EnumerateObjectProperties (ECMAScript 2015, 13.7.5.15), which takes
each object's keys from [[OwnPropertyKeys]] and their attributes from [[GetOwnProperty]], skips a
key whose property is gone by the time the loop reaches it, and leaves out a prototype's property
when an object before it in the chain has a property of the same name; the informative definition
counts a key as processed whether it is enumerable or not, and test262 checks that. Rhino checks
each key it hands out with `has`, which on a proxy is [[HasProperty]], answered by its `has` trap or
else by its target, remembers only the enumerable keys of the objects it has passed, and before
any of that asks the object for `__iterator__`, the iterator protocol of JavaScript 1.7:

- `for (k in new Proxy({}, { ownKeys: () => ['a', 'b'], getOwnPropertyDescriptor: (t, k) => ({
  value: 1, enumerable: k === 'a', configurable: true }) }))` hands out nothing; V8 hands out `a`.
- A for-in over `new Proxy({ x: 1 }, { has: ... })` calls the `has` trap with `__iterator__` and
  then `x`; V8 calls it with nothing.
- A for-in over a proxy whose `getOwnPropertyDescriptor` stops reporting `y` once the loop has
  started hands out `x,y`; V8 hands out `x`.
- `var o = Object.create({ x: 1, y: 2 }); Object.defineProperty(o, 'x', { value: 3, enumerable:
  false })` enumerates `x,y`; V8 enumerates `y`.
- `{ a: 1, __iterator__: function () { ... } }` enumerates what the function's iterator gives,
  here nothing; V8 enumerates `a,__iterator__`.

- Where: `ScriptRuntime.enumNext`, which calls `obj.has(id, obj)` for every key, and
  `enumChangeObject`, which adds only the previous object's `getIds()` to the keys it hides;
  `ScriptRuntime.enumInit`, which calls `toIterator` for every for-in.
- Test: `EvalOracleTest.forInFollowsEnumerateObjectProperties`; `ForInEnumerationTest` (common);
  `language/statements/for-in/12.6.4-2.js` and `order-enumerable-shadowed.js`.

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

### A WeakMap value that refers to its key keeps the entry forever (D-82)

`NativeWeakMap` and `NativeWeakSet` keep a `java.util.WeakHashMap`, which holds its keys weakly
and its values strongly. A value that leads back to its own key, `map.set(k, { owner: k })`, is
reachable from the map and keeps the key, so the entry is never collected while the map lives.
The same goes for two entries whose values lead to each other's keys. This is the commonest way
a `WeakMap` is used, as a side table of metadata about an object, so a long-lived cache keyed by
objects grows without bound. The note on WeakMap in the spec (ECMAScript 2015, 23.3) says the
collections are meant not to keep an object that would otherwise be unreachable, and V8,
SpiderMonkey and JavaScriptCore, whose collectors have ephemerons, all let such entries go.

- Where: `NativeWeakMap` and `NativeWeakSet`, the `WeakHashMap` field. The JVM has no ephemeron,
  so the fix is the inverted representation: store each collection's value on the key, in a
  table keyed weakly by the collection, so only the key leads to its values.
- Test: `WeakMapGcTest.upstreamKeepsAKeyItsValueLeadsTo`.

## Maths accuracy

### log2, acosh, asinh and atanh are formulas on top of log (D-73)

`Math.log2(8)` is `2.9999999999999996`, because it is computed as `log(x) * LOG2E`, and
`Math.acosh(1e300)` is `Infinity`, because `x * x` overflows inside the formula. fdlibm's and
FreeBSD's routines, which V8 uses, are exact for powers of two and do not overflow.

- Where: `NativeMath`.
- Test: `EvalOracleTest.mathIsFdlibmAsV8HasIt`; `built-ins/Math/log2/log2-basicTests.js`.
