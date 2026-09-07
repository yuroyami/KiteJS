/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * Every compiled script implements this. It runs a script against an object scope.
 *
 * The scope holds the global variables and functions the script can see. For a spec-compliant
 * script that scope has to be a global object built by `Context.initStandardObjects`.
 */
public interface Script {

    /**
     * Runs the script with [thisObj] as its `this` value.
     *
     * [cx] has to be the Context associated with the calling thread.
     */
    public fun exec(cx: Context, scope: Scriptable, thisObj: Scriptable): Any?

    /** Kept from upstream, where it exists for scripts compiled by an older release. */
    @Deprecated("Use exec(cx, scope, thisObj)", ReplaceWith("exec(cx, scope, scope)"))
    public fun exec(cx: Context, scope: Scriptable): Any? = exec(cx, scope, scope)
}
