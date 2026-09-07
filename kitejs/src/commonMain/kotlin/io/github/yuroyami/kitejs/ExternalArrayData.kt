/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** Implement this to back a [ScriptableObject] with data that lives outside the engine. */
public interface ExternalArrayData {

    /**
     * The element at [index]. It has to be a type script understands: a number, a string or a
     * [Scriptable]. Never called with an out-of-range index.
     */
    public fun getArrayElement(index: Int): Any?

    /**
     * Writes [value] at [index]. Never called with an out-of-range index. The implementation checks
     * the type of [value] and converts it if it has to.
     */
    public fun setArrayElement(index: Int, value: Any?)

    /** How many elements there are. */
    public val arrayLength: Int
}
