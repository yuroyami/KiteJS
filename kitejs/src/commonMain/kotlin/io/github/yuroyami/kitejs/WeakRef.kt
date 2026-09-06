/*
 * Copyright 2026 yuroyami
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Taken from KiteCore (https://github.com/yuroyami/KiteCore), which is where
 * this code was written. A copy of the license is in LICENSE-APACHE-2.0.
 */

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
