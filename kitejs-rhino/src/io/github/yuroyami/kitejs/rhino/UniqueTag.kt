/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

/**
 * A marker value that is only ever compared by identity. Four of them exist and no more can be
 * made.
 */
public class UniqueTag private constructor(private val tagId: Int) {

    // Overridden for readable debug output.
    override fun toString(): String {
        val name = when (tagId) {
            ID_NOT_FOUND -> "NOT_FOUND"
            ID_NULL_VALUE -> "NULL_VALUE"
            ID_DOUBLE_MARK -> "DOUBLE_MARK"
            ID_UNINITIALIZED -> "UNINITIALIZED"
            else -> throw Kit.codeBug()
        }
        return super.toString() + ": " + name
    }

    public companion object {
        private const val ID_NOT_FOUND = 1
        private const val ID_NULL_VALUE = 2
        private const val ID_DOUBLE_MARK = 3
        private const val ID_UNINITIALIZED = 4

        /** Returned when a property is absent, as opposed to present and undefined. */
        public val NOT_FOUND: UniqueTag = UniqueTag(ID_NOT_FOUND)

        /** Stands in for a JavaScript null where a Kotlin null would mean something else. */
        public val NULL_VALUE: UniqueTag = UniqueTag(ID_NULL_VALUE)

        /** Marks a slot in the interpreter stack whose real value lives in the parallel doubles. */
        public val DOUBLE_MARK: UniqueTag = UniqueTag(ID_DOUBLE_MARK)

        /**
         * The value of a `let`, `const` or class binding in its temporal dead zone (ECMAScript
         * 2015, 8.1.1.1.6). It lives only in a frame slot or a scope object, and every read of a
         * name throws a ReferenceError on it, so a script never sees it (#137).
         */
        internal val UNINITIALIZED: UniqueTag = UniqueTag(ID_UNINITIALIZED)
    }
}
