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
 * Wasm has no weak reference. A Kotlin object on Wasm lives in the Wasm heap, not the JavaScript
 * one, so the `WeakRef` global cannot hold it, and the standard library offers nothing else.
 *
 * The referent is therefore held strongly until [clear]. Nothing a script can do notices: a
 * `WeakMap` has no iteration and no size, so whether an entry was collected is unobservable. The
 * cost is memory, not behaviour. [isWeakSupported] is `false` here, which is how a host finds out.
 */
public actual class WeakRef<T : Any> actual constructor(referred: T) {

    private var strong: T? = referred

    public actual fun get(): T? = strong

    public actual fun clear() {
        strong = null
    }

    public actual companion object {
        public actual val isWeakSupported: Boolean = false
    }
}
