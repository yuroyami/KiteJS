/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.coroutines

import io.github.yuroyami.kitejs.api.JsEngineError
import io.github.yuroyami.kitejs.api.JsError
import io.github.yuroyami.kitejs.api.JsFunction
import io.github.yuroyami.kitejs.api.JsObject
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * An engine that lives on one dispatcher, so suspending code can use it from anywhere.
 *
 * The engine underneath is single-threaded, as JavaScript is. Every call here hops onto the
 * engine's own dispatcher, which runs one thing at a time, so two callers never overlap.
 *
 * ```
 * val js = asyncKiteJs()
 * js.use { println(js.evaluate("1 + 1").asDouble()) }
 * js.close()
 * ```
 */
class AsyncKiteJs internal constructor(
    private val dispatcher: CoroutineDispatcher,
    private val engine: KiteJs,
    private val state: EngineState,
) : AutoCloseable {

    private var closed = false

    /** Runs [block] on the engine's dispatcher with the engine in hand. */
    suspend fun <T> onEngine(block: (KiteJs) -> T): T {
        check(!closed) { "this engine is closed" }
        val caller = coroutineContext[Job]
        return withContext(dispatcher) {
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
    }

    /** Parses and runs [source] on the engine's dispatcher. */
    suspend fun evaluate(source: String, fileName: String = "<eval>"): JsValue =
        onEngine { it.evaluate(source, fileName) }

    /** Parses [source] once, for running more than once. */
    suspend fun compile(source: String, fileName: String = "<script>"): JsScript =
        onEngine { it.compile(source, fileName) }

    /** Runs [source] and, if it answers a promise, waits for that promise to settle. */
    suspend fun evaluateAwaiting(source: String, fileName: String = "<eval>"): JsValue =
        await(evaluate(source, fileName))

    /** Waits for a JavaScript promise. A rejection comes back as the [JsError] it carries. */
    suspend fun await(value: JsValue): JsValue {
        val settled = CompletableDeferred<Result<JsValue>>()
        val registered = onEngine { js ->
            js.onSettled(value) { v, error ->
                if (error == null) settled.complete(Result.success(v))
                else settled.complete(Result.failure(JsError.from(error)))
            }
        }
        if (!registered) return value
        // Draining is what lets the reactions run; each hop onto the engine drains what it queued.
        onEngine { it.runMicrotasks() }
        return settled.await().getOrThrow()
    }

    /** Hands the script a promise that settles when [deferred] does. */
    suspend fun <T> deferredToPromise(deferred: Deferred<T>, scope: CoroutineScope): JsObject {
        val handle = onEngine { it.newPromise() }
        scope.launch {
            val outcome = runCatching { deferred.await() }
            onEngine { js ->
                outcome.fold(
                    onSuccess = { handle.resolve(it) },
                    onFailure = { handle.reject(it.message ?: it::class.simpleName ?: "failed") },
                )
                js.runMicrotasks()
            }
        }
        return handle.promise
    }

    /**
     * Binds a host function that suspends. The script sees a normal function returning a promise;
     * [body] runs on [scope] and settles it.
     */
    suspend fun suspendFunction(
        target: JsObject,
        name: String,
        arity: Int = 0,
        scope: CoroutineScope,
        body: suspend (List<JsValue>) -> Any?,
    ): JsFunction = onEngine { js ->
        target.function(name, arity) { args ->
            val handle = js.newPromise()
            scope.launch {
                val outcome = runCatching { body(args) }
                onEngine { inner ->
                    outcome.fold(
                        onSuccess = { handle.resolve(it) },
                        onFailure = { handle.reject(it.message ?: it::class.simpleName ?: "failed") },
                    )
                    inner.runMicrotasks()
                }
            }
            handle.promise
        }
    }

    /** Releases the engine. */
    override fun close() {
        if (closed) return
        closed = true
        engine.close()
    }

}

/** What the interrupt hook reads to notice a cancelled caller. */
internal class EngineState {
    var runningJob: Job? = null
}

/**
 * Builds an engine on its own dispatcher.
 *
 * Cancelling the coroutine that called into the engine stops the running script: the engine asks
 * the hook between instructions, and a cancelled job answers "stop". That needs an instruction
 * budget to be set, so one is set for you when you do not name one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun asyncKiteJs(
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    configure: KiteJsConfig.() -> Unit = {},
): AsyncKiteJs {
    val state = EngineState()
    val engine = withContext(dispatcher) {
        KiteJs {
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
    return AsyncKiteJs(dispatcher, engine, state)
}
