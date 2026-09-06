/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

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
