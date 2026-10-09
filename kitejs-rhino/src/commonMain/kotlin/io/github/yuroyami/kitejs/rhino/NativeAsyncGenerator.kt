/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

/**
 * The object an async generator function returns (ECMAScript 2018, 27.6). Its body is a frozen
 * interpreter frame, as an async function's is. `next`, `return` and `throw` each queue a
 * request and answer a promise; the body runs while requests wait, and each `yield` settles the
 * oldest one. This class follows the spec's AsyncGeneratorEnqueue, AsyncGeneratorResume,
 * AsyncGeneratorCompleteStep, AsyncGeneratorAwaitReturn and AsyncGeneratorDrainQueue, and runs
 * `yield*` itself, as [ES6Generator] does.
 */
public class NativeAsyncGenerator : ScriptableObject {

    private var savedState: Any? = null
    private var isStrict = false
    private var state = State.SUSPENDED_START
    private val queue = ArrayDeque<Request>()

    /** The iterator a `yield*` hands requests to, until it is done. */
    private var delegate: OpenIterator? = null

    /** Only for building the prototype object. */
    private constructor() : super()

    internal constructor(scope: Scriptable, function: JSFunction, savedState: Any?) : super() {
        this.savedState = savedState
        this.isStrict = function.descriptor.isStrict
        val top = getTopLevelScope(scope)
        parentScope = top
        prototype = getProperty(function, "prototype") as? Scriptable ?: getTopScopeValue(top, ASYNC_GENERATOR_TAG) as? Scriptable
    }

    override val className: String
        get() = "AsyncGenerator"

    private enum class State { SUSPENDED_START, SUSPENDED_YIELD, EXECUTING, AWAITING_RETURN, COMPLETED }

    /** One call of `next`, `return` or `throw`: a NativeGenerator operation, its value and its promise. */
    private class Request(val operation: Int, val value: Any?, val promise: NativePromise)

    /** What a `yield` in an async generator suspends the body with. */
    internal class YieldRequest(val value: Any?)

    private val scope: Scriptable get() = parentScope!!

    // ---- The requests ---------------------------------------------------------------------------

    private fun enqueue(cx: Context, operation: Int, value: Any?): NativePromise {
        val promise = NativePromise.newIntrinsic(cx, scope)
        val request = Request(operation, value, promise)
        when (operation) {
            NativeGenerator.GENERATOR_SEND -> {
                if (state == State.COMPLETED) {
                    promise.resolveFromEngine(cx, scope, ES6Iterator.makeIteratorResult(cx, scope, true))
                    return promise
                }
                queue.addLast(request)
                if (state == State.SUSPENDED_START || state == State.SUSPENDED_YIELD) resume(cx, request)
            }
            NativeGenerator.GENERATOR_CLOSE -> {
                queue.addLast(request)
                if (state == State.SUSPENDED_START || state == State.COMPLETED) {
                    state = State.AWAITING_RETURN
                    awaitReturn(cx)
                } else if (state == State.SUSPENDED_YIELD) {
                    resume(cx, request)
                }
            }
            else -> {
                if (state == State.SUSPENDED_START) state = State.COMPLETED
                if (state == State.COMPLETED) {
                    promise.rejectFromEngine(cx, scope, value)
                    return promise
                }
                queue.addLast(request)
                if (state == State.SUSPENDED_YIELD) resume(cx, request)
            }
        }
        return promise
    }

    /** AsyncGeneratorResume: the body starts, or takes [request] at the `yield` it waits at. */
    private fun resume(cx: Context, request: Request) {
        val started = state != State.SUSPENDED_START
        state = State.EXECUTING
        if (started) unwrapYieldResumption(cx, request.operation, request.value) else bodyStep(cx, NativeGenerator.GENERATOR_SEND, Undefined.instance)
    }

    /** AsyncGeneratorUnwrapYieldResumption: a `return` awaits its value at the `yield` first. */
    private fun unwrapYieldResumption(cx: Context, operation: Int, value: Any?) {
        if (operation != NativeGenerator.GENERATOR_CLOSE) return deliver(cx, operation, value)
        awaitThen(cx, value, { v -> deliver(cx, NativeGenerator.GENERATOR_CLOSE, v) }, { e -> deliver(cx, NativeGenerator.GENERATOR_THROW, e) })
    }

    /** Hands a resumption to the `yield*` delegate when there is one, else to the body. */
    private fun deliver(cx: Context, operation: Int, value: Any?) {
        when {
            delegate != null -> delegateStep(cx, operation, value)
            operation == NativeGenerator.GENERATOR_CLOSE -> bodyReturn(cx, value)
            else -> bodyStep(cx, operation, value)
        }
    }

    /** AsyncGeneratorCompleteStep: settles the oldest request. */
    private fun completeStep(cx: Context, thrown: Boolean, value: Any?, done: Boolean) {
        val request = queue.removeFirst()
        if (thrown) {
            request.promise.rejectFromEngine(cx, scope, value)
        } else {
            request.promise.resolveFromEngine(cx, scope, ES6Iterator.makeIteratorResult(cx, scope, done, value))
        }
    }

    /** AsyncGeneratorYield: settles the oldest request, then goes on with the next one, if any. */
    private fun yielded(cx: Context, value: Any?) {
        completeStep(cx, false, value, false)
        val next = queue.firstOrNull()
        if (next == null) {
            state = State.SUSPENDED_YIELD
        } else {
            unwrapYieldResumption(cx, next.operation, next.value)
        }
    }

    /** The body ended: settle the request that ran it, then every one that waits. */
    private fun completed(cx: Context, thrown: Boolean, value: Any?) {
        state = State.COMPLETED
        delegate = null
        completeStep(cx, thrown, value, true)
        drainQueue(cx)
    }

    /** AsyncGeneratorDrainQueue. */
    private fun drainQueue(cx: Context) {
        while (true) {
            val next = queue.firstOrNull() ?: return
            if (next.operation == NativeGenerator.GENERATOR_CLOSE) {
                state = State.AWAITING_RETURN
                awaitReturn(cx)
                return
            }
            val thrown = next.operation == NativeGenerator.GENERATOR_THROW
            completeStep(cx, thrown, if (thrown) next.value else Undefined.instance, true)
        }
    }

    /** AsyncGeneratorAwaitReturn: a `return` on a generator that is not running awaits its value. */
    private fun awaitReturn(cx: Context) {
        val value = queue.first().value
        awaitThen(cx, value, { v ->
            state = State.COMPLETED
            completeStep(cx, false, v, true)
            drainQueue(cx)
        }, { e ->
            state = State.COMPLETED
            completeStep(cx, true, e, true)
            drainQueue(cx)
        })
    }

    // ---- The body ------------------------------------------------------------------------------

    /** Runs the body with [operation] and [value] until it awaits, yields or ends. */
    private fun bodyStep(cx: Context, operation: Int, value: Any?) {
        var op = operation
        var v = value
        while (true) {
            val result = try {
                Interpreter.resumeAsync(cx, op, savedState, v)
            } catch (e: NativeGenerator.GeneratorClosedException) {
                // A return that went through every finally block.
                return completed(cx, false, e.value)
            } catch (e: RhinoException) {
                return completed(cx, true, errorValue(cx, e))
            }
            when (result) {
                is AsyncFunctionDriver.AwaitRequest -> {
                    try {
                        NativePromise.await(cx, scope, result.value, bodyResumption(NativeGenerator.GENERATOR_SEND), bodyResumption(NativeGenerator.GENERATOR_THROW))
                        return
                    } catch (e: RhinoException) {
                        op = NativeGenerator.GENERATOR_THROW
                        v = errorValue(cx, e)
                    }
                }
                is YieldRequest -> return yielded(cx, result.value)
                is ES6Generator.YieldStarResult -> {
                    try {
                        delegate = OpenIterator.openAsync(cx, scope, result.result, -1)
                    } catch (e: RhinoException) {
                        op = NativeGenerator.GENERATOR_THROW
                        v = errorValue(cx, e)
                        continue
                    }
                    return delegateStep(cx, NativeGenerator.GENERATOR_SEND, Undefined.instance)
                }
                else -> return completed(cx, false, result)
            }
        }
    }

    private fun bodyResumption(operation: Int): Callable = reaction { cx, value -> bodyStep(cx, operation, value) }

    // ---- yield* ------------------------------------------------------------------------------

    /**
     * One pass of `yield*` in an async generator (ECMAScript 2018, 14.4.14): hands what the
     * generator received to the delegate's `next`, `throw` or `return`, and awaits its answer.
     */
    private fun delegateStep(cx: Context, operation: Int, received: Any?) {
        val record = delegate!!
        val inner: Any?
        try {
            when (operation) {
                NativeGenerator.GENERATOR_SEND -> {
                    val next = record.nextMethod as? Callable
                        ?: throw ScriptRuntime.typeErrorById("msg.isnt.function", "iterator.next", ScriptRuntime.typeOf(record.nextMethod))
                    inner = next.call(cx, scope, record.iterator, arrayOf(received))
                }
                NativeGenerator.GENERATOR_THROW -> {
                    val method = getMethod(record.iterator, "throw")
                    if (method == null) return closeDelegateForThrow(cx)
                    inner = method.call(cx, scope, record.iterator, arrayOf(received))
                }
                else -> {
                    val method = getMethod(record.iterator, ES6Iterator.RETURN_PROPERTY)
                    if (method == null) {
                        delegate = null
                        return awaitThen(cx, received, { v -> bodyReturn(cx, v) }, { e -> bodyStep(cx, NativeGenerator.GENERATOR_THROW, e) })
                    }
                    inner = method.call(cx, scope, record.iterator, arrayOf(received))
                }
            }
        } catch (e: RhinoException) {
            delegate = null
            return bodyStep(cx, NativeGenerator.GENERATOR_THROW, errorValue(cx, e))
        }
        val isReturn = operation == NativeGenerator.GENERATOR_CLOSE
        awaitThen(cx, inner, { r -> delegateResult(cx, r, isReturn) }, { e ->
            delegate = null
            bodyStep(cx, NativeGenerator.GENERATOR_THROW, e)
        })
    }

    /** What the delegate answered, awaited: done ends the `yield*`, anything else is yielded on. */
    private fun delegateResult(cx: Context, result: Any?, isReturn: Boolean) {
        val done: Boolean
        val value: Any?
        try {
            if (result !is Scriptable) throw ScriptRuntime.typeErrorById("msg.iterator.result.not.object", ScriptRuntime.toString(result))
            done = ScriptRuntime.toBoolean(OpenIterator.read(result, ES6Iterator.DONE_PROPERTY))
            value = OpenIterator.read(result, ES6Iterator.VALUE_PROPERTY)
        } catch (e: RhinoException) {
            delegate = null
            return bodyStep(cx, NativeGenerator.GENERATOR_THROW, errorValue(cx, e))
        }
        if (!done) return yielded(cx, value)
        delegate = null
        if (!isReturn) return bodyStep(cx, NativeGenerator.GENERATOR_SEND, value)
        // A return the delegate finished returns from the generator, with its value awaited.
        awaitThen(cx, value, { v -> bodyReturn(cx, v) }, { e -> bodyStep(cx, NativeGenerator.GENERATOR_THROW, e) })
    }

    /**
     * A throw into a delegate with no `throw` method: close the delegate, awaiting what its
     * `return` answers, then throw a TypeError into the body.
     */
    private fun closeDelegateForThrow(cx: Context) {
        val record = delegate!!
        delegate = null
        val inner: Any?
        try {
            val method = getMethod(record.iterator, ES6Iterator.RETURN_PROPERTY)
            if (method == null) return bodyStep(cx, NativeGenerator.GENERATOR_THROW, noThrowError(cx))
            inner = method.call(cx, scope, record.iterator, ScriptRuntime.emptyArgs)
        } catch (e: RhinoException) {
            return bodyStep(cx, NativeGenerator.GENERATOR_THROW, errorValue(cx, e))
        }
        awaitThen(cx, inner, { r ->
            val error = if (r is Scriptable) noThrowError(cx) else typeError(cx, "msg.iterator.result.not.object", ScriptRuntime.toString(r))
            bodyStep(cx, NativeGenerator.GENERATOR_THROW, error)
        }, { e -> bodyStep(cx, NativeGenerator.GENERATOR_THROW, e) })
    }

    private fun bodyReturn(cx: Context, value: Any?) =
        bodyStep(cx, NativeGenerator.GENERATOR_CLOSE, NativeGenerator.GeneratorClosedException(value))

    private fun noThrowError(cx: Context): Any? = typeError(cx, "msg.iterator.no.throw")

    private fun typeError(cx: Context, id: String, vararg args: Any?): Any? =
        errorValue(cx, ScriptRuntime.typeErrorById(id, *args))

    private fun getMethod(obj: Scriptable, name: String): Callable? {
        val method = OpenIterator.read(obj, name)
        if (method == null || Undefined.isUndefined(method)) return null
        return method as? Callable ?: throw ScriptRuntime.typeErrorById("msg.isnt.function", "iterator.$name", ScriptRuntime.typeOf(method))
    }

    // ---- Awaiting --------------------------------------------------------------------------------

    /**
     * Await from outside the body: [onFulfilled] or [onRejected] runs from the microtask queue
     * once [value] settles, or [onRejected] runs now when resolving [value] throws.
     */
    private fun awaitThen(cx: Context, value: Any?, onFulfilled: (Any?) -> Unit, onRejected: (Any?) -> Unit) {
        try {
            NativePromise.await(cx, scope, value, reaction { _, v -> onFulfilled(v) }, reaction { _, e -> onRejected(e) })
        } catch (e: RhinoException) {
            onRejected(errorValue(cx, e))
        }
    }

    private fun reaction(body: (Context, Any?) -> Unit): Callable = HostReaction(scope, isStrict, body)

    private fun errorValue(cx: Context, e: RhinoException): Any? =
        if (e is JavaScriptException) e.value else ScriptRuntime.wrapException(e, scope, cx)

    /**
     * A handler the engine attaches to a promise. The microtask queue runs outside any script,
     * so the handler runs inside a top call of its own, as a function called from the host would.
     */
    internal class HostReaction(private val realm: Scriptable, private val isStrict: Boolean, private val body: (Context, Any?) -> Unit) : Callable {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (!ScriptRuntime.hasTopCall(cx)) return ScriptRuntime.doTopCall(this, cx, scope, thisObj, args, isStrict)
            cx.withRealm(realm) { body(cx, if (args.isNotEmpty()) args[0] else Undefined.instance) }
            return Undefined.instance
        }
    }

    public companion object {
        /** The key %AsyncGeneratorPrototype% is cached under on the top scope. */
        internal val ASYNC_GENERATOR_TAG: Any = "AsyncGenerator"

        private val ASYNC_ITERATOR_PROTOTYPE_TAG: Any = "AsyncIteratorPrototype"

        /**
         * %AsyncIteratorPrototype% (ECMAScript 2018, 27.1.3) of [scope]'s realm: an ordinary
         * object whose one property is `[Symbol.asyncIterator]`, which answers its `this`.
         */
        internal fun asyncIteratorPrototype(scope: Scriptable, sealed: Boolean = false): ScriptableObject {
            val top = getTopLevelScope(scope) as ScriptableObject
            (top.getAssociatedValue(ASYNC_ITERATOR_PROTOTYPE_TAG) as? ScriptableObject)?.let { return it }
            val proto = NativeObject()
            proto.parentScope = top
            proto.prototype = getObjectPrototype(top)
            val iterator = LambdaFunction(top, "[Symbol.asyncIterator]", 0, SerializableCallable { _, _, thisObj, _ -> ScriptRuntime.takeReceiver(thisObj) ?: thisObj })
            proto.defineProperty(SymbolKey.ASYNC_ITERATOR, iterator, DONTENUM)
            if (sealed) proto.sealObject()
            return top.associateValue(ASYNC_ITERATOR_PROTOTYPE_TAG, proto) as ScriptableObject
        }

        /**
         * Builds %AsyncGeneratorPrototype% (ECMAScript 2018, 27.6.1). The caller links its
         * `constructor` and seals it.
         */
        internal fun init(scope: ScriptableObject, sealed: Boolean): ScriptableObject {
            val prototype = NativeAsyncGenerator()
            prototype.parentScope = scope
            prototype.prototype = asyncIteratorPrototype(scope, sealed)
            for ((name, operation) in listOf("next" to NativeGenerator.GENERATOR_SEND, "return" to NativeGenerator.GENERATOR_CLOSE, "throw" to NativeGenerator.GENERATOR_THROW)) {
                val method = LambdaFunction(scope, name, 1, SerializableCallable { cx, s, thisObj, args -> request(cx, s, thisObj, operation, args) })
                defineProperty(prototype, name, method, DONTENUM)
            }
            prototype.defineProperty(SymbolKey.TO_STRING_TAG, "AsyncGenerator", DONTENUM or READONLY)
            scope.associateValue(ASYNC_GENERATOR_TAG, prototype)
            return prototype
        }

        /** `next`, `return` and `throw`: a `this` that is no async generator rejects (AsyncGeneratorValidate). */
        private fun request(cx: Context, scope: Scriptable, thisObj: Scriptable?, operation: Int, args: Array<Any?>): Any? {
            val value = if (args.isNotEmpty()) args[0] else Undefined.instance
            val generator = thisObj as? NativeAsyncGenerator
            if (generator == null || generator.savedState == null) {
                val promise = NativePromise.newIntrinsic(cx, scope)
                val name = if (operation == NativeGenerator.GENERATOR_SEND) "next" else if (operation == NativeGenerator.GENERATOR_CLOSE) "return" else "throw"
                val error = ScriptRuntime.typeErrorById("msg.incompat.call", "AsyncGenerator.prototype.$name")
                promise.rejectFromEngine(cx, scope, ScriptRuntime.wrapException(error, scope, cx))
                return promise
            }
            return generator.enqueue(cx, operation, value)
        }
    }
}
