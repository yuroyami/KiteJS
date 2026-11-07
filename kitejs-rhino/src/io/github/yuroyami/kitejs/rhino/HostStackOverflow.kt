/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

/**
 * Whether [t] is the host running out of stack, where the host lets a program catch that: a
 * `StackOverflowError` on the JVM and Android, a `RangeError` on JavaScript. Native code and
 * WebAssembly cannot catch it, so there the parser counts its own depth (#139).
 */
internal expect fun isHostStackOverflow(t: Throwable): Boolean
