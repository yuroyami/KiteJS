# Promises and coroutines

## Promises on their own

`Promise` works without any extra dependency. Reactions run on the engine's microtask queue,
which drains when a top level call returns.

```kotlin
KiteJs().use { js ->
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

There is no `async`/`await`. See [Differences from a browser](differences.md).

## The suspending API

`kitejs-coroutines` is a separate artifact. Add it when you want the engine from suspending code.

```kotlin
implementation("io.github.yuroyami:kitejs-coroutines:0.0.1")
```

```kotlin
val js = asyncKiteJs()
try {
    println(js.evaluate("2 + 2").asDouble())
} finally {
    js.close()
}
```

The engine is single threaded, as JavaScript is. `asyncKiteJs` owns a dispatcher that runs one
thing at a time and moves every call onto it, so two callers never overlap.

Reach the engine directly with `onEngine`:

```kotlin
js.onEngine { engine ->
    engine.global.function("double") { args -> args.first().asDouble() * 2 }
}
```

## Awaiting a promise

```kotlin
val user = js.evaluateAwaiting("fetchUser(7)")
println(user.asObject()["name"].asString())
```

`evaluateAwaiting` runs the script and, if the answer is a promise, waits for it to settle.
`await` does the same for a value you already have. A value that is not a promise comes straight
back.

A rejection arrives as the `JsError` it carried:

```kotlin
try {
    js.evaluateAwaiting("Promise.reject(new TypeError('nope'))")
} catch (e: JsError) {
    println(e.name)          // TypeError
    println(e.errorMessage)  // nope
}
```

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

If the body throws, the promise rejects with that message.

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
KiteJs { interruptWhen = { now() > deadline } }.use { js -> /* ... */ }
```

Setting `interruptWhen` turns the check on even when you set no instruction budget. Whatever you
set is still asked when `asyncKiteJs` adds its own cancellation check on top.

**One limit.** Cancelling from another coroutine cannot work on Kotlin/JS. The engine and the
canceller share the one thread there, so while a script loops nothing else can run and no signal
arrives. The hook itself works everywhere, because it is asked from inside the running script.
Use `instructionBudget` or a clock-reading `interruptWhen` if you need to stop a script on JS.
