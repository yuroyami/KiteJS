/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** Work to run with a [Context] entered. See [ContextFactory.call]. */
public fun interface ContextAction<T> {
    public fun run(cx: Context): T
}
