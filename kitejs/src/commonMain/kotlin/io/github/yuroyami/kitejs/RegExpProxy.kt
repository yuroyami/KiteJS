/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** What the runtime needs from a regular expression engine. The engine itself lands in phase 4. */
interface RegExpProxy {

    fun register(scope: ScriptableObject, sealed: Boolean)

    fun isRegExp(obj: Scriptable?): Boolean

    fun compileRegExp(cx: Context, source: String, flags: String?): Any

    fun wrapRegExp(cx: Context, scope: Scriptable, compiled: Any): Scriptable

    fun action(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>, actionType: Int): Any?

    fun find_split(
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

    fun js_split(cx: Context, scope: Scriptable, thisString: String, args: Array<Any?>): Any?

    companion object {
        const val RA_MATCH = 1
        const val RA_REPLACE = 2
        const val RA_REPLACE_ALL = 3
        const val RA_SEARCH = 4
    }
}
