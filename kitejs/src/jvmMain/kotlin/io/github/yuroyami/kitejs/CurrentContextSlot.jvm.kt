/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

// The jvmMain and androidMain copies are identical; there is no source set shared by the two.
internal actual object CurrentContextSlot {

    private val slot = ThreadLocal<Context?>()

    actual var value: Context?
        get() = slot.get()
        set(value) {
            if (value == null) slot.remove() else slot.set(value)
        }
}
