/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * Stand-in for `java.math.BigInteger` (D-5). It stores what the lexer scanned and nothing else;
 * phase 5 replaces it with a real arbitrary-precision integer.
 *
 * It extends [Number] because upstream's `BigInteger` does, and the runtime keeps bigints in the
 * same slots as other numbers.
 */
class KBigInt internal constructor(val digits: String, val radix: Int) : Number() {

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

    override fun equals(other: Any?): Boolean = other is KBigInt && other.digits == digits && other.radix == radix

    override fun hashCode(): Int = digits.hashCode() * 31 + radix

    override fun toDouble(): Double = if (radix == 10) digits.toDouble() else digits.toLong(radix).toDouble()
    override fun toFloat(): Float = toDouble().toFloat()
    override fun toLong(): Long = toDouble().toLong()
    override fun toInt(): Int = toDouble().toInt()
    override fun toShort(): Short = toInt().toShort()
    override fun toByte(): Byte = toInt().toByte()

    // Arithmetic waits for phase 5 (D-5). Each one says so rather than giving a wrong answer.
    private fun notYet(): Nothing = throw UnsupportedOperationException("BigInt arithmetic lands in phase 5")
    fun add(other: KBigInt): KBigInt = notYet()
    fun subtract(other: KBigInt): KBigInt = notYet()
    fun multiply(other: KBigInt): KBigInt = notYet()
    fun divide(other: KBigInt): KBigInt = notYet()
    fun remainder(other: KBigInt): KBigInt = notYet()
    fun pow(exponent: Int): KBigInt = notYet()
    fun negate(): KBigInt = notYet()
    fun not(): KBigInt = notYet()
    fun and(other: KBigInt): KBigInt = notYet()
    fun or(other: KBigInt): KBigInt = notYet()
    fun xor(other: KBigInt): KBigInt = notYet()
    fun shiftLeft(n: Int): KBigInt = notYet()
    fun shiftRight(n: Int): KBigInt = notYet()
    fun signum(): Int = notYet()
    fun intValueExact(): Int = notYet()
    operator fun compareTo(other: KBigInt): Int = notYet()
    fun compareToDouble(d: Double): Int = notYet()

    companion object {
        val ZERO: KBigInt = KBigInt("0", 10)
        val ONE: KBigInt = KBigInt("1", 10)

        /** Parses decimal digits, with an optional sign. Other radixes wait for phase 5. */
        fun parse(text: String, radix: Int = 10): KBigInt = KBigInt(text, radix)
    }
}
