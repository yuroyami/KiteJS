/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The [Callable] that `LambdaFunction` and `LambdaConstructor` take. Upstream also requires it to be
 * `Serializable`; there is no serialization here, so the name is kept for parity and the marker is
 * empty (D-22).
 */
public fun interface SerializableCallable : Callable
