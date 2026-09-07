/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** How `decompile` should print a function. */
public enum class DecompilerFlag {
    /** Omit the function header and the trailing brace. */
    ONLY_BODY,

    /** Produce a `toSource` result. */
    TO_SOURCE,
}
