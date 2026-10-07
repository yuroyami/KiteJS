# Evaluating scripts

## Running source

`evaluate` parses and runs a string. It answers the value of the last expression.

```kotlin
KiteJs(Rhino).use { js ->
    js.evaluate("var total = 0; for (var i = 1; i <= 10; i++) total += i; total").asInt()  // 55
}
```

The second argument names the source. It only shows up in error messages and stack traces, so
use something a reader will recognise.

```kotlin
js.evaluate(userScript, "user-rule.js")
```

## Reading the answer

Every result is a `JsValue`. Ask what it is, then read it.

```kotlin
val v = js.evaluate("({ name: 'ada', age: 36 })")

when (v.type) {
    JsType.OBJECT -> println(v.asObject()["name"].asString())
    JsType.NUMBER -> println(v.asDouble())
    else -> println(v)
}
```

`JsType` has ten members: `UNDEFINED`, `NULL`, `BOOLEAN`, `NUMBER`, `BIGINT`, `STRING`, `SYMBOL`,
`ARRAY`, `FUNCTION` and `OBJECT`. A `when` over it can be exhaustive, so the compiler tells you
when you have missed one.

### Readers that coerce

`asBoolean`, `asDouble`, `asInt` and `asString` convert the way JavaScript itself does.
`asString()` on the number 5 gives `"5"`, the same answer `String(5)` gives in a script.

```kotlin
js.evaluate("2 + 2").asString()    // "4"
js.evaluate("''").asBoolean()      // false
js.evaluate("[1, 2]").asString()   // "1,2"
```

`asLong()` preserves an integral Number or BigInt in Kotlin's signed 64-bit range. Other values
are converted to a numeric primitive first, so a numeric string or an object's `valueOf` works
too. Fractions, NaN, infinities and values outside that range throw `JsError` with the name
`RangeError`; negative zero becomes zero. It never wraps, truncates or saturates. A Number can
already have been rounded by JavaScript: use BigInt for an integer whose original digits must
remain exact. Typed `Long` callback arguments use this same conversion, and Kotlin Long values
outside the safe Number range cross into the script as BigInts.

```kotlin
js.evaluate("4294967296").asLong()            // 4294967296L
js.evaluate("9223372036854775807n").asLong()  // Long.MAX_VALUE
js.evaluate("4294967296").asInt()             // 0: ToInt32 still wraps
js.evaluate("1.5").asLong()                    // throws RangeError
```

### Readers that do not

`asObject`, `asArray` and `asFunction` throw if the value is something else. Each has an
`OrNull` twin that answers null instead.

```kotlin
val list = js.evaluate("[1, 2, 3]").asArrayOrNull() ?: return
println(list.size)      // 3
println(list.toList())  // [1.0, 2.0, 3.0]
```

`JsArray.length` is a Long and keeps the full JavaScript array length, up to 2^32 - 1. `size`
keeps its Int type; if the length exceeds Int.MAX_VALUE, reading `size` or materializing the
array with `values()`, `toList()` or `toKotlin()` throws `JsEngineError`. A large sparse array
still supports individual reads and writes using a string index, such as `array["2147483648"]`.
`add(value)` appends using the accurate length, and refuses an array already at the maximum
JavaScript length before writing anything.

### Converting the whole thing

`toKotlin()` walks the value all the way down. An object becomes a `Map`, an array becomes a
`List`, a number becomes a `Double`, and `undefined` becomes null. A function has no Kotlin twin,
so it stays a `JsFunction`.

In the other direction, Kotlin collections keep their graph: a list containing itself becomes
an array whose first element is that same array, and two references to one child collection
become two references to the same JavaScript object. This includes primitive arrays, byte
arrays and objects that registered converters turn into collections. Each call makes a separate
copy. Traversal uses an explicit work stack, so deeply nested input uses no recursive host calls.
Custom converter chains that cycle or take more than 256 transformations before reaching a
built-in type throw `JsEngineError`.

An `ArrayBuffer`, a typed array or a `DataView` becomes a `ByteArray` with a copy of the bytes it
views. `JsObject.toByteArrayOrNull()` gives the same copy for one object, and null for any other
object. Neither runs a getter that the script redefined. A detached buffer gives no bytes.

```kotlin
val pixels = js.evaluate("new Uint8ClampedArray([255, 0, 0, 255])").toKotlin() as ByteArray
```

```kotlin
val config = js.evaluate("({ retries: 3, hosts: ['a', 'b'] })").toKotlin()
// {retries=3.0, hosts=[a, b]}
```

If the object points back at itself, the cycle stops at the `JsObject` that closed it rather
than looping forever.

## Errors

Three things can go wrong, and each has its own type. All three are `JsException`.

| Type | When |
|---|---|
| `JsSyntaxError` | The source did not parse. Carries the file name, line and column |
| `JsError` | A script threw. Carries the thrown value, its name, its message and the script's own stack |
| `JsEngineError` | The engine gave up: it is closed, misused, or out of budget |

```kotlin
try {
    js.evaluate(userScript, "user-rule.js")
} catch (e: JsSyntaxError) {
    println("line ${e.lineNumber}: ${e.message}")
} catch (e: JsError) {
    println("${e.name}: ${e.errorMessage}")
    e.scriptStack.forEach { println("  $it") }
}
```

A script can throw anything, not only an `Error`. `e.value` is whatever it threw.

```kotlin
val e = runCatching { js.evaluate("throw 42") }.exceptionOrNull() as JsError
e.value.asInt()   // 42
```

## Running the same script more than once

`compile` parses once and gives back a `JsScript` you can run as often as you like. Parsing is
the expensive half, so this is worth doing for anything on a hot path.

```kotlin
val rule = js.compile("input.price * (1 - input.discount)", "pricing.js")

for (order in orders) {
    js.global["input"] = order
    total += rule.run().asDouble()
}
```

## Running the same script in many engines

A `JsScript` belongs to its engine. To skip the parse in other engines, keep its bytecode and load
that bytecode in each new engine. QuickJS has bytecode; on Rhino `bytecode()` answers null and
`loadBytecode` throws a `JsEngineError`.

```kotlin
val bytes = KiteJs(QuickJs).use { it.compile(bigLibrary, "library.js").bytecode() }

KiteJs(QuickJs).use { js ->
    if (bytes != null) js.loadBytecode(bytes).run() else js.evaluate(bigLibrary, "library.js")
}
```

- The bytecode keeps the file name and the lines, so a stack trace still points at the source.
- Bytecode fits only the engine build that wrote it. Keep it for the process, or key a saved
  copy on `KiteJs.version`.
- The engine runs bytecode without checking it. Load only bytecode your own code wrote, never
  bytes from a document or the network.

## Long scripts on the web

A browser page has one thread. A script that runs for a second freezes the page for that second.
`evaluatePausing` runs a script like `evaluate`, but the script pauses about once a slice and lets
the event loop draw and run timers before it goes on.

```kotlin
scope.launch {
    val answer = js.evaluatePausing(longScript, "long.js", slice = 16.milliseconds)
}
```

- Only QuickJS on a runtime with WebAssembly stack switching (JSPI) pauses: Chrome 137 and newer,
  and Node 25 and newer. `js.canPause` says whether this one does.
- Everywhere else, `evaluatePausing` runs the script to the end in one go, as `evaluate` does.
  On the JVM, Android and native, run a long script on a thread of its own instead.
- The script does not pause while a host function runs, since a pause cannot hold a Kotlin frame.
- One script pauses at a time in a process. A second `evaluatePausing` that starts while one is
  paused runs to the end in one go.
- While its script is paused, the engine refuses every other call with a `JsEngineError`. Other
  engines stay usable.

## Building values

```kotlin
js.global["settings"] = mapOf("retries" to 3, "verbose" to true)
js.global["names"] = listOf("ada", "grace")

val obj = js.newObject()
obj["id"] = 7
js.global["record"] = obj

val arr = js.newArray(1, "two", true)
```

The conversion table is fixed and does not use reflection:

| Kotlin | JavaScript |
|---|---|
| `null`, `Unit` | `undefined` |
| `Boolean` | boolean |
| `Int`, `Short`, `Byte`, `Float`, `Double` | number |
| `Long` inside the safe range | number |
| `Long` outside the safe range | BigInt |
| `Char`, `String`, any `CharSequence` | string |
| `ByteArray` | `Uint8Array` over a new `ArrayBuffer` with a copy of the bytes |
| `List`, `Array`, `Set`, the other primitive arrays | array |
| `Map` | object, with every key turned into a string |
| `JsValue`, `JsObject` | itself |

To add your own type, register a rule once:

```kotlin
Converters.register { value -> if (value is Money) value.amount.toDouble() else null }
```

Answer null to let the next rule try.
