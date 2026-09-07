/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

/**
 * Simple visitor for traversing the AST. Nodes are visited in an arbitrary order; the
 * visitor casts nodes to the right type based on their token type.
 */
public fun interface NodeVisitor {

    /**
     * Visits an AST node. Never receives an [AstRoot], since that is where visiting begins.
     *
     * @return true to visit the children, false to skip the subtree rooted at [node].
     */
    public fun visit(node: AstNode): Boolean
}
