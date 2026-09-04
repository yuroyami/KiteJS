package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.dtoa.DecimalFormatter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mozilla.javascript.dtoa.DecimalFormatter as UpstreamDecimalFormatter

/**
 * The port formats numbers without BigDecimal. This checks it against upstream on a fixed corpus
 * plus a seeded batch of random doubles, for every digit count the JavaScript methods accept.
 */
class DecimalFormatterOracleTest {

    private val corpus: List<Double> = listOf(
        0.0, -0.0, 1.0, -1.0, 0.5, 1.5, 2.5, -2.5, 1.005, 1.045, 8.345, 0.000001234, 1e-7, 5e-7,
        123.456, 999.999, 99.99, 9.995, 9.9999, 0.1, 0.2, 0.3, 1.0 / 3, 2.0 / 3, 1e-10, 12345.6789,
        1234567.891, 1e15 + 0.3, 9007199254740992.0, 1e20, 1e21, 1e22, 123456789012345680000.0,
        Double.MAX_VALUE, Double.MIN_VALUE, 2.2250738585072014e-308, 4.9e-324, 0.05, 0.15, 0.25,
        0.35, 0.45, 1.23e-5, 4.35, 1.255, 100.0, 1000.0, 0.001, 0.0001, 25.0, 3.14159265358979,
        2.718281828459045, 0.49999999999999994, 1e100, 1.7976931348623157e308,
    )

    private fun randomDoubles(): List<Double> {
        val random = Random(42)
        val out = ArrayList<Double>()
        repeat(150) {
            val d = Double.fromBits(random.nextLong())
            if (d.isFinite()) out.add(d)
        }
        repeat(150) { out.add(random.nextDouble() * 1000) }
        repeat(50) { out.add(random.nextDouble() * 1e-5) }
        return out
    }

    @Test
    fun toFixedMatchesUpstream() {
        for (v in corpus + randomDoubles()) {
            for (digits in 0..20) {
                assertEquals(UpstreamDecimalFormatter.toFixed(v, digits), DecimalFormatter.toFixed(v, digits), "toFixed($v, $digits)")
            }
            assertEquals(UpstreamDecimalFormatter.toFixed(v, 100), DecimalFormatter.toFixed(v, 100), "toFixed($v, 100)")
        }
    }

    @Test
    fun toExponentialMatchesUpstream() {
        for (v in corpus + randomDoubles()) {
            for (digits in -1..20) {
                assertEquals(
                    UpstreamDecimalFormatter.toExponential(v, digits),
                    DecimalFormatter.toExponential(v, digits),
                    "toExponential($v, $digits)",
                )
            }
            assertEquals(UpstreamDecimalFormatter.toExponential(v, 100), DecimalFormatter.toExponential(v, 100), "toExponential($v, 100)")
        }
    }

    @Test
    fun toPrecisionMatchesUpstream() {
        for (v in corpus + randomDoubles()) {
            for (precision in 1..21) {
                assertEquals(
                    UpstreamDecimalFormatter.toPrecision(v, precision),
                    DecimalFormatter.toPrecision(v, precision),
                    "toPrecision($v, $precision)",
                )
            }
            assertEquals(UpstreamDecimalFormatter.toPrecision(v, 100), DecimalFormatter.toPrecision(v, 100), "toPrecision($v, 100)")
        }
    }

    @Test
    fun theHalfUpRoundingIsTheOneTheSpecWants() {
        // 1.005 is really 1.00499999999999989... so it rounds down; 2.5 is exact and rounds up.
        assertEquals("1.00", DecimalFormatter.toFixed(1.005, 2))
        assertEquals("3", DecimalFormatter.toFixed(2.5, 0))
        assertEquals("1", DecimalFormatter.toFixed(0.5, 0))
        assertEquals("0", DecimalFormatter.toFixed(0.49999999999999994, 0))
        assertEquals("1.0e+2", DecimalFormatter.toExponential(99.99, 1))
        assertEquals("100", DecimalFormatter.toPrecision(99.99, 3))
        assertEquals("1.0e+2", DecimalFormatter.toPrecision(99.99, 2))
    }
}
