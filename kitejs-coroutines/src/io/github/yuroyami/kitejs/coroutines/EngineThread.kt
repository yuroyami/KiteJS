/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.coroutines

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable

/**
 * A thread of its own for one engine, which [asyncKiteJs] makes when it is given no dispatcher.
 *
 * An engine is held by the thread that opened it, so it can only be used and released there. A
 * pool view such as `Dispatchers.Default.limitedParallelism(1)` runs one task at a time, but not
 * on one thread, so it cannot hold an engine. One thread per engine also lets several be open at
 * once. JavaScript and WebAssembly have one thread, and every engine runs on it.
 */
internal expect class EngineThread() : CoroutineDispatcher {

    /** Queues [block] on the engine's thread, or on the one thread there is. */
    override fun dispatch(context: CoroutineContext, block: Runnable)

    /**
     * Runs [last] on the thread after the tasks already queued there, then lets the thread end.
     * Returns without waiting. Where there is only one thread, [last] runs at once.
     */
    fun finish(last: () -> Unit)
}
