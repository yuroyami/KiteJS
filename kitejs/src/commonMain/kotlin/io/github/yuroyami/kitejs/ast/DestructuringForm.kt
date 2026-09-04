/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

/**
 * Shared by [ArrayLiteral] and [ObjectLiteral], the two node types that can appear in a
 * destructuring position.
 */
interface DestructuringForm {

    /**
     * True when this node sits in a destructuring position: a function parameter, the target of
     * a variable initializer, the iterator of a for..in loop, and so on.
     */
    var isDestructuring: Boolean
}
