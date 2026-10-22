/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

/**
 * A String, Number, Boolean, BigInt or Symbol object. When the engine made it for the primitive
 * receiver of a call, [isReceiver] is true, and strict code and the built-ins see [primitiveValue].
 */
internal interface PrimitiveWrapper {
    val primitiveValue: Any
    var isReceiver: Boolean
}
