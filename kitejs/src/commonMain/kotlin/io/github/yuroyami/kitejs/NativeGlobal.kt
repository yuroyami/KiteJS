/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The global functions: `eval`, `parseInt`, `isNaN` and the rest. Phase 3.8 ports them; the
 * runtime only needs to recognise the real `eval` before then.
 */
object NativeGlobal {
    /** The function object registered as the global `eval`. Set when the globals are installed. */
    internal var evalFunction: Any? = null

    internal fun isEvalFunction(functionObj: Any?): Boolean = functionObj != null && functionObj === evalFunction
}
