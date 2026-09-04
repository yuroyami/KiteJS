/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import java.lang.reflect.Method
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mozilla.javascript.ScriptRuntime as UpstreamScriptRuntime

/**
 * The small runtime helpers the IR generator leans on. Each one is compared against upstream over
 * a wide value range, because a wrong answer here would show up much later as a mis-lowered
 * property access or a wrong array index.
 */
class RuntimeHelperParityTest {

    private val upstreamGetIndexObjectString: Method =
        UpstreamScriptRuntime::class.java
            .getDeclaredMethod("getIndexObject", String::class.java)
            .also { it.isAccessible = true }

    private val upstreamGetIndexObjectDouble: Method =
        UpstreamScriptRuntime::class.java
            .getDeclaredMethod("getIndexObject", Double::class.javaPrimitiveType)
            .also { it.isAccessible = true }

    @Test
    fun toInt32MatchesUpstream() {
        val values = buildList {
            addAll(
                listOf(
                    0.0, -0.0, 1.0, -1.0, 0.5, -0.5, 1.5, -1.5,
                    2147483647.0, 2147483648.0, -2147483648.0, -2147483649.0,
                    4294967295.0, 4294967296.0, 4294967297.0,
                    1e9, 1e10, 1e20, 1e21, -1e20,
                    Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                    Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
                ),
            )
            for (i in -70000..70000 step 7) add(i.toDouble())
            val random = Random(4242)
            repeat(100_000) { add(Double.fromBits(random.nextLong())) }
            repeat(50_000) { add(random.nextLong(-1L shl 40, 1L shl 40).toDouble() / 7.0) }
        }
        val failures = mutableListOf<String>()
        for (d in values) {
            val expected = UpstreamScriptRuntime.toInt32(d)
            val actual = ScriptRuntime.toInt32(d)
            if (expected != actual) {
                failures.add("bits=${d.toRawBits()} expected=$expected actual=$actual")
                if (failures.size > 20) break
            }
        }
        assertTrue(values.size > 100_000)
        assertEquals(emptyList(), failures, "toInt32 differs from upstream")
    }

    @Test
    fun indexFromStringMatchesUpstream() {
        val strings = buildList {
            addAll(
                listOf(
                    "", "0", "1", "9", "10", "-0", "-1", "00", "01", "000", " 1", "1 ",
                    "2147483647", "2147483648", "4294967295", "4294967296",
                    "-2147483648", "-2147483649", "12345678901", "1e3", "0x10", "+1",
                    "abc", "1a", "a1", ".5", "1.5", "-", "--1", "9999999999",
                ),
            )
            for (i in -3000..3000) add(i.toString())
            val random = Random(99)
            repeat(20_000) { add(random.nextLong(-1L shl 35, 1L shl 35).toString()) }
        }
        val failures = mutableListOf<String>()
        for (s in strings) {
            val expected = UpstreamScriptRuntime.indexFromString(s)
            val actual = ScriptRuntime.indexFromString(s)
            if (expected != actual) {
                failures.add("\"$s\" expected=$expected actual=$actual")
                if (failures.size > 20) break
            }
        }
        assertEquals(emptyList(), failures, "indexFromString differs from upstream")
    }

    @Test
    fun getIndexObjectMatchesUpstream() {
        val failures = mutableListOf<String>()
        for (s in listOf("", "0", "1", "-0", "-1", "abc", "4294967295", "4294967296", "007")) {
            val expected = upstreamGetIndexObjectString.invoke(null, s)
            val actual = ScriptRuntime.getIndexObject(s)
            if (expected != actual) failures.add("string \"$s\": $expected vs $actual")
        }
        for (d in listOf(0.0, -0.0, 1.0, -1.0, 1.5, 1e21, Double.NaN, 2147483647.0, 2147483648.0)) {
            val expected = upstreamGetIndexObjectDouble.invoke(null, d)
            val actual = ScriptRuntime.getIndexObject(d)
            if (expected != actual) failures.add("double $d: $expected vs $actual")
        }
        assertEquals(emptyList(), failures, "getIndexObject differs from upstream")
    }

    @Test
    fun magicPropertyNamesMatchUpstream() {
        assertEquals(
            org.mozilla.javascript.NativeObject.PROTO_PROPERTY,
            NativeObject.PROTO_PROPERTY,
        )
        assertEquals(
            org.mozilla.javascript.NativeObject.PARENT_PROPERTY,
            NativeObject.PARENT_PROPERTY,
        )
    }
}
