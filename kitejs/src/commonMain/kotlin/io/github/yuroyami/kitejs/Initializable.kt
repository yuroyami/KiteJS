/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** A one-method interface so a lambda can stand in for the lazy setup of a native class. */
fun interface Initializable {
    /** Builds the class and returns its new constructor. */
    fun initialize(cx: Context, scope: Scriptable, sealed: Boolean): Any?
}
