/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The port of fdlibm against the JVM's StrictMath, which is fdlibm 5.3: the specification of
 * StrictMath requires its results, and JDK 21 computes them with a Java port of the same C
 * sources. Every answer has to match to the bit, which is the whole point of having the port
 * (D-73): `Math` on the JVM, JavaScript's functions and the native libm each round a few
 * arguments differently.
 *
 * The port follows V8 where V8 left fdlibm 5.3, and the oracles below say so: `exp(1)` is
 * `Math.E`, and so `cosh(1)` is built from it, `tanh` returns a tiny argument as it is, and
 * `atan2` answers +-pi/2 whenever `|y/x|` exceeds 2^60. `cbrt` is FreeBSD's newer algorithm, as in V8, so it is not compared here;
 * `MathDigitsTest` checks it, and the functions StrictMath lacks, against V8's own digits.
 */
class FdLibmOracleTest {

    private val specials = doubleArrayOf(
        0.0, -0.0, 1.0, -1.0, 0.5, -0.5, 2.0, -2.0, 3.0, 10.0, 0.1, -0.1, 0.25, 0.75, 1.5,
        Math.PI, -Math.PI, Math.PI / 2, -Math.PI / 2, Math.PI / 4, 3 * Math.PI / 4, Math.E, Math.E * 2,
        Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
        Double.MIN_VALUE, -Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
        java.lang.Double.MIN_NORMAL, -java.lang.Double.MIN_NORMAL, 4.9e-320, 1.0e-310,
        1.0e-300, 1.0e-20, 1.0e-10, 1.0e-5, 1.0e5, 1.0e10, 1.0e20, 1.0e100, 1.0e300, -1.0e300,
        709.782712893384, 709.7827128933841, -745.1332191019411, -745.1332191019412, 710.0, -710.0,
        22.0, -22.0, 21.999999999999996, 0.34657359027997264, 1.0397207708399179, 56 * 0.6931471805599453,
        0.4142135623730950, -0.2928932188134524, 0.9999999999999999, 1.0000000000000002,
        0.975, 0.4375, 0.6875, 1.1875, 2.4375, 7.378697629483821E19, 1e16, 1e17,
        1.5707963267948966, 3.141592653589793, 4.71238898038469, 6.283185307179586,
        1.0e6 * Math.PI, 2.0.pow(19) * Math.PI / 2, 2.0.pow(20) * Math.PI / 2, 2.0.pow(60), 2.0.pow(66),
        8.98846567431158e307, 3.0e-15, 2.0.pow(-27), 2.0.pow(-28), 2.0.pow(-29), 2.0.pow(-54), 2.0.pow(-55),
    )

    private fun Double.pow(n: Int): Double = StrictMath.pow(this, n.toDouble())

    /** Every double the generators produce, spread over all exponents and the hot ranges. */
    private fun inputs(seed: Int, count: Int): DoubleArray {
        val random = Random(seed)
        val out = DoubleArray(count)
        for (i in 0 until count) {
            out[i] = when (i % 6) {
                0 -> Double.fromBits(random.nextLong()) // any bit pattern
                1 -> random.nextDouble(-2.0, 2.0)
                2 -> random.nextDouble(-50.0, 50.0)
                3 -> random.nextDouble(-1000.0, 1000.0)
                4 -> (if (random.nextBoolean()) 1.0 else -1.0) * Math.scalb(1.0 + random.nextDouble(), random.nextInt(-1074, 1024))
                // Near multiples of pi/2, where argument reduction cancels the most.
                else -> (random.nextInt(-100000, 100000) * (Math.PI / 2)) * (1.0 + (random.nextDouble() - 0.5) * 1e-15)
            }
        }
        return out
    }

    private fun check(name: String, ported: (Double) -> Double, oracle: (Double) -> Double) {
        val failures = mutableListOf<String>()
        val values = specials + inputs(name.hashCode(), 300_000)
        for (x in values) {
            val expected = oracle(x)
            val actual = ported(x)
            if (expected.isNaN() && actual.isNaN()) continue
            if (expected.toRawBits() != actual.toRawBits()) {
                failures.add("$name($x) = $actual, StrictMath gives $expected")
                if (failures.size >= 10) break
            }
        }
        assertEquals(emptyList(), failures, "$name differs from StrictMath")
    }

    @Test fun exp() = check("exp", FdLibm::exp) { x -> if (x == 1.0) Math.E else StrictMath.exp(x) }
    @Test fun expm1() = check("expm1", FdLibm::expm1, StrictMath::expm1)
    @Test fun log() = check("log", FdLibm::log, StrictMath::log)
    @Test fun log10() = check("log10", FdLibm::log10, StrictMath::log10)
    @Test fun log1p() = check("log1p", FdLibm::log1p, StrictMath::log1p)
    @Test fun sin() = check("sin", FdLibm::sin, StrictMath::sin)
    @Test fun cos() = check("cos", FdLibm::cos, StrictMath::cos)
    @Test fun tan() = check("tan", FdLibm::tan, StrictMath::tan)
    @Test fun asin() = check("asin", FdLibm::asin, StrictMath::asin)
    @Test fun acos() = check("acos", FdLibm::acos, StrictMath::acos)
    @Test fun atan() = check("atan", FdLibm::atan, StrictMath::atan)
    @Test fun sinh() = check("sinh", FdLibm::sinh, StrictMath::sinh)
    @Test fun cosh() = check("cosh", FdLibm::cosh) { x -> if (Math.abs(x) == 1.0) 0.5 * Math.E + 0.5 / Math.E else StrictMath.cosh(x) }
    @Test fun tanh() = check("tanh", FdLibm::tanh) { x -> if (Math.abs(x) < TWO_TO_MINUS_28) x else StrictMath.tanh(x) }

    /** Huge arguments go through the long reduction, which the random inputs reach only rarely. */
    @Test
    fun trigOfHugeArguments() {
        val random = Random(2026)
        val huge = DoubleArray(100_000) { Math.scalb(1.0 + random.nextDouble(), random.nextInt(20, 1024)) * (if (it % 2 == 0) 1 else -1) }
        for ((name, pair) in listOf(
            "sin" to (FdLibm::sin to StrictMath::sin),
            "cos" to (FdLibm::cos to StrictMath::cos),
            "tan" to (FdLibm::tan to StrictMath::tan),
        )) {
            val failures = huge.filter { pair.first(it).toRawBits() != pair.second(it).toRawBits() }.take(10)
            assertEquals(emptyList(), failures, "$name differs from StrictMath for huge arguments")
        }
    }

    private fun check2(name: String, ported: (Double, Double) -> Double, oracle: (Double, Double) -> Double, pairs: List<Pair<Double, Double>>) {
        val failures = mutableListOf<String>()
        for ((x, y) in pairs) {
            val expected = oracle(x, y)
            val actual = ported(x, y)
            if (expected.isNaN() && actual.isNaN()) continue
            if (expected.toRawBits() != actual.toRawBits()) {
                failures.add("$name($x, $y) = $actual, StrictMath gives $expected")
                if (failures.size >= 10) break
            }
        }
        assertEquals(emptyList(), failures, "$name differs from StrictMath")
    }

    /** Pairs for the two-argument functions: specials crossed with specials, then random ones. */
    private fun pairs(seed: Int): List<Pair<Double, Double>> {
        val random = Random(seed)
        val out = ArrayList<Pair<Double, Double>>()
        for (x in specials) for (y in specials) out.add(x to y)
        val xs = inputs(seed, 200_000)
        for (i in xs.indices) {
            val y = when (i % 5) {
                0 -> Double.fromBits(random.nextLong())
                1 -> random.nextInt(-40, 40).toDouble()
                2 -> random.nextDouble(-10.0, 10.0)
                3 -> random.nextDouble(-1100.0, 1100.0)
                else -> Math.scalb(random.nextDouble(), random.nextInt(-60, 70)) * (if (random.nextBoolean()) 1 else -1)
            }
            out.add(xs[i] to y)
        }
        // Close to one, raised to huge powers, where pow takes its own path.
        for (i in 0 until 20_000) {
            out.add((1.0 + (random.nextDouble() - 0.5) * 1e-6) to random.nextDouble(-1e12, 1e12))
        }
        return out
    }

    @Test fun pow() = check2("pow", FdLibm::pow, StrictMath::pow, pairs(7))

    @Test
    fun atan2() = check2("atan2", FdLibm::atan2, { y, x ->
        val k = ((highWord(y) and 0x7fffffff) - (highWord(x) and 0x7fffffff)) shr 20
        val ordinary = y.isFinite() && x.isFinite() && y != 0.0 && x != 0.0 && x != 1.0
        if (ordinary && k > 60) Math.copySign(Math.PI / 2, y) else StrictMath.atan2(y, x)
    }, pairs(11))

    private fun highWord(x: Double): Int = (x.toRawBits() ushr 32).toInt()

    private companion object {
        const val TWO_TO_MINUS_28 = 3.725290298461914E-9
    }

    @Test
    fun scalbnIsExact() {
        val random = Random(3)
        for (i in 0 until 100_000) {
            val x = Double.fromBits(random.nextLong())
            val n = random.nextInt(-2200, 2200)
            val expected = Math.scalb(x, n)
            val actual = FdLibm.scalbn(x, n)
            if (expected.isNaN() && actual.isNaN()) continue
            assertEquals(expected.toRawBits(), actual.toRawBits(), "scalbn($x, $n)")
        }
    }
}
