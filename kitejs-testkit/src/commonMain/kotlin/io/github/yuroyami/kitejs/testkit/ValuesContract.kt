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
import io.github.yuroyami.kitejs.api.function
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestResult

private class CollectionBox(var child: Any? = null)

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
    public fun aLongConcatenationComesBackAsAString(): TestResult = withEngine { js ->
        // Past a few hundred characters an engine may hold a concatenation as a tree of parts.
        val built = js.evaluate("var s = ''; for (var i = 0; i < 2000; i++) s += 'part ' + i + ';'; s")
        assertEquals(JsType.STRING, built.type)
        assertEquals((0 until 2000).joinToString("") { "part $it;" }, built.asString())
        val joined = js.evaluate("var a = 'x'.repeat(600); `${'$'}{a}${'$'}{a}`")
        assertEquals(JsType.STRING, joined.type)
        assertEquals(1200, joined.asString().length)
        assertEquals("x".repeat(1200), js.evaluate("({ toString: function () { return a + a; } })").asString())
        assertEquals(1200, js.evaluate("[a + a]").asArray()[0].asString().length)
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
    public fun aLongKeepsAllItsBits(): TestResult = withEngine { js ->
        val numbers = listOf(
            2147483648L, -2147483649L, 4294967296L, -4294967296L,
            9007199254740991L, -9007199254740991L, 9007199254740992L,
            Long.MIN_VALUE, 9223372036854774784L,
        )
        for (number in numbers) assertEquals(number, js.evaluate(number.toString()).asLong(), number.toString())
        for (number in numbers + Long.MAX_VALUE) {
            assertEquals(number, js.evaluate("${number}n").asLong(), "BigInt $number")
        }
        assertEquals(0L, js.evaluate("-0").asLong())
        assertEquals(4294967296L, js.evaluate("'4294967296'").asLong())
        assertEquals(4294967296L, js.evaluate("({ valueOf: function () { return 4294967296 } })").asLong())
        assertEquals(Long.MAX_VALUE, js.evaluate("({ valueOf: function () { return 9223372036854775807n } })").asLong())
        assertEquals(Long.MIN_VALUE, js.evaluate("Object(-9223372036854775808n)").asLong())
        assertEquals(Long.MAX_VALUE, js.evaluate("({ [Symbol.toPrimitive]: function (hint) { if (hint !== 'number') throw Error(hint); return 9223372036854775807n } })").asLong())
        assertEquals(0L, JsValue.nullValue.asLong())
        assertEquals(1L, JsValue.`true`.asLong())
        assertEquals(0, js.evaluate("4294967296").asInt(), "Int still wraps")
    }

    @Test
    public fun aLongRefusesValuesItCannotRepresent(): TestResult = withEngine { js ->
        for (source in listOf(
            "1.5", "-1.5", "NaN", "Infinity", "-Infinity", "undefined", "'not a number'",
            "9223372036854775808", "-9223372036854777856", "9223372036854775808n", "-9223372036854775809n",
        )) {
            val error = assertFailsWith<JsError>(source) { js.evaluate(source).asLong() }
            assertEquals("RangeError", error.name, source)
        }
        assertEquals("TypeError", assertFailsWith<JsError> { js.evaluate("Symbol()").asLong() }.name)
        assertEquals(2, js.evaluate("1 + 1").asInt())
    }

    @Test
    public fun numericPrimitiveConversionKeepsTheHookOrder(): TestResult = withEngine { js ->
        val value = js.evaluate(
            "var steps = []; ({ valueOf: function () { steps.push('valueOf'); return {} }, " +
                "toString: function () { steps.push('toString'); return '4294967296' } })",
        )
        assertEquals(4294967296L, value.asLong())
        assertEquals("valueOf,toString", js.evaluate("steps.join()").asString())
        assertEquals("TypeError", assertFailsWith<JsError> { js.evaluate("({ [Symbol.toPrimitive]: 1 })").asLong() }.name)
        assertEquals("TypeError", assertFailsWith<JsError> { js.evaluate("({ [Symbol.toPrimitive]: function () { return {} } })").asLong() }.name)
        assertEquals("TypeError", assertFailsWith<JsError> { js.evaluate("({ valueOf: function () { return {} }, toString: function () { return {} } })").asLong() }.name)
        assertEquals("hook", assertFailsWith<JsError> { js.evaluate("({ valueOf: function () { throw Error('hook') } })").asLong() }.errorMessage)
    }

    @Test
    public fun typedLongCallbacksRoundTripNumbersAndBigInts(): TestResult = withEngine { js ->
        var calls = 0
        js.global.function<Long, Long>("echoLong") { calls++; it }
        for (number in listOf(4294967296L, -4294967296L, 9007199254740991L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            js.global["longValue"] = number
            assertEquals(number, js.evaluate("echoLong(longValue)").asLong())
        }
        assertEquals(5, calls)
        assertFailsWith<JsError> { js.evaluate("echoLong(1.5)") }
        assertEquals(5, calls, "an invalid argument must not reach the callback")
        assertEquals(4294967296L, js.evaluate("echoLong(4294967296)").asLong())
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
    public fun aLargeSparseArrayKeepsItsLengthAndRefusesIntMaterialization(): TestResult = withEngine { js ->
        for (length in listOf(Int.MAX_VALUE.toLong(), 2147483648L, 4294967295L)) {
            val array = js.evaluate("var sparse = new Array($length); sparse[${length - 1}] = 7; sparse").asArray()
            assertEquals(length, array.length)
            assertEquals(7, array[(length - 1).toString()].asInt())
            if (length == Int.MAX_VALUE.toLong()) {
                assertEquals(Int.MAX_VALUE, array.size)
            } else {
                assertFailsWith<JsEngineError> { array.size }
                assertFailsWith<JsEngineError> { array.values() }
                assertFailsWith<JsEngineError> { array.toList() }
                assertFailsWith<JsEngineError> { array.value.toKotlin() }
            }
        }
        assertEquals(2, js.evaluate("1 + 1").asInt())
    }

    @Test
    public fun anAppendAboveTheIntRangeUsesTheNextIndex(): TestResult = withEngine { js ->
        val array = js.evaluate("new Array(2147483648)").asArray()
        array.add("tail")
        assertEquals(2147483649L, array.length)
        assertEquals("tail", array["2147483648"].asString())
        assertEquals(false, array.has("-2147483648"))
        assertFailsWith<JsEngineError> { array.size }
    }

    @Test
    public fun anAppendAtTheMaximumArrayLengthIsRefused(): TestResult = withEngine { js ->
        val array = js.evaluate("new Array(4294967295)").asArray()
        assertFailsWith<JsEngineError> { array.add("too far") }
        assertEquals(4294967295L, array.length)
        assertEquals(false, array.has("4294967295"))
        assertEquals(false, array.has("-1"))
        assertEquals(2, js.evaluate("1 + 1").asInt())
    }

    @Test
    public fun aCycleStopsAtTheObjectThatClosedIt(): TestResult = withEngine { js ->
        val map = js.evaluate("var a = { name: 'root' }; a.self = a; a").asObject().toMap()
        assertEquals("root", map["name"])
        assertTrue(map["self"] is JsObject)
    }

    @Test
    public fun incomingCollectionsKeepTheirCycles(): TestResult = withEngine { js ->
        val list = mutableListOf<Any?>()
        list.add(list)
        js.global["listCycle"] = list
        assertTrue(js.evaluate("listCycle[0] === listCycle").asBoolean())
        val map = mutableMapOf<String, Any?>()
        map["self"] = map
        js.global["mapCycle"] = map
        assertTrue(js.evaluate("mapCycle.self === mapCycle").asBoolean())
        val array = arrayOfNulls<Any?>(1)
        array[0] = array
        js.global["arrayCycle"] = array
        assertTrue(js.evaluate("arrayCycle[0] === arrayCycle").asBoolean())
        val mutual = mutableListOf<Any?>(map)
        map["list"] = mutual
        js.global["mutual"] = mutual
        assertTrue(js.evaluate("mutual[0].list === mutual").asBoolean())
        val set = mutableSetOf<Any?>()
        val back = arrayOf<Any?>(set)
        set.add(back)
        js.global["setCycle"] = set
        assertTrue(js.evaluate("setCycle[0][0] === setCycle").asBoolean())
        js.global.function("cycleFromHost") { _ -> map }
        assertTrue(js.evaluate("var result = cycleFromHost(); result.self === result && result.list[0] === result").asBoolean())
        assertEquals(2, js.evaluate("1 + 1").asInt())
    }

    @Test
    public fun incomingCollectionsKeepSharedReferencesWithoutMergingEqualChildren(): TestResult = withEngine { js ->
        val child = mutableListOf(1)
        val bytes = byteArrayOf(2)
        val ints = intArrayOf(3)
        js.global["shared"] = listOf(child, child, mutableListOf(1), bytes, bytes, ints, ints)
        assertTrue(js.evaluate("shared[0] === shared[1] && shared[0] !== shared[2] && shared[3] === shared[4] && shared[5] === shared[6]").asBoolean())
        js.global["separate"] = child
        assertTrue(js.evaluate("separate !== shared[0]").asBoolean())
    }

    @Test
    public fun incomingIdentityChecksRunNoCollectionHashCodeOrEquals(): TestResult = withEngine { js ->
        class SelfList : AbstractList<Any?>() {
            override val size: Int get() = 1
            override fun get(index: Int): Any = this
            override fun hashCode(): Int = error("structural hashCode ran")
            override fun equals(other: Any?): Boolean = error("structural equals ran")
        }
        js.global["identityOnly"] = SelfList()
        assertTrue(js.evaluate("identityOnly[0] === identityOnly").asBoolean())
    }

    @Test
    public fun deepIncomingCollectionsUseNoRecursiveHostTraversal(): TestResult = withEngine { js ->
        var source: Any? = 7
        repeat(5000) { source = listOf(source) }
        js.global["deep"] = source
        assertEquals(7, js.evaluate("var node = deep; for (var i = 0; i < 5000; i++) node = node[0]; node").asInt())
        // Break the chain explicitly so engine destruction does not measure recursive finalization.
        js.evaluate("node = deep; for (var i = 0; i < 5000; i++) { var next = node[0]; node[0] = null; node = next; } deep = null;")
    }

    @Test
    public fun customConvertersKeepCyclesAndRefuseNonTerminatingChains(): TestResult = withEngine { js ->
        val box = CollectionBox()
        box.child = box
        var conversions = 0
        Converters.register { if (it is CollectionBox) { conversions++; mapOf("child" to it.child) } else null }
        try {
            js.global["box"] = box
            assertTrue(js.evaluate("box.child === box").asBoolean())
            assertEquals(1, conversions, "a shared custom source is converted once")
        } finally {
            Converters.clearRegistrations()
        }
        Converters.register { if (it is CollectionBox) it else null }
        try {
            assertFailsWith<JsEngineError> { js.valueOf(CollectionBox()) }
        } finally {
            Converters.clearRegistrations()
        }
        Converters.register { if (it is CollectionBox) CollectionBox() else null }
        try {
            assertFailsWith<JsEngineError> { js.valueOf(CollectionBox()) }
        } finally {
            Converters.clearRegistrations()
        }
        assertEquals(2, js.evaluate("1 + 1").asInt())
    }

    @Test
    public fun incomingCollectionsDefineOwnDataWithoutRunningPrototypeSetters(): TestResult = withEngine { js ->
        js.evaluate(
            "Object.defineProperty(Array.prototype, '0', { set: function () { throw Error('array setter') }, configurable: true });" +
                "Object.defineProperty(Object.prototype, 'tag', { set: function () { throw Error('object setter') }, configurable: true });",
        )
        js.global["ownData"] = listOf(mapOf("tag" to "kept", "__proto__" to mapOf("marker" to 7), "0" to 9))
        assertTrue(js.evaluate(
            "ownData[0].tag === 'kept' && ownData[0][0] === 9 && " +
                "Object.getPrototypeOf(ownData[0]) === Object.prototype && " +
                "Object.prototype.hasOwnProperty.call(ownData[0], '__proto__') && ownData[0].__proto__.marker === 7",
        ).asBoolean())
    }

    @Test
    public fun aByteArrayReachesTheScriptAsAUint8ArrayOfACopy(): TestResult = withEngine { js ->
        val bytes = byteArrayOf(1, 2, 3, -1)
        js.global.function("bytes") { _ -> bytes }
        assertEquals("true,4,1,2,3,255", js.evaluate("var b = bytes(); [b instanceof Uint8Array, b.length, b[0], b[1], b[2], b[3]].join()").asString())
        js.evaluate("b[0] = 9")
        assertEquals(1, bytes[0], "the script wrote to the Kotlin array")
        js.global["empty"] = ByteArray(0)
        assertEquals("true,0", js.evaluate("[empty instanceof Uint8Array, empty.byteLength].join()").asString())
    }

    @Test
    public fun aByteArrayIgnoresAUint8ArrayTheScriptReplaced(): TestResult = withEngine { js ->
        js.evaluate("var Original = Uint8Array; Uint8Array = function () { throw new Error('replaced') }")
        js.global["b"] = byteArrayOf(7, 8)
        assertEquals("true,7,8", js.evaluate("[b instanceof Original, b[0], b[1]].join()").asString())
    }

    @Test
    public fun theOtherPrimitiveArraysBecomeArrays(): TestResult = withEngine { js ->
        js.global["shorts"] = shortArrayOf(1, -2)
        js.global["floats"] = floatArrayOf(0.5f, 2f)
        js.global["chars"] = charArrayOf('a', 'b')
        assertEquals("true,1,-2", js.evaluate("[Array.isArray(shorts), shorts[0], shorts[1]].join()").asString())
        assertEquals("true,0.5,2", js.evaluate("[Array.isArray(floats), floats[0], floats[1]].join()").asString())
        assertEquals("true,a,b", js.evaluate("[Array.isArray(chars), chars[0], chars[1]].join()").asString())
    }

    @Test
    public fun binaryDataComesBackAsTheBytesItViews(): TestResult = withEngine { js ->
        fun bytes(source: String): List<Byte> = (js.evaluate(source).toKotlin() as ByteArray).toList()
        assertEquals(listOf<Byte>(1, 2, -1), bytes("new Uint8Array([1, 2, 255])"))
        assertEquals(listOf<Byte>(5, 6), bytes("new Uint8Array([5, 6]).buffer"))
        // A view sees only its own window of the buffer, in the engine's little-endian order.
        assertEquals(listOf<Byte>(3, 0, -2, -1), bytes("new Int16Array(new Int16Array([9, 3, -2]).buffer, 2, 2)"))
        assertEquals(listOf<Byte>(2, 3), bytes("new DataView(new Uint8Array([1, 2, 3, 4]).buffer, 1, 2)"))
        assertEquals(listOf<Byte>(), bytes("var d = new ArrayBuffer(4); d.transfer(); d"))
        assertEquals(listOf<Byte>(), bytes("var g = new ArrayBuffer(4), v = new Uint8Array(g); g.transfer(); v"))
        // A host function gets the bytes of a typed array it is passed.
        var got: ByteArray? = null
        js.global.function("take") { args -> got = args[0].toKotlin() as ByteArray; null }
        js.evaluate("take(new Uint8ClampedArray([300, -5, 7]))")
        assertEquals(listOf<Byte>(-1, 0, 7), got!!.toList())
        // Inside an object, as everywhere on the way down.
        @Suppress("UNCHECKED_CAST")
        val map = js.evaluate("({ data: new Uint8Array([4]) })").toKotlin() as Map<String, Any?>
        assertEquals(listOf<Byte>(4), (map["data"] as ByteArray).toList())
    }

    @Test
    public fun theBytesIgnoreGettersTheScriptRedefined(): TestResult = withEngine { js ->
        val view = js.evaluate(
            "var u = new Uint8Array([1, 2, 3]).subarray(1);" +
                "Object.defineProperty(Object.getPrototypeOf(Uint8Array.prototype), 'byteLength', { get: function () { throw new Error('ran') } });" +
                "Object.defineProperty(u, 'buffer', { value: new ArrayBuffer(9) }); u",
        ).asObject()
        assertEquals(listOf<Byte>(2, 3), view.toByteArrayOrNull()!!.toList())
        assertNull(js.evaluate("({ length: 2, 0: 1, 1: 2 })").asObject().toByteArrayOrNull())
        assertNull(js.evaluate("[1, 2]").asObject().toByteArrayOrNull())
        // toMap still answers the keys of a typed array.
        assertEquals(mapOf("0" to 2.0, "1" to 3.0), view.toMap())
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
    public fun theGlobalObjectTakesItsClassFromItsPrototype(): TestResult = withEngine { js ->
        assertEquals("[object global]", js.evaluate("Object.prototype.toString.call(globalThis)").asString())
        assertEquals(false, js.evaluate("Object.getOwnPropertySymbols(globalThis).indexOf(Symbol.toStringTag) >= 0").asBoolean())
        // A host that models a browser gives the global a prototype of its own, as HTML gives it Window.prototype.
        js.evaluate("Object.setPrototypeOf(globalThis, Object.create(Object.getPrototypeOf(globalThis), { [Symbol.toStringTag]: { value: 'Window' } }))")
        assertEquals("[object Window]", js.evaluate("Object.prototype.toString.call(globalThis)").asString())
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
