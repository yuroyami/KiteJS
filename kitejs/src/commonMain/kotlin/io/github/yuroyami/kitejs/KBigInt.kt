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

    /**
     * The decimal spelling. Only radix 10 works until the real big integer lands, because the stub
     * cannot convert between radixes (D-5).
     */
    fun toString(radix: Int): String {
        require(radix == 10) { "only radix 10 is supported until BigInt lands" }
        require(this.radix == 10) { "this literal was written in radix ${this.radix}" }
        return digits
    }

    /** Whether this is zero. Works on the stub because a zero literal only ever spells out zeroes. */
    fun isZero(): Boolean = digits.all { it == '0' }
}
