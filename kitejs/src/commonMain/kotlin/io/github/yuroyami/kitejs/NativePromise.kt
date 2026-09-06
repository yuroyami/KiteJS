/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The `Promise` builtin.
 *
 * Every reaction runs as a microtask on `Context`'s queue, which `doTopCall` drains after the
 * script's synchronous part finishes. That queue, not a thread, is what makes the ordering rules
 * work: `then` never runs before the code that registered it has returned.
 */
class NativePromise : ScriptableObject() {

    private enum class State { PENDING, FULFILLED, REJECTED }

    private enum class ReactionType { FULFILL, REJECT }

    private var state = State.PENDING
    private var result: Any? = null
    private var handled = false

    private var fulfillReactions = ArrayList<Reaction>()
    private var rejectReactions = ArrayList<Reaction>()

    override val className: String
        get() = "Promise"

    internal fun getResult(): Any? = result

    private fun markHandled(cx: Context) {
        if (!handled) {
            cx.unhandledPromiseTracker.promiseHandled(this)
            handled = true
        }
    }

    /** The spec's FulfillPromise: settle, then queue every reaction that was waiting. */
    private fun fulfillPromise(cx: Context, scope: Scriptable, value: Any?): Any {
        result = value
        val reactions = fulfillReactions
        fulfillReactions = ArrayList()
        if (rejectReactions.isNotEmpty()) rejectReactions = ArrayList()
        state = State.FULFILLED
        for (r in reactions) cx.enqueueMicrotask(Context.Runnable { r.invoke(cx, scope, value) })
        return Undefined.instance
    }

    /** The spec's RejectPromise. A rejection with no reaction waiting is an unhandled one. */
    private fun rejectPromise(cx: Context, scope: Scriptable, reason: Any?): Any {
        result = reason
        val reactions = rejectReactions
        rejectReactions = ArrayList()
        if (fulfillReactions.isNotEmpty()) fulfillReactions = ArrayList()
        state = State.REJECTED
        cx.unhandledPromiseTracker.promiseRejected(this)
        for (r in reactions) cx.enqueueMicrotask(Context.Runnable { r.invoke(cx, scope, reason) })
        if (reactions.isNotEmpty()) markHandled(cx)
        return Undefined.instance
    }

    private fun then(cx: Context, scope: Scriptable, args: Array<Any?>): Any? {
        val constructable = AbstractEcmaObjectOperations.speciesConstructor(
            cx,
            this,
            TopLevel.getBuiltinCtor(cx, getTopLevelScope(scope), TopLevel.Builtins.Promise)!!,
        )
        val capability = Capability(cx, scope, constructable)

        val onFulfilled = if (args.isNotEmpty() && args[0] is Callable) args[0] as Callable else null
        val onRejected = if (args.size >= 2 && args[1] is Callable) args[1] as Callable else null

        val fulfillReaction = Reaction(capability, ReactionType.FULFILL, onFulfilled)
        val rejectReaction = Reaction(capability, ReactionType.REJECT, onRejected)

        when (state) {
            State.PENDING -> {
                fulfillReactions.add(fulfillReaction)
                rejectReactions.add(rejectReaction)
            }
            State.FULFILLED -> {
                val value = result
                cx.enqueueMicrotask(Context.Runnable { fulfillReaction.invoke(cx, scope, value) })
            }
            State.REJECTED -> {
                markHandled(cx)
                val value = result
                cx.enqueueMicrotask(Context.Runnable { rejectReaction.invoke(cx, scope, value) })
            }
        }
        return capability.promise
    }

    /** The Promise Resolve Thenable Job: hand our own resolvers to someone else's `then`. */
    private fun callThenable(cx: Context, scope: Scriptable, resolution: Any?, thenFunc: Callable) {
        val resolving = ResolvingFunctions(scope, this)
        val thisObj = if (resolution is Scriptable) resolution else Undefined.SCRIPTABLE_UNDEFINED
        try {
            thenFunc.call(cx, scope, thisObj, arrayOf(resolving.resolve, resolving.reject))
        } catch (re: RhinoException) {
            resolving.reject.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, arrayOf(getErrorObject(cx, scope, re)))
        }
    }

    /** The pair of functions the executor is handed. Only the first call to either counts. */
    private class ResolvingFunctions(topScope: Scriptable, promise: NativePromise) {
        private var alreadyResolved = false
        val resolve: LambdaFunction
        val reject: LambdaFunction

        init {
            resolve = LambdaFunction(topScope, 1, SerializableCallable { cx, scope, _, args ->
                doResolve(cx, scope, promise, if (args.isNotEmpty()) args[0] else Undefined.instance)
            })
            reject = LambdaFunction(topScope, 1, SerializableCallable { cx, scope, _, args ->
                doReject(cx, scope, promise, if (args.isNotEmpty()) args[0] else Undefined.instance)
            })
        }

        private fun doReject(cx: Context, scope: Scriptable, promise: NativePromise, reason: Any?): Any {
            if (alreadyResolved) return Undefined.instance
            alreadyResolved = true
            return promise.rejectPromise(cx, scope, reason)
        }

        private fun doResolve(cx: Context, scope: Scriptable, promise: NativePromise, resolution: Any?): Any {
            if (alreadyResolved) return Undefined.instance
            alreadyResolved = true

            if (resolution === promise) {
                val err = ScriptRuntime.newNativeError(
                    cx, scope, TopLevel.NativeErrors.TypeError, arrayOf<Any?>("No promise self-resolution"),
                )
                return promise.rejectPromise(cx, scope, err)
            }

            if (!ScriptRuntime.isObject(resolution)) return promise.fulfillPromise(cx, scope, resolution)

            val thenObj = getProperty(ensureScriptable(resolution), "then")
            if (thenObj !is Callable) return promise.fulfillPromise(cx, scope, resolution)

            // A thenable is adopted through a microtask, never synchronously.
            cx.enqueueMicrotask(Context.Runnable { promise.callThenable(cx, scope, resolution, thenObj) })
            return Undefined.instance
        }
    }

    /** One waiting handler plus the promise its answer settles. */
    private class Reaction(
        val capability: Capability,
        val reaction: ReactionType,
        val handler: Callable?,
    ) {
        /** NewPromiseReactionJob: run the handler, then settle the next promise with its answer. */
        fun invoke(cx: Context, scope: Scriptable, arg: Any?) {
            try {
                val result: Any?
                if (handler == null) {
                    // No handler means the value passes straight through, in whichever direction.
                    when (reaction) {
                        ReactionType.FULFILL -> result = arg
                        ReactionType.REJECT -> {
                            capability.reject.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, arrayOf(arg))
                            return
                        }
                    }
                } else {
                    result = handler.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, arrayOf(arg))
                }
                capability.resolve.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, arrayOf(result))
            } catch (re: RhinoException) {
                capability.reject.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, arrayOf(getErrorObject(cx, scope, re)))
            }
        }
    }

    /**
     * A promise plus its resolve and reject functions, obtained by running a constructor the way
     * the Promise constructor behaves. That is what lets a subclass or a look-alike stand in.
     */
    private class Capability(topCx: Context, topScope: Scriptable, pc: Any?) {
        val promise: Any?
        private var rawResolve: Any? = Undefined.instance
        private var rawReject: Any? = Undefined.instance
        val resolve: Callable
        val reject: Callable

        init {
            if (pc !is Constructable) throw ScriptRuntime.typeErrorById("msg.constructor.expected")
            val executorFunc = LambdaFunction(topScope, 2, SerializableCallable { _, _, _, args -> executor(args) })

            promise = pc.construct(topCx, topScope, arrayOf<Any?>(executorFunc))

            if (rawResolve !is Callable) throw ScriptRuntime.typeErrorById("msg.function.expected")
            resolve = rawResolve as Callable
            if (rawReject !is Callable) throw ScriptRuntime.typeErrorById("msg.function.expected")
            reject = rawReject as Callable
        }

        private fun executor(args: Array<Any?>): Any {
            if (!Undefined.isUndefined(rawResolve) || !Undefined.isUndefined(rawReject)) {
                throw ScriptRuntime.typeErrorById("msg.promise.capability.state")
            }
            if (args.isNotEmpty()) rawResolve = args[0]
            if (args.size > 1) rawReject = args[1]
            return Undefined.instance
        }
    }

    /** Runs `Promise.all` and `Promise.allSettled`, which differ only in how a rejection lands. */
    private class PromiseAllResolver(
        val iterator: IteratorLikeIterable.Itr,
        val thisObj: Scriptable,
        val capability: Capability,
        val failFast: Boolean,
    ) {
        val values = ArrayList<Any?>()

        /** Starts at one so the walk itself counts; it drops to zero when the last one settles. */
        var remainingElements = 1

        fun resolve(topCx: Context, topScope: Scriptable): Any? {
            var index = 0
            // Looked up first, so a throw here happens before the iterator is touched.
            val resolve = ScriptRuntime.getPropAndThis(thisObj, "resolve", topCx, topScope)!!

            while (true) {
                if (index == MAX_PROMISES) throw ScriptRuntime.rangeErrorById("msg.promise.all.toobig")
                val hasNext: Boolean
                var nextVal: Any? = Undefined.instance
                var nextOk = false
                try {
                    hasNext = iterator.hasNext()
                    if (hasNext) nextVal = iterator.next()
                    nextOk = true
                } finally {
                    if (!nextOk) iterator.isDone = true
                }

                if (!hasNext) {
                    if (--remainingElements == 0) finalResolution(topCx, topScope)
                    return capability.promise
                }

                values.add(Undefined.instance)

                val nextPromise = resolve.call(topCx, topScope, arrayOf(nextVal))
                val eltResolver = PromiseElementResolver(index)
                val resolveFunc = LambdaFunction(topScope, 1, SerializableCallable { cx, scope, _, args ->
                    var value = if (args.isNotEmpty()) args[0] else Undefined.instance
                    if (!failFast) {
                        val elementResult = cx.newObject(scope)
                        elementResult.put("status", elementResult, "fulfilled")
                        elementResult.put("value", elementResult, value)
                        value = elementResult
                    }
                    eltResolver.resolve(cx, scope, value, this)
                })

                var rejectFunc: Callable = capability.reject
                if (!failFast) {
                    // allSettled turns a rejection into an ordinary result, so nothing fails fast.
                    val resolveSettledRejection = LambdaFunction(topScope, 1, SerializableCallable { cx, scope, _, args ->
                        val r = cx.newObject(scope)
                        r.put("status", r, " rejected")
                        r.put("reason", r, if (args.isNotEmpty()) args[0] else Undefined.instance)
                        eltResolver.resolve(cx, scope, r, this)
                    })
                    resolveSettledRejection.setStandardPropertyAttributes(DONTENUM or READONLY)
                    rejectFunc = resolveSettledRejection
                }
                remainingElements++

                val thenFunc = ScriptRuntime.getPropAndThis(nextPromise, "then", topCx, topScope)!!
                thenFunc.call(topCx, topScope, arrayOf(resolveFunc, rejectFunc))
                index++
            }
        }

        fun finalResolution(cx: Context, scope: Scriptable) {
            val newArray = cx.newArray(scope, values.toTypedArray())
            capability.resolve.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, arrayOf<Any?>(newArray))
        }

        companion object {
            // The same ceiling V8 uses.
            const val MAX_PROMISES = 1 shl 21
        }
    }

    /** Runs `Promise.any`, which settles on the first success and gathers every failure. */
    private class PromiseAnyRejector(
        val iterator: IteratorLikeIterable.Itr,
        val thisObj: Scriptable,
        val capability: Capability,
    ) {
        val errors = ArrayList<Any?>()
        var remainingElements = 1

        fun reject(topCx: Context, topScope: Scriptable): Any? {
            var index = 0
            val resolve = ScriptRuntime.getPropAndThis(thisObj, "resolve", topCx, topScope)!!

            while (true) {
                if (index == MAX_PROMISES) throw ScriptRuntime.rangeErrorById("msg.promise.any.toobig")
                val hasNext: Boolean
                var nextVal: Any? = Undefined.instance
                var nextOk = false
                try {
                    hasNext = iterator.hasNext()
                    if (hasNext) nextVal = iterator.next()
                    nextOk = true
                } finally {
                    if (!nextOk) iterator.isDone = true
                }

                if (!hasNext) {
                    if (--remainingElements == 0) {
                        // Nothing at all was supplied, so the AggregateError is thrown, not
                        // delivered through the promise.
                        val newArray = topCx.newArray(topScope, errors.toTypedArray())
                        val error = topCx.newObject(topScope, "AggregateError", arrayOf<Any?>(newArray)) as NativeError
                        throw JavaScriptException(error, null, 0)
                    }
                    return capability.promise
                }

                errors.add(Undefined.instance)

                val nextPromise = resolve.call(topCx, topScope, arrayOf(nextVal))
                val eltResolver = PromiseElementResolver(index)
                val rejectFunc = LambdaFunction(topScope, 1, SerializableCallable { cx, scope, _, args ->
                    eltResolver.reject(cx, scope, if (args.isNotEmpty()) args[0] else Undefined.instance, this)
                })
                remainingElements++

                val thenFunc = ScriptRuntime.getPropAndThis(nextPromise, "then", topCx, topScope)!!
                thenFunc.call(topCx, topScope, arrayOf(capability.resolve, rejectFunc))
                index++
            }
        }

        fun finalRejection(cx: Context, scope: Scriptable) {
            val newArray = cx.newArray(scope, errors.toTypedArray())
            val error = cx.newObject(scope, "AggregateError", arrayOf<Any?>(newArray)) as NativeError
            capability.reject.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, arrayOf<Any?>(error))
        }

        companion object {
            const val MAX_PROMISES = 1 shl 21
        }
    }

    /** Drops one settled element into its slot. Only the first call for that slot counts. */
    private class PromiseElementResolver(private val index: Int) {
        private var alreadyCalled = false

        fun resolve(cx: Context, scope: Scriptable, result: Any?, resolver: PromiseAllResolver): Any {
            if (alreadyCalled) return Undefined.instance
            alreadyCalled = true
            resolver.values[index] = result
            if (--resolver.remainingElements == 0) resolver.finalResolution(cx, scope)
            return Undefined.instance
        }

        fun reject(cx: Context, scope: Scriptable, result: Any?, rejector: PromiseAnyRejector): Any {
            if (alreadyCalled) return Undefined.instance
            alreadyCalled = true
            rejector.errors[index] = result
            if (--rejector.remainingElements == 0) rejector.finalRejection(cx, scope)
            return Undefined.instance
        }
    }

    companion object {

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val constructor = LambdaConstructor(
                scope,
                "Promise",
                1,
                LambdaConstructor.CONSTRUCTOR_NEW,
                SerializableConstructable { icx, s, args -> constructor(icx, s, args) },
            )
            constructor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)

            constructor.defineConstructorMethod(scope, "resolve", 1, SerializableCallable { icx, s, t, a -> js_resolve(icx, s, t, a) })
            constructor.defineConstructorMethod(scope, "reject", 1, SerializableCallable { icx, s, t, a -> js_reject(icx, s, t, a) })
            constructor.defineConstructorMethod(scope, "all", 1, SerializableCallable { icx, s, t, a -> doAll(icx, s, t, a, true) })
            constructor.defineConstructorMethod(scope, "allSettled", 1, SerializableCallable { icx, s, t, a -> doAll(icx, s, t, a, false) })
            constructor.defineConstructorMethod(scope, "race", 1, SerializableCallable { icx, s, t, a -> race(icx, s, t, a) })
            constructor.defineConstructorMethod(scope, "any", 1, SerializableCallable { icx, s, t, a -> any(icx, s, t, a) })
            constructor.defineConstructorMethod(scope, "withResolvers", 0, SerializableCallable { icx, s, t, a -> withResolvers(icx, s, t) })
            constructor.defineConstructorMethod(scope, "try", 1, SerializableCallable { icx, s, t, a -> promiseTry(icx, s, t, a) })

            ScriptRuntimeES6.addSymbolSpecies(cx, scope, constructor)

            constructor.definePrototypeMethod(scope, "then", 2, SerializableCallable { icx, s, t, a ->
                LambdaConstructor.convertThisObject<NativePromise>(t).then(icx, s, a)
            })
            constructor.definePrototypeMethod(scope, "catch", 1, SerializableCallable { icx, s, t, a -> doCatch(icx, s, t, a) })
            constructor.definePrototypeMethod(scope, "finally", 1, SerializableCallable { icx, s, t, a -> doFinally(icx, s, t, a) })

            constructor.definePrototypeProperty(SymbolKey.TO_STRING_TAG, "Promise", DONTENUM or READONLY)
            if (sealed) {
                constructor.sealObject()
                (constructor.prototypeProperty as ScriptableObject).sealObject()
            }
            return constructor
        }

        private fun constructor(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
            if (args.isEmpty() || args[0] !is Callable) throw ScriptRuntime.typeErrorById("msg.function.expected")
            val executor = args[0] as Callable
            val promise = NativePromise()
            val resolving = ResolvingFunctions(scope, promise)

            var thisObj: Scriptable = Undefined.SCRIPTABLE_UNDEFINED
            if (!cx.isStrictMode()) {
                cx.topCallScope?.let { thisObj = it }
            }

            try {
                executor.call(cx, scope, thisObj, arrayOf(resolving.resolve, resolving.reject))
            } catch (re: RhinoException) {
                // An executor that throws rejects the promise rather than propagating.
                resolving.reject.call(cx, scope, thisObj, arrayOf(getErrorObject(cx, scope, re)))
            }
            return promise
        }

        private fun js_resolve(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (!ScriptRuntime.isObject(thisObj)) {
                throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeOf(thisObj))
            }
            return resolveInternal(cx, scope, thisObj, if (args.isNotEmpty()) args[0] else Undefined.instance)
        }

        /** The spec's PromiseResolve: an already-matching promise is handed straight back. */
        private fun resolveInternal(cx: Context, scope: Scriptable, constructor: Any?, arg: Any?): Any? {
            if (arg is NativePromise) {
                if (ScriptRuntime.getObjectProp(arg, "constructor", cx, scope) === constructor) return arg
            }
            val cap = Capability(cx, scope, constructor)
            cap.resolve.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, arrayOf(arg))
            return cap.promise
        }

        private fun js_reject(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (!ScriptRuntime.isObject(thisObj)) {
                throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeOf(thisObj))
            }
            val cap = Capability(cx, scope, thisObj)
            cap.reject.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, arrayOf(if (args.isNotEmpty()) args[0] else Undefined.instance))
            return cap.promise
        }

        private fun doAll(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>, failFast: Boolean): Any? {
            val cap = Capability(cx, scope, thisObj)
            val arg = if (args.isNotEmpty()) args[0] else Undefined.instance

            val iterable: IteratorLikeIterable
            try {
                iterable = IteratorLikeIterable(cx, scope, ScriptRuntime.callIterator(arg, cx, scope))
            } catch (re: RhinoException) {
                cap.reject.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, arrayOf(getErrorObject(cx, scope, re)))
                return cap.promise
            }

            val iterator = iterable.iterator()
            try {
                // Capability threw above if `this` was not a constructor, so it is an object here.
                val resolver = PromiseAllResolver(iterator, checkNotNull(thisObj), cap, failFast)
                try {
                    return resolver.resolve(cx, scope)
                } finally {
                    if (!iterator.isDone) iterable.close()
                }
            } catch (re: RhinoException) {
                cap.reject.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, arrayOf(getErrorObject(cx, scope, re)))
                return cap.promise
            }
        }

        private fun race(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val cap = Capability(cx, scope, thisObj)
            val arg = if (args.isNotEmpty()) args[0] else Undefined.instance

            val iterable: IteratorLikeIterable
            try {
                iterable = IteratorLikeIterable(cx, scope, ScriptRuntime.callIterator(arg, cx, scope))
            } catch (re: RhinoException) {
                cap.reject.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, arrayOf(getErrorObject(cx, scope, re)))
                return cap.promise
            }

            val iterator = iterable.iterator()
            try {
                try {
                    // Capability threw above if `this` was not a constructor.
                    return performRace(cx, scope, iterator, checkNotNull(thisObj), cap)
                } finally {
                    if (!iterator.isDone) iterable.close()
                }
            } catch (re: RhinoException) {
                cap.reject.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, arrayOf(getErrorObject(cx, scope, re)))
                return cap.promise
            }
        }

        private fun performRace(
            cx: Context,
            scope: Scriptable,
            iterator: IteratorLikeIterable.Itr,
            thisObj: Scriptable,
            cap: Capability,
        ): Any? {
            val resolve = ScriptRuntime.getPropAndThis(thisObj, "resolve", cx, scope)!!

            while (true) {
                val hasNext: Boolean
                var nextVal: Any? = Undefined.instance
                var nextOk = false
                try {
                    hasNext = iterator.hasNext()
                    if (hasNext) nextVal = iterator.next()
                    nextOk = true
                } finally {
                    if (!nextOk) iterator.isDone = true
                }

                if (!hasNext) return cap.promise

                val nextPromise = resolve.call(cx, scope, arrayOf(nextVal))
                // Every entry gets the same pair; the resolvers themselves drop all but the first.
                val thenFunc = ScriptRuntime.getPropAndThis(nextPromise, "then", cx, scope)!!
                thenFunc.call(cx, scope, arrayOf(cap.resolve, cap.reject))
            }
        }

        private fun any(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val cap = Capability(cx, scope, thisObj)
            val arg = if (args.isNotEmpty()) args[0] else Undefined.instance

            val iterable: IteratorLikeIterable
            try {
                iterable = IteratorLikeIterable(cx, scope, ScriptRuntime.callIterator(arg, cx, scope))
            } catch (re: RhinoException) {
                cap.reject.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, arrayOf(getErrorObject(cx, scope, re)))
                return cap.promise
            }

            val iterator = iterable.iterator()
            try {
                // Capability threw above if `this` was not a constructor.
                val rejector = PromiseAnyRejector(iterator, checkNotNull(thisObj), cap)
                try {
                    return rejector.reject(cx, scope)
                } finally {
                    if (!iterator.isDone) iterable.close()
                }
            } catch (re: RhinoException) {
                cap.reject.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, arrayOf(getErrorObject(cx, scope, re)))
                return cap.promise
            }
        }

        private fun withResolvers(cx: Context, scope: Scriptable, thisObj: Scriptable?): Any? {
            if (!ScriptRuntime.isObject(thisObj)) {
                throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeOf(thisObj))
            }
            val cap = Capability(cx, scope, thisObj)
            val result = cx.newObject(scope)
            result.put("promise", result, cap.promise)
            result.put("resolve", result, cap.resolve)
            result.put("reject", result, cap.reject)
            return result
        }

        private fun promiseTry(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (!ScriptRuntime.isObject(thisObj)) {
                throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeOf(thisObj))
            }
            if (args.isEmpty() || args[0] !is Callable) throw ScriptRuntime.typeErrorById("msg.function.expected")
            val func = args[0] as Callable
            val cap = Capability(cx, scope, thisObj)
            val funcArgs = args.copyOfRange(1, args.size)

            try {
                // The function runs now, not on the queue; only its answer is deferred.
                cap.resolve.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, arrayOf(func.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, funcArgs)))
            } catch (re: RhinoException) {
                cap.reject.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, arrayOf(getErrorObject(cx, scope, re)))
            }
            return cap.promise
        }

        private fun doCatch(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val arg = if (args.isNotEmpty()) args[0] else Undefined.instance
            val coercedThis = ScriptRuntime.toObject(cx, scope, thisObj)
            // Looked up rather than called directly: a script may have replaced `then`.
            val thenFunc = ScriptRuntime.getPropAndThis(coercedThis, "then", cx, scope)!!
            return thenFunc.call(cx, scope, arrayOf(Undefined.instance, arg))
        }

        private fun doFinally(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (!ScriptRuntime.isObject(thisObj)) {
                throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeOf(thisObj))
            }
            val onFinally: Any? = if (args.isNotEmpty()) args[0] else Undefined.SCRIPTABLE_UNDEFINED
            var thenFinally: Any? = onFinally
            var catchFinally: Any? = onFinally
            val ctor = TopLevel.getBuiltinCtor(cx, getTopLevelScope(scope), TopLevel.Builtins.Promise)!!
            val constructor = AbstractEcmaObjectOperations.speciesConstructor(cx, thisObj!!, ctor)
            if (onFinally is Callable) {
                thenFinally = makeThenFinally(scope, constructor, onFinally)
                catchFinally = makeCatchFinally(scope, constructor, onFinally)
            }
            val thenFunc = ScriptRuntime.getPropAndThis(thisObj, "then", cx, scope)!!
            return thenFunc.call(cx, scope, arrayOf(thenFinally, catchFinally))
        }

        /** Runs the callback and then hands the original value on unchanged. */
        private fun makeThenFinally(scope: Scriptable, constructor: Any?, onFinally: Callable): Callable =
            LambdaFunction(scope, 1, SerializableCallable { cx, ls, _, args ->
                val value = if (args.isNotEmpty()) args[0] else Undefined.instance
                val valueThunk = LambdaFunction(scope, 0, SerializableCallable { _, _, _, _ -> value })
                val result = onFinally.call(cx, ls, Undefined.SCRIPTABLE_UNDEFINED, ScriptRuntime.emptyArgs)
                val promise = resolveInternal(cx, scope, constructor, result)
                ScriptRuntime.getPropAndThis(promise, "then", cx, scope)!!.call(cx, scope, arrayOf<Any?>(valueThunk))
            })

        /** Runs the callback and then throws the original reason on again. */
        private fun makeCatchFinally(scope: Scriptable, constructor: Any?, onFinally: Callable): Callable =
            LambdaFunction(scope, 1, SerializableCallable { cx, ls, _, args ->
                val reason = if (args.isNotEmpty()) args[0] else Undefined.instance
                val reasonThrower = LambdaFunction(scope, 0, SerializableCallable { _, _, _, _ ->
                    throw JavaScriptException(reason, null, 0)
                })
                val result = onFinally.call(cx, ls, Undefined.SCRIPTABLE_UNDEFINED, ScriptRuntime.emptyArgs)
                val promise = resolveInternal(cx, scope, constructor, result)
                ScriptRuntime.getPropAndThis(promise, "then", cx, scope)!!.call(cx, scope, arrayOf<Any?>(reasonThrower))
            })

        /** The value a rejection carries: the thrown value itself, or a fresh error object. */
        private fun getErrorObject(cx: Context, scope: Scriptable, re: RhinoException): Any? {
            if (re is JavaScriptException) return re.value

            var constructor = TopLevel.NativeErrors.Error
            if (re is EcmaError) {
                constructor = when (re.name) {
                    "EvalError" -> TopLevel.NativeErrors.EvalError
                    "RangeError" -> TopLevel.NativeErrors.RangeError
                    "ReferenceError" -> TopLevel.NativeErrors.ReferenceError
                    "SyntaxError" -> TopLevel.NativeErrors.SyntaxError
                    "TypeError" -> TopLevel.NativeErrors.TypeError
                    "URIError" -> TopLevel.NativeErrors.URIError
                    "InternalError" -> TopLevel.NativeErrors.InternalError
                    "JavaException" -> TopLevel.NativeErrors.JavaException
                    else -> constructor
                }
            }
            return ScriptRuntime.newNativeError(cx, scope, constructor, arrayOf<Any?>(re.message))
        }
    }
}
