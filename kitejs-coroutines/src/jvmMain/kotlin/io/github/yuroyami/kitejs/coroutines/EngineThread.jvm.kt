/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.coroutines

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.newSingleThreadContext

// The jvmMain and androidMain copies are identical; there is no source set shared by the two.
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal actual class EngineThread actual constructor() : CoroutineDispatcher() {

    // A daemon thread, so an engine that is never closed does not keep the process alive.
    private val thread = newSingleThreadContext("KiteJS engine")

    actual override fun dispatch(context: CoroutineContext, block: Runnable) = thread.dispatch(context, block)

    actual fun finish(last: () -> Unit) {
        thread.dispatch(EmptyCoroutineContext, Runnable(last))
        // Tasks already queued, the last one included, still run before the thread ends.
        thread.close()
    }
}
