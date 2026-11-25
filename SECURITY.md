# Security policy

KiteJS runs JavaScript that it did not write. An application hands it the scripts of a document, a
plugin or a user, often from an untrusted source. The engine and its limits are the whole attack
surface of the library.

## Reporting a vulnerability

Report privately through GitHub's
[security advisories](https://github.com/yuroyami/KiteJS/security/advisories/new). Do not open a
public issue. A useful report includes:

- the script, or a generator for it
- the engine (`Rhino` or `QuickJs`) and the configuration
- the target it happens on, for example the JVM, Android, iOS or Wasm
- what the engine does with the script

Expect a first response within a week.

## Supported versions

Fixes go into the latest release. The project is before 1.0, so an older minor version gets no
fixes. Update to the latest release to get a fix.

## What counts as a vulnerability

A script must end in a value, in a `JsException`, or in a `JsEngineError` when a limit stops it. The
engine must stay usable after each of these. Anything else from a call into an engine is worth a
report:

- a script that runs past its `instructionBudget`, or that an `interruptWhen` hook cannot stop
- a script that reaches a host object, a host function or a Kotlin class that the host did not give
  it
- a script that ends the host process, for example through a native crash in the QuickJS build, a
  stack overflow or an uncaught error on Kotlin/Native
- a read or a write of memory outside the engine's own heap on QuickJS
- a script that uses more memory than the QuickJS `memoryLimit` allows and is not stopped
- state that one engine leaks into another engine

## What does not

- **A script that throws.** A `JsException` from a bad script is the documented behaviour.
- **A wrong answer.** A built-in that answers differently from the specification is a correctness
  bug. File it as a normal issue with the script attached.
- **Work inside the limits that you set.** With no `instructionBudget` and no hook, `while (true) {}`
  runs forever. With no `memoryLimit`, or on Rhino, which has no memory budget, a script can use the
  whole heap. [Limits and safety](docs/limits.md) describes each limit.
- **Changes that a script makes to its own global scope.** Without `sealBuiltins`, a script can
  replace `Array.prototype.push` for the scripts that run after it in the same engine.

## Guards already in place

- **An instruction budget and an interrupt hook** on both engines. Each call from the host gets the
  budget again, and the Promise reactions of that call count against it.
- **A memory limit** on QuickJS (`memoryLimit`).
- **Call depth limits**: `maxCallDepth` and `maxHostCallDepth` on Rhino, and `maxStackSize` on
  QuickJS. Deep recursion ends in a `RangeError` that the script can catch.
- **Safe and sealed built-ins** (`safeBuiltins` and `sealBuiltins`) for a sandbox.
- **One engine per thread.** A handle from one engine cannot be used in another engine or on another
  thread.

## Reducing your own exposure

- Set `instructionBudget` or `interruptWhen` for every script that you did not write.
- Prefer QuickJS with a `memoryLimit` when a script can allocate without bound, or run the engine in
  a process that you can stop.
- Give the script only the host functions that it needs, and seal the built-ins.
