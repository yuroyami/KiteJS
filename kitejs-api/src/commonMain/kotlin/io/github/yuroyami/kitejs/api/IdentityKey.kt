/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

/** A map key that never calls the source object's structural equals or hashCode. */
internal class IdentityKey(private val value: Any) {
    override fun equals(other: Any?): Boolean = other is IdentityKey && value === other.value
    override fun hashCode(): Int = identityHash(value)
}

internal expect fun identityHash(value: Any): Int
