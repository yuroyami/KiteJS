/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

// A JavaScript engine reports a full call stack as a RangeError, "Maximum call stack size
// exceeded" in V8 and "too much recursion" in SpiderMonkey.
internal actual fun isHostStackOverflow(t: Throwable): Boolean = t.asDynamic().name == "RangeError"
