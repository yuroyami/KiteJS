# Programs compiled to JavaScript

A program written in C or C++ and compiled for the web with Emscripten does not arrive as ordinary
JavaScript. It arrives as **asm.js**: a subset of JavaScript where the type of every value can be
worked out before anything runs. The engine compiles such a module ahead of time and runs it as
typed code, which is many times faster than running it as ordinary JavaScript.

Nothing has to be switched on. A module that validates is compiled the first time the engine parses
it, and a module that does not is run as ordinary JavaScript, which is what a browser does too.

## How to tell what happened

```kotlin
KiteJs().use { js ->
    js.evaluate(source, "game.js")
    for (report in js.asmReports) println(report)
}
```

Each line covers one function whose body starts with `"use asm"`:

```
asm: compiled
asm: not compiled (Oa: a division mixes signed and double)
asm: compiled, not linked (Math.imul is not the engine's own)
```

The reason names the first thing in the module that asm.js does not allow. It is the answer to
"why is this slow": a module that does not compile still gives the same results, only slower.

## What a module looks like

```javascript
var m = (function (stdlib, foreign, heap) {
  "use asm";
  var H32 = new stdlib.Int32Array(heap);
  var imul = stdlib.Math.imul;
  function scramble(n) {
    n = n | 0;
    var i = 0, x = 0;
    for (i = 0; (i | 0) < (n | 0); i = (i + 1) | 0) {
      x = (imul(x, 1103515245) + 12345) | 0;
      H32[(i << 2) >> 2] = x;
    }
    return x | 0;
  }
  return { scramble: scramble };
})({ Math: Math, Int32Array: Int32Array }, {}, new ArrayBuffer(1 << 20));

m.scramble(1000000);
```

The three arguments are the standard library, an object of functions the module imports from
outside, and an `ArrayBuffer` the module uses as its memory. All three are checked when the module
is called: `stdlib.Math.imul` has to be the engine's own `Math.imul`, not something that shares the
name. A module given anything else is run as ordinary JavaScript instead.

## Compiling is a decision about types, not about speed

Every rule asm.js has exists so that a value's type is known in advance. `x = x | 0` at the top of
a function says the parameter is a 32 bit integer. `0` declares an integer variable and `0.0`
declares a double. `(a | 0) / (b | 0) | 0` is integer division and `a / b` on two doubles is not.

The rule that matters most is the one about uncoerced values. The result of `+`, `-` or `*` on two
integers may only be read where its overflow cannot be seen: under `|`, `&`, `^`, a shift, a byte
heap index, or inside another `+`, `-` or `*`. This is what lets the engine add two integers with
one machine instruction and let the result wrap at 32 bits, where plain JavaScript would widen the
sum to a double and keep every bit of it.

A heap read is written `H32[p >> 2]`, where the shift matches the width of the view. The engine
reads the bytes directly. Reading past the end of the heap answers zero, and writing past it does
nothing, the same as through a typed array view.

## What the compiler does not take

- A module the engine cannot read as asm.js at all, for example one using a value whose type is
  not fixed.
- Arithmetic on floats where the result is not passed to `Math.fround`, since that is how asm.js
  says a float result is rounded back to a float.
- An engine set to big-endian byte order through `KiteJsConfig.littleEndian`, because the compiled
  code reads and writes the heap in little-endian order and the views around it have to agree.

## Turning it off

```kotlin
KiteJs { asmJs = false }.use { js -> /* ... */ }
```

A module then runs as ordinary JavaScript. The answers are the same either way, so this is only
useful for comparing the two, or as a way around a problem in the compiler.

## Budgets and stopping

An instruction budget and `interruptWhen` both still apply. The compiled code checks them at the
top of every loop rather than on every instruction, so a module that never returns is still
stopped, a little later than an ordinary script would be.
