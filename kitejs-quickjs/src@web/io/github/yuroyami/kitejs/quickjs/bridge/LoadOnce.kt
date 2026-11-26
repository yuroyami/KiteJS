/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.quickjs.bridge

import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * One load at a time. A call while a load runs waits for that load and does not start another. A
 * failed load is not kept, so the next call starts again. There is one thread, so this needs no lock.
 *
 * [start] begins a load and calls its argument once, with null when the load ended or with the failure.
 */
internal class LoadOnce(private val start: (done: (Throwable?) -> Unit) -> Unit) {

    /** The callers that wait for the load on its way, or null when no load runs. */
    private var waiting: MutableList<Continuation<Unit>>? = null

    suspend fun await(): Unit = suspendCoroutine { continuation ->
        waiting?.let {
            it.add(continuation)
            return@suspendCoroutine
        }
        val callers = mutableListOf(continuation)
        waiting = callers
        try {
            start { failure ->
                waiting = null
                for (caller in callers) {
                    if (failure == null) caller.resume(Unit) else caller.resumeWithException(failure)
                }
            }
        } catch (t: Throwable) {
            waiting = null
            throw t
        }
    }
}
