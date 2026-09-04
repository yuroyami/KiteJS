/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

/**
 * Base class for loop nodes. A loop introduces a scope, so it extends [Scope].
 *
 * KMP: upstream's protected `body` field stays as [bodyField], because
 * [ArrayComprehensionLoop] overrides the accessor to report no body at all.
 */
abstract class Loop(pos: Int = -1, len: Int = 1) : Scope(pos, len) {

    protected var bodyField: AstNode? = null

    /** Left paren position, -1 if missing. */
    var lp: Int = -1

    /** Right paren position, -1 if missing. */
    var rp: Int = -1

    /** The loop body. Setting it reparents the body and grows this node to include it. */
    open var body: AstNode?
        get() = bodyField
        set(value) {
            val newBody = value!!
            bodyField = newBody
            val end = newBody.position + newBody.length
            this.length = end - this.position
            newBody.parent = this
        }

    /** Sets both paren positions. */
    fun setParens(lp: Int, rp: Int) {
        this.lp = lp
        this.rp = rp
    }
}
