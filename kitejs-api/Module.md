# Module kitejs-api

What an embedder programs against, whichever engine runs underneath. Build a `KiteJs` from an
engine, `KiteJs(Rhino) { }` or `KiteJs(QuickJs) { }`, bind what a script may reach with the host
bindings, run the script, and read the answer as a `JsValue`. The values, the handles, the
converters, the promise helpers and the exceptions behave the same on every engine; each engine
module adds only what is its own, in its configuration.
