/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * Remembers promises that were rejected with nothing to catch them. One of these belongs to each
 * [Context].
 *
 * Nothing happens by default, because there is no one right answer: an embedder decides whether an
 * unhandled rejection should log, throw or be ignored. Turning tracking on with
 * `Context.trackUnhandledPromiseRejections` starts collecting them here, and it is then the
 * embedder's job to drain them, or they simply pile up.
 */
public class UnhandledRejectionTracker {

    private var enabled = false

    // Identity, not equality: ScriptableObject does not override equals, so a plain set is one.
    private val unhandled = LinkedHashSet<NativePromise>()

    /**
     * Hands every rejection so far to [handler] and forgets it, so each one is reported once.
     */
    public fun process(handler: (Any?) -> Unit) {
        val it = unhandled.iterator()
        while (it.hasNext()) {
            val p = it.next()
            try {
                handler(p.result)
            } finally {
                // Removed even when the handler throws.
                it.remove()
            }
        }
    }

    /** The rejections so far, left in place. [process] is what clears them. */
    public fun enumerate(): List<Any?> = unhandled.map { it.result }

    internal fun enable(enabled: Boolean) {
        this.enabled = enabled
    }

    internal fun promiseRejected(p: NativePromise) {
        if (enabled) unhandled.add(p)
    }

    internal fun promiseHandled(p: NativePromise) {
        if (enabled) unhandled.remove(p)
    }
}
