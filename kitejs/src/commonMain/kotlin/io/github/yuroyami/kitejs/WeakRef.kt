/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * A weak reference to [T]: holds its referent without keeping it alive.
 *
 * [get] returns the referent, or `null` once it has been collected or after [clear]. Check
 * [isWeakSupported] before leaning on collection for correctness.
 *
 * Platform behaviour:
 * - JVM, Android, Apple/Native: the platform's own weak reference.
 * - JS: the ES2021 `WeakRef` global. On an older runtime, and for referents backed by JS
 *   primitives (`String`, boxed numbers) that `WeakRef` will not accept, the referent is held
 *   strongly instead.
 *
 * This is the engine's only expect/actual. `WeakMap` and `WeakSet` need it and there is no
 * multiplatform weak reference in the standard library (D-52).
 */
expect class WeakRef<T : Any>(referred: T) {
    /** The referent, or `null` once collected or [clear]ed. */
    fun get(): T?

    /** Drops the reference, so [get] returns `null` from here on. Calling it twice is fine. */
    fun clear()

    companion object {
        /**
         * Whether the platform gives real weak semantics.
         *
         * `true` on JVM, Android, Apple/Native and any JS runtime with `WeakRef`. Where it is
         * `false` the referent is held strongly and [get] keeps answering until [clear].
         */
        val isWeakSupported: Boolean
    }
}
