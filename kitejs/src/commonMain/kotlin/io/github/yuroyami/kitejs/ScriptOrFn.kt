/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** Something compiled from source: a script or a function. [T] is the concrete type itself. */
interface ScriptOrFn<T : ScriptOrFn<T>> {
    val homeObject: Scriptable?
        get() = null

    val descriptor: JSDescriptor<T>?
        get() = null

    val declarationScope: Scriptable?
        get() = null
}
