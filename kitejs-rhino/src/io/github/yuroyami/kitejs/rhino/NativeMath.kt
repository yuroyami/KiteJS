/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import io.github.yuroyami.kitejs.rhino.typedarrays.Float16

import kotlin.math.E
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The JavaScript `Math` object. The transcendental functions come from [FdLibm] rather than
 * `kotlin.math`, so they give the same digits on every target (D-73).
 */
internal class NativeMath private constructor() : ScriptableObject() {

    override val className: String
        get() = "Math"

    public companion object {
        private const val MATH_TAG = "Math"
        private const val LOG2E = 1.4426950408889634
        private const val Double32 = 32.0

        internal fun init(cx: Context, scope: Scriptable, sealed: Boolean): Any {
            val math = NativeMath()
            math.prototype = getObjectPrototype(scope)
            math.parentScope = scope
            math.defineProperty("toSource", "Math", DONTENUM or READONLY or PERMANENT)
            math.defineBuiltinProperty(scope, "abs", 1, SerializableCallable { cx, s, t, a -> abs(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "acos", 1, SerializableCallable { cx, s, t, a -> acos(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "acosh", 1, SerializableCallable { cx, s, t, a -> acosh(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "asin", 1, SerializableCallable { cx, s, t, a -> asin(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "asinh", 1, SerializableCallable { cx, s, t, a -> asinh(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "atan", 1, SerializableCallable { cx, s, t, a -> atan(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "atanh", 1, SerializableCallable { cx, s, t, a -> atanh(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "atan2", 2, SerializableCallable { cx, s, t, a -> atan2(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "cbrt", 1, SerializableCallable { cx, s, t, a -> cbrt(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "ceil", 1, SerializableCallable { cx, s, t, a -> ceil(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "clz32", 1, SerializableCallable { cx, s, t, a -> clz32(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "cos", 1, SerializableCallable { cx, s, t, a -> cos(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "cosh", 1, SerializableCallable { cx, s, t, a -> cosh(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "exp", 1, SerializableCallable { cx, s, t, a -> exp(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "expm1", 1, SerializableCallable { cx, s, t, a -> expm1(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "f16round", 1, SerializableCallable { cx, s, t, a -> f16round(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "floor", 1, SerializableCallable { cx, s, t, a -> floor(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "fround", 1, SerializableCallable { cx, s, t, a -> fround(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "hypot", 2, SerializableCallable { cx, s, t, a -> hypot(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "imul", 2, SerializableCallable { cx, s, t, a -> imul(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "log", 1, SerializableCallable { cx, s, t, a -> log(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "log1p", 1, SerializableCallable { cx, s, t, a -> log1p(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "log10", 1, SerializableCallable { cx, s, t, a -> log10(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "log2", 1, SerializableCallable { cx, s, t, a -> log2(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "max", 2, SerializableCallable { cx, s, t, a -> max(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "min", 2, SerializableCallable { cx, s, t, a -> min(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "pow", 2, SerializableCallable { cx, s, t, a -> pow(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "random", 0, SerializableCallable { cx, s, t, a -> random(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "round", 1, SerializableCallable { cx, s, t, a -> round(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "sign", 1, SerializableCallable { cx, s, t, a -> sign(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "sin", 1, SerializableCallable { cx, s, t, a -> sin(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "sinh", 1, SerializableCallable { cx, s, t, a -> sinh(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "sqrt", 1, SerializableCallable { cx, s, t, a -> sqrt(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "tan", 1, SerializableCallable { cx, s, t, a -> tan(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "tanh", 1, SerializableCallable { cx, s, t, a -> tanh(cx, s, t, a) })
            math.defineBuiltinProperty(scope, "trunc", 1, SerializableCallable { cx, s, t, a -> trunc(cx, s, t, a) })
            math.defineProperty("E", E, DONTENUM or READONLY or PERMANENT)
            math.defineProperty("PI", PI, DONTENUM or READONLY or PERMANENT)
            math.defineProperty("LN10", 2.302585092994046, DONTENUM or READONLY or PERMANENT)
            math.defineProperty("LN2", 0.6931471805599453, DONTENUM or READONLY or PERMANENT)
            math.defineProperty("LOG2E", LOG2E, DONTENUM or READONLY or PERMANENT)
            math.defineProperty("LOG10E", 0.4342944819032518, DONTENUM or READONLY or PERMANENT)
            math.defineProperty("SQRT1_2", 0.7071067811865476, DONTENUM or READONLY or PERMANENT)
            math.defineProperty("SQRT2", 1.4142135623730951, DONTENUM or READONLY or PERMANENT)
            math.defineProperty(SymbolKey.TO_STRING_TAG, MATH_TAG, DONTENUM or READONLY)
            TopLevel.cacheMathFunctions(scope, math)
            if (sealed) math.sealObject()
            return math
        }

        private fun abs(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            var x = ScriptRuntime.toNumber(args, 0)
            x = if (x == 0.0) 0.0 else if (x < 0.0) -x else x
            return ScriptRuntime.wrapNumber(x)
        }

        private fun acos(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            var x = ScriptRuntime.toNumber(args, 0)
            x = if (!x.isNaN() && -1.0 <= x && x <= 1.0) FdLibm.acos(x) else Double.NaN
            return ScriptRuntime.wrapNumber(x)
        }

        private fun acosh(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            ScriptRuntime.wrapNumber(FdLibm.acosh(ScriptRuntime.toNumber(args, 0)))

        private fun asin(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            var x = ScriptRuntime.toNumber(args, 0)
            x = if (!x.isNaN() && -1.0 <= x && x <= 1.0) FdLibm.asin(x) else Double.NaN
            return ScriptRuntime.wrapNumber(x)
        }

        private fun asinh(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            ScriptRuntime.wrapNumber(FdLibm.asinh(ScriptRuntime.toNumber(args, 0)))

        private fun atan(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            ScriptRuntime.wrapNumber(FdLibm.atan(ScriptRuntime.toNumber(args, 0)))

        private fun atanh(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            ScriptRuntime.wrapNumber(FdLibm.atanh(ScriptRuntime.toNumber(args, 0)))

        private fun atan2(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val x = ScriptRuntime.toNumber(args, 0)
            return ScriptRuntime.wrapNumber(FdLibm.atan2(x, ScriptRuntime.toNumber(args, 1)))
        }

        private fun cbrt(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            ScriptRuntime.wrapNumber(FdLibm.cbrt(ScriptRuntime.toNumber(args, 0)))

        private fun ceil(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            ScriptRuntime.wrapNumber(ceil(ScriptRuntime.toNumber(args, 0)))

        private fun clz32(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val x = ScriptRuntime.toNumber(args, 0)
            if (x == 0.0 || x.isNaN() || x.isInfinite()) return Double32
            var n = ScriptRuntime.toUint32(x)
            if (n == 0L) return Double32
            var place = 0
            if (n and 0xFFFF0000L != 0L) {
                place += 16
                n = n ushr 16
            }
            if (n and 0xFF00L != 0L) {
                place += 8
                n = n ushr 8
            }
            if (n and 0xF0L != 0L) {
                place += 4
                n = n ushr 4
            }
            if (n and 0b1100L != 0L) {
                place += 2
                n = n ushr 2
            }
            if (n and 0b10L != 0L) {
                place += 1
                n = n ushr 1
            }
            if (n and 0b1L != 0L) place += 1
            return (32 - place).toDouble()
        }

        private fun cos(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val x = ScriptRuntime.toNumber(args, 0)
            return ScriptRuntime.wrapNumber(if (x.isInfinite()) Double.NaN else FdLibm.cos(x))
        }

        private fun cosh(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            ScriptRuntime.wrapNumber(FdLibm.cosh(ScriptRuntime.toNumber(args, 0)))

        private fun exp(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            var x = ScriptRuntime.toNumber(args, 0)
            x = if (x == Double.POSITIVE_INFINITY) x else if (x == Double.NEGATIVE_INFINITY) 0.0 else FdLibm.exp(x)
            return ScriptRuntime.wrapNumber(x)
        }

        private fun expm1(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            ScriptRuntime.wrapNumber(FdLibm.expm1(ScriptRuntime.toNumber(args, 0)))

        private fun floor(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            ScriptRuntime.wrapNumber(floor(ScriptRuntime.toNumber(args, 0)))

        private fun f16round(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            ScriptRuntime.wrapNumber(Float16.nearest(ScriptRuntime.toNumber(args, 0)))

        private fun fround(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val x = ScriptRuntime.toNumber(args, 0)
            return ScriptRuntime.wrapNumber(froundToDouble(x))
        }

        /**
         * The nearest 32-bit float, as a double.
         *
         * `Double.toFloat()` cannot be used for this: on Kotlin/JS it does nothing at all, because
         * JavaScript has only doubles, so `Math.fround` would answer its own argument (D-59). The
         * rounding is done here so that every target gives the same answer.
         */
        internal fun froundToDouble(x: Double): Double {
            if (x.isNaN() || x.isInfinite() || x == 0.0) return x

            val bits = x.toRawBits()
            val negative = bits < 0
            val biased = ((bits ushr 52) and 0x7FFL).toInt()
            val fraction = bits and 0x000FFFFFFFFFFFFFL

            // Every double subnormal sits far below the smallest float, so it becomes a signed zero.
            if (biased == 0) return if (negative) -0.0 else 0.0

            val exponent = biased - 1023
            if (exponent > 127) return if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY

            // A float keeps 23 fraction bits against a double's 52, and fewer still once the
            // answer is subnormal as a float, which starts below 2^-126.
            val drop = if (exponent >= -126) 29 else 29 + (-126 - exponent)
            if (drop >= 64) return if (negative) -0.0 else 0.0

            val significand = fraction or (1L shl 52)
            val kept = significand ushr drop
            val roundBit = (significand ushr (drop - 1)) and 1L
            val sticky = (significand and ((1L shl (drop - 1)) - 1L)) != 0L
            val rounded = if (roundBit == 1L && (sticky || (kept and 1L) == 1L)) kept + 1L else kept

            val magnitude = rounded.toDouble() * pow2(exponent - (52 - drop))
            // Rounding up at the very top carries past what a float can hold.
            if (magnitude >= pow2(128)) {
                return if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            }
            return if (negative) -magnitude else magnitude
        }

        /** An exact power of two, built from the exponent field rather than by multiplying. */
        private fun pow2(n: Int): Double = Double.fromBits(((n + 1023).toLong() and 0x7FFL) shl 52)

        private fun hypot(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            var y = 0.0
            var hasNaN = false
            var hasInfinity = false
            for (o in args) {
                val d = ScriptRuntime.toNumber(o)
                if (d.isNaN()) {
                    hasNaN = true
                } else if (d.isInfinite()) {
                    hasInfinity = true
                } else {
                    y += d * d
                }
            }
            if (hasInfinity) return Double.POSITIVE_INFINITY
            if (hasNaN) return Double.NaN
            return sqrt(y)
        }

        private fun imul(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val x = ScriptRuntime.toInt32(args, 0)
            val y = ScriptRuntime.toInt32(args, 1)
            return ScriptRuntime.wrapNumber((x * y).toDouble())
        }

        private fun log(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val x = ScriptRuntime.toNumber(args, 0)
            return ScriptRuntime.wrapNumber(if (x < 0) Double.NaN else FdLibm.log(x))
        }

        private fun log1p(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            ScriptRuntime.wrapNumber(FdLibm.log1p(ScriptRuntime.toNumber(args, 0)))

        private fun log10(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            ScriptRuntime.wrapNumber(FdLibm.log10(ScriptRuntime.toNumber(args, 0)))

        private fun log2(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            ScriptRuntime.wrapNumber(FdLibm.log2(ScriptRuntime.toNumber(args, 0)))

        private fun max(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            var x = Double.NEGATIVE_INFINITY
            for (arg in args) {
                val d = ScriptRuntime.toNumber(arg)
                x = javaMax(x, d)
            }
            return ScriptRuntime.wrapNumber(x)
        }

        private fun min(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            var x = Double.POSITIVE_INFINITY
            for (arg in args) {
                val d = ScriptRuntime.toNumber(arg)
                x = javaMin(x, d)
            }
            return ScriptRuntime.wrapNumber(x)
        }

        // Math.max/min: NaN wins, and -0 sorts below +0.
        private fun javaMax(a: Double, b: Double): Double {
            if (a.isNaN()) return a
            if (a == 0.0 && b == 0.0 && a.toRawBits() == NEGATIVE_ZERO_BITS) return b
            return if (a >= b) a else b
        }

        private fun javaMin(a: Double, b: Double): Double {
            if (a.isNaN()) return a
            if (a == 0.0 && b == 0.0 && b.toRawBits() == NEGATIVE_ZERO_BITS) return b
            return if (a <= b) a else b
        }

        private val NEGATIVE_ZERO_BITS = (-0.0).toRawBits()

        private fun pow(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val x = ScriptRuntime.toNumber(args, 0)
            val y = ScriptRuntime.toNumber(args, 1)
            var result: Double
            if (y.isNaN()) {
                result = y
            } else if (y == 0.0) {
                result = 1.0
            } else if (x == 0.0) {
                if (1 / x > 0) {
                    result = if (y > 0) 0.0 else Double.POSITIVE_INFINITY
                } else {
                    val yLong = y.toLong()
                    result = if (yLong.toDouble() == y && (yLong and 0x1L) != 0L) {
                        if (y > 0) -0.0 else Double.NEGATIVE_INFINITY
                    } else {
                        if (y > 0) 0.0 else Double.POSITIVE_INFINITY
                    }
                }
            } else {
                result = FdLibm.pow(x, y)
                if (result.isNaN()) {
                    if (y == Double.POSITIVE_INFINITY) {
                        if (x < -1.0 || 1.0 < x) {
                            result = Double.POSITIVE_INFINITY
                        } else if (-1.0 < x && x < 1.0) {
                            result = 0.0
                        }
                    } else if (y == Double.NEGATIVE_INFINITY) {
                        if (x < -1.0 || 1.0 < x) {
                            result = 0.0
                        } else if (-1.0 < x && x < 1.0) {
                            result = Double.POSITIVE_INFINITY
                        }
                    } else if (x == Double.POSITIVE_INFINITY) {
                        result = if (y > 0) Double.POSITIVE_INFINITY else 0.0
                    } else if (x == Double.NEGATIVE_INFINITY) {
                        val yLong = y.toLong()
                        result = if (yLong.toDouble() == y && (yLong and 0x1L) != 0L) {
                            if (y > 0) Double.NEGATIVE_INFINITY else -0.0
                        } else {
                            if (y > 0) Double.POSITIVE_INFINITY else 0.0
                        }
                    }
                }
            }
            return ScriptRuntime.wrapNumber(result)
        }

        private fun random(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            ScriptRuntime.wrapNumber(Random.nextDouble())

        private fun round(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            var x = ScriptRuntime.toNumber(args, 0)
            if (!x.isNaN() && !x.isInfinite()) {
                val l = javaRound(x)
                if (l != 0L) {
                    x = l.toDouble()
                } else {
                    if (x < 0.0) {
                        x = ScriptRuntime.negativeZero
                    } else if (x != 0.0) {
                        x = 0.0
                    }
                }
            }
            return ScriptRuntime.wrapNumber(x)
        }

        /** java.lang.Math.round: floor(x + 0.5) done exactly, without the double addition. */
        private fun javaRound(a: Double): Long {
            val longBits = a.toRawBits()
            val biasedExp = (longBits and 0x7FF0000000000000L) shr 52
            val shift = (53 - 2 + 1023) - biasedExp
            if ((shift and -64L) == 0L) {
                var r = (longBits and 0x000FFFFFFFFFFFFFL) or (0x000FFFFFFFFFFFFFL + 1)
                if (longBits < 0) r = -r
                return ((r shr shift.toInt()) + 1) shr 1
            }
            return a.toLong()
        }

        private fun sign(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val x = ScriptRuntime.toNumber(args, 0)
            if (!x.isNaN()) {
                if (x == 0.0) {
                    return if (1 / x > 0) ScriptRuntime.zeroObj else ScriptRuntime.negativeZeroObj
                }
                return sign(x)
            }
            return ScriptRuntime.NaNobj
        }

        private fun sin(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val x = ScriptRuntime.toNumber(args, 0)
            return ScriptRuntime.wrapNumber(if (x.isInfinite()) Double.NaN else FdLibm.sin(x))
        }

        private fun sinh(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            ScriptRuntime.wrapNumber(FdLibm.sinh(ScriptRuntime.toNumber(args, 0)))

        private fun sqrt(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            ScriptRuntime.wrapNumber(sqrt(ScriptRuntime.toNumber(args, 0)))

        private fun tan(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            ScriptRuntime.wrapNumber(FdLibm.tan(ScriptRuntime.toNumber(args, 0)))

        private fun tanh(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
            ScriptRuntime.wrapNumber(FdLibm.tanh(ScriptRuntime.toNumber(args, 0)))

        private fun trunc(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val x = ScriptRuntime.toNumber(args, 0)
            return ScriptRuntime.wrapNumber(if (x < 0.0) ceil(x) else floor(x))
        }
    }
}
