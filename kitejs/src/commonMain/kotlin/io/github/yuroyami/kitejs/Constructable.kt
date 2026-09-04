/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** Anything that can be used with `new`. */
interface Constructable {

    fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable
}
