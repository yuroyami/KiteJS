/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.coroutines

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable

// One thread, so every engine is already on it.
internal actual class EngineThread actual constructor() : CoroutineDispatcher() {

    override fun dispatch(context: CoroutineContext, block: Runnable) = Dispatchers.Default.dispatch(context, block)

    actual fun finish(last: () -> Unit) = last()
}
