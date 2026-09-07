# Module kitejs-coroutines

The engine behind suspending functions. `asyncKiteJs` builds one on a dispatcher that runs a
single call at a time, and `AsyncKiteJs` is how you reach it.

Awaiting a JavaScript promise, handing a `Deferred` to a script as a promise, binding host
functions that suspend, and stopping a running script when its caller is cancelled.
