# Differences from a browser

KiteJS is a JavaScript engine, not a browser and not Node. This page lists what a script cannot
do here, and what to write instead. Everything below was checked against the engine, not
remembered.

## Syntax the parser rejects

These are syntax errors. A script using any of them will not even parse.

| Not supported | Write instead |
|---|---|
| `class A { }`, `extends`, `super` | A constructor function and `prototype` |
| `import` and `export` | Nothing. Concatenate the sources, or bind a loader function yourself |
| `async function`, `await` | `Promise` with `.then`, or a host function that suspends |
| `for await (... of ...)` | Iterate the promises and await each one |
| `f(...args)`, `new C(...args)` | `f.apply(null, args)`, or build the call yourself |
| `var [a, ...rest] = list` | `var a = list[0], rest = list.slice(1)` |
| `return` outside a function | Wrap the script in a function and call it |
| The regular expression flags `d` and `v` | Read `exec` results for positions; use `u` for Unicode |

Spread works in an array literal and in an object literal. It is a call argument list, and
destructuring with a rest element, that the parser does not take.

```js
[...set]              // fine
({ ...defaults })     // fine
Math.max(...numbers)  // syntax error, use Math.max.apply(null, numbers)
```

## Globals that are not there

Reading them gives `undefined`, so `typeof x === 'undefined'` is a safe check.

| Missing | Why, and what to do |
|---|---|
| `setTimeout`, `setInterval` | There is no event loop. Bind a scheduler from Kotlin if you need one |
| `fetch`, `XMLHttpRequest` | No network. Bind a function that does the request |
| `document`, `window`, `navigator` | No DOM. Bind whatever object your host needs to expose |
| `Intl` | Needs a full locale database. Format in Kotlin instead |
| `structuredClone` | Use `JSON.parse(JSON.stringify(x))`, or bind your own |
| `SharedArrayBuffer`, `Atomics` | One thread, so they would mean nothing |
| `WeakRef`, `FinalizationRegistry` | Not exposed to scripts. `WeakMap` and `WeakSet` are |
| `Array.fromAsync` | Needs async iteration |

`globalThis` is there and works.

## Things that work but answer differently

These are the ones that will surprise you, because the code runs and the answer is wrong for
your purpose rather than obviously broken.

### Text comparison ignores locale

`localeCompare` compares by UTF-16 code unit, not by any collation order.

```js
'a'.localeCompare('B')   //  1 here, -1 in a browser
```

Sort user-visible text in Kotlin, where you have the platform's collator.

### `normalize` does nothing

`String.prototype.normalize` returns its input unchanged. Unicode normalisation needs tables the
engine does not carry.

```js
'é'.normalize('NFC').length   // 2 here, 1 in a browser
```

If you compare user text for equality, normalise it in Kotlin before handing it over.

### `toLowerCase` differs by target for one letter

Case conversion is the one operation still borrowed from the platform, and the platforms disagree
about the Greek final sigma. On the JVM, `'ΑΣ'.toLowerCase()` gives `'ας'`. On JavaScript,
WebAssembly and the native targets it gives `'ασ'`. Every other character agrees.

### Dates format for en-US only

`toLocaleString`, `toLocaleDateString` and `toLocaleTimeString` use fixed en-US patterns and
ignore their locale argument. `Date.prototype.toString` prints the zone's id rather than its
abbreviation. See [Dates and time zones](dates-and-time-zones.md).

## Things that work here and not in a browser

The engine keeps some behaviour a browser dropped, because a script written for it may rely on
that:

- `with` statements, in non-strict code.
- `arguments.callee`, in non-strict code.
- The legacy `RegExp.$1` through `RegExp.$9` statics.
- `__proto__` as a plain property, which browsers also still allow.

## Everything else

The rest of ES5.1 and most of ES2015 and later is there and tested: `let`, `const`, arrow
functions, template literals and tagged templates, destructuring in declarations and parameters,
default and rest parameters, computed keys, getters and setters in object literals, generators
and `yield*`, `Symbol` and every well-known symbol, `Map`, `Set`, `WeakMap`, `WeakSet`,
`Promise`, `Proxy`, `Reflect`, `BigInt`, typed arrays and `DataView`, optional chaining, nullish
coalescing and the logical assignment operators, numeric separators, `**`, and the full regular
expression syntax including named groups, lookbehind and `\p{...}`.

Recent library methods are there too, among them `Array.prototype.at`, `flat`, `findLast`,
`Object.hasOwn`, `Object.groupBy`, `String.prototype.at` and `replaceAll`.
