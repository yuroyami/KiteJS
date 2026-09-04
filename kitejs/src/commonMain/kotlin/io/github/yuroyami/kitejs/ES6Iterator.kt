/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The names the iteration protocol uses. The iterator objects themselves land in phase 4; the
 * runtime only needs the names before then.
 */
object ES6Iterator {
    const val NEXT_METHOD = "next"
    const val DONE_PROPERTY = "done"
    const val RETURN_PROPERTY = "return"
    const val VALUE_PROPERTY = "value"
    const val RETURN_METHOD = "return"
}
