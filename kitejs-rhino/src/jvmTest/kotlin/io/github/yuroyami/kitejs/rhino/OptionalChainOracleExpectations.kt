/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.assertEquals

/** #132: upstream's optional-chain rendering and lowering preserve the wrong call boundaries. */
internal object OptionalChainOracleExpectations {
    const val source = "a = b?.c;\na = b?.[c];\na = b?.();\na = b?.c?.d;\na = b?.c();\na = b ?? c;\na = b ?? c ?? d;\n"

    fun assertStructure(tree: Node) {
        val chains = mutableListOf<String>()
        fun shape(node: Node): String {
            val children = generateSequence(node.firstChild) { it.next }.joinToString(",") { shape(it) }
            if (node.type == Token.NAME || node.type == Token.STRING) return node.string!!
            val optional = if (node.getIntProp(Node.OPTIONAL_CHAINING, 0) == 1) "?" else ""
            val type = if (node.type == Token.QUESTION_DOT) "CHAIN" else Token.typeToName(node.type)
            return "$type$optional($children)"
        }
        fun visit(node: Node) {
            if (node.type == Token.QUESTION_DOT) chains.add(shape(node))
            var child = node.firstChild
            while (child != null) { visit(child); child = child.next }
        }
        visit(tree)
        assertEquals(listOf(
            "CHAIN(GETPROP?(b,c))", "CHAIN(GETELEM?(b,c))", "CHAIN(CALL?(b))",
            "CHAIN(GETPROP?(GETPROP?(b,c),d))", "CHAIN(CALL(GETPROP?(b,c)))",
        ), chains)
    }
}
