/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

// The jvmMain and androidMain copies are identical; there is no source set shared by the two.
actual class WeakRef<T : Any> actual constructor(referred: T) {

    private val ref = java.lang.ref.WeakReference(referred)

    actual fun get(): T? = ref.get()

    actual fun clear(): Unit = ref.clear()

    actual companion object {
        actual val isWeakSupported: Boolean = true
    }
}
