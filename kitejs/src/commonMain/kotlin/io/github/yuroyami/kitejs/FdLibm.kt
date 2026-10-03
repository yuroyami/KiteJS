/*
 * ====================================================
 * Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.
 *
 * Developed at SunSoft, a Sun Microsystems, Inc. business.
 * Permission to use, copy, modify, and distribute this
 * software is freely granted, provided that this notice
 * is preserved.
 * ====================================================
 *
 * Copyright 2004 Sun Microsystems, Inc.  All Rights Reserved.
 * (the __kernel_tan routine)
 *
 * Permission to use, copy, modify, and distribute this
 * software is freely granted, provided that this notice
 * is preserved.
 * ====================================================
 */

package io.github.yuroyami.kitejs

import kotlin.math.withSign

/**
 * The transcendental functions of fdlibm 5.3, ported to common Kotlin so every target computes
 * the same digits.
 *
 * `kotlin.math` hands these to the platform: `Math` on the JVM, which may be an intrinsic within
 * one unit in the last place of the correct answer, JavaScript's own on Kotlin/JS, and the
 * system libm on the native targets. They disagree in the last bit for some arguments, so the
 * same script gave different numbers on different targets. fdlibm is what the JVM's StrictMath
 * is, and what V8's `ieee754.cc` is derived from, so these are also the digits a browser gives
 * (D-73).
 *
 * Each routine follows the C source line by line, with the C names. `hi` and `lo` stand for the
 * `__HI` and `__LO` macros: the high and low 32-bit words of a double.
 */
internal object FdLibm {

    private fun hi(x: Double): Int = (x.toRawBits() ushr 32).toInt()

    private fun lo(x: Double): Int = x.toRawBits().toInt()

    private fun fromWords(hi: Int, lo: Int): Double =
        Double.fromBits((hi.toLong() shl 32) or (lo.toLong() and 0xFFFFFFFFL))

    private fun withHi(x: Double, hi: Int): Double = fromWords(hi, lo(x))

    private fun withLo(x: Double, lo: Int): Double = fromWords(hi(x), lo)

    private const val ZERO = 0.0
    private const val ONE = 1.0
    private const val TWO = 2.0
    private const val HALF = 0.5
    private const val HUGE = 1.0e+300
    private const val TINY = 1.0e-300
    private const val TWO24 = 1.67772160000000000000e+07 // 0x41700000, 0x00000000
    private const val TWON24 = 5.96046447753906250000e-08 // 0x3E700000, 0x00000000
    private const val TWO53 = 9007199254740992.0 // 0x43400000, 0x00000000
    private const val TWO54 = 1.80143985094819840000e+16 // 0x43500000, 0x00000000
    private const val TWOM54 = 5.55111512312578270212e-17 // 0x3C900000, 0x00000000
    private const val LN2_HI = 6.93147180369123816490e-01 // 0x3fe62e42, 0xfee00000
    private const val LN2_LO = 1.90821492927058770002e-10 // 0x3dea39ef, 0x35793c76
    private const val INVLN2 = 1.44269504088896338700e+00 // 0x3ff71547, 0x652b82fe

    // ---- s_scalbn.c -------------------------------------------------------------------------------

    /** x * 2^n, computed by exponent manipulation. */
    fun scalbn(xIn: Double, n: Int): Double {
        var x = xIn
        var hx = hi(x)
        val lx = lo(x)
        var k = (hx and 0x7ff00000) shr 20
        if (k == 0) {
            if ((lx or (hx and 0x7fffffff)) == 0) return x // +-0
            x *= TWO54
            hx = hi(x)
            k = ((hx and 0x7ff00000) shr 20) - 54
            if (n < -50000) return TINY * x // underflow
        }
        if (k == 0x7ff) return x + x // NaN or Inf
        k += n
        if (k > 0x7fe) return HUGE * HUGE.withSign(x) // overflow
        if (k > 0) return withHi(x, (hx and 0x800fffff.toInt()) or (k shl 20)) // normal result
        if (k <= -54) {
            return if (n > 50000) HUGE * HUGE.withSign(x) else TINY * TINY.withSign(x)
        }
        k += 54 // subnormal result
        x = withHi(x, (hx and 0x800fffff.toInt()) or (k shl 20))
        return x * TWOM54
    }

    // ---- e_exp.c ----------------------------------------------------------------------------------

    private val halF = doubleArrayOf(0.5, -0.5)
    private const val TWOM1000 = 9.33263618503218878990e-302 // 2**-1000=0x01700000,0
    private const val E = 2.718281828459045 // 0x4005bf0a, 0x8b145769
    private const val O_THRESHOLD = 7.09782712893383973096e+02 // 0x40862E42, 0xFEFA39EF
    private const val U_THRESHOLD = -7.45133219101941108420e+02 // 0xc0874910, 0xD52D3051
    private val ln2HI = doubleArrayOf(6.93147180369123816490e-01, -6.93147180369123816490e-01)
    private val ln2LO = doubleArrayOf(1.90821492927058770002e-10, -1.90821492927058770002e-10)
    private const val P1 = 1.66666666666666019037e-01 // 0x3FC55555, 0x5555553E
    private const val P2 = -2.77777777770155933842e-03 // 0xBF66C16C, 0x16BEBD93
    private const val P3 = 6.61375632143793436117e-05 // 0x3F11566A, 0xAF25DE2C
    private const val P4 = -1.65339022054652515390e-06 // 0xBEBBBD41, 0xC5D26BF1
    private const val P5 = 4.13813679705723846039e-08 // 0x3E663769, 0x72BEA4D0

    fun exp(xIn: Double): Double {
        var x = xIn
        var y: Double
        var hiPart = 0.0
        var loPart = 0.0
        var k = 0
        var hx = hi(x)
        val xsb = (hx ushr 31) and 1 // sign bit of x
        hx = hx and 0x7fffffff // high word of |x|

        // filter out non-finite argument
        if (hx >= 0x40862E42) { // if |x|>=709.78...
            if (hx >= 0x7ff00000) {
                return if (((hx and 0xfffff) or lo(x)) != 0) {
                    x + x // NaN
                } else {
                    if (xsb == 0) x else 0.0 // exp(+-inf)={inf,0}
                }
            }
            if (x > O_THRESHOLD) return HUGE * HUGE // overflow
            if (x < U_THRESHOLD) return TWOM1000 * TWOM1000 // underflow
        }

        // argument reduction
        if (hx > 0x3fd62e42) { // if  |x| > 0.5 ln2
            if (hx < 0x3FF0A2B2) { // and |x| < 1.5 ln2
                // V8: the computation below gets the last bit of exp(1) wrong, so it is special
                // cased to give Math.E, as every browser does.
                if (x == 1.0) return E
                hiPart = x - ln2HI[xsb]
                loPart = ln2LO[xsb]
                k = 1 - xsb - xsb
            } else {
                k = (INVLN2 * x + halF[xsb]).toInt()
                val t = k.toDouble()
                hiPart = x - t * ln2HI[0] // t*ln2HI is exact here
                loPart = t * ln2LO[0]
            }
            x = hiPart - loPart
        } else if (hx < 0x3e300000) { // when |x|<2**-28
            if (HUGE + x > ONE) return ONE + x // trigger inexact
        } else {
            k = 0
        }

        // x is now in primary range
        val t = x * x
        val c = x - t * (P1 + t * (P2 + t * (P3 + t * (P4 + t * P5))))
        if (k == 0) return ONE - ((x * c) / (c - 2.0) - x)
        y = ONE - ((loPart - (x * c) / (2.0 - c)) - hiPart)
        return if (k >= -1021) {
            withHi(y, hi(y) + (k shl 20)) // add k to y's exponent
        } else {
            y = withHi(y, hi(y) + ((k + 1000) shl 20))
            y * TWOM1000
        }
    }

    // ---- e_log.c ----------------------------------------------------------------------------------

    private const val LG1 = 6.666666666666735130e-01 // 3FE55555 55555593
    private const val LG2 = 3.999999999940941908e-01 // 3FD99999 9997FA04
    private const val LG3 = 2.857142874366239149e-01 // 3FD24924 94229359
    private const val LG4 = 2.222219843214978396e-01 // 3FCC71C5 1D8E78AF
    private const val LG5 = 1.818357216161805012e-01 // 3FC74664 96CB03DE
    private const val LG6 = 1.531383769920937332e-01 // 3FC39A09 D078C69F
    private const val LG7 = 1.479819860511658591e-01 // 3FC2F112 DF3E5244

    fun log(xIn: Double): Double {
        var x = xIn
        var hx = hi(x)
        val lx = lo(x)

        var k = 0
        if (hx < 0x00100000) { // x < 2**-1022
            if (((hx and 0x7fffffff) or lx) == 0) return -TWO54 / ZERO // log(+-0)=-inf
            if (hx < 0) return (x - x) / ZERO // log(-#) = NaN
            k -= 54
            x *= TWO54 // subnormal number, scale up x
            hx = hi(x)
        }
        if (hx >= 0x7ff00000) return x + x
        k += (hx shr 20) - 1023
        hx = hx and 0x000fffff
        var i = (hx + 0x95f64) and 0x100000
        x = withHi(x, hx or (i xor 0x3ff00000)) // normalize x or x/2
        k += i shr 20
        val f = x - 1.0
        if ((0x000fffff and (2 + hx)) < 3) { // |f| < 2**-20
            if (f == ZERO) {
                if (k == 0) return ZERO
                val dk = k.toDouble()
                return dk * LN2_HI + dk * LN2_LO
            }
            val r = f * f * (0.5 - 0.33333333333333333 * f)
            if (k == 0) return f - r
            val dk = k.toDouble()
            return dk * LN2_HI - ((r - dk * LN2_LO) - f)
        }
        val s = f / (2.0 + f)
        val dk = k.toDouble()
        val z = s * s
        i = hx - 0x6147a
        val w = z * z
        val j = 0x6b851 - hx
        val t1 = w * (LG2 + w * (LG4 + w * LG6))
        val t2 = z * (LG1 + w * (LG3 + w * (LG5 + w * LG7)))
        i = i or j
        val r = t2 + t1
        return if (i > 0) {
            val hfsq = 0.5 * f * f
            if (k == 0) {
                f - (hfsq - s * (hfsq + r))
            } else {
                dk * LN2_HI - ((hfsq - (s * (hfsq + r) + dk * LN2_LO)) - f)
            }
        } else {
            if (k == 0) f - s * (f - r) else dk * LN2_HI - ((s * (f - r) - dk * LN2_LO) - f)
        }
    }

    // ---- e_log10.c --------------------------------------------------------------------------------

    private const val IVLN10 = 4.34294481903251816668e-01 // 0x3FDBCB7B, 0x1526E50E
    private const val LOG10_2HI = 3.01029995663611771306e-01 // 0x3FD34413, 0x509F6000
    private const val LOG10_2LO = 3.69423907715893078616e-13 // 0x3D59FEF3, 0x11F12B36

    fun log10(xIn: Double): Double {
        var x = xIn
        var hx = hi(x)
        val lx = lo(x)

        var k = 0
        if (hx < 0x00100000) { // x < 2**-1022
            if (((hx and 0x7fffffff) or lx) == 0) return -TWO54 / ZERO // log(+-0)=-inf
            if (hx < 0) return (x - x) / ZERO // log(-#) = NaN
            k -= 54
            x *= TWO54 // subnormal number, scale up x
            hx = hi(x)
        }
        if (hx >= 0x7ff00000) return x + x
        k += (hx shr 20) - 1023
        val i = (k and 0x80000000.toInt()) ushr 31
        hx = (hx and 0x000fffff) or ((0x3ff - i) shl 20)
        val y = (k + i).toDouble()
        x = withHi(x, hx)
        val z = y * LOG10_2LO + IVLN10 * log(x)
        return z + y * LOG10_2HI
    }

    // ---- s_log1p.c --------------------------------------------------------------------------------

    fun log1p(x: Double): Double {
        var f = 0.0
        var c = 0.0
        var u: Double
        val hx = hi(x)
        val ax = hx and 0x7fffffff

        var k = 1
        var hu = 0
        if (hx < 0x3FDA827A) { // x < 0.41422
            if (ax >= 0x3ff00000) { // x <= -1.0
                return if (x == -1.0) -TWO54 / ZERO else (x - x) / (x - x) // log1p(-1)=-inf, log1p(x<-1)=NaN
            }
            if (ax < 0x3e200000) { // |x| < 2**-29
                return if (TWO54 + x > ZERO && ax < 0x3c900000) { // |x| < 2**-54
                    x
                } else {
                    x - x * x * 0.5
                }
            }
            if (hx > 0 || hx <= 0xbfd2bec3.toInt()) { // -0.2929<x<0.41422
                k = 0
                f = x
                hu = 1
            }
        }
        if (hx >= 0x7ff00000) return x + x
        if (k != 0) {
            if (hx < 0x43400000) {
                u = 1.0 + x
                hu = hi(u)
                k = (hu shr 20) - 1023
                c = if (k > 0) 1.0 - (u - x) else x - (u - 1.0) // correction term
                c /= u
            } else {
                u = x
                hu = hi(u)
                k = (hu shr 20) - 1023
                c = 0.0
            }
            hu = hu and 0x000fffff
            if (hu < 0x6a09e) {
                u = withHi(u, hu or 0x3ff00000) // normalize u
            } else {
                k += 1
                u = withHi(u, hu or 0x3fe00000) // normalize u/2
                hu = (0x00100000 - hu) shr 2
            }
            f = u - 1.0
        }
        val hfsq = 0.5 * f * f
        if (hu == 0) { // |f| < 2**-20
            if (f == ZERO) {
                if (k == 0) return ZERO
                c += k * LN2_LO
                return k * LN2_HI + c
            }
            val r = hfsq * (1.0 - 0.66666666666666666 * f)
            return if (k == 0) f - r else k * LN2_HI - ((r - (k * LN2_LO + c)) - f)
        }
        val s = f / (2.0 + f)
        val z = s * s
        val r = z * (LG1 + z * (LG2 + z * (LG3 + z * (LG4 + z * (LG5 + z * (LG6 + z * LG7))))))
        return if (k == 0) {
            f - (hfsq - s * (hfsq + r))
        } else {
            k * LN2_HI - ((hfsq - (s * (hfsq + r) + (k * LN2_LO + c))) - f)
        }
    }

    // ---- s_expm1.c --------------------------------------------------------------------------------

    private const val Q1 = -3.33333333333331316428e-02 // BFA11111 111110F4
    private const val Q2 = 1.58730158725481460165e-03 // 3F5A01A0 19FE5585
    private const val Q3 = -7.93650757867487942473e-05 // BF14CE19 9EAADBB7
    private const val Q4 = 4.00821782732936239552e-06 // 3ED0CFCA 86E65239
    private const val Q5 = -2.01099218183624371326e-07 // BE8AFDB7 6E09C32D

    fun expm1(xIn: Double): Double {
        var x = xIn
        var y: Double
        val hiPart: Double
        val loPart: Double
        var c = 0.0
        var t: Double
        var e: Double
        val k: Int
        var hx = hi(x)
        val xsb = hx and 0x80000000.toInt() // sign bit of x
        hx = hx and 0x7fffffff // high word of |x|

        // filter out huge and non-finite argument
        if (hx >= 0x4043687A) { // if |x|>=56*ln2
            if (hx >= 0x40862E42) { // if |x|>=709.78...
                if (hx >= 0x7ff00000) {
                    return if (((hx and 0xfffff) or lo(x)) != 0) {
                        x + x // NaN
                    } else {
                        if (xsb == 0) x else -1.0 // exp(+-inf)={inf,-1}
                    }
                }
                if (x > O_THRESHOLD) return HUGE * HUGE // overflow
            }
            if (xsb != 0) { // x < -56*ln2, return -1.0 with inexact
                if (x + TINY < 0.0) return TINY - ONE // raise inexact, return -1
            }
        }

        // argument reduction
        if (hx > 0x3fd62e42) { // if  |x| > 0.5 ln2
            if (hx < 0x3FF0A2B2) { // and |x| < 1.5 ln2
                if (xsb == 0) {
                    hiPart = x - LN2_HI
                    loPart = LN2_LO
                    k = 1
                } else {
                    hiPart = x + LN2_HI
                    loPart = -LN2_LO
                    k = -1
                }
            } else {
                k = (INVLN2 * x + (if (xsb == 0) 0.5 else -0.5)).toInt()
                t = k.toDouble()
                hiPart = x - t * LN2_HI // t*ln2_hi is exact here
                loPart = t * LN2_LO
            }
            x = hiPart - loPart
            c = (hiPart - x) - loPart
        } else if (hx < 0x3c900000) { // when |x|<2**-54, return x
            t = HUGE + x // return x with inexact flags when x!=0
            return x - (t - (HUGE + x))
        } else {
            k = 0
        }

        // x is now in primary range
        val hfx = 0.5 * x
        val hxs = x * hfx
        val r1 = ONE + hxs * (Q1 + hxs * (Q2 + hxs * (Q3 + hxs * (Q4 + hxs * Q5))))
        t = 3.0 - r1 * hfx
        e = hxs * ((r1 - t) / (6.0 - x * t))
        if (k == 0) return x - (x * e - hxs) // c is 0
        e = (x * (e - c) - c)
        e -= hxs
        if (k == -1) return 0.5 * (x - e) - 0.5
        if (k == 1) {
            return if (x < -0.25) -2.0 * (e - (x + 0.5)) else ONE + 2.0 * (x - e)
        }
        if (k <= -2 || k > 56) { // suffice to return exp(x)-1
            y = ONE - (e - x)
            y = withHi(y, hi(y) + (k shl 20)) // add k to y's exponent
            return y - ONE
        }
        t = ONE
        if (k < 20) {
            t = withHi(t, 0x3ff00000 - (0x200000 shr k)) // t=1-2^-k
            y = t - (e - x)
            y = withHi(y, hi(y) + (k shl 20)) // add k to y's exponent
        } else {
            t = withHi(t, (0x3ff - k) shl 20) // 2^-k
            y = x - (e + t)
            y += ONE
            y = withHi(y, hi(y) + (k shl 20)) // add k to y's exponent
        }
        return y
    }

    // ---- e_pow.c ----------------------------------------------------------------------------------

    private val bp = doubleArrayOf(1.0, 1.5)
    private val dpH = doubleArrayOf(0.0, 5.84962487220764160156e-01) // 0x3FE2B803, 0x40000000
    private val dpL = doubleArrayOf(0.0, 1.35003920212974897128e-08) // 0x3E4CFDEB, 0x43CFD006

    // poly coefs for (3/2)*(log(x)-2s-2/3*s**3
    private const val L1 = 5.99999999999994648725e-01 // 0x3FE33333, 0x33333303
    private const val L2 = 4.28571428578550184252e-01 // 0x3FDB6DB6, 0xDB6FABFF
    private const val L3 = 3.33333329818377432918e-01 // 0x3FD55555, 0x518F264D
    private const val L4 = 2.72728123808534006489e-01 // 0x3FD17460, 0xA91D4101
    private const val L5 = 2.30660745775561754067e-01 // 0x3FCD864A, 0x93C9DB65
    private const val L6 = 2.06975017800338417784e-01 // 0x3FCA7E28, 0x4A454EEF
    private const val LG2_F = 6.93147180559945286227e-01 // 0x3FE62E42, 0xFEFA39EF
    private const val LG2_H = 6.93147182464599609375e-01 // 0x3FE62E43, 0x00000000
    private const val LG2_L = -1.90465429995776804525e-09 // 0xBE205C61, 0x0CA86C39
    private const val OVT = 8.0085662595372944372e-0017 // -(1024-log2(ovfl+.5ulp))
    private const val CP = 9.61796693925975554329e-01 // 0x3FEEC709, 0xDC3A03FD =2/(3ln2)
    private const val CP_H = 9.61796700954437255859e-01 // 0x3FEEC709, 0xE0000000 =(float)cp
    private const val CP_L = -7.02846165095275826516e-09 // 0xBE3E2FE0, 0x145B01F5 =tail of cp_h
    private const val IVLN2 = 1.44269504088896338700e+00 // 0x3FF71547, 0x652B82FE =1/ln2
    private const val IVLN2_H = 1.44269502162933349609e+00 // 0x3FF71547, 0x60000000 =24b 1/ln2
    private const val IVLN2_L = 1.92596299112661746887e-08 // 0x3E54AE0B, 0xF85DDF44 =1/ln2 tail

    fun pow(x: Double, y: Double): Double {
        var z: Double
        var ax: Double
        val zH: Double
        val zL: Double
        var pH: Double
        var pL: Double
        var t1: Double
        var t2: Double
        var r: Double
        var t: Double
        var u: Double
        var v: Double
        var w: Double
        var i: Int
        var j: Int
        var k: Int
        var n: Int

        val hx = hi(x)
        val lx = lo(x)
        val hy = hi(y)
        val ly = lo(y)
        var ix = hx and 0x7fffffff
        val iy = hy and 0x7fffffff

        // y==zero: x**0 = 1
        if ((iy or ly) == 0) return ONE

        // +-NaN return x+y
        if (ix > 0x7ff00000 || ((ix == 0x7ff00000) && (lx != 0)) ||
            iy > 0x7ff00000 || ((iy == 0x7ff00000) && (ly != 0))
        ) {
            return x + y
        }

        // determine if y is an odd int when x < 0
        // yisint = 0 ... y is not an integer
        // yisint = 1 ... y is an odd int
        // yisint = 2 ... y is an even int
        var yisint = 0
        if (hx < 0) {
            if (iy >= 0x43400000) {
                yisint = 2 // even integer y
            } else if (iy >= 0x3ff00000) {
                k = (iy shr 20) - 0x3ff // exponent
                if (k > 20) {
                    j = ly ushr (52 - k)
                    if ((j shl (52 - k)) == ly) yisint = 2 - (j and 1)
                } else if (ly == 0) {
                    j = iy shr (20 - k)
                    if ((j shl (20 - k)) == iy) yisint = 2 - (j and 1)
                }
            }
        }

        // special value of y
        if (ly == 0) {
            if (iy == 0x7ff00000) { // y is +-inf
                return if (((ix - 0x3ff00000) or lx) == 0) {
                    y - y // inf**+-1 is NaN
                } else if (ix >= 0x3ff00000) { // (|x|>1)**+-inf = inf,0
                    if (hy >= 0) y else ZERO
                } else { // (|x|<1)**-,+inf = inf,0
                    if (hy < 0) -y else ZERO
                }
            }
            if (iy == 0x3ff00000) { // y is  +-1
                return if (hy < 0) ONE / x else x
            }
            if (hy == 0x40000000) return x * x // y is  2
            if (hy == 0x3fe00000) { // y is  0.5
                if (hx >= 0) return kotlin.math.sqrt(x) // x >= +0
            }
        }

        ax = kotlin.math.abs(x)
        // special value of x
        if (lx == 0) {
            if (ix == 0x7ff00000 || ix == 0 || ix == 0x3ff00000) {
                z = ax // x is +-0,+-inf,+-1
                if (hy < 0) z = ONE / z // z = (1/|x|)
                if (hx < 0) {
                    if (((ix - 0x3ff00000) or yisint) == 0) {
                        z = (z - z) / (z - z) // (-1)**non-int is NaN
                    } else if (yisint == 1) {
                        z = -z // (x<0)**odd = -(|x|**odd)
                    }
                }
                return z
            }
        }

        n = (hx shr 31) + 1

        // (x<0)**(non-int) is NaN
        if ((n or yisint) == 0) return (x - x) / (x - x)

        var s = ONE // s (sign of result -ve**odd) = -1 else = 1
        if ((n or (yisint - 1)) == 0) s = -ONE // (-ve)**(odd int)

        // |y| is huge
        if (iy > 0x41e00000) { // if |y| > 2**31
            if (iy > 0x43f00000) { // if |y| > 2**64, must o/uflow
                if (ix <= 0x3fefffff) return if (hy < 0) HUGE * HUGE else TINY * TINY
                if (ix >= 0x3ff00000) return if (hy > 0) HUGE * HUGE else TINY * TINY
            }
            // over/underflow if x is not close to one
            if (ix < 0x3fefffff) return if (hy < 0) s * HUGE * HUGE else s * TINY * TINY
            if (ix > 0x3ff00000) return if (hy > 0) s * HUGE * HUGE else s * TINY * TINY
            // now |1-x| is tiny <= 2**-20, suffice to compute
            // log(x) by x-x^2/2+x^3/3-x^4/4
            t = ax - ONE // t has 20 trailing zeros
            w = (t * t) * (0.5 - t * (0.3333333333333333333333 - t * 0.25))
            u = IVLN2_H * t // ivln2_h has 21 sig. bits
            v = t * IVLN2_L - w * IVLN2
            t1 = u + v
            t1 = withLo(t1, 0)
            t2 = v - (t1 - u)
        } else {
            n = 0
            // take care subnormal number
            if (ix < 0x00100000) {
                ax *= TWO53
                n -= 53
                ix = hi(ax)
            }
            n += (ix shr 20) - 0x3ff
            j = ix and 0x000fffff
            // determine interval
            ix = j or 0x3ff00000 // normalize ix
            if (j <= 0x3988E) {
                k = 0 // |x|<sqrt(3/2)
            } else if (j < 0xBB67A) {
                k = 1 // |x|<sqrt(3)
            } else {
                k = 0
                n += 1
                ix -= 0x00100000
            }
            ax = withHi(ax, ix)

            // compute ss = s_h+s_l = (x-1)/(x+1) or (x-1.5)/(x+1.5)
            u = ax - bp[k] // bp[0]=1.0, bp[1]=1.5
            v = ONE / (ax + bp[k])
            val ss = u * v
            var sH = ss
            sH = withLo(sH, 0)
            // t_h=ax+bp[k] High
            var tH = ZERO
            tH = withHi(tH, ((ix shr 1) or 0x20000000) + 0x00080000 + (k shl 18))
            var tL = ax - (tH - bp[k])
            val sL = v * ((u - sH * tH) - sH * tL)
            // compute log(ax)
            var s2 = ss * ss
            r = s2 * s2 * (L1 + s2 * (L2 + s2 * (L3 + s2 * (L4 + s2 * (L5 + s2 * L6)))))
            r += sL * (sH + ss)
            s2 = sH * sH
            tH = 3.0 + s2 + r
            tH = withLo(tH, 0)
            tL = r - ((tH - 3.0) - s2)
            // u+v = ss*(1+...)
            u = sH * tH
            v = sL * tH + tL * ss
            // 2/(3log2)*(ss+...)
            pH = u + v
            pH = withLo(pH, 0)
            pL = v - (pH - u)
            zH = CP_H * pH // cp_h+cp_l = 2/(3*log2)
            zL = CP_L * pH + pL * CP + dpL[k]
            // log2(ax) = (ss+..)*2/(3*log2) = n + dp_h + z_h + z_l
            t = n.toDouble()
            t1 = (((zH + zL) + dpH[k]) + t)
            t1 = withLo(t1, 0)
            t2 = zL - (((t1 - t) - dpH[k]) - zH)
        }

        // split up y into y1+y2 and compute (y1+y2)*(t1+t2)
        var y1 = y
        y1 = withLo(y1, 0)
        pL = (y - y1) * t1 + y * t2
        pH = y1 * t1
        z = pL + pH
        j = hi(z)
        i = lo(z)
        if (j >= 0x40900000) { // z >= 1024
            if (((j - 0x40900000) or i) != 0) { // if z > 1024
                return s * HUGE * HUGE // overflow
            } else {
                if (pL + OVT > z - pH) return s * HUGE * HUGE // overflow
            }
        } else if ((j and 0x7fffffff) >= 0x4090cc00) { // z <= -1075
            if (((j - 0xc090cc00.toInt()) or i) != 0) { // z < -1075
                return s * TINY * TINY // underflow
            } else {
                if (pL <= z - pH) return s * TINY * TINY // underflow
            }
        }

        // compute 2**(p_h+p_l)
        i = j and 0x7fffffff
        k = (i shr 20) - 0x3ff
        n = 0
        if (i > 0x3fe00000) { // if |z| > 0.5, set n = [z+0.5]
            n = j + (0x00100000 shr (k + 1))
            k = ((n and 0x7fffffff) shr 20) - 0x3ff // new k for n
            t = ZERO
            t = withHi(t, n and (0x000fffff shr k).inv())
            n = ((n and 0x000fffff) or 0x00100000) shr (20 - k)
            if (j < 0) n = -n
            pH -= t
        }
        t = pL + pH
        t = withLo(t, 0)
        u = t * LG2_H
        v = (pL - (t - pH)) * LG2_F + t * LG2_L
        z = u + v
        w = v - (z - u)
        t = z * z
        t1 = z - t * (P1 + t * (P2 + t * (P3 + t * (P4 + t * P5))))
        r = (z * t1) / (t1 - TWO) - (w + z * w)
        z = ONE - (r - z)
        j = hi(z)
        j += (n shl 20)
        z = if ((j shr 20) <= 0) scalbn(z, n) else withHi(z, hi(z) + (n shl 20)) // subnormal output
        return s * z
    }

    // ---- k_sin.c, k_cos.c, k_tan.c ----------------------------------------------------------------

    private const val S1 = -1.66666666666666324348e-01 // 0xBFC55555, 0x55555549
    private const val S2 = 8.33333333332248946124e-03 // 0x3F811111, 0x1110F8A6
    private const val S3 = -1.98412698298579493134e-04 // 0xBF2A01A0, 0x19C161D5
    private const val S4 = 2.75573137070700676789e-06 // 0x3EC71DE3, 0x57B1FE7D
    private const val S5 = -2.50507602534068634195e-08 // 0xBE5AE5E6, 0x8A2B9CEB
    private const val S6 = 1.58969099521155010221e-10 // 0x3DE5D93A, 0x5ACFD57C

    private fun kernelSin(x: Double, y: Double, iy: Int): Double {
        val ix = hi(x) and 0x7fffffff // high word of x
        if (ix < 0x3e400000) { // |x| < 2**-27
            if (x.toInt() == 0) return x // generate inexact
        }
        val z = x * x
        val v = z * x
        val r = S2 + z * (S3 + z * (S4 + z * (S5 + z * S6)))
        return if (iy == 0) x + v * (S1 + z * r) else x - ((z * (HALF * y - v * r) - y) - v * S1)
    }

    private const val C1 = 4.16666666666666019037e-02 // 0x3FA55555, 0x5555554C
    private const val C2 = -1.38888888888741095749e-03 // 0xBF56C16C, 0x16C15177
    private const val C3 = 2.48015872894767294178e-05 // 0x3EFA01A0, 0x19CB1590
    private const val C4 = -2.75573143513906633035e-07 // 0xBE927E4F, 0x809C52AD
    private const val C5 = 2.08757232129817482790e-09 // 0x3E21EE9E, 0xBDB4B1C4
    private const val C6 = -1.13596475577881948265e-11 // 0xBDA8FAE9, 0xBE8838D4

    private fun kernelCos(x: Double, y: Double): Double {
        val ix = hi(x) and 0x7fffffff // ix = |x|'s high word
        if (ix < 0x3e400000) { // if x < 2**27
            if (x.toInt() == 0) return ONE // generate inexact
        }
        val z = x * x
        val r = z * (C1 + z * (C2 + z * (C3 + z * (C4 + z * (C5 + z * C6)))))
        if (ix < 0x3FD33333) { // if |x| < 0.3
            return ONE - (0.5 * z - (z * r - x * y))
        }
        val qx = if (ix > 0x3fe90000) { // x > 0.78125
            0.28125
        } else {
            fromWords(ix - 0x00200000, 0) // x/4
        }
        val hz = 0.5 * z - qx
        val a = ONE - qx
        return a - (hz - (z * r - x * y))
    }

    private val T = doubleArrayOf(
        3.33333333333334091986e-01, // 3FD55555, 55555563
        1.33333333333201242699e-01, // 3FC11111, 1110FE7A
        5.39682539762260521377e-02, // 3FABA1BA, 1BB341FE
        2.18694882948595424599e-02, // 3F9664F4, 8406D637
        8.86323982359930005737e-03, // 3F8226E3, E96E8493
        3.59207910759131235356e-03, // 3F6D6D22, C9560328
        1.45620945432529025516e-03, // 3F57DBC8, FEE08315
        5.88041240820264096874e-04, // 3F4344D8, F2F26501
        2.46463134818469906812e-04, // 3F3026F7, 1A8D1068
        7.81794442939557092300e-05, // 3F147E88, A03792A6
        7.14072491382608190305e-05, // 3F12B80F, 32F0A7E9
        -1.85586374855275456654e-05, // BEF375CB, DB605373
        2.59073051863633712884e-05, // 3EFB2A70, 74BF7AD4
    )
    private const val PIO4 = 7.85398163397448278999e-01 // 3FE921FB, 54442D18
    private const val PIO4LO = 3.06161699786838301793e-17 // 3C81A626, 33145C07

    private fun kernelTan(xIn: Double, yIn: Double, iy: Int): Double {
        var x = xIn
        var y = yIn
        var z: Double
        var r: Double
        var v: Double
        var w: Double
        var s: Double
        val hx = hi(x) // high word of x
        val ix = hx and 0x7fffffff // high word of |x|
        if (ix < 0x3e300000) { // x < 2**-28
            if (x.toInt() == 0) { // generate inexact
                if (((ix or lo(x)) or (iy + 1)) == 0) {
                    return ONE / kotlin.math.abs(x)
                } else {
                    if (iy == 1) {
                        return x
                    } else { // compute -1 / (x+y) carefully
                        w = x + y
                        z = withLo(w, 0)
                        v = y - (z - x)
                        val a = -ONE / w
                        val t = withLo(a, 0)
                        s = ONE + t * z
                        return t + a * (s + t * v)
                    }
                }
            }
        }
        if (ix >= 0x3FE59428) { // |x| >= 0.6744
            if (hx < 0) {
                x = -x
                y = -y
            }
            z = PIO4 - x
            w = PIO4LO - y
            x = z + w
            y = 0.0
        }
        z = x * x
        w = z * z
        // Break x^5*(T[1]+x^2*T[2]+...) into
        // x^5(T[1]+x^4*T[3]+...+x^20*T[11]) +
        // x^5(x^2*(T[2]+x^4*T[4]+...+x^22*[T12]))
        r = T[1] + w * (T[3] + w * (T[5] + w * (T[7] + w * (T[9] + w * T[11]))))
        v = z * (T[2] + w * (T[4] + w * (T[6] + w * (T[8] + w * (T[10] + w * T[12])))))
        s = z * x
        r = y + z * (s * (r + v) + y)
        r += T[0] * s
        w = x + r
        if (ix >= 0x3FE59428) {
            v = iy.toDouble()
            return (1 - ((hx shr 30) and 2)).toDouble() * (v - 2.0 * (x - (w * w / (w + v) - r)))
        }
        if (iy == 1) return w
        // compute -1.0 / (x+r) accurately
        z = withLo(w, 0)
        v = r - (z - x) // z+v = r+x
        val a = -1.0 / w // a = -1.0/w
        val t = withLo(a, 0)
        s = 1.0 + t * z
        return t + a * (s + t * v)
    }

    // ---- e_rem_pio2.c, k_rem_pio2.c ---------------------------------------------------------------

    // Table of constants for 2/pi, 396 Hex digits (476 decimal) of 2/pi
    private val twoOverPi = intArrayOf(
        0xA2F983, 0x6E4E44, 0x1529FC, 0x2757D1, 0xF534DD, 0xC0DB62,
        0x95993C, 0x439041, 0xFE5163, 0xABDEBB, 0xC561B7, 0x246E3A,
        0x424DD2, 0xE00649, 0x2EEA09, 0xD1921C, 0xFE1DEB, 0x1CB129,
        0xA73EE8, 0x8235F5, 0x2EBB44, 0x84E99C, 0x7026B4, 0x5F7E41,
        0x3991D6, 0x398353, 0x39F49C, 0x845F8B, 0xBDF928, 0x3B1FF8,
        0x97FFDE, 0x05980F, 0xEF2F11, 0x8B5A0A, 0x6D1F6D, 0x367ECF,
        0x27CB09, 0xB74F46, 0x3F669E, 0x5FEA2D, 0x7527BA, 0xC7EBE5,
        0xF17B3D, 0x0739F7, 0x8A5292, 0xEA6BFB, 0x5FB11F, 0x8D5D08,
        0x560330, 0x46FC7B, 0x6BABF0, 0xCFBC20, 0x9AF436, 0x1DA9E3,
        0x91615E, 0xE61B08, 0x659985, 0x5F14A0, 0x68408D, 0xFFD880,
        0x4D7327, 0x310606, 0x1556CA, 0x73A8C9, 0x60E27B, 0xC08C6B,
    )

    private val npio2Hw = intArrayOf(
        0x3FF921FB, 0x400921FB, 0x4012D97C, 0x401921FB, 0x401F6A7A, 0x4022D97C,
        0x4025FDBB, 0x402921FB, 0x402C463A, 0x402F6A7A, 0x4031475C, 0x4032D97C,
        0x40346B9C, 0x4035FDBB, 0x40378FDB, 0x403921FB, 0x403AB41B, 0x403C463A,
        0x403DD85A, 0x403F6A7A, 0x40407E4C, 0x4041475C, 0x4042106C, 0x4042D97C,
        0x4043A28C, 0x40446B9C, 0x404534AC, 0x4045FDBB, 0x4046C6CB, 0x40478FDB,
        0x404858EB, 0x404921FB,
    )

    private const val INVPIO2 = 6.36619772367581382433e-01 // 0x3FE45F30, 0x6DC9C883
    private const val PIO2_1 = 1.57079632673412561417e+00 // 0x3FF921FB, 0x54400000
    private const val PIO2_1T = 6.07710050650619224932e-11 // 0x3DD0B461, 0x1A626331
    private const val PIO2_2 = 6.07710050630396597660e-11 // 0x3DD0B461, 0x1A600000
    private const val PIO2_2T = 2.02226624879595063154e-21 // 0x3BA3198A, 0x2E037073
    private const val PIO2_3 = 2.02226624871116645580e-21 // 0x3BA3198A, 0x2E000000
    private const val PIO2_3T = 8.47842766036889956997e-32 // 0x397B839A, 0x252049C1

    /** Returns x mod pi/2 in y[0] + y[1], and the low bits of the quotient. */
    private fun remPio2(x: Double, y: DoubleArray): Int {
        var z: Double
        var w: Double
        var t: Double
        var r: Double
        val fn: Double
        val n: Int
        val hx = hi(x) // high word of x
        val ix = hx and 0x7fffffff
        if (ix <= 0x3fe921fb) { // |x| ~<= pi/4 , no need for reduction
            y[0] = x
            y[1] = 0.0
            return 0
        }
        if (ix < 0x4002d97c) { // |x| < 3pi/4, special case with n=+-1
            if (hx > 0) {
                z = x - PIO2_1
                if (ix != 0x3ff921fb) { // 33+53 bit pi is good enough
                    y[0] = z - PIO2_1T
                    y[1] = (z - y[0]) - PIO2_1T
                } else { // near pi/2, use 33+33+53 bit pi
                    z -= PIO2_2
                    y[0] = z - PIO2_2T
                    y[1] = (z - y[0]) - PIO2_2T
                }
                return 1
            } else { // negative x
                z = x + PIO2_1
                if (ix != 0x3ff921fb) { // 33+53 bit pi is good enough
                    y[0] = z + PIO2_1T
                    y[1] = (z - y[0]) + PIO2_1T
                } else { // near pi/2, use 33+33+53 bit pi
                    z += PIO2_2
                    y[0] = z + PIO2_2T
                    y[1] = (z - y[0]) + PIO2_2T
                }
                return -1
            }
        }
        if (ix <= 0x413921fb) { // |x| ~<= 2^19*(pi/2), medium size
            t = kotlin.math.abs(x)
            n = (t * INVPIO2 + HALF).toInt()
            fn = n.toDouble()
            r = t - fn * PIO2_1
            w = fn * PIO2_1T // 1st round good to 85 bit
            if (n < 32 && ix != npio2Hw[n - 1]) {
                y[0] = r - w // quick check no cancellation
            } else {
                val j = ix shr 20
                y[0] = r - w
                var i = j - ((hi(y[0]) shr 20) and 0x7ff)
                if (i > 16) { // 2nd iteration needed, good to 118
                    t = r
                    w = fn * PIO2_2
                    r = t - w
                    w = fn * PIO2_2T - ((t - r) - w)
                    y[0] = r - w
                    i = j - ((hi(y[0]) shr 20) and 0x7ff)
                    if (i > 49) { // 3rd iteration need, 151 bits acc
                        t = r // will cover all possible cases
                        w = fn * PIO2_3
                        r = t - w
                        w = fn * PIO2_3T - ((t - r) - w)
                        y[0] = r - w
                    }
                }
            }
            y[1] = (r - y[0]) - w
            if (hx < 0) {
                y[0] = -y[0]
                y[1] = -y[1]
                return -n
            }
            return n
        }
        // all other (large) arguments
        if (ix >= 0x7ff00000) { // x is inf or NaN
            y[0] = x - x
            y[1] = y[0]
            return 0
        }
        // set z = scalbn(|x|,ilogb(x)-23)
        val e0 = (ix shr 20) - 1046 // e0 = ilogb(z)-23;
        z = fromWords(ix - (e0 shl 20), lo(x))
        val tx = DoubleArray(3)
        for (i in 0 until 2) {
            tx[i] = z.toInt().toDouble()
            z = (z - tx[i]) * TWO24
        }
        tx[2] = z
        var nx = 3
        while (tx[nx - 1] == ZERO) nx-- // skip zero term
        val m = kernelRemPio2(tx, y, e0, nx)
        if (hx < 0) {
            y[0] = -y[0]
            y[1] = -y[1]
            return -m
        }
        return m
    }

    private val PIo2 = doubleArrayOf(
        1.57079625129699707031e+00, // 0x3FF921FB, 0x40000000
        7.54978941586159635335e-08, // 0x3E74442D, 0x00000000
        5.39030252995776476554e-15, // 0x3CF84698, 0x80000000
        3.28200341580791294123e-22, // 0x3B78CC51, 0x60000000
        1.27065575308067607349e-29, // 0x39F01B83, 0x80000000
        1.22933308981111328932e-36, // 0x387A2520, 0x40000000
        2.73370053816464559624e-44, // 0x36E38222, 0x80000000
        2.16741683877804819444e-51, // 0x3569F31D, 0x00000000
    )

    /**
     * __kernel_rem_pio2 at the precision e_rem_pio2.c asks for (prec 2, so jk is 4), with
     * `two_over_pi` as ipio2.
     */
    private fun kernelRemPio2(x: DoubleArray, y: DoubleArray, e0: Int, nx: Int): Int {
        val ipio2 = twoOverPi
        val iq = IntArray(20)
        val f = DoubleArray(20)
        val fq = DoubleArray(20)
        val q = DoubleArray(20)
        var z: Double
        var fw: Double
        var n: Int
        var ih: Int
        var carry: Int
        var i: Int
        var j: Int
        var k: Int

        // initialize jk
        val jk = 4
        val jp = jk

        // determine jx,jv,q0, note that 3>q0
        val jx = nx - 1
        var jv = (e0 - 3) / 24
        if (jv < 0) jv = 0
        var q0 = e0 - 24 * (jv + 1)

        // set up f[0] to f[jx+jk] where f[jx+jk] = ipio2[jv+jk]
        j = jv - jx
        val m = jx + jk
        i = 0
        while (i <= m) {
            f[i] = if (j < 0) ZERO else ipio2[j].toDouble()
            i++
            j++
        }

        // compute q[0],q[1],...q[jk]
        i = 0
        while (i <= jk) {
            fw = 0.0
            j = 0
            while (j <= jx) {
                fw += x[j] * f[jx + i - j]
                j++
            }
            q[i] = fw
            i++
        }

        var jz = jk
        while (true) { // recompute:
            // distill q[] into iq[] reversingly
            i = 0
            j = jz
            z = q[jz]
            while (j > 0) {
                fw = (TWON24 * z).toInt().toDouble()
                iq[i] = (z - TWO24 * fw).toInt()
                z = q[j - 1] + fw
                i++
                j--
            }

            // compute n
            z = scalbn(z, q0) // actual value of z
            z -= 8.0 * kotlin.math.floor(z * 0.125) // trim off integer >= 8
            n = z.toInt()
            z -= n.toDouble()
            ih = 0
            if (q0 > 0) { // need iq[jz-1] to determine n
                i = iq[jz - 1] shr (24 - q0)
                n += i
                iq[jz - 1] -= i shl (24 - q0)
                ih = iq[jz - 1] shr (23 - q0)
            } else if (q0 == 0) {
                ih = iq[jz - 1] shr 23
            } else if (z >= 0.5) {
                ih = 2
            }

            if (ih > 0) { // q > 0.5
                n += 1
                carry = 0
                i = 0
                while (i < jz) { // compute 1-q
                    j = iq[i]
                    if (carry == 0) {
                        if (j != 0) {
                            carry = 1
                            iq[i] = 0x1000000 - j
                        }
                    } else {
                        iq[i] = 0xffffff - j
                    }
                    i++
                }
                if (q0 > 0) { // rare case: chance is 1 in 12
                    when (q0) {
                        1 -> iq[jz - 1] = iq[jz - 1] and 0x7fffff
                        2 -> iq[jz - 1] = iq[jz - 1] and 0x3fffff
                    }
                }
                if (ih == 2) {
                    z = ONE - z
                    if (carry != 0) z -= scalbn(ONE, q0)
                }
            }

            // check if recomputation is needed
            if (z == ZERO) {
                j = 0
                i = jz - 1
                while (i >= jk) {
                    j = j or iq[i]
                    i--
                }
                if (j == 0) { // need recomputation
                    k = 1
                    while (iq[jk - k] == 0) k++ // k = no. of terms needed

                    i = jz + 1
                    while (i <= jz + k) { // add q[jz+1] to q[jz+k]
                        f[jx + i] = ipio2[jv + i].toDouble()
                        fw = 0.0
                        j = 0
                        while (j <= jx) {
                            fw += x[j] * f[jx + i - j]
                            j++
                        }
                        q[i] = fw
                        i++
                    }
                    jz += k
                    continue
                }
            }
            break
        }

        // chop off zero terms
        if (z == 0.0) {
            jz -= 1
            q0 -= 24
            while (iq[jz] == 0) {
                jz--
                q0 -= 24
            }
        } else { // break z into 24-bit if necessary
            z = scalbn(z, -q0)
            if (z >= TWO24) {
                fw = (TWON24 * z).toInt().toDouble()
                iq[jz] = (z - TWO24 * fw).toInt()
                jz += 1
                q0 += 24
                iq[jz] = fw.toInt()
            } else {
                iq[jz] = z.toInt()
            }
        }

        // convert integer "bit" chunk to floating-point value
        fw = scalbn(ONE, q0)
        i = jz
        while (i >= 0) {
            q[i] = fw * iq[i].toDouble()
            fw *= TWON24
            i--
        }

        // compute PIo2[0,...,jp]*q[jz,...,0]
        i = jz
        while (i >= 0) {
            fw = 0.0
            k = 0
            while (k <= jp && k <= jz - i) {
                fw += PIo2[k] * q[i + k]
                k++
            }
            fq[jz - i] = fw
            i--
        }

        // compress fq[] into y[]
        fw = 0.0
        i = jz
        while (i >= 0) {
            fw += fq[i]
            i--
        }
        y[0] = if (ih == 0) fw else -fw
        fw = fq[0] - fw
        i = 1
        while (i <= jz) {
            fw += fq[i]
            i++
        }
        y[1] = if (ih == 0) fw else -fw
        return n and 7
    }

    // ---- s_sin.c, s_cos.c, s_tan.c ----------------------------------------------------------------

    fun sin(x: Double): Double {
        val ix = hi(x) and 0x7fffffff
        if (ix <= 0x3fe921fb) return kernelSin(x, 0.0, 0)
        if (ix >= 0x7ff00000) return x - x // sin(Inf or NaN) is NaN
        val y = DoubleArray(2)
        val n = remPio2(x, y)
        return when (n and 3) {
            0 -> kernelSin(y[0], y[1], 1)
            1 -> kernelCos(y[0], y[1])
            2 -> -kernelSin(y[0], y[1], 1)
            else -> -kernelCos(y[0], y[1])
        }
    }

    fun cos(x: Double): Double {
        val ix = hi(x) and 0x7fffffff
        if (ix <= 0x3fe921fb) return kernelCos(x, 0.0)
        if (ix >= 0x7ff00000) return x - x // cos(Inf or NaN) is NaN
        val y = DoubleArray(2)
        val n = remPio2(x, y)
        return when (n and 3) {
            0 -> kernelCos(y[0], y[1])
            1 -> -kernelSin(y[0], y[1], 1)
            2 -> -kernelCos(y[0], y[1])
            else -> kernelSin(y[0], y[1], 1)
        }
    }

    fun tan(x: Double): Double {
        val ix = hi(x) and 0x7fffffff
        if (ix <= 0x3fe921fb) return kernelTan(x, 0.0, 1)
        if (ix >= 0x7ff00000) return x - x // NaN
        val y = DoubleArray(2)
        val n = remPio2(x, y)
        return kernelTan(y[0], y[1], 1 - ((n and 1) shl 1)) // 1 -- n even, -1 -- n odd
    }

    // ---- e_asin.c, e_acos.c -----------------------------------------------------------------------

    private const val PIO2_HI = 1.57079632679489655800e+00 // 0x3FF921FB, 0x54442D18
    private const val PIO2_LO = 6.12323399573676603587e-17 // 0x3C91A626, 0x33145C07
    private const val PIO4_HI = 7.85398163397448278999e-01 // 0x3FE921FB, 0x54442D18
    private const val PI = 3.14159265358979311600e+00 // 0x400921FB, 0x54442D18

    // coefficient for R(x^2)
    private const val PS0 = 1.66666666666666657415e-01 // 0x3FC55555, 0x55555555
    private const val PS1 = -3.25565818622400915405e-01 // 0xBFD4D612, 0x03EB6F7D
    private const val PS2 = 2.01212532134862925881e-01 // 0x3FC9C155, 0x0E884455
    private const val PS3 = -4.00555345006794114027e-02 // 0xBFA48228, 0xB5688F3B
    private const val PS4 = 7.91534994289814532176e-04 // 0x3F49EFE0, 0x7501B288
    private const val PS5 = 3.47933107596021167570e-05 // 0x3F023DE1, 0x0DFDF709
    private const val QS1 = -2.40339491173441421878e+00 // 0xC0033A27, 0x1C8A2D4B
    private const val QS2 = 2.02094576023350569471e+00 // 0x40002AE5, 0x9C598AC8
    private const val QS3 = -6.88283971605453293030e-01 // 0xBFE6066C, 0x1B8D0159
    private const val QS4 = 7.70381505559019352791e-02 // 0x3FB3B8C5, 0xB12E9282

    fun asin(x: Double): Double {
        var t: Double
        var w: Double
        var p: Double
        var q: Double
        val hx = hi(x)
        val ix = hx and 0x7fffffff
        if (ix >= 0x3ff00000) { // |x|>= 1
            if (((ix - 0x3ff00000) or lo(x)) == 0) {
                return x * PIO2_HI + x * PIO2_LO // asin(1)=+-pi/2 with inexact
            }
            return (x - x) / (x - x) // asin(|x|>1) is NaN
        } else if (ix < 0x3fe00000) { // |x|<0.5
            if (ix < 0x3e400000) { // if |x| < 2**-27
                if (HUGE + x > ONE) return x // return x with inexact if x!=0
            }
            t = x * x
            p = t * (PS0 + t * (PS1 + t * (PS2 + t * (PS3 + t * (PS4 + t * PS5)))))
            q = ONE + t * (QS1 + t * (QS2 + t * (QS3 + t * QS4)))
            w = p / q
            return x + x * w
        }
        // 1> |x|>= 0.5
        w = ONE - kotlin.math.abs(x)
        t = w * 0.5
        p = t * (PS0 + t * (PS1 + t * (PS2 + t * (PS3 + t * (PS4 + t * PS5)))))
        q = ONE + t * (QS1 + t * (QS2 + t * (QS3 + t * QS4)))
        val s = kotlin.math.sqrt(t)
        if (ix >= 0x3FEF3333) { // if |x| > 0.975
            w = p / q
            t = PIO2_HI - (2.0 * (s + s * w) - PIO2_LO)
        } else {
            w = withLo(s, 0)
            val c = (t - w * w) / (s + w)
            val r = p / q
            p = 2.0 * s * r - (PIO2_LO - 2.0 * c)
            q = PIO4_HI - 2.0 * w
            t = PIO4_HI - (p - q)
        }
        return if (hx > 0) t else -t
    }

    fun acos(x: Double): Double {
        val z: Double
        val p: Double
        val q: Double
        val r: Double
        val w: Double
        val s: Double
        val hx = hi(x)
        val ix = hx and 0x7fffffff
        if (ix >= 0x3ff00000) { // |x| >= 1
            if (((ix - 0x3ff00000) or lo(x)) == 0) { // |x|==1
                return if (hx > 0) 0.0 else PI + 2.0 * PIO2_LO // acos(1) = 0, acos(-1)= pi
            }
            return (x - x) / (x - x) // acos(|x|>1) is NaN
        }
        if (ix < 0x3fe00000) { // |x| < 0.5
            if (ix <= 0x3c600000) return PIO2_HI + PIO2_LO // if|x|<2**-57
            z = x * x
            p = z * (PS0 + z * (PS1 + z * (PS2 + z * (PS3 + z * (PS4 + z * PS5)))))
            q = ONE + z * (QS1 + z * (QS2 + z * (QS3 + z * QS4)))
            r = p / q
            return PIO2_HI - (x - (PIO2_LO - x * r))
        } else if (hx < 0) { // x < -0.5
            z = (ONE + x) * 0.5
            p = z * (PS0 + z * (PS1 + z * (PS2 + z * (PS3 + z * (PS4 + z * PS5)))))
            q = ONE + z * (QS1 + z * (QS2 + z * (QS3 + z * QS4)))
            s = kotlin.math.sqrt(z)
            r = p / q
            w = r * s - PIO2_LO
            return PI - 2.0 * (s + w)
        } else { // x > 0.5
            z = (ONE - x) * 0.5
            s = kotlin.math.sqrt(z)
            val df = withLo(s, 0)
            val c = (z - df * df) / (s + df)
            p = z * (PS0 + z * (PS1 + z * (PS2 + z * (PS3 + z * (PS4 + z * PS5)))))
            q = ONE + z * (QS1 + z * (QS2 + z * (QS3 + z * QS4)))
            r = p / q
            w = r * s + c
            return 2.0 * (df + w)
        }
    }

    // ---- s_atan.c, e_atan2.c ----------------------------------------------------------------------

    private val atanhi = doubleArrayOf(
        4.63647609000806093515e-01, // atan(0.5)hi 0x3FDDAC67, 0x0561BB4F
        7.85398163397448278999e-01, // atan(1.0)hi 0x3FE921FB, 0x54442D18
        9.82793723247329054082e-01, // atan(1.5)hi 0x3FEF730B, 0xD281F69B
        1.57079632679489655800e+00, // atan(inf)hi 0x3FF921FB, 0x54442D18
    )

    private val atanlo = doubleArrayOf(
        2.26987774529616870924e-17, // atan(0.5)lo 0x3C7A2B7F, 0x222F65E2
        3.06161699786838301793e-17, // atan(1.0)lo 0x3C81A626, 0x33145C07
        1.39033110312309984516e-17, // atan(1.5)lo 0x3C700788, 0x7AF0CBBD
        6.12323399573676603587e-17, // atan(inf)lo 0x3C91A626, 0x33145C07
    )

    private val aT = doubleArrayOf(
        3.33333333333329318027e-01, // 0x3FD55555, 0x5555550D
        -1.99999999998764832476e-01, // 0xBFC99999, 0x9998EBC4
        1.42857142725034663711e-01, // 0x3FC24924, 0x920083FF
        -1.11111104054623557880e-01, // 0xBFBC71C6, 0xFE231671
        9.09088713343650656196e-02, // 0x3FB745CD, 0xC54C206E
        -7.69187620504482999495e-02, // 0xBFB3B0F2, 0xAF749A6D
        6.66107313738753120669e-02, // 0x3FB10D66, 0xA0D03D51
        -5.83357013379057348645e-02, // 0xBFADDE2D, 0x52DEFD9A
        4.97687799461593236017e-02, // 0x3FA97B4B, 0x24760DEB
        -3.65315727442169155270e-02, // 0xBFA2B444, 0x2C6A6C2F
        1.62858201153657823623e-02, // 0x3F90AD3A, 0xE322DA11
    )

    fun atan(xIn: Double): Double {
        var x = xIn
        val id: Int
        val hx = hi(x)
        val ix = hx and 0x7fffffff
        if (ix >= 0x44100000) { // if |x| >= 2^66
            if (ix > 0x7ff00000 || (ix == 0x7ff00000 && lo(x) != 0)) return x + x // NaN
            return if (hx > 0) atanhi[3] + atanlo[3] else -atanhi[3] - atanlo[3]
        }
        if (ix < 0x3fdc0000) { // |x| < 0.4375
            if (ix < 0x3e200000) { // |x| < 2^-29
                if (HUGE + x > ONE) return x // raise inexact
            }
            id = -1
        } else {
            x = kotlin.math.abs(x)
            if (ix < 0x3ff30000) { // |x| < 1.1875
                if (ix < 0x3fe60000) { // 7/16 <=|x|<11/16
                    id = 0
                    x = (2.0 * x - ONE) / (2.0 + x)
                } else { // 11/16<=|x|< 19/16
                    id = 1
                    x = (x - ONE) / (x + ONE)
                }
            } else {
                if (ix < 0x40038000) { // |x| < 2.4375
                    id = 2
                    x = (x - 1.5) / (ONE + 1.5 * x)
                } else { // 2.4375 <= |x| < 2^66
                    id = 3
                    x = -1.0 / x
                }
            }
        }
        // end of argument reduction
        val z = x * x
        val w = z * z
        // break sum from i=0 to 10 aT[i]z**(i+1) into odd and even poly
        val s1 = z * (aT[0] + w * (aT[2] + w * (aT[4] + w * (aT[6] + w * (aT[8] + w * aT[10])))))
        val s2 = w * (aT[1] + w * (aT[3] + w * (aT[5] + w * (aT[7] + w * aT[9]))))
        if (id < 0) return x - x * (s1 + s2)
        val zz = atanhi[id] - ((x * (s1 + s2) - atanlo[id]) - x)
        return if (hx < 0) -zz else zz
    }

    private const val PI_O_4 = 7.8539816339744827900E-01 // 0x3FE921FB, 0x54442D18
    private const val PI_O_2 = 1.5707963267948965580E+00 // 0x3FF921FB, 0x54442D18
    private const val PI_LO = 1.2246467991473531772E-16 // 0x3CA1A626, 0x33145C07

    fun atan2(y: Double, x: Double): Double {
        var z: Double
        val hx = hi(x)
        val ix = hx and 0x7fffffff
        val lx = lo(x)
        val hy = hi(y)
        val iy = hy and 0x7fffffff
        val ly = lo(y)
        if ((ix or ((lx or -lx) ushr 31)) > 0x7ff00000 || (iy or ((ly or -ly) ushr 31)) > 0x7ff00000) {
            return x + y // x or y is NaN
        }
        if (((hx - 0x3ff00000) or lx) == 0) return atan(y) // x=1.0
        val m = ((hy shr 31) and 1) or ((hx shr 30) and 2) // 2*sign(x)+sign(y)

        // when y = 0
        if ((iy or ly) == 0) {
            when (m) {
                0, 1 -> return y // atan(+-0,+anything)=+-0
                2 -> return PI + TINY // atan(+0,-anything) = pi
                3 -> return -PI - TINY // atan(-0,-anything) =-pi
            }
        }
        // when x = 0
        if ((ix or lx) == 0) return if (hy < 0) -PI_O_2 - TINY else PI_O_2 + TINY

        // when x is INF
        if (ix == 0x7ff00000) {
            if (iy == 0x7ff00000) {
                when (m) {
                    0 -> return PI_O_4 + TINY // atan(+INF,+INF)
                    1 -> return -PI_O_4 - TINY // atan(-INF,+INF)
                    2 -> return 3.0 * PI_O_4 + TINY // atan(+INF,-INF)
                    3 -> return -3.0 * PI_O_4 - TINY // atan(-INF,-INF)
                }
            } else {
                when (m) {
                    0 -> return ZERO // atan(+...,+INF)
                    1 -> return -ZERO // atan(-...,+INF)
                    2 -> return PI + TINY // atan(+...,-INF)
                    3 -> return -PI - TINY // atan(-...,-INF)
                }
            }
        }
        // when y is INF
        if (iy == 0x7ff00000) return if (hy < 0) -PI_O_2 - TINY else PI_O_2 + TINY

        // compute y/x
        val k = (iy - ix) shr 20
        var m2 = m
        z = if (k > 60) {
            // |y/x| > 2**60: the answer is +-pi/2 whatever the sign of x. fdlibm 5.3 took the
            // x < 0 branches below and missed by one ulp; FreeBSD and V8 fix it like this.
            m2 = m2 and 1
            PI_O_2 + 0.5 * PI_LO
        } else if (hx < 0 && k < -60) {
            0.0 // |y|/x < -2**60
        } else {
            atan(kotlin.math.abs(y / x)) // safe to do y/x
        }
        return when (m2) {
            0 -> z // atan(+,+)
            1 -> withHi(z, hi(z) xor 0x80000000.toInt()) // atan(-,+)
            2 -> PI - (z - PI_LO) // atan(+,-)
            else -> (z - PI_LO) - PI // atan(-,-)
        }
    }

    // ---- e_sinh.c, e_cosh.c, s_tanh.c -------------------------------------------------------------

    private const val SHUGE = 1.0e307

    fun sinh(x: Double): Double {
        val t: Double
        val w: Double
        // High word of |x|.
        val jx = hi(x)
        val ix = jx and 0x7fffffff

        // x is INF or NaN
        if (ix >= 0x7ff00000) return x + x

        var h = 0.5
        if (jx < 0) h = -h
        // |x| in [0,22], return sign(x)*0.5*(E+E/(E+1)))
        if (ix < 0x40360000) { // |x|<22
            if (ix < 0x3e300000) { // |x|<2**-28
                if (SHUGE + x > ONE) return x // sinh(tiny) = tiny with inexact
            }
            t = expm1(kotlin.math.abs(x))
            if (ix < 0x3ff00000) return h * (2.0 * t - t * t / (t + ONE))
            return h * (t + t / (t + ONE))
        }

        // |x| in [22, log(maxdouble)] return 0.5*exp(|x|)
        if (ix < 0x40862E42) return h * exp(kotlin.math.abs(x))

        // |x| in [log(maxdouble), overflowthresold]
        val lx = lo(x)
        if (ix < 0x408633CE || (ix == 0x408633ce && lx.toUInt() <= 0x8fb9f87du)) {
            w = exp(0.5 * kotlin.math.abs(x))
            return h * w * w
        }

        // |x| > overflowthresold, sinh(x) overflow
        return x * SHUGE
    }

    fun cosh(x: Double): Double {
        val t: Double
        val w: Double
        // High word of |x|.
        val ix = hi(x) and 0x7fffffff

        // x is INF or NaN
        if (ix >= 0x7ff00000) return x * x

        // |x| in [0,0.5*ln2], return 1+expm1(|x|)^2/(2*exp(|x|))
        if (ix < 0x3fd62e43) {
            t = expm1(kotlin.math.abs(x))
            w = ONE + t
            if (ix < 0x3c800000) return w // cosh(tiny) = 1
            return ONE + (t * t) / (w + w)
        }

        // |x| in [0.5*ln2,22], return (exp(|x|)+1/exp(|x|)/2;
        if (ix < 0x40360000) {
            t = exp(kotlin.math.abs(x))
            return HALF * t + HALF / t
        }

        // |x| in [22, log(maxdouble)] return half*exp(|x|)
        if (ix < 0x40862E42) return HALF * exp(kotlin.math.abs(x))

        // |x| in [log(maxdouble), overflowthresold]
        val lx = lo(x)
        if (ix < 0x408633CE || (ix == 0x408633ce && lx.toUInt() <= 0x8fb9f87du)) {
            w = exp(HALF * kotlin.math.abs(x))
            return HALF * w * w
        }

        // |x| > overflowthresold, cosh(x) overflow
        return HUGE * HUGE
    }

    fun tanh(x: Double): Double {
        val t: Double
        val z: Double
        // High word of |x|.
        val jx = hi(x)
        val ix = jx and 0x7fffffff

        // x is INF or NaN
        if (ix >= 0x7ff00000) {
            return if (jx >= 0) ONE / x + ONE else ONE / x - ONE // tanh(+-inf)=+-1, tanh(NaN) = NaN
        }

        // |x| < 22
        if (ix < 0x40360000) { // |x|<22
            // |x|<2**-28, tanh(tiny) = tiny. fdlibm 5.3 stopped at 2**-55 and went through
            // expm1 below that, a ulp off; FreeBSD and V8 return x.
            if (ix < 0x3e300000) {
                if (HUGE + x > ONE) return x
            }
            if (ix >= 0x3ff00000) { // |x|>=1
                t = expm1(TWO * kotlin.math.abs(x))
                z = ONE - TWO / (t + TWO)
            } else {
                t = expm1(-TWO * kotlin.math.abs(x))
                z = -t / (t + TWO)
            }
        } else { // |x| > 22, return +-1
            z = ONE - TINY // raised inexact flag
        }
        return if (jx >= 0) z else -z
    }

    // ---- s_cbrt.c, as FreeBSD rewrote it and V8 ships it -------------------------------------------

    private const val B1 = 715094163 // B1 = (1023-1023/3-0.03306235651)*2**20
    private const val B2 = 696219795 // B2 = (1023-1023/3-54/3-0.03306235651)*2**20

    // |1/cbrt(x) - p(x)| < 2**-23.5 (~[-7.93e-8, 7.929e-8]).
    private const val CP0 = 1.87595182427177009643 // 0x3ffe03e6, 0x0f61e692
    private const val CP1 = -1.88497979543377169875 // 0xbffe28e0, 0x92f02420
    private const val CP2 = 1.621429720105354466140 // 0x3ff9f160, 0x4a49d6c2
    private const val CP3 = -0.758397934778766047437 // 0xbfe844cb, 0xbee751d9
    private const val CP4 = 0.145996192886612446982 // 0x3fc2b000, 0xd4e4edd7

    fun cbrt(x: Double): Double {
        var r: Double
        val s: Double
        var t: Double
        val w: Double
        var hx = hi(x)
        val low = lo(x)
        val sign = hx and 0x80000000.toInt() // sign= sign(x)
        hx = hx xor sign
        if (hx >= 0x7ff00000) return x + x // cbrt(NaN,INF) is itself

        // Rough cbrt to 5 bits
        if (hx < 0x00100000) { // zero or subnormal?
            if ((hx or low) == 0) return x // cbrt(0) is itself
            t = fromWords(0x43500000, 0) // set t= 2**54
            t *= x
            val high = hi(t)
            t = fromWords(sign or ((high and 0x7fffffff) / 3 + B2), 0)
        } else {
            t = fromWords(sign or (hx / 3 + B1), 0)
        }

        // New cbrt to 23 bits: cbrt(x) = t*cbrt(x/t**3) ~= t*P(t**3/x)
        r = (t * t) * (t / x)
        t *= ((CP0 + r * (CP1 + r * CP2)) + ((r * r) * r) * (CP3 + r * CP4))

        // Round t away from zero to 23 bits, so it is larger in magnitude than cbrt(x).
        t = Double.fromBits((t.toRawBits() + 0x80000000L) and -0x40000000L)

        // one step Newton iteration to 53 bits with error < 0.667 ulps
        s = t * t // t*t is exact
        r = x / s // error <= 0.5 ulps; |r| < |t|
        w = t + t // t+t is exact
        r = (r - t) / (w + r) // r-t is exact; w+r ~= 3*t
        return t + t * r // error <= 0.5 + 0.5/3 + epsilon
    }

    // ---- e_acosh.c, s_asinh.c, e_atanh.c ----------------------------------------------------------

    private const val LN2 = 6.93147180559945286227e-01 // 0x3FE62E42, 0xFEFA39EF

    fun acosh(x: Double): Double {
        val t: Double
        val hx = hi(x)
        return if (hx < 0x3ff00000) { // x < 1
            (x - x) / (x - x)
        } else if (hx >= 0x41b00000) { // x > 2**28
            if (hx >= 0x7ff00000) x + x else log(x) + LN2 // x is inf or NaN; acosh(huge)=log(2x)
        } else if (((hx - 0x3ff00000) or lo(x)) == 0) {
            0.0 // acosh(1) = 0
        } else if (hx > 0x40000000) { // 2**28 > x > 2
            t = x * x
            log(2.0 * x - ONE / (x + kotlin.math.sqrt(t - ONE)))
        } else { // 1<x<2
            t = x - ONE
            log1p(t + kotlin.math.sqrt(2.0 * t + t * t))
        }
    }

    fun asinh(x: Double): Double {
        val t: Double
        val w: Double
        val hx = hi(x)
        val ix = hx and 0x7fffffff
        if (ix >= 0x7ff00000) return x + x // x is inf or NaN
        if (ix < 0x3e300000) { // |x|<2**-28
            if (HUGE + x > ONE) return x // return x inexact except 0
        }
        if (ix > 0x41b00000) { // |x| > 2**28
            w = log(kotlin.math.abs(x)) + LN2
        } else if (ix > 0x40000000) { // 2**28 > |x| > 2.0
            t = kotlin.math.abs(x)
            w = log(2.0 * t + ONE / (kotlin.math.sqrt(x * x + ONE) + t))
        } else { // 2.0 > |x| > 2**-28
            t = x * x
            w = log1p(kotlin.math.abs(x) + t / (ONE + kotlin.math.sqrt(ONE + t)))
        }
        return if (hx > 0) w else -w
    }

    fun atanh(xIn: Double): Double {
        var x = xIn
        var t: Double
        val hx = hi(x)
        val lx = lo(x)
        val ix = hx and 0x7fffffff
        if ((ix or ((lx or -lx) ushr 31)) > 0x3ff00000) return (x - x) / (x - x) // |x|>1
        if (ix == 0x3ff00000) return x / ZERO
        if (ix < 0x3e300000 && (HUGE + x) > ZERO) return x // x<2**-28
        x = withHi(x, ix) // x <- |x|
        if (ix < 0x3fe00000) { // x < 0.5
            t = x + x
            t = 0.5 * log1p(t + t * x / (ONE - x))
        } else {
            t = 0.5 * log1p((x + x) / (ONE - x))
        }
        return if (hx >= 0) t else -t
    }

    // ---- e_log2.c, from FreeBSD as V8 ships it ----------------------------------------------------

    private const val IVLN2HI = 1.44269504072144627571e+00 // 0x3ff71547, 0x65200000
    private const val IVLN2LO = 1.67517131648865118353e-10 // 0x3de705fc, 0x2eefa200

    /** k_log.h: log(1+f) - f + f*f/2 for f in the reduced range. */
    private fun kLog1p(f: Double): Double {
        val s = f / (2.0 + f)
        val z = s * s
        val w = z * z
        val t1 = w * (LG2 + w * (LG4 + w * LG6))
        val t2 = z * (LG1 + w * (LG3 + w * (LG5 + w * LG7)))
        val r = t2 + t1
        val hfsq = 0.5 * f * f
        return s * (hfsq + r)
    }

    fun log2(xIn: Double): Double {
        var x = xIn
        var hx = hi(x)
        val lx = lo(x)

        var k = 0
        if (hx < 0x00100000) { // x < 2**-1022
            if (((hx and 0x7fffffff) or lx) == 0) return -TWO54 / ZERO // log(+-0)=-inf
            if (hx < 0) return (x - x) / ZERO // log(-#) = NaN
            k -= 54
            x *= TWO54 // subnormal number, scale up x
            hx = hi(x)
        }
        if (hx >= 0x7ff00000) return x + x
        if (hx == 0x3ff00000 && lx == 0) return ZERO // log(1) = +0
        k += (hx shr 20) - 1023
        hx = hx and 0x000fffff
        val i = (hx + 0x95f64) and 0x100000
        x = withHi(x, hx or (i xor 0x3ff00000)) // normalize x or x/2
        k += i shr 20
        val y = k.toDouble()
        val f = x - 1.0
        val hfsq = 0.5 * f * f
        val r = kLog1p(f)

        // f-hfsq must (for args near 1) be evaluated in extra precision to avoid a large
        // cancellation when x is near sqrt(2) or 1/sqrt(2); y must be added in extra precision
        // for the same arguments. Both use Dekker-style splitting.
        var hiPart = f - hfsq
        hiPart = withLo(hiPart, 0)
        val loPart = (f - hiPart) - hfsq + r
        var valHi = hiPart * IVLN2HI
        var valLo = (loPart + hiPart) * IVLN2LO + loPart * IVLN2HI

        // spadd(val_hi, val_lo, y)
        val w = y + valHi
        valLo += (y - w) + valHi
        valHi = w

        return valLo + valHi
    }
}
