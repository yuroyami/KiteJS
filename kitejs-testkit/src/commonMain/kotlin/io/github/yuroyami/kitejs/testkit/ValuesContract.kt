/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.testkit

import io.github.yuroyami.kitejs.api.Converters
import io.github.yuroyami.kitejs.api.JsEngine
import io.github.yuroyami.kitejs.api.JsEngineError
import io.github.yuroyami.kitejs.api.JsError
import io.github.yuroyami.kitejs.api.JsObject
import io.github.yuroyami.kitejs.api.JsSymbol
import io.github.yuroyami.kitejs.api.JsType
import io.github.yuroyami.kitejs.api.JsValue
import io.github.yuroyami.kitejs.api.KBigInt
import io.github.yuroyami.kitejs.api.KiteJsConfig
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestResult

/** Values in both directions: what comes back from a script, and what a Kotlin value becomes. */
public abstract class ValuesContract<C : KiteJsConfig>(engine: JsEngine<C>) : EngineContract<C>(engine) {

    @Test
    public fun everyKindOfValueComesBackWithItsType(): TestResult = withEngine { js ->
        assertEquals(JsType.NUMBER, js.evaluate("1 + 1").type)
        assertEquals(JsType.STRING, js.evaluate("'a' + 'b'").type)
        assertEquals(JsType.BOOLEAN, js.evaluate("1 < 2").type)
        assertEquals(JsType.NULL, js.evaluate("null").type)
        assertEquals(JsType.UNDEFINED, js.evaluate("undefined").type)
        assertEquals(JsType.ARRAY, js.evaluate("[1, 2]").type)
        assertEquals(JsType.OBJECT, js.evaluate("({ a: 1 })").type)
        assertEquals(JsType.FUNCTION, js.evaluate("(function () {})").type)
        assertEquals(JsType.BIGINT, js.evaluate("1n").type)
        assertEquals(JsType.SYMBOL, js.evaluate("Symbol('s')").type)
        // A symbol wrapped in an object is an object, as typeof says.
        assertEquals(JsType.OBJECT, js.evaluate("Object(Symbol('s'))").type)
    }

    @Test
    public fun typeOfMatchesTheOperator(): TestResult = withEngine { js ->
        for (source in listOf("1", "'a'", "true", "undefined", "({})", "[]", "(function(){})", "1n", "Symbol()", "null")) {
            assertEquals(js.evaluate("typeof ($source)").asString(), js.evaluate(source).typeOf, source)
        }
    }

    @Test
    public fun readersCoerceTheWayJavaScriptDoes(): TestResult = withEngine { js ->
        assertEquals(4.0, js.evaluate("2 + 2").asDouble())
        assertEquals(4, js.evaluate("2 + 2").asInt())
        assertEquals("4", js.evaluate("2 + 2").asString())
        assertEquals("0.1", js.evaluate("0.1").asString())
        assertEquals("1e+21", js.evaluate("1e21").asString())
        assertEquals(-1, js.evaluate("4294967295").asInt())
        assertEquals(true, js.evaluate("'x'").asBoolean())
        assertEquals(false, js.evaluate("''").asBoolean())
        assertEquals(false, js.evaluate("0n").asBoolean())
        assertEquals("1,2", js.evaluate("[1, 2]").asString())
        assertEquals(7L, js.evaluate("7").asLong())
        assertEquals(255.0, js.evaluate("'  0xff  '").asDouble())
        assertTrue(js.evaluate("'12px'").asDouble().isNaN())
        assertEquals(5.0, js.evaluate("({ valueOf: function () { return 5 } })").asDouble())
        assertEquals("12345678901234567890", js.evaluate("12345678901234567890n").asBigInt().toString())
        assertEquals(js.evaluate("String(123.456e-7)").asString(), js.evaluate("123.456e-7").asString())
    }

    @Test
    public fun aBigIntOrASymbolRefusesToBecomeANumber(): TestResult = withEngine { js ->
        assertFailsWith<JsError> { js.evaluate("1n").asDouble() }
        assertFailsWith<JsError> { js.evaluate("Symbol()").asDouble() }
        assertFailsWith<JsError> { js.evaluate("Symbol()").asString() }
    }

    @Test
    public fun toKotlinGoesAllTheWayDown(): TestResult = withEngine { js ->
        val v = js.evaluate("({ n: 1, s: 'x', b: true, big: 2n, list: [1, [2]], inner: { deep: null, gone: undefined } })").toKotlin()
        @Suppress("UNCHECKED_CAST")
        val map = v as Map<String, Any?>
        assertEquals(1.0, map["n"])
        assertEquals("x", map["s"])
        assertEquals(true, map["b"])
        assertEquals(KBigInt.fromLong(2), map["big"])
        assertEquals(listOf(1.0, listOf(2.0)), map["list"])
        @Suppress("UNCHECKED_CAST")
        val inner = map["inner"] as Map<String, Any?>
        assertNull(inner["deep"])
        assertTrue(inner.containsKey("gone"))
    }

    @Test
    public fun aCycleStopsAtTheObjectThatClosedIt(): TestResult = withEngine { js ->
        val map = js.evaluate("var a = { name: 'root' }; a.self = a; a").asObject().toMap()
        assertEquals("root", map["name"])
        assertTrue(map["self"] is JsObject)
    }

    @Test
    public fun anIndexLikeKeyReachesItsElement(): TestResult = withEngine { js ->
        val o = js.evaluate("({ 1: 'one', b: 'bee' })").asObject()
        assertEquals(listOf("1", "b"), o.keys)
        assertEquals("one", o["1"].asString())
        assertEquals("one", o[1].asString())
        assertEquals(mapOf("1" to "one", "b" to "bee"), o.toMap())
        val a = js.evaluate("['x', 'y']").asArray()
        assertEquals("y", a["1"].asString())
        a["0"] = "z"
        assertEquals("z,y", a.value.asString())
        assertEquals(listOf("0", "1"), a.keys)
    }

    @Test
    public fun symbolsKeepTheirIdentityAndDescription(): TestResult = withEngine { js ->
        val s = js.evaluate("var s = Symbol('tag'); s").asSymbol()
        assertEquals("tag", s.description)
        assertEquals("Symbol(tag)", s.toString())
        assertEquals(s, js.evaluate("s").asSymbol())
        assertNotEquals<JsSymbol>(s, js.evaluate("Symbol('tag')").asSymbol())
        assertNull(js.evaluate("Symbol()").asSymbol().description)
        assertEquals("", js.evaluate("Symbol('')").asSymbol().description)
        // A symbol handed back is the same symbol.
        js.global["back"] = s
        assertEquals(true, js.evaluate("back === s").asBoolean())
        assertEquals(true, js.evaluate("Symbol.for('k')").asSymbol() == js.evaluate("Symbol.for('k')").asSymbol())
    }

    @Test
    public fun kotlinValuesCrossIntoTheEngine(): TestResult = withEngine { js ->
        js.global["n"] = 42
        js.global["f"] = 1.5f
        js.global["big"] = Long.MAX_VALUE
        js.global["safe"] = 9007199254740991L
        js.global["s"] = "text"
        js.global["c"] = 'q'
        js.global["flag"] = true
        js.global["nothing"] = null
        js.global["unit"] = Unit
        js.global["list"] = listOf(1, 2, 3)
        js.global["ints"] = intArrayOf(4, 5)
        js.global["set"] = setOf("a")
        js.global["map"] = mapOf("k" to "v", "nested" to mapOf("deep" to listOf(true)))
        js.global["bi"] = KBigInt.parse("123456789012345678901234567890", 10)
        assertEquals("number", js.evaluate("typeof n").asString())
        assertEquals(42.0, js.evaluate("n").asDouble())
        assertEquals(1.5, js.evaluate("f").asDouble())
        assertEquals("bigint", js.evaluate("typeof big").asString())
        assertEquals("9223372036854775807", js.evaluate("big.toString()").asString())
        assertEquals("number", js.evaluate("typeof safe").asString())
        assertEquals("text", js.evaluate("s").asString())
        assertEquals("q", js.evaluate("c").asString())
        assertEquals(true, js.evaluate("flag").asBoolean())
        assertEquals("undefined", js.evaluate("typeof nothing").asString())
        assertEquals("undefined", js.evaluate("typeof unit").asString())
        assertEquals(3.0, js.evaluate("list.length").asDouble())
        assertEquals(true, js.evaluate("Array.isArray(list)").asBoolean())
        assertEquals("4,5", js.evaluate("ints.join()").asString())
        assertEquals("a", js.evaluate("set[0]").asString())
        assertEquals("v", js.evaluate("map.k").asString())
        assertEquals(true, js.evaluate("map.nested.deep[0]").asBoolean())
        assertEquals("123456789012345678901234567890", js.evaluate("bi.toString()").asString())
    }

    @Test
    public fun aKotlinValueWithNoJavaScriptFormIsRefused(): TestResult = withEngine { js ->
        val e = assertFailsWith<JsEngineError> { js.global["x"] = Opaque() }
        assertContains(e.message ?: "", "Converters.register")
        assertFailsWith<JsEngineError> { js.valueOf(listOf(Opaque())) }
        assertEquals("undefined", js.evaluate("typeof x").asString())
    }

    @Test
    public fun aRegisteredConverterTeachesANewType(): TestResult = withEngine { js ->
        Converters.register { if (it is Point) mapOf("x" to it.x, "y" to it.y) else null }
        try {
            js.global["p"] = Point(3, 4)
            assertEquals(7.0, js.evaluate("p.x + p.y").asDouble())
        } finally {
            Converters.clearRegistrations()
        }
    }

    @Test
    public fun ofTakesScalarsAndSendsCollectionsToTheEngine(): TestResult = test {
        assertEquals(JsType.NUMBER, JsValue.of(3).type)
        assertEquals(JsType.UNDEFINED, JsValue.of(null).type)
        assertEquals(JsType.BIGINT, JsValue.of(Long.MIN_VALUE).type)
        assertFailsWith<IllegalArgumentException> { JsValue.of(listOf(1)) }
        // Scalars need no engine.
        assertEquals("3", JsValue.of(3).asString())
        assertEquals("undefined", JsValue.undefined.toString())
        assertEquals("null", JsValue.nullValue.toString())
    }

    @Test
    public fun newObjectAndNewArrayBuildRealScriptValues(): TestResult = withEngine { js ->
        val o = js.newObject()
        o["a"] = 1
        js.global["made"] = o
        assertEquals(1.0, js.evaluate("made.a").asDouble())
        assertEquals(true, js.evaluate("Object.getPrototypeOf(made) === Object.prototype").asBoolean())

        val a = js.newArray(1, "two", true)
        js.global["arr"] = a
        assertEquals("1,two,true", js.evaluate("arr.join(',')").asString())
        assertEquals(true, js.evaluate("Array.isArray(arr)").asBoolean())

        val v = js.valueOf(mapOf("list" to listOf(1, 2)))
        assertEquals(JsType.OBJECT, v.type)
        assertEquals(listOf(1.0, 2.0), v.asObject()["list"].asArray().toList())
    }

    @Test
    public fun theSameObjectGivesEqualHandles(): TestResult = withEngine { js ->
        js.evaluate("var o = {}")
        val first = js.global["o"].asObject()
        val second = js.evaluate("o").asObject()
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, js.evaluate("({})").asObject())
        assertEquals(js.global, js.evaluate("globalThis").asObject())
    }

    @Test
    public fun toStringNeverThrows(): TestResult = withEngine { js ->
        assertEquals("[object Object]", js.evaluate("({})").toString())
        assertEquals("1,2", js.evaluate("[1, 2]").toString())
        assertEquals("Symbol(s)", js.evaluate("Symbol('s')").toString())
        assertEquals("[object Object]", js.evaluate("({ toString: function () { throw new Error('no') } })").toString())
        assertEquals("[object Object]", js.evaluate("Object.create(null)").toString())
        assertEquals("1", js.evaluate("1n").toString())
    }
}

private class Opaque

private class Point(val x: Int, val y: Int)
