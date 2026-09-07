/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** The iterator behind `for (const ch of "text")`: one code point per step. */
class NativeStringIterator : ES6Iterator {

    private var string: String = ""
    private var index = 0

    private constructor() : super()

    internal constructor(scope: Scriptable, stringLike: Any?) : super(scope, ITERATOR_TAG) {
        index = 0
        string = ScriptRuntime.toString(stringLike)
    }

    override val className: String
        get() = "String Iterator"

    override fun isDone(cx: Context, scope: Scriptable): Boolean = index >= string.length

    override fun nextValue(cx: Context, scope: Scriptable): Any? {
        val newIndex = offsetByOneCodePoint(string, index)
        val value = string.substring(index, newIndex)
        index = newIndex
        return value
    }

    override val tag: String get() = ITERATOR_TAG

    companion object {
        private const val ITERATOR_TAG = "StringIterator"

        internal fun init(scope: ScriptableObject, sealed: Boolean) {
            ES6Iterator.init(scope, sealed, NativeStringIterator(), ITERATOR_TAG)
        }

        /** String.offsetByCodePoints(index, 1): skips a whole surrogate pair. */
        private fun offsetByOneCodePoint(s: String, index: Int): Int {
            if (s[index].isHighSurrogate() && index + 1 < s.length && s[index + 1].isLowSurrogate()) return index + 2
            return index + 1
        }
    }
}
