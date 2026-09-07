# Limits and safety

Running someone else's script means it might not stop. This page is about that.

## Stop a script that will not return

```kotlin
KiteJs { instructionBudget = 5_000_000 }.use { js ->
    js.evaluate("while (true) {}")   // throws JsEngineError
}
```

The engine counts interpreter instructions. When one call passes the budget it gives up and
throws `JsEngineError`. Each call gets the budget again, so a long session is not punished for
what an earlier call did.

There is no right number for every case. Measure your own scripts and leave room:

| Script | Roughly |
|---|---|
| An arithmetic expression | under 1,000 |
| A loop over a hundred items with a callback | tens of thousands |
| Parsing and transforming a large JSON document | millions |

Zero, the default, means no limit.

## Stop it on your own signal

```kotlin
val deadline = TimeSource.Monotonic.markNow() + 2.seconds

KiteJs { interruptWhen = { deadline.hasPassedNow() } }.use { js ->
    js.evaluate(untrustedScript)
}
```

The hook is asked from inside the running script every hundred thousand instructions or so.
Answer true and the script stops. Setting the hook turns the check on even with no budget.

Use this for a deadline, a stop button, or a cancelled coroutine. `asyncKiteJs` uses it for
exactly that, and it chains: whatever you set is still asked.

## Take away what a sandbox does not need

```kotlin
KiteJs {
    safeBuiltins = true    // leaves out the built-ins a sandbox does not want
    sealBuiltins = true    // a script cannot replace Array.prototype.push
    console = null         // no console at all
}
```

Sealing matters more than it sounds. Without it, one script can redefine `Array.prototype.push`
and the next script in the same engine gets the redefined one.

## Memory

There is no memory budget. A script that builds an ever larger array will exhaust the heap, and
the engine cannot stop it. If that is a real risk for you, run the engine in a process or a
worker you can kill.

Weak collections are the one place memory behaves differently by target:

| Target | `WeakMap` and `WeakSet` |
|---|---|
| JVM, Android, iOS, macOS, Linux, Windows | Entries are released when the key is collected |
| JavaScript | Same, on any runtime with `WeakRef`, which is every current one |
| WebAssembly | Entries are held until the map is dropped |

No script can tell the difference, because a `WeakMap` has no iteration and no size. The cost on
WebAssembly is memory, not behaviour. `WeakRef.isWeakSupported` answers false there if your host
code needs to know.

## One engine at a time

An engine belongs to one thread. Opening a second while the first is open throws a
`JsEngineError` that says so, rather than quietly sharing the first one's state.

If you need concurrency, either run the engines in separate processes, or use `asyncKiteJs` and
let it serialise the calls for you.

## Errors from a script cannot break the engine

A script that throws leaves the engine usable. So does one that runs out of budget.

```kotlin
KiteJs { instructionBudget = 100_000 }.use { js ->
    runCatching { js.evaluate("for (;;) {}") }
    js.evaluate("1 + 2").asInt()   // 3, the engine is fine
}
```

Global state the script already changed is still changed. If you need a clean slate, build a new
engine.
