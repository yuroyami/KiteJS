/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.quickjs.bridge

import io.github.yuroyami.kitejs.api.JsEngineError
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** What [GLUE] evaluates to. Engines are pointers into the module's memory, so they fit an Int. */
internal external interface KiteGlue : JsAny {
    fun load(
        gzippedBase64: String,
        call: (Int, Int, Int) -> Int,
        interrupt: (Int) -> Int,
        now: (Int) -> Double,
        tzOffset: (Int, Double) -> Int,
    ): Thenable

    fun loaded(): Boolean
    fun newEngine(id: Int, memoryLimit: Double, stackSize: Double, options: Int): Int
    fun free(e: Int)
    fun enter(e: Int)
    fun setBudget(e: Int, budget: Double)
    fun askHost(e: Int, ask: Boolean)
    fun stopReason(e: Int): Int
    fun version(): String
    fun type(e: Int, h: Int): Int
    fun number(e: Int, h: Int): Double
    fun string(e: Int, h: Int): String?
    fun identity(e: Int, h: Int): Double
    fun isPromise(e: Int, h: Int): Boolean
    fun dup(e: Int, h: Int): Int
    fun release(e: Int, h: Int)
    fun newNumber(e: Int, d: Double): Int
    fun newString(e: Int, s: String): Int
    fun newObject(e: Int): Int
    fun newArray(e: Int): Int
    fun arrayPush(e: Int, array: Int, value: Int)
    fun objectPut(e: Int, obj: Int, key: String, value: Int)
    fun arrayLength(e: Int, array: Int): Int
    fun arrayGet(e: Int, array: Int, index: Int): Int
    fun get(e: Int, obj: Int, key: String): Int
    fun global(e: Int): Int
    fun compile(e: Int, source: String, file: String): Int
    fun run(e: Int, compiled: Int): Int
    fun canPause(): Boolean
    fun runPausing(e: Int, compiled: Int): Thenable
    fun drainPausing(e: Int): Thenable
    fun pausedAnswer(): Int

    /** The bytecode as a string with one code unit for each byte, or null. */
    fun writeScript(e: Int, compiled: Int): String?
    fun readScript(e: Int, bytes: String): Int
    fun pushArg(e: Int, h: Int)
    fun call(e: Int, fn: Int, self: Int): Int
    fun construct(e: Int, fn: Int): Int
    fun drain(e: Int): Int
    fun discardJobs(e: Int)
    fun exception(e: Int): Int
    fun newFunction(e: Int, fn: Int, name: String, arity: Int): Int
    fun deadFunction(e: Int): Int
    fun cbThis(e: Int): Int
    fun cbArg(e: Int, index: Int): Int
    fun newRegistry(release: (Int) -> Unit): JsAny?
    fun watch(registry: JsAny, owner: JsAny, handle: Int)
}

/** A promise, as far as waiting for one goes. */
internal external interface Thenable : JsAny {
    fun then(onFulfilled: (JsAny?) -> JsAny?, onRejected: (JsAny?) -> JsAny?): JsAny?
}

internal fun createGlue(): KiteGlue = js(GLUE)

private val glue: KiteGlue by lazy { createGlue() }

private object WebBridge : QuickJsBridge {
    override fun newEngine(id: Int, memoryLimit: Double, stackSize: Double, options: Int): Long =
        glue.newEngine(id, memoryLimit, stackSize, options).toLong()

    override fun free(e: Long) = glue.free(e.toInt())
    override fun enter(e: Long) = glue.enter(e.toInt())
    override fun setBudget(e: Long, budget: Double) = glue.setBudget(e.toInt(), budget)
    override fun askHost(e: Long, ask: Boolean) = glue.askHost(e.toInt(), ask)
    override fun stopReason(e: Long): Int = glue.stopReason(e.toInt())
    override fun version(): String = glue.version()
    override fun type(e: Long, h: Int): Int = glue.type(e.toInt(), h)
    override fun number(e: Long, h: Int): Double = glue.number(e.toInt(), h)
    override fun string(e: Long, h: Int): String? = glue.string(e.toInt(), h)
    override fun identity(e: Long, h: Int): Double = glue.identity(e.toInt(), h)
    override fun isPromise(e: Long, h: Int): Boolean = glue.isPromise(e.toInt(), h)
    override fun dup(e: Long, h: Int): Int = glue.dup(e.toInt(), h)
    override fun release(e: Long, h: Int) = glue.release(e.toInt(), h)
    override fun newNumber(e: Long, d: Double): Int = glue.newNumber(e.toInt(), d)
    override fun newString(e: Long, s: String): Int = glue.newString(e.toInt(), s)
    override fun newObject(e: Long): Int = glue.newObject(e.toInt())
    override fun newArray(e: Long): Int = glue.newArray(e.toInt())
    override fun arrayPush(e: Long, array: Int, value: Int) = glue.arrayPush(e.toInt(), array, value)
    override fun objectPut(e: Long, obj: Int, key: String, value: Int) = glue.objectPut(e.toInt(), obj, key, value)
    override fun arrayLength(e: Long, array: Int): Int = glue.arrayLength(e.toInt(), array)
    override fun arrayGet(e: Long, array: Int, index: Int): Int = glue.arrayGet(e.toInt(), array, index)
    override fun get(e: Long, obj: Int, key: String): Int = glue.get(e.toInt(), obj, key)
    override fun global(e: Long): Int = glue.global(e.toInt())
    override fun compile(e: Long, source: String, file: String): Int = glue.compile(e.toInt(), source, file)
    override fun run(e: Long, compiled: Int): Int = glue.run(e.toInt(), compiled)
    override fun canPause(): Boolean = glue.canPause()
    override suspend fun runPausing(e: Long, compiled: Int): Int = settled(glue.runPausing(e.toInt(), compiled))
    override suspend fun drainPausing(e: Long): Int = settled(glue.drainPausing(e.toInt()))

    /** Waits for [pending], a run or a drain that may pause, and answers what it answered. */
    private suspend fun settled(pending: Thenable): Int = suspendCoroutine { continuation ->
        pending.then(
            { _ ->
                continuation.resume(glue.pausedAnswer())
                null
            },
            { error ->
                // A trap in the paused stack, such as running out of the native stack, ends the call.
                continuation.resumeWithException(JsEngineError("QuickJS failed while it could pause: $error"))
                null
            },
        )
    }
    override fun writeScript(e: Long, compiled: Int): ByteArray? =
        glue.writeScript(e.toInt(), compiled)?.let { s -> ByteArray(s.length) { s[it].code.toByte() } }
    override fun readScript(e: Long, bytes: ByteArray): Int =
        glue.readScript(e.toInt(), CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() }.concatToString())
    override fun pushArg(e: Long, h: Int) = glue.pushArg(e.toInt(), h)
    override fun call(e: Long, fn: Int, self: Int): Int = glue.call(e.toInt(), fn, self)
    override fun construct(e: Long, fn: Int): Int = glue.construct(e.toInt(), fn)
    override fun drain(e: Long): Int = glue.drain(e.toInt())
    override fun discardJobs(e: Long) = glue.discardJobs(e.toInt())
    override fun exception(e: Long): Int = glue.exception(e.toInt())
    override fun newFunction(e: Long, fn: Int, name: String, arity: Int): Int = glue.newFunction(e.toInt(), fn, name, arity)
    override fun deadFunction(e: Long): Int = glue.deadFunction(e.toInt())
    override fun cbThis(e: Long): Int = glue.cbThis(e.toInt())
    override fun cbArg(e: Long, index: Int): Int = glue.cbArg(e.toInt(), index)
}

internal actual fun bridge(): QuickJsBridge {
    if (!glue.loaded()) {
        throw JsEngineError("QuickJS is not loaded yet; call QuickJs.load() once before the first engine opens")
    }
    return WebBridge
}

internal actual fun bridgeLoaded(): Boolean = glue.loaded()

internal actual suspend fun loadBridge() {
    if (glue.loaded()) return
    val loading = glue.load(
        QUICKJS_WASM.joinToString(""),
        { engine, fn, argc -> QuickJsHost.call(engine, fn, argc) },
        { engine -> QuickJsHost.interrupt(engine) },
        { engine -> QuickJsHost.now(engine) },
        { engine, time -> QuickJsHost.timeZoneOffset(engine, time) },
    )
    suspendCoroutine { continuation ->
        loading.then(
            { _ ->
                continuation.resume(Unit)
                null
            },
            { error ->
                continuation.resumeWithException(JsEngineError("QuickJS failed to load: $error"))
                null
            },
        )
    }
}

/** There is one thread. */
internal actual fun currentThreadToken(): Any = Unit

internal actual class HandleCleaner actual constructor(release: (Int) -> Unit) {

    /** Null where there is no FinalizationRegistry; handles then live until the engine closes. */
    private val registry: JsAny? = glue.newRegistry(release)

    actual fun register(owner: Any, handle: Int): Any? {
        registry?.let { glue.watch(it, owner.toJsReference(), handle) }
        return null
    }

    actual fun poll() {}
}
