/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** What the runtime needs from a regular expression engine. The engine itself lands in phase 4. */
public interface RegExpProxy {

    public fun register(scope: ScriptableObject, sealed: Boolean)

    public fun isRegExp(obj: Scriptable?): Boolean

    public fun compileRegExp(cx: Context, source: String, flags: String?): Any

    public fun wrapRegExp(cx: Context, scope: Scriptable, compiled: Any): Scriptable

    public fun action(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>, actionType: Int): Any?

    public fun find_split(
        cx: Context,
        scope: Scriptable,
        target: String,
        separator: String,
        re: Scriptable,
        ip: IntArray,
        matchlen: IntArray,
        matched: BooleanArray,
        parensp: Array<Array<String>?>,
    ): Int

    public fun js_split(cx: Context, scope: Scriptable, thisString: String, args: Array<Any?>): Any?

    public companion object {
        public const val RA_MATCH: Int = 1
        public const val RA_REPLACE: Int = 2
        public const val RA_REPLACE_ALL: Int = 3
        public const val RA_SEARCH: Int = 4
    }
}
