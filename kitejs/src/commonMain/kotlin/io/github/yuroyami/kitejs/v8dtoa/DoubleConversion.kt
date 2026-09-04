// Copyright 2011 the V8 project authors. All rights reserved.
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are
// met:
//
//     * Redistributions of source code must retain the above copyright
//       notice, this list of conditions and the following disclaimer.
//     * Redistributions in binary form must reproduce the above
//       copyright notice, this list of conditions and the following
//       disclaimer in the documentation and/or other materials provided
//       with the distribution.
//     * Neither the name of Google Inc. nor the names of its
//       contributors may be used to endorse or promote products derived
//       from this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
// OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
// LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
// DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
// THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

// Ported to Java from V8's conversions-inl.h and double.h files, revision r12273
// of the bleeding_edge branch, and from there to Kotlin.

package io.github.yuroyami.kitejs.v8dtoa

import kotlin.math.ceil
import kotlin.math.floor

object DoubleConversion {

    // 0x8000000000000000, the sign bit. Kotlin cannot write that as a hex literal.
    private const val K_SIGN_MASK = Long.MIN_VALUE
    private const val K_EXPONENT_MASK = 0x7FF0000000000000L
    private const val K_SIGNIFICAND_MASK = 0x000FFFFFFFFFFFFFL
    private const val K_HIDDEN_BIT = 0x0010000000000000L
    private const val K_PHYSICAL_SIGNIFICAND_SIZE = 52 // excludes the hidden bit
    private const val K_SIGNIFICAND_SIZE = 53
    private const val K_EXPONENT_BIAS = 0x3FF + K_PHYSICAL_SIGNIFICAND_SIZE
    private const val K_DENORMAL_EXPONENT = -K_EXPONENT_BIAS + 1

    private fun exponent(d64: Long): Int {
        if (isDenormal(d64)) return K_DENORMAL_EXPONENT

        val biasedE = ((d64 and K_EXPONENT_MASK) shr K_PHYSICAL_SIGNIFICAND_SIZE).toInt()
        return biasedE - K_EXPONENT_BIAS
    }

    private fun significand(d64: Long): Long {
        val significand = d64 and K_SIGNIFICAND_MASK
        if (!isDenormal(d64)) {
            return significand + K_HIDDEN_BIT
        }
        return significand
    }

    private fun isDenormal(d64: Long): Boolean = (d64 and K_EXPONENT_MASK) == 0L

    private fun sign(d64: Long): Int = if ((d64 and K_SIGN_MASK) == 0L) 1 else -1

    fun doubleToInt32(x: Double): Int {
        val i = x.toInt()
        if (i.toDouble() == x) {
            return i
        }
        val d64 = x.toBits()
        val exponent = exponent(d64)
        if (exponent <= -K_SIGNIFICAND_SIZE || exponent > 31) {
            return 0
        }
        val s = significand(d64)
        return sign(d64) * (if (exponent < 0) s shr -exponent else s shl exponent).toInt()
    }

    fun truncate(x: Double): Double {
        if (!x.isFinite()) {
            return x
        }
        return if (x < 0.0) ceil(x) else floor(x)
    }
}
