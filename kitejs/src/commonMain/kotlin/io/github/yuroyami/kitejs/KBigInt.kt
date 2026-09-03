/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * Stand-in for java.math.BigInteger (ledger D-5). Phase 0 only stores what the lexer
 * scanned; Phase 5 replaces this with a real arbitrary-precision integer.
 */
@ConsistentCopyVisibility
data class KBigInt internal constructor(val digits: String, val radix: Int) {
    override fun toString(): String = if (radix == 10) digits else "$digits (radix $radix)"
}
