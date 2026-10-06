# Promises and coroutines

## Promises on their own

`Promise` works without any extra dependency. Reactions run on the engine's microtask queue,
which drains when a top level call returns.

```kotlin
KiteJs(Rhino).use { js ->
    js.evaluate("var log = []; Promise.resolve(1).then(function (v) { log.push(v) })")
    js.evaluate("log.join()").asString()   // "1"
}
```

The reaction has already run by the time `evaluate` returns. That is why the second line sees it.

If a host callback settles a promise after the call has finished, drain the queue yourself:

```kotlin
js.evaluate("resolveIt('done')")
js.runMicrotasks()
```

To hear from a promise in Kotlin, `onSettled` attaches to it the way `await` does in a script,
and `isThenable` says whether a value is one `await` would wait for:

```kotlin
val promise = js.evaluate("fetchUser(7)")
if (promise.isThenable) {
    js.onSettled(promise) { value, error -> if (error == null) show(value) else report(error) }
}
```

There is no `async`/`await`. See [Differences from a browser](differences.md).

## The suspending API

`kitejs-coroutines` is a separate artifact. Add it when you want the engine from suspending code.

```kotlin
implementation("io.github.yuroyami:kitejs-coroutines:0.5.0")
```

```kotlin
val js = asyncKiteJs(Rhino)
try {
    println(js.evaluate("2 + 2").asDouble())
} finally {
    js.close()
}
```

The engine is single threaded, as JavaScript is, and the thread that opens it holds it.
`asyncKiteJs` gives the engine a thread of its own and moves every call onto that thread, so two
callers never overlap. `close()` releases the engine on that thread, and the thread then ends.

To run the engine on a dispatcher of your own, pass one that runs everything on one thread, such
as one from `newSingleThreadContext`. You close that dispatcher yourself. A pool view such as
`Dispatchers.Default.limitedParallelism(1)` does not work, because it moves calls between threads.

Reach the engine directly with `onEngine`:

```kotlin
js.onEngine { engine ->
    engine.global.function("double") { args -> args.first().asDouble() * 2 }
}
```

What comes back from the engine is the engine's own. A number, a string, a boolean, a BigInt,
null or undefined is a copy you can read anywhere. An object, an array or a function still
belongs to the engine, so read it inside `onEngine`; from another thread it throws
`JsEngineError` instead of running script there.

## Awaiting a promise

```kotlin
val user = js.evaluateAwaiting("fetchUser(7)")
println(js.onEngine { user.asObject()["name"].asString() })
```

`evaluateAwaiting` runs the script and, if the answer is a promise, waits for it to settle.
`await` does the same for a value you already have, the way `await` does in a script: any object
with a callable `then` is waited for, a thenable that answers with another promise or thenable is
followed to the value at the end, and a value that is not thenable comes straight back.

A rejection arrives as the `JsError` it carried:

```kotlin
try {
    js.evaluateAwaiting("Promise.reject(new TypeError('nope'))")
} catch (e: JsError) {
    println(e.name)          // TypeError
    println(e.errorMessage)  // nope
}
```

Any value can be a rejection reason. When reading its `name` or `message` fails, the error is
still raised, named `Error`, with the original value in `JsError.value`.

Closing the engine ends every `await` still waiting with `IllegalStateException`, because nothing
can settle the promise any more. A waiting coroutine that is cancelled simply stops waiting.

## Giving a script a promise

```kotlin
val deferred = async { loadFromDisk() }
val promise = js.deferredToPromise(deferred, this)

js.onEngine { it.global["data"] = promise }
js.evaluate("data.then(function (rows) { render(rows) })")
```

## Host functions that suspend

The script sees an ordinary function that answers a promise. Your suspending body settles it.

```kotlin
js.suspendFunction(js.onEngine { it.global }, "fetch", 1, scope) { args ->
    httpClient.get(args.first().asString()).bodyAsText()
}

val length = js.evaluateAwaiting("fetch('/a').then(function (body) { return body.length })")
```

If the body throws, the promise rejects with that message. The body runs on `scope`, off the
engine's thread, so read an object or a function argument inside `onEngine`; scalars can be read
as they are.

Every promise these hand out settles once, whatever happens to the work behind it:

| What happens | The script sees |
|---|---|
| The body returns, or the `Deferred` completes | The promise fulfils with the value |
| The body throws, or the `Deferred` fails | It rejects with the failure's message |
| `scope` is cancelled, before the work starts or while it runs, or the `Deferred` is cancelled | It rejects with the cancellation's message |
| The engine is closed first | Nothing: the engine is gone, and the work carries on in its scope |

Settling a promise runs the script's reactions to it. If one of them fails in a way the script
cannot catch, such as running out of its instruction budget, the failure goes to the
`CoroutineExceptionHandler` in `scope`, or to the platform's handler if there is none.

## Cancellation

Cancelling the coroutine that called into the engine stops the running script.

```kotlin
val job = launch { js.evaluate(untrustedScript) }
delay(1000)
job.cancel()     // the script stops, and the engine is usable again
```

This works because the engine asks a hook between instructions. You can use the same hook
yourself for a deadline or a stop button:

```kotlin
val deadline = now() + 2000
KiteJs(Rhino) { interruptWhen = { now() > deadline } }.use { js -> /* ... */ }
```

Setting `interruptWhen` turns the check on even when you set no instruction budget. Whatever you
set is still asked when `asyncKiteJs` adds its own cancellation check on top.

**One limit.** Cancelling from another coroutine cannot work on Kotlin/JS. The engine and the
canceller share the one thread there, so while a script loops nothing else can run and no signal
arrives. The hook itself works everywhere, because it is asked from inside the running script.
Use `instructionBudget` or a clock-reading `interruptWhen` if you need to stop a script on JS.
