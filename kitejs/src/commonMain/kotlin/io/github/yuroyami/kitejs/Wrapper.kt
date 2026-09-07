/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** Wraps a value that came from outside the engine. */
public interface Wrapper {

    /** The value this wrapper stands for. */
    public fun unwrap(): Any?
}
