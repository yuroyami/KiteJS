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

// A Kotlin/JS object is a JS object, so the ES2021 `WeakRef` can hold it directly. It is
// feature-detected: an older runtime falls back to a strong hold and reports isWeakSupported as
// false. JS primitives (String, boxed numbers) are not valid WeakRef targets either, which is why
// construction is wrapped in a try and falls back the same way.
private val hasWeakRef: Boolean = js("typeof WeakRef !== 'undefined'")

private fun newWeakRefOrNull(o: Any): dynamic =
    js("(function(t){ try { return new WeakRef(t) } catch (e) { return null } })(o)")

private fun derefOrNull(r: dynamic): dynamic =
    js("(function(x){ var v = x.deref(); return v === undefined ? null : v; })(r)")

actual class WeakRef<T : Any> actual constructor(referred: T) {

    private var cleared = false
    private val ref: dynamic = if (hasWeakRef) newWeakRefOrNull(referred) else null
    private var strong: T? = if (ref == null) referred else null

    actual fun get(): T? {
        if (cleared) return null
        strong?.let { return it }
        val v: Any? = derefOrNull(ref)
        @Suppress("UNCHECKED_CAST")
        return v as T?
    }

    actual fun clear() {
        cleared = true
        strong = null
    }

    actual companion object {
        actual val isWeakSupported: Boolean = hasWeakRef
    }
}
