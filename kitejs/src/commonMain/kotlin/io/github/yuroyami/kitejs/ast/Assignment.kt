/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

/**
 * An assignment expression such as `a = b` or `a += b`. The node type is the assignment
 * operator token.
 */
public class Assignment : InfixExpression {

    public constructor() : super()

    public constructor(pos: Int) : super(pos)

    public constructor(pos: Int, len: Int) : super(pos, len)

    public constructor(pos: Int, len: Int, left: AstNode, right: AstNode) : super(pos, len, left, right)

    public constructor(left: AstNode, right: AstNode) : super(left, right)

    public constructor(
        operator: Int,
        left: AstNode,
        right: AstNode,
        operatorPos: Int,
    ) : super(operator, left, right, operatorPos)
}
