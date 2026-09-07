/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * A lazily flattened rope of two character sequences. Repeated string concatenation builds a tree
 * of these instead of copying, and the tree is flattened once, on the first read.
 *
 * KMP: upstream synchronizes [flatten]. Dropped under the single-thread contract (D-3).
 */
public class ConsString(str1: CharSequence, str2: CharSequence) : CharSequence {

    private var left: CharSequence
    private var right: CharSequence
    private val len: Int
    private var isFlat: Boolean

    init {
        left = if (str1 is String || str1 is ConsString) str1 else str1.toString()
        right = if (str2 is String || str2 is ConsString) str2 else str2.toString()
        len = left.length + right.length
        isFlat = false
    }

    override fun toString(): String = if (isFlat) left as String else flatten()

    private fun flatten(): String {
        if (!isFlat) {
            val chars = CharArray(len)
            var charPos = len

            val stack = ArrayDeque<CharSequence>()
            stack.addFirst(left)

            var next: CharSequence? = right
            do {
                if (next is ConsString) {
                    if (next.isFlat) {
                        next = next.left
                    } else {
                        stack.addFirst(next.left)
                        next = next.right
                        continue
                    }
                }

                val str = next as String
                charPos -= str.length
                for (i in str.indices) {
                    chars[charPos + i] = str[i]
                }
                next = if (stack.isEmpty()) null else stack.removeFirst()
            } while (next != null)

            left = chars.concatToString()
            right = ""
            isFlat = true
        }
        return left as String
    }

    override val length: Int
        get() = len

    override fun get(index: Int): Char {
        val str = if (isFlat) left as String else flatten()
        return str[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
        val str = if (isFlat) left as String else flatten()
        return str.substring(startIndex, endIndex)
    }
}
