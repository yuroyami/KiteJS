/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.quickjs.bridge

import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue

/** The native methods native/jni/kitejs_quickjs_jni.c implements, one per C function. */
internal object JniNatives {
    @JvmStatic external fun newEngine(id: Int, memoryLimit: Double, stackSize: Double, options: Int): Long
    @JvmStatic external fun free(e: Long)
    @JvmStatic external fun enter(e: Long)
    @JvmStatic external fun setBudget(e: Long, budget: Double)
    @JvmStatic external fun askHost(e: Long, ask: Boolean)
    @JvmStatic external fun stopReason(e: Long): Int
    @JvmStatic external fun version(): String
    @JvmStatic external fun type(e: Long, h: Int): Int
    @JvmStatic external fun number(e: Long, h: Int): Double
    @JvmStatic external fun string(e: Long, h: Int): String?
    @JvmStatic external fun identity(e: Long, h: Int): Double
    @JvmStatic external fun isPromise(e: Long, h: Int): Boolean
    @JvmStatic external fun dup(e: Long, h: Int): Int
    @JvmStatic external fun release(e: Long, h: Int)
    @JvmStatic external fun newNumber(e: Long, d: Double): Int
    @JvmStatic external fun newString(e: Long, s: String): Int
    @JvmStatic external fun newObject(e: Long): Int
    @JvmStatic external fun newArray(e: Long): Int
    @JvmStatic external fun arrayPush(e: Long, array: Int, value: Int)
    @JvmStatic external fun objectPut(e: Long, obj: Int, key: String, value: Int)
    @JvmStatic external fun arrayLength(e: Long, array: Int): Int
    @JvmStatic external fun arrayGet(e: Long, array: Int, index: Int): Int
    @JvmStatic external fun global(e: Long): Int
    @JvmStatic external fun compile(e: Long, source: String, file: String): Int
    @JvmStatic external fun run(e: Long, compiled: Int): Int
    @JvmStatic external fun pushArg(e: Long, h: Int)
    @JvmStatic external fun call(e: Long, fn: Int, self: Int): Int
    @JvmStatic external fun construct(e: Long, fn: Int): Int
    @JvmStatic external fun drain(e: Long): Int
    @JvmStatic external fun discardJobs(e: Long)
    @JvmStatic external fun exception(e: Long): Int
    @JvmStatic external fun newFunction(e: Long, fn: Int, name: String, arity: Int): Int
    @JvmStatic external fun deadFunction(e: Long): Int
    @JvmStatic external fun cbThis(e: Long): Int
    @JvmStatic external fun cbArg(e: Long, index: Int): Int
}

/** Where the C side calls back into Kotlin, by the names and signatures the JNI glue looks up. */
internal object JniHost {
    @JvmStatic fun call(engine: Int, fn: Int, argc: Int): Int = QuickJsHost.call(engine, fn, argc)
    @JvmStatic fun interrupt(engine: Int): Int = QuickJsHost.interrupt(engine)
    @JvmStatic fun now(engine: Int): Double = QuickJsHost.now(engine)
    @JvmStatic fun timeZoneOffset(engine: Int, time: Double): Int = QuickJsHost.timeZoneOffset(engine, time)
}

/** QuickJS through JNI, once loadNativeLibrary, which the JVM and Android each have, has loaded the library. */
private object JniBridge : QuickJsBridge {

    init {
        loadNativeLibrary()
    }

    override fun newEngine(id: Int, memoryLimit: Double, stackSize: Double, options: Int): Long =
        JniNatives.newEngine(id, memoryLimit, stackSize, options)

    override fun free(e: Long) = JniNatives.free(e)
    override fun enter(e: Long) = JniNatives.enter(e)
    override fun setBudget(e: Long, budget: Double) = JniNatives.setBudget(e, budget)
    override fun askHost(e: Long, ask: Boolean) = JniNatives.askHost(e, ask)
    override fun stopReason(e: Long): Int = JniNatives.stopReason(e)
    override fun version(): String = JniNatives.version()
    override fun type(e: Long, h: Int): Int = JniNatives.type(e, h)
    override fun number(e: Long, h: Int): Double = JniNatives.number(e, h)
    override fun string(e: Long, h: Int): String? = JniNatives.string(e, h)
    override fun identity(e: Long, h: Int): Double = JniNatives.identity(e, h)
    override fun isPromise(e: Long, h: Int): Boolean = JniNatives.isPromise(e, h)
    override fun dup(e: Long, h: Int): Int = JniNatives.dup(e, h)
    override fun release(e: Long, h: Int) = JniNatives.release(e, h)
    override fun newNumber(e: Long, d: Double): Int = JniNatives.newNumber(e, d)
    override fun newString(e: Long, s: String): Int = JniNatives.newString(e, s)
    override fun newObject(e: Long): Int = JniNatives.newObject(e)
    override fun newArray(e: Long): Int = JniNatives.newArray(e)
    override fun arrayPush(e: Long, array: Int, value: Int) = JniNatives.arrayPush(e, array, value)
    override fun objectPut(e: Long, obj: Int, key: String, value: Int) = JniNatives.objectPut(e, obj, key, value)
    override fun arrayLength(e: Long, array: Int): Int = JniNatives.arrayLength(e, array)
    override fun arrayGet(e: Long, array: Int, index: Int): Int = JniNatives.arrayGet(e, array, index)
    override fun global(e: Long): Int = JniNatives.global(e)
    override fun compile(e: Long, source: String, file: String): Int = JniNatives.compile(e, source, file)
    override fun run(e: Long, compiled: Int): Int = JniNatives.run(e, compiled)
    override fun pushArg(e: Long, h: Int) = JniNatives.pushArg(e, h)
    override fun call(e: Long, fn: Int, self: Int): Int = JniNatives.call(e, fn, self)
    override fun construct(e: Long, fn: Int): Int = JniNatives.construct(e, fn)
    override fun drain(e: Long): Int = JniNatives.drain(e)
    override fun discardJobs(e: Long) = JniNatives.discardJobs(e)
    override fun exception(e: Long): Int = JniNatives.exception(e)
    override fun newFunction(e: Long, fn: Int, name: String, arity: Int): Int = JniNatives.newFunction(e, fn, name, arity)
    override fun deadFunction(e: Long): Int = JniNatives.deadFunction(e)
    override fun cbThis(e: Long): Int = JniNatives.cbThis(e)
    override fun cbArg(e: Long, index: Int): Int = JniNatives.cbArg(e, index)
}

internal actual fun bridge(): QuickJsBridge = JniBridge

internal actual suspend fun loadBridge() {}

internal actual fun currentThreadToken(): Any = Thread.currentThread()

internal actual class HandleCleaner actual constructor(private val release: (Int) -> Unit) {

    private val queue = ReferenceQueue<Any>()

    /** The references themselves have to stay reachable until the collector enqueues them. */
    private val live = HashSet<Watch>()

    private class Watch(owner: Any, queue: ReferenceQueue<Any>, val handle: Int) : PhantomReference<Any>(owner, queue)

    actual fun register(owner: Any, handle: Int): Any? {
        live += Watch(owner, queue, handle)
        return null
    }

    actual fun poll() {
        while (true) {
            val watch = queue.poll() as Watch? ?: return
            live -= watch
            release(watch.handle)
        }
    }
}
