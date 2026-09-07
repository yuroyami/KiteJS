# Binding host objects

A script can only reach what you give it. Everything on this page attaches to `js.global`, which
is the global object a script sees.

None of this uses reflection, so it behaves the same on every target.

## Functions

```kotlin
js.global.function("log") { args ->
    println(args.joinToString(" ") { it.asString() })
}

js.evaluate("log('hello', 42)")   // hello 42
```

The lambda takes a `List<JsValue>` and answers anything the conversion table understands. Missing
arguments are not an error: the list is simply shorter.

### Typed functions

If you name the types, the arguments and the answer are converted for you.

```kotlin
js.global.function<Double, Double, Double>("hypot") { a, b -> sqrt(a * a + b * b) }
js.global.function<String, Int>("length") { s -> s.length }

js.evaluate("hypot(3, 4)").asDouble()   // 5.0
```

One, two and three arguments are supported. Past that, take the list.

### Functions that need `this`

```kotlin
js.global.method("describe") { self, _ ->
    "n=" + self.asObject()["n"].asString()
}

js.evaluate("var o = { n: 4, d: describe }; o.d()")   // "n=4"
```

## Values and properties

```kotlin
js.global.obj("app") {
    property("name", "Reader")           // readable and writable
    constant("version", "1.4.0")         // a script cannot change it
    getter("uptime") { clock.seconds }   // computed on every read
    accessor(
        "theme",
        read = { settings.theme },
        write = { v -> settings.theme = v.asString() },
    )
}
```

`obj` builds a nested object and returns it, so you can keep a handle:

```kotlin
val app = js.global.obj("app") { property("name", "Reader") }
app["name"] = "Reader Pro"
```

### Property flags

Every binding takes a `PropertyFlags`. The defaults match what `obj.x = 1` gives you in a script.

```kotlin
property("id", 7, PropertyFlags(writable = false, enumerable = false))
```

| Flag | Default | What false means |
|---|---|---|
| `writable` | true | A script cannot assign to it |
| `enumerable` | true | It does not show up in `Object.keys` or `for...in` |
| `configurable` | true | It cannot be deleted or redefined |

Functions default to `enumerable = false`, the same as the engine's own built-ins.

## Constructors

```kotlin
js.global.constructor("Point", 2) { obj, args ->
    obj["x"] = args[0].asDouble()
    obj["y"] = args[1].asDouble()
}

js.evaluate("new Point(3, 4).x").asDouble()   // 3.0
```

## Binding a Kotlin object

`bind` exposes the members you name, and nothing else.

```kotlin
class Session(var user: String) {
    val startedAt: Long = now()
    fun renew(): Boolean = true
}

js.global.bind("session", session) {
    property("user", Session::user)          // a var, so a script can write it too
    property("startedAt", Session::startedAt) // a val, so it is read only
    method("renew") { renew() }
}
```

The property references are resolved at compile time. There is no reflection library involved,
and nothing you did not name is reachable.

## Calling back into the script

```kotlin
val handler = js.evaluate("(function (event) { return event.type + ':ok' })").asFunction()

println(handler(mapOf("type" to "click")).asString())   // "click:ok"
```

| Call | What it does |
|---|---|
| `f(args)` | Calls it with `this` set to the global object |
| `f.callOn(thisArg, args)` | Calls it with the `this` you name |
| `f.construct(args)` | Calls it with `new` |
| `f.bind(thisArg, args)` | A new function with `this` and the leading arguments fixed |
| `obj.call("name", args)` | Calls a method on an object, as `obj.name(...)` does |

## What a script cannot reach

Anything you did not bind. There is no `require`, no file system, no network, and no way to
reach a Kotlin class you did not hand over. A script that needs one of those needs you to bind a
function that does it.
