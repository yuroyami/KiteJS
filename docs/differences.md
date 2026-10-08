# Differences from a browser

KiteJS is a JavaScript engine, not a browser and not Node. This page lists what a script cannot
do here, and what to write instead. Everything below was checked against the engine, not
remembered.

This page is about Rhino. QuickJS is a different engine with its own, much shorter list: it runs
the newer syntax and built-ins below, async iteration included, and the gaps it shares with Rhino
are the ones that come from not being a browser, such as having no DOM and no `fetch`. See
[Choosing an engine](engines.md).

## Syntax the parser rejects

These are syntax errors. A script using any of them will not even parse.

| Not supported | Write instead |
|---|---|
| `import` and `export` | Nothing. Concatenate the sources, or bind a loader function yourself |
| `for await (... of ...)` | A plain `for` loop that awaits each promise in an async function |
| `async function*`, `async *m() {}` | An async function that returns an array, or a generator of promises |
| `var [a, ...rest] = list` | `var a = list[0], rest = list.slice(1)` |
| `var { a, ...rest } = obj` | `var a = obj.a, rest = Object.assign({}, obj); delete rest.a` |
| `var { [key]: v } = obj` | `var v = obj[key]` |
| `return` outside a function | Wrap the script in a function and call it |
| The regular expression flags `d` and `v` | Read `exec` results for positions; use `u` for Unicode |

Spread works in an array literal, an object literal and an argument list. It is destructuring
with a rest element that the parser does not take.

```js
[...set]              // fine
({ ...defaults })     // fine
Math.max(...numbers)  // fine
new Date(...parts)    // fine
var [a, ...rest] = xs // syntax error, use xs[0] and xs.slice(1)
```

Spreading a value with no iterator, such as `[...5]` or `f(...null)`, throws a `TypeError`, as it
does in a browser.

## Globals that are not there

Reading them gives `undefined`, so `typeof x === 'undefined'` is a safe check.

| Missing | Why, and what to do |
|---|---|
| `setTimeout`, `setInterval` | There is no event loop. Bind a scheduler from Kotlin if you need one |
| `fetch`, `XMLHttpRequest` | No network. Bind a function that does the request |
| `document`, `window`, `navigator` | No DOM. Bind whatever object your host needs to expose |
| `Intl` | Needs a full locale database. `toLocaleString` formats numbers and dates for en-US; format other locales in Kotlin |
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

### `Math.pow` can differ from Chrome in the last digit

`Math` gives the same digits on every target, and they are the digits V8 gives, with one
exception: `Math.pow`, and the `**` operator, follow fdlibm, as Java's `StrictMath.pow` does. V8
computes powers its own way, and the two disagree in the last digit for about one argument in a
hundred. Where that argument is an integer power, the answer here is the correctly rounded one.

```js
121.60979304462671 ** 21   // 6.086106353125176e43 here, 6.086106353125175e43 in Chrome
```

### Numbers format for en-US only

`toLocaleString` on a number or a BigInt prints what `Intl.NumberFormat("en-US", options)` prints
in a browser, whatever locale it is given: `(2046430).toLocaleString("de-DE")` is `2,046,430`.
Every option of `Intl.NumberFormat` applies, in English: digits and rounding, grouping, sign
display, `percent`, `currency` (symbols, codes and names), `unit`, and scientific, engineering
and compact notation. `numberingSystem` is checked and then ignored, so digits are always 0 to 9.
There is no `Intl` object itself; this holds on both engines.

```js
(1234.5).toLocaleString("en-US", { style: "currency", currency: "EUR" })   // "€1,234.50"
(0.256).toLocaleString(undefined, { style: "percent" })                    // "26%"
(1234).toLocaleString("ja-JP", { notation: "compact" })                    // "1.2K", not "1234"
```

### Dates format for en-US only

`toLocaleString`, `toLocaleDateString` and `toLocaleTimeString` use fixed en-US patterns and
ignore their locale argument. `Date.prototype.toString` prints the zone's id rather than its
abbreviation. See [Dates and time zones](dates-and-time-zones.md).

### A `let`, `const` or class is readable before its declaration

A browser throws a `ReferenceError` when code reaches a `let`, `const` or class binding before
its declaration has run. Here the binding is there from the start of its block and holds
`undefined` until then, so `new C()` above `class C {}` throws a `TypeError` for calling
`undefined` rather than a `ReferenceError`. Code that runs in a browser runs the same here.

### Leaving a `for-of` early does not close the iterator

A `break`, `return` or throw out of a `for-of` loop, or an array pattern that takes fewer
elements than the iterable has, never calls the iterator's `return` method, so a generator's
`finally` block does not run then. Call `it.return()` yourself where the cleanup matters.

### Older scoping rules for parameters and block functions

A parameter default runs in the body's scope, so it cannot read `arguments` and a closure made
in it sees the body's `var`s. A function declared inside a block belongs to the whole enclosing
function, in strict code too, and a `for (let ...)` body's function sees the last value of the
loop variable. In sloppy code `yield` outside a generator is still a keyword. Code that avoids
these corners runs the same as in a browser.

## Things that work here and not in a browser

The engine keeps some behaviour a browser dropped, because a script written for it may rely on
that:

- `with` statements, in non-strict code.
- `arguments.callee`, in non-strict code.
- The legacy `RegExp.$1` through `RegExp.$9` statics.
- `__proto__` as a plain property, which browsers also still allow.

## Everything else

The rest of ES5.1 and most of ES2015 and later is there and tested: `let`, `const` (also in a
`for` head, as in `for (const x of xs)`, with a fresh binding on every pass), classes (with
`extends`, `super`, `new.target`, public and private fields, private methods and accessors,
`#x in obj`, static fields and static blocks), arrow functions, template literals and tagged templates, destructuring in declarations and parameters,
default and rest parameters, computed keys, getters and setters in object literals, generators
and `yield*`, `Symbol` and every well-known symbol, `Map`, `Set`, `WeakMap`, `WeakSet`,
`Promise`, `Proxy`, `Reflect`, `BigInt`, typed arrays and `DataView`, optional chaining, nullish
coalescing and the logical assignment operators, numeric separators, `**`, and the full regular
expression syntax including named groups, lookbehind and `\p{...}`.

Recent library methods are there too, among them `Array.prototype.at`, `flat`, `findLast`,
`Object.hasOwn`, `Object.groupBy`, `String.prototype.at` and `replaceAll`.

## Source serialization

Rhino's `uneval` and `Object.prototype.toSource` extensions can reconstruct enumerable object
properties, including methods and getter/setter pairs. Serialization reads accessor descriptors
without running their getters, and writes resolved property names rather than reevaluating computed
name expressions. Ordinary function and arrow values retain their expression syntax.

This writes source, not a snapshot of captured lexical environments, object identity, prototypes,
or every property attribute. Native functions have no JavaScript body to serialize. Use a data
format such as JSON when a stable data interchange contract is required.
