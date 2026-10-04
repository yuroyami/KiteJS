/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.coroutines

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.IO
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal actual class EngineThread actual constructor() : CoroutineDispatcher() {

    private val thread = newSingleThreadContext("KiteJS engine")

    actual override fun dispatch(context: CoroutineContext, block: Runnable) = thread.dispatch(context, block)

    actual fun finish(last: () -> Unit) {
        thread.dispatch(EmptyCoroutineContext, Runnable(last))
        // Closing waits for the thread to end, after the last task. The thread cannot wait for
        // itself, and close() may be called from inside a call on it, so another thread waits.
        GlobalScope.launch(Dispatchers.IO) { thread.close() }
    }
}
