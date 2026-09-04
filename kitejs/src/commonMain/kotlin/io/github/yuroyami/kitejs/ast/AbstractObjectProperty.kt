/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.ast

/** Base class for the entries of an object literal: [ObjectProperty] and [SpreadObjectProperty]. */
abstract class AbstractObjectProperty protected constructor(
    pos: Int = -1,
    len: Int = 1,
) : AstNode(pos, len)
