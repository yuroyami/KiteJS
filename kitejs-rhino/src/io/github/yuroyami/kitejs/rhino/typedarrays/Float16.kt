/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino.typedarrays

import kotlin.math.abs
import kotlin.math.round

/**
 * IEEE 754 binary16, which `Float16Array`, `DataView`'s float16 methods and `Math.f16round` read
 * and write (ECMAScript 2025). A double rounds to the nearest half, ties to even, directly: going
 * through a float would round twice and miss some ties.
 */
internal object Float16 {

    /** The largest finite half, 65504, plus half its spacing: from here a double rounds to infinity. */
    private const val OVERFLOW = 65520.0

    /** 2^-14, the smallest normal half. */
    private const val MIN_NORMAL = 6.103515625e-5

    /** The bits of the half nearest [x], ties to even. NaN is the quiet NaN 0x7E00. */
    fun toBits(x: Double): Int {
        if (x.isNaN()) return 0x7E00
        val sign = if (x.toRawBits() < 0) 0x8000 else 0
        val a = abs(x)
        if (a >= OVERFLOW) return sign or 0x7C00
        if (a < MIN_NORMAL) {
            // A subnormal is a multiple of 2^-24; the product is exact, and 1024 carries into the
            // smallest normal on its own.
            return sign or round(a * 16777216.0).toInt()
        }
        val e = ((a.toRawBits() ushr 52) and 0x7FF).toInt() - 1023
        // a / 2^e is in [1, 2), and both steps are exact; a carry from 1023 moves to the exponent.
        val significand = a * Double.fromBits((1023L - e) shl 52)
        val fraction = round((significand - 1.0) * 1024.0).toInt()
        val bits = ((e + 15) shl 10) + fraction
        return sign or minOf(bits, 0x7C00)
    }

    /** The value of the half with bits [h], whose upper 16 bits are ignored. */
    fun fromBits(h: Int): Double {
        val negative = (h and 0x8000) != 0
        val exponent = (h ushr 10) and 0x1F
        val fraction = h and 0x3FF
        val magnitude = when (exponent) {
            0 -> fraction * 5.9604644775390625e-8 // 2^-24
            0x1F -> if (fraction == 0) Double.POSITIVE_INFINITY else return Double.NaN
            else -> (1024 + fraction) * Double.fromBits((exponent - 25 + 1023).toLong() shl 52)
        }
        return if (negative) -magnitude else magnitude
    }

    /** `Math.f16round`: [x] rounded to the nearest half. */
    fun nearest(x: Double): Double = if (x.isNaN()) x else fromBits(toBits(x))
}
