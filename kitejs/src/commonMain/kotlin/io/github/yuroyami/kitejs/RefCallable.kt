/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** A callable that can also produce a [Ref], for `delete f(x)` and friends. */
public interface RefCallable : Callable {

    public fun refCall(cx: Context, thisObj: Scriptable?, args: Array<Any?>): Ref
}
