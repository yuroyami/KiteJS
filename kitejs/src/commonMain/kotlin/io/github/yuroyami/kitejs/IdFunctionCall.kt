/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** The owner of a set of id-based functions: it knows their properties and how to run them. */
public interface IdFunctionCall {
    /** [thisObj] is null when called as a constructor, and then the result has to be a Scriptable. */
    public fun execIdCall(f: IdFunctionObject, cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any?
}
