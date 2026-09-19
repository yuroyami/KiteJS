/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * Where the entered [Context] of the calling thread is kept.
 *
 * Upstream holds it in a `ThreadLocal`, one slot per thread, and an engine belongs to one thread
 * (D-3). A single slot for the whole process would let one thread's engine block every other
 * thread's, so the slot follows upstream and is per thread where threads exist.
 *
 * JavaScript and WebAssembly run one thread, so there the slot is a plain field.
 */
internal expect object CurrentContextSlot {
    var value: Context?
}
