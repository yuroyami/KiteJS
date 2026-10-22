/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.quickjs.bridge

/*
 * The C surface in native/kitejs_quickjs.h, one function each, as every platform reaches it: JNI
 * on the JVM and Android, cinterop on Kotlin/Native, a WebAssembly instance on JS and Wasm. An
 * engine is a pointer, carried as a Long; a value is an Int handle into the engine's table.
 *
 * Anything that answers a handle answers -1 when something was thrown, and [exception] then
 * hands the thrown value over.
 */
internal interface QuickJsBridge {
    fun newEngine(id: Int, memoryLimit: Double, stackSize: Double, options: Int): Long
    fun free(e: Long)
    fun enter(e: Long)
    fun setBudget(e: Long, budget: Double)
    fun askHost(e: Long, ask: Boolean)
    fun stopReason(e: Long): Int
    fun version(): String

    fun type(e: Long, h: Int): Int
    fun number(e: Long, h: Int): Double

    /** The string [h] converts to, or null when converting threw. */
    fun string(e: Long, h: Int): String?
    fun identity(e: Long, h: Int): Double
    fun isPromise(e: Long, h: Int): Boolean
    fun dup(e: Long, h: Int): Int
    fun release(e: Long, h: Int)

    fun newNumber(e: Long, d: Double): Int
    fun newString(e: Long, s: String): Int
    fun newObject(e: Long): Int
    fun newArray(e: Long): Int

    /** A Uint8Array over a new ArrayBuffer with a copy of [bytes]. */
    fun newBytes(e: Long, bytes: ByteArray): Int

    /** A copy of the bytes [h], of [TYPE_BYTES], views, or null when copying threw. */
    fun viewBytes(e: Long, h: Int): ByteArray?
    fun arrayPush(e: Long, array: Int, value: Int)
    fun objectPut(e: Long, obj: Int, key: String, value: Int)
    fun arrayLength(e: Long, array: Int): Int
    fun arrayGet(e: Long, array: Int, index: Int): Int
    fun get(e: Long, obj: Int, key: String): Int
    fun global(e: Long): Int

    fun compile(e: Long, source: String, file: String): Int
    fun run(e: Long, compiled: Int): Int

    /** The bytecode of [compiled], or null when writing it threw. */
    fun writeScript(e: Long, compiled: Int): ByteArray?

    /** A script from [bytes] that [writeScript] wrote, as [compile] answers one. */
    fun readScript(e: Long, bytes: ByteArray): Int
    /** Whether [runPausing] and [drainPausing] can pause; only the web, with WebAssembly stack switching. */
    fun canPause(): Boolean = false

    /** [run], where the script may pause when the interrupt hook answers [INTERRUPT_PAUSE]. */
    suspend fun runPausing(e: Long, compiled: Int): Int = run(e, compiled)

    /** [drain], where a job may pause the same way. */
    suspend fun drainPausing(e: Long): Int = drain(e)

    fun pushArg(e: Long, h: Int)
    fun call(e: Long, fn: Int, self: Int): Int
    fun construct(e: Long, fn: Int): Int
    fun drain(e: Long): Int
    fun discardJobs(e: Long)
    fun exception(e: Long): Int

    fun newFunction(e: Long, fn: Int, name: String, arity: Int): Int
    fun deadFunction(e: Long): Int
    fun cbThis(e: Long): Int
    fun cbArg(e: Long, index: Int): Int

    companion object {
        const val UNDEFINED = 0
        const val NULL = 1
        const val TRUE = 2
        const val FALSE = 3

        const val TYPE_UNDEFINED = 0
        const val TYPE_NULL = 1
        const val TYPE_BOOLEAN = 2
        const val TYPE_NUMBER = 3
        const val TYPE_BIGINT = 4
        const val TYPE_STRING = 5
        const val TYPE_SYMBOL = 6
        const val TYPE_ARRAY = 7
        const val TYPE_FUNCTION = 8
        const val TYPE_BYTES = 10

        const val STOP_NONE = 0
        const val STOP_BUDGET = 1
        const val STOP_HOOK = 2
        const val STOP_HOOK_THREW = 3
        const val STOP_MEMORY = 4

        /** What the interrupt hook answers to have the script pause, as KITE_INTERRUPT_PAUSE. */
        const val INTERRUPT_PAUSE = 3

        const val OPT_CLOCK = 1
        const val OPT_TIME_ZONE = 2
        const val OPT_INTERRUPT = 4
    }
}

/** This platform's way in, once [loadBridge] has made it ready. */
internal expect fun bridge(): QuickJsBridge

/** Makes the bridge ready. Only the WebAssembly build has anything to do. */
internal expect suspend fun loadBridge()

/** Whether [bridge] is ready: always, but on the web once [loadBridge] has finished. */
internal expect fun bridgeLoaded(): Boolean

/** Something that tells this thread from every other, for keeping an engine on its own thread. */
internal expect fun currentThreadToken(): Any

/**
 * Hears when a handle's Kotlin wrapper is garbage collected, so its slot in the engine's table
 * can be freed. [release] is called on whatever thread the collector uses, so it only queues.
 */
internal expect class HandleCleaner(release: (Int) -> Unit) {
    /** Watches [owner]; keep what this answers in a field of [owner], or the watch may end early. */
    fun register(owner: Any, handle: Int): Any?

    /** Hands over what has been collected, where the platform has to be asked. Engine thread only. */
    fun poll()
}
