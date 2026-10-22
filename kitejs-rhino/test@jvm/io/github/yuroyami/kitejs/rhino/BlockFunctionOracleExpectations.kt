/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Exact D-120 additions in corpus/let-const.js; the rest of its IR still matches upstream. */
internal object BlockFunctionOracleExpectations {
    fun assertIterationMarkers(root: Node) {
        val iterations = mutableListOf<Node>()
        fun walk(node: Node) {
            if (node.iterationBindings != null) iterations.add(node)
            for (child in node) walk(child)
        }
        walk(root)
        assertEquals(listOf(listOf("g"), listOf("g")), iterations.map { it.iterationBindings })
        val update = iterations.single { it.type == Token.EMPTY }
        assertEquals(Token.EXPR_VOID, assertNotNull(update.next).type)
        assertEquals(Token.INC, assertNotNull(update.next!!.firstChild).type)
    }
}
