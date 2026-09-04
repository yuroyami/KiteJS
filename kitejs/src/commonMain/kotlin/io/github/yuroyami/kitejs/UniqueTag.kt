/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * A marker value that is only ever compared by identity. Three of them exist and no more can be
 * made.
 */
class UniqueTag private constructor(private val tagId: Int) {

    // Overridden for readable debug output.
    override fun toString(): String {
        val name = when (tagId) {
            ID_NOT_FOUND -> "NOT_FOUND"
            ID_NULL_VALUE -> "NULL_VALUE"
            ID_DOUBLE_MARK -> "DOUBLE_MARK"
            else -> throw Kit.codeBug()
        }
        return super.toString() + ": " + name
    }

    companion object {
        private const val ID_NOT_FOUND = 1
        private const val ID_NULL_VALUE = 2
        private const val ID_DOUBLE_MARK = 3

        /** Returned when a property is absent, as opposed to present and undefined. */
        val NOT_FOUND: UniqueTag = UniqueTag(ID_NOT_FOUND)

        /** Stands in for a JavaScript null where a Kotlin null would mean something else. */
        val NULL_VALUE: UniqueTag = UniqueTag(ID_NULL_VALUE)

        /** Marks a slot in the interpreter stack whose real value lives in the parallel doubles. */
        val DOUBLE_MARK: UniqueTag = UniqueTag(ID_DOUBLE_MARK)
    }
}
