/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * A [LambdaFunction] the engine recognises by its [tag], so the interpreter and the runtime can
 * treat it specially. `Function.prototype.apply` and `call` are the two that matter most.
 */
open class KnownBuiltInFunction(
    /** What the engine identifies this function by. */
    val tag: Any,
    scope: Scriptable,
    name: String,
    length: Int,
    prototype: Any?,
    target: SerializableCallable,
) : LambdaFunction(scope, name, length, prototype, target)
