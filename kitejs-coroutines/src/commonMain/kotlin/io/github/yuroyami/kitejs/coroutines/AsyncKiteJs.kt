/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.coroutines

import io.github.yuroyami.kitejs.api.JsEngineError
import io.github.yuroyami.kitejs.api.JsError
import io.github.yuroyami.kitejs.api.JsFunction
import io.github.yuroyami.kitejs.api.JsObject
import io.github.yuroyami.kitejs.api.JsPromiseHandle
import io.github.yuroyami.kitejs.api.JsScript
import io.github.yuroyami.kitejs.api.JsValue
import io.github.yuroyami.kitejs.api.KiteJs
import io.github.yuroyami.kitejs.api.KiteJsConfig
import io.github.yuroyami.kitejs.api.function
import io.github.yuroyami.kitejs.api.newPromise
import io.github.yuroyami.kitejs.api.onSettled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * An engine that lives on one thread, so suspending code can use it from anywhere.
 *
 * The engine underneath is single-threaded, as JavaScript is, and the thread that opened it holds
 * it. Every call here hops onto that thread, one at a time, so two callers never overlap.
 *
 * The values that come back are the engine's own. A number, a string, a boolean, a BigInt, null
 * or undefined is a copy and can be read anywhere. An object, an array or a function still belongs
 * to the engine, so read it inside [onEngine]; touched from another thread it throws
 * `JsEngineError` rather than running script there.
 *
 * ```
 * val js = asyncKiteJs()
 * js.use { println(js.evaluate("1 + 1").asDouble()) }
 * js.close()
 * ```
 */
@OptIn(ExperimentalAtomicApi::class)
public class AsyncKiteJs internal constructor(
    private val dispatcher: CoroutineDispatcher,
    private val engine: KiteJs,
    private val state: EngineState,
) : AutoCloseable {

    /** Set by whichever thread closes first. The engine's own thread learns it from [EngineState]. */
    private val closed = AtomicBoolean(false)

    /** Runs [block] on the engine's dispatcher with the engine in hand. */
    public suspend fun <T> onEngine(block: (KiteJs) -> T): T {
        check(!closed.load()) { "this engine is closed" }
        val caller = coroutineContext[Job]
        try {
            return withContext(dispatcher) {
                // A close that raced the check above has already released the engine on this thread.
                check(!state.released) { "this engine is closed" }
                state.runningJob = caller
                try {
                    block(engine)
                } catch (e: JsEngineError) {
                    // The interrupt hook stops the script with this; a cancelled caller wants the
                    // cancellation, not an engine failure.
                    if (caller?.isActive == false) throw CancellationException(e.message ?: "cancelled")
                    throw e
                } finally {
                    state.runningJob = null
                }
            }
        } catch (e: CancellationException) {
            // A thread made for the engine refuses work once it has ended, and the refused call
            // comes back cancelled. A caller that was not cancelled itself is told why instead.
            if (closed.load() && caller?.isActive != false) throw IllegalStateException("this engine is closed", e)
            throw e
        }
    }

    /** Parses and runs [source] on the engine's dispatcher. */
    public suspend fun evaluate(source: String, fileName: String = "<eval>"): JsValue =
        onEngine { it.evaluate(source, fileName) }

    /** Parses [source] once, for running more than once. */
    public suspend fun compile(source: String, fileName: String = "<script>"): JsScript =
        onEngine { it.compile(source, fileName) }

    /** Runs [source] and, if it answers a promise, waits for that promise to settle. */
    public suspend fun evaluateAwaiting(source: String, fileName: String = "<eval>"): JsValue =
        await(evaluate(source, fileName))

    /**
     * Waits for [value] as `await` does in a script. A promise comes back as what it fulfils
     * with, and a rejection as the [JsError] it carries. Any other thenable is resolved the way a
     * promise resolves it, so a thenable that answers with a promise comes back as that promise's
     * value. A value that is not thenable comes straight back.
     *
     * Closing the engine while this waits ends the wait with [IllegalStateException], since
     * nothing can settle the promise any more.
     */
    public suspend fun await(value: JsValue): JsValue {
        val settled = CompletableDeferred<JsValue>()
        val registered = onEngine { js ->
            js.onSettled(value) { v, error ->
                if (error == null) {
                    settled.complete(v)
                } else {
                    // Reading the reason runs its getters, which can fail too, and the wait still
                    // has to end.
                    settled.completeExceptionally(
                        try {
                            JsError.from(error)
                        } catch (e: Throwable) {
                            e
                        },
                    )
                }
            }.also { if (it) state.watch(settled) }
        }
        if (!registered) return value
        try {
            // Draining is what lets the reactions run; each hop onto the engine drains what it queued.
            onEngine { it.runMicrotasks() }
            return settled.await()
        } finally {
            // A caller that stops waiting gives up its place, so close has nothing to end for it.
            settled.cancel()
        }
    }

    /**
     * Hands the script a promise that settles when [deferred] does. If [deferred] fails, or is
     * cancelled, or [scope] is cancelled before it is awaited, the promise rejects with the
     * failure's message.
     */
    public suspend fun <T> deferredToPromise(deferred: Deferred<T>, scope: CoroutineScope): JsObject {
        val handle = onEngine { it.newPromise() }
        bridge(handle, scope) { deferred.await() }
        return handle.promise
    }

    /**
     * Binds a host function that suspends. The script sees a normal function returning a promise;
     * [body] runs on [scope] and settles it. A body that throws, or is cancelled with [scope]
     * before or while it runs, rejects the promise with the failure's message.
     *
     * [body] runs off the engine's thread, so its arguments follow the rule above: scalars can be
     * read as they are, and an object or a function argument is read inside [onEngine].
     */
    public suspend fun suspendFunction(
        target: JsObject,
        name: String,
        arity: Int = 0,
        scope: CoroutineScope,
        body: suspend (List<JsValue>) -> Any?,
    ): JsFunction = onEngine { js ->
        target.function(name, arity) { args ->
            val handle = js.newPromise()
            bridge(handle, scope) { body(args) }
            handle.promise
        }
    }

    /**
     * Runs [work] on [scope] and settles [handle] with how it ended, on the engine's thread.
     *
     * The outcome is taken from the job's completion, so a job cancelled before it ever started
     * still settles its promise, as `Deferred.asPromise` does in kotlinx.coroutines. Settling runs
     * on the engine's own scope rather than [scope], which may be the thing that was cancelled.
     * Once the engine is closed, nothing is settled.
     */
    private fun bridge(handle: JsPromiseHandle, scope: CoroutineScope, work: suspend () -> Any?) {
        var outcome: Result<Any?>? = null
        val job = scope.launch { outcome = runCatching { work() } }
        // A failure while the reactions run has nobody waiting for it, so it goes to the handler
        // the caller's scope names, as an uncaught failure in that scope would.
        val handler = scope.coroutineContext[CoroutineExceptionHandler] ?: EmptyCoroutineContext
        job.invokeOnCompletion { cause ->
            val result = outcome ?: Result.failure(cause ?: CancellationException("the work never ran"))
            state.settling.launch(handler) {
                if (state.released) return@launch
                result.fold(
                    onSuccess = {
                        try {
                            handle.resolve(it)
                        } catch (e: Exception) {
                            // A value the engine cannot take still has to settle the promise.
                            handle.reject(reasonOf(e))
                        }
                    },
                    onFailure = { handle.reject(reasonOf(it)) },
                )
                engine.runMicrotasks()
            }
        }
    }

    private fun reasonOf(failure: Throwable): String = failure.message ?: failure::class.simpleName ?: "failed"

    /**
     * Releases the engine. The release runs on the engine's thread, after any call still running
     * there, so it can happen after this returns. A thread made for the engine then ends.
     *
     * An [await] still waiting ends with [IllegalStateException], and a promise handed out by
     * [deferredToPromise] or [suspendFunction] is no longer settled. The work behind such a
     * promise belongs to the scope it was given and carries on.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        state.settling.cancel()
        // Only the thread that opened the engine can release it.
        val release = {
            state.release()
            engine.close()
        }
        if (dispatcher is EngineThread) dispatcher.finish(release)
        else dispatcher.dispatch(EmptyCoroutineContext, Runnable(release))
    }

}

/**
 * What the engine's thread keeps beside the engine. Everything here but [settling] is touched only
 * on that thread, so it needs no locking.
 */
internal class EngineState(dispatcher: CoroutineDispatcher) {

    /** What the interrupt hook reads to notice a cancelled caller. */
    var runningJob: Job? = null

    /** Set as the engine is released; nothing reaches the engine afterwards. */
    var released = false

    /** Where bridged promises are settled. Cancelled on close, so nothing queued there runs. */
    val settling = CoroutineScope(SupervisorJob() + dispatcher)

    /** The [AsyncKiteJs.await] calls still waiting, which close has to end. */
    private val waiting = ArrayList<CompletableDeferred<JsValue>>()

    /** The size at which ended waits are swept out, so the list does not grow with them. */
    private var sweepAt = SWEEP_FLOOR

    fun watch(wait: CompletableDeferred<JsValue>) {
        if (waiting.size >= sweepAt) {
            waiting.removeAll { it.isCompleted }
            sweepAt = maxOf(SWEEP_FLOOR, waiting.size * 2)
        }
        waiting += wait
    }

    fun release() {
        released = true
        for (wait in waiting) {
            wait.completeExceptionally(IllegalStateException("the engine was closed before the promise settled"))
        }
        waiting.clear()
    }

    private companion object {
        const val SWEEP_FLOOR = 16
    }
}

/**
 * Builds an engine on a thread of its own.
 *
 * The thread that opens an engine holds it, so every call to the engine has to run on that
 * thread. By default the engine gets a new thread, which [AsyncKiteJs.close] ends. A [dispatcher]
 * you pass instead must run everything on one thread, such as one from `newSingleThreadContext`,
 * and you close it yourself. A pool view such as `Dispatchers.Default.limitedParallelism(1)` does
 * not work, because it moves calls between the threads of the pool.
 *
 * Cancelling the coroutine that called into the engine stops the running script: the engine asks
 * the hook between instructions, and a cancelled job answers "stop". That needs an instruction
 * budget to be set, so one is set for you when you do not name one.
 */
public suspend fun asyncKiteJs(
    dispatcher: CoroutineDispatcher = EngineThread(),
    configure: KiteJsConfig.() -> Unit = {},
): AsyncKiteJs {
    val state = EngineState(dispatcher)
    // Set on the engine's thread and read there too, by the release below, so it needs no lock.
    var built: KiteJs? = null
    try {
        withContext(dispatcher) {
            built = KiteJs {
                configure()
                // Whatever the caller asked for still gets asked; the job check is added to it.
                val theirs = interruptWhen
                val caller = state
                interruptWhen = {
                    val job = caller.runningJob
                    (job != null && !job.isActive) || theirs?.invoke() == true
                }
            }
        }
    } catch (e: Throwable) {
        // A cancellation can land after the engine was built, as withContext hands it back, and
        // then nothing returns it. Release whatever was built, on its own thread, and end a thread
        // made for it; a dispatcher of the caller's own is left open for the next engine.
        val release = { built?.close() }
        try {
            if (dispatcher is EngineThread) dispatcher.finish { release() }
            else withContext(NonCancellable + dispatcher) { release() }
        } catch (cleanup: Throwable) {
            e.addSuppressed(cleanup)
        }
        throw e
    }
    return AsyncKiteJs(dispatcher, built!!, state)
}
