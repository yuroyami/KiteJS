/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

import io.github.yuroyami.kitejs.Token

/**
 * Root of a parse tree. Holds the statements and functions in the script plus the [Comment]
 * nodes attached to the script as a whole. Node type is [Token.SCRIPT].
 *
 * The tree itself does not store errors. To collect parse errors and warnings, hand an
 * ErrorReporter to the Parser through CompilerEnvirons.
 *
 * KMP: upstream keeps the comments in a `TreeSet` ordered by [AstNode.PositionComparator].
 * Common Kotlin has no sorted set, so the port keeps an insertion-sorted list with the same
 * ordering and the same drop-on-equal-position behavior a TreeSet has (D-8).
 */
class AstRoot(pos: Int = -1) : ScriptNode(pos) {

    private var commentList: MutableList<Comment>? = null

    init {
        typeField = Token.SCRIPT
    }

    /** Comments sorted by start position, or null if the script has none. */
    val comments: List<Comment>?
        get() = commentList

    /** Replaces the comment list and reparents every entry to this node. */
    fun setComments(comments: Collection<Comment>?) {
        if (comments == null) {
            this.commentList = null
        } else {
            this.commentList?.clear()
            for (c in comments) addComment(c)
        }
    }

    /** Adds a comment to the comment set. */
    fun addComment(comment: Comment) {
        val list = commentList ?: mutableListOf<Comment>().also { commentList = it }
        val cmp = PositionComparator()
        var lo = 0
        var hi = list.size
        var duplicate = false
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val c = cmp.compare(list[mid], comment)
            if (c == 0) {
                duplicate = true
                break
            }
            if (c < 0) lo = mid + 1 else hi = mid
        }
        if (!duplicate) list.add(lo, comment)
        comment.parent = this
    }

    /**
     * Visits the comment nodes in source order. The comments are not visited by [visit], so
     * this is the only way to reach them. Return values are ignored.
     */
    fun visitComments(visitor: NodeVisitor) {
        commentList?.forEach { visitor.visit(it) }
    }

    /** Visits the AST nodes, then the comment nodes. */
    fun visitAll(visitor: NodeVisitor) {
        visit(visitor)
        visitComments(visitor)
    }

    override fun toSource(depth: Int): String {
        val sb = StringBuilder()
        for (node in this) {
            sb.append((node as AstNode).toSource(depth))
            if (node.type == Token.COMMENT) {
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    /** A debug printer that includes the comments, at the end. */
    override fun debugPrint(): String {
        val dpv = DebugPrintVisitor(StringBuilder(1000))
        visitAll(dpv)
        return dpv.toString()
    }

    /** Checks that the parser set the parent link for every node in the tree. */
    fun checkParentLinks() {
        this.visit { node ->
            if (node.type != Token.SCRIPT && node.parent == null) {
                throw IllegalStateException("No parent for node: $node\n${node.toSource(0)}")
            }
            true
        }
    }
}
