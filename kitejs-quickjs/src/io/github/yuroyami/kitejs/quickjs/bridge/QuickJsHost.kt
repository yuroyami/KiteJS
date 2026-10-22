/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.quickjs.bridge

import io.github.yuroyami.kitejs.quickjs.facade.QuickJsKiteJs
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * Where the C side calls back into Kotlin. Every bridge routes its four callbacks here, naming the
 * engine by the id it was opened with.
 */
@OptIn(ExperimentalAtomicApi::class)
internal object QuickJsHost {

    private val engines = AtomicReference<Map<Int, QuickJsKiteJs>>(emptyMap())
    private val ids = AtomicInt(0)

    fun nextId(): Int = ids.incrementAndFetch()

    fun register(id: Int, engine: QuickJsKiteJs) {
        while (true) {
            val now = engines.load()
            if (engines.compareAndSet(now, now + (id to engine))) return
        }
    }

    fun unregister(id: Int) {
        while (true) {
            val now = engines.load()
            if (engines.compareAndSet(now, now - id)) return
        }
    }

    private fun engine(id: Int): QuickJsKiteJs = engines.load().getValue(id)

    /** Runs host function [fn]: a handle the engine takes over, or -1 when the host threw. */
    fun call(engine: Int, fn: Int, argc: Int): Int = engine(engine).hostCall(fn, argc)

    /** 0 to go on, 1 to stop, 2 when the hook threw. */
    fun interrupt(engine: Int): Int = engine(engine).hostInterrupt()

    fun now(engine: Int): Double = engine(engine).hostNow()

    fun timeZoneOffset(engine: Int, time: Double): Int = engine(engine).hostTimeZoneOffset(time)
}
