/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kitejs.quickjs.bridge

import cnames.structs.KiteEngine
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_array_get
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_get
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_array_length
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_array_push
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_bytes_free
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_bytes_length
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_read_script
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_view_bytes
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_write_script
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_ask_host
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_call
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_cb_arg
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_cb_this
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_compile
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_construct
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_dead_function
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_discard_jobs
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_drain
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_dup
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_enter
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_exception
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_free
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_global
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_identity
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_is_promise
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_new
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_new_array
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_new_bytes
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_new_function
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_new_number
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_new_object
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_new_string
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_number
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_object_put
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_push_arg
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_release
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_run
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_set_budget
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_set_host
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_stop_reason
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_string
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_string_free
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_string_length
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_type
import io.github.yuroyami.kitejs.quickjs.cinterop.kite_version
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.concurrent.ThreadLocal
import kotlin.native.ref.createCleaner
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong

/** QuickJS linked into the binary through cinterop. */
private object NativeBridge : QuickJsBridge {

    init {
        kite_set_host(
            staticCFunction { engine: Int, fn: Int, argc: Int ->
                try {
                    QuickJsHost.call(engine, fn, argc)
                } catch (t: Throwable) {
                    -1
                }
            },
            staticCFunction { engine: Int ->
                try {
                    QuickJsHost.interrupt(engine)
                } catch (t: Throwable) {
                    2
                }
            },
            staticCFunction { engine: Int ->
                try {
                    QuickJsHost.now(engine)
                } catch (t: Throwable) {
                    0.0
                }
            },
            staticCFunction { engine: Int, time: Double ->
                try {
                    QuickJsHost.timeZoneOffset(engine, time)
                } catch (t: Throwable) {
                    0
                }
            },
        )
    }

    private fun e(e: Long): CPointer<KiteEngine> = e.toCPointer()!!

    override fun newEngine(id: Int, memoryLimit: Double, stackSize: Double, options: Int): Long =
        kite_new(id, memoryLimit, stackSize, options).toLong()

    override fun free(e: Long) = kite_free(e(e))
    override fun enter(e: Long) = kite_enter(e(e))
    override fun setBudget(e: Long, budget: Double) = kite_set_budget(e(e), budget)
    override fun askHost(e: Long, ask: Boolean) = kite_ask_host(e(e), if (ask) 1 else 0)
    override fun stopReason(e: Long): Int = kite_stop_reason(e(e))
    override fun version(): String = kite_version()!!.toKString()

    override fun type(e: Long, h: Int): Int = kite_type(e(e), h)
    override fun number(e: Long, h: Int): Double = kite_number(e(e), h)

    override fun string(e: Long, h: Int): String? {
        val engine = e(e)
        val chars = kite_string(engine, h) ?: return null
        val length = kite_string_length(engine)
        val text = CharArray(length).also { if (length > 0) it.usePinned { p -> memcpy(p.addressOf(0), chars, (length * 2).convert()) } }
            .concatToString()
        kite_string_free(engine)
        return text
    }

    override fun identity(e: Long, h: Int): Double = kite_identity(e(e), h)
    override fun isPromise(e: Long, h: Int): Boolean = kite_is_promise(e(e), h) != 0
    override fun dup(e: Long, h: Int): Int = kite_dup(e(e), h)
    override fun release(e: Long, h: Int) = kite_release(e(e), h)

    override fun newNumber(e: Long, d: Double): Int = kite_new_number(e(e), d)
    override fun newString(e: Long, s: String): Int = utf16(s) { kite_new_string(e(e), it, s.length) }
    override fun newObject(e: Long): Int = kite_new_object(e(e))
    override fun newArray(e: Long): Int = kite_new_array(e(e))
    override fun newBytes(e: Long, bytes: ByteArray): Int =
        if (bytes.isEmpty()) kite_new_bytes(e(e), null, 0)
        else bytes.usePinned { kite_new_bytes(e(e), it.addressOf(0).reinterpret(), bytes.size) }

    override fun viewBytes(e: Long, h: Int): ByteArray? {
        val engine = e(e)
        val bytes = kite_view_bytes(engine, h) ?: return null
        val length = kite_bytes_length(engine)
        val out = ByteArray(length).also { if (length > 0) it.usePinned { p -> memcpy(p.addressOf(0), bytes, length.convert()) } }
        kite_bytes_free(engine)
        return out
    }
    override fun arrayPush(e: Long, array: Int, value: Int) = kite_array_push(e(e), array, value)
    override fun objectPut(e: Long, obj: Int, key: String, value: Int) = utf16(key) { kite_object_put(e(e), obj, it, key.length, value) }
    override fun arrayLength(e: Long, array: Int): Int = kite_array_length(e(e), array)
    override fun arrayGet(e: Long, array: Int, index: Int): Int = kite_array_get(e(e), array, index)
    override fun get(e: Long, obj: Int, key: String): Int = utf16(key) { kite_get(e(e), obj, it, key.length) }
    override fun global(e: Long): Int = kite_global(e(e))

    override fun compile(e: Long, source: String, file: String): Int =
        utf16(source) { s -> utf16(file) { f -> kite_compile(e(e), s, source.length, f, file.length) } }

    override fun writeScript(e: Long, compiled: Int): ByteArray? {
        val engine = e(e)
        val bytes = kite_write_script(engine, compiled) ?: return null
        val length = kite_bytes_length(engine)
        val out = ByteArray(length).also { if (length > 0) it.usePinned { p -> memcpy(p.addressOf(0), bytes, length.convert()) } }
        kite_bytes_free(engine)
        return out
    }

    override fun readScript(e: Long, bytes: ByteArray): Int =
        if (bytes.isEmpty()) kite_read_script(e(e), null, 0)
        else bytes.usePinned { kite_read_script(e(e), it.addressOf(0).reinterpret(), bytes.size) }

    override fun run(e: Long, compiled: Int): Int = kite_run(e(e), compiled)
    override fun pushArg(e: Long, h: Int) = kite_push_arg(e(e), h)
    override fun call(e: Long, fn: Int, self: Int): Int = kite_call(e(e), fn, self)
    override fun construct(e: Long, fn: Int): Int = kite_construct(e(e), fn)
    override fun drain(e: Long): Int = kite_drain(e(e))
    override fun discardJobs(e: Long) = kite_discard_jobs(e(e))
    override fun exception(e: Long): Int = kite_exception(e(e))

    override fun newFunction(e: Long, fn: Int, name: String, arity: Int): Int =
        utf16(name) { kite_new_function(e(e), fn, it, name.length, arity) }

    override fun deadFunction(e: Long): Int = kite_dead_function(e(e))
    override fun cbThis(e: Long): Int = kite_cb_this(e(e))
    override fun cbArg(e: Long, index: Int): Int = kite_cb_arg(e(e), index)

    /**
     * [s] as UTF-16 in memory while [block] runs. Strings and bytes cross with one copy in native
     * code, since a loop over each character runs slowly in a debug build.
     */
    private inline fun <T> utf16(s: String, block: (CPointer<UShortVar>) -> T): T =
        (if (s.isEmpty()) CharArray(1) else s.toCharArray()).usePinned { block(it.addressOf(0).reinterpret()) }
}

internal actual fun bridge(): QuickJsBridge = NativeBridge

internal actual suspend fun loadBridge() {}

internal actual fun bridgeLoaded(): Boolean = true

/** One per thread, so comparing them tells threads apart. */
@ThreadLocal
private object ThreadToken

internal actual fun currentThreadToken(): Any = ThreadToken

@OptIn(ExperimentalNativeApi::class)
internal actual class HandleCleaner actual constructor(private val release: (Int) -> Unit) {
    actual fun register(owner: Any, handle: Int): Any? =
        createCleaner(Pending(release, handle)) { it.release(it.handle) }

    private class Pending(val release: (Int) -> Unit, val handle: Int)

    actual fun poll() {}
}
