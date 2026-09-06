/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.native.ref.WeakReference

actual class WeakRef<T : Any> actual constructor(referred: T) {

    private val ref = WeakReference(referred)

    actual fun get(): T? = ref.get()

    actual fun clear() {
        ref.clear()
    }

    actual companion object {
        actual val isWeakSupported: Boolean = true
    }
}
