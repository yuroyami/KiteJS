/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone

/** What an embedder actually touches. Runs on every target. */
class FacadeTest {

    private fun <T> engine(configure: KiteJsConfig.() -> Unit = {}, body: (KiteJs) -> T): T =
        KiteJs(configure).use(body)

    // ---- Values ------------------------------------------------------------------------------

    @Test
    fun everyKindOfValueComesBackWithItsType() = engine { js ->
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
    }

    @Test
    fun typeOfMatchesTheOperator() = engine { js ->
        for (source in listOf("1", "'a'", "true", "undefined", "({})", "[]", "(function(){})", "1n", "Symbol()", "null")) {
            assertEquals(js.evaluate("typeof ($source)").asString(), js.evaluate(source).typeOf, source)
        }
    }

    @Test
    fun readersCoerceTheWayJavaScriptDoes() = engine { js ->
        assertEquals(4.0, js.evaluate("2 + 2").asDouble())
        assertEquals(4, js.evaluate("2 + 2").asInt())
        assertEquals("4", js.evaluate("2 + 2").asString())
        assertEquals(true, js.evaluate("'x'").asBoolean())
        assertEquals(false, js.evaluate("''").asBoolean())
        assertEquals("1,2", js.evaluate("[1, 2]").asString())
        assertEquals(7L, js.evaluate("7").asLong())
        assertEquals("12345678901234567890", js.evaluate("12345678901234567890n").asBigInt().toString())
    }

    @Test
    fun toKotlinGoesAllTheWayDown() = engine { js ->
        val v = js.evaluate("({ n: 1, s: 'x', b: true, list: [1, [2]], inner: { deep: null } })").toKotlin()
        @Suppress("UNCHECKED_CAST")
        val map = v as Map<String, Any?>
        assertEquals(1.0, map["n"])
        assertEquals("x", map["s"])
        assertEquals(true, map["b"])
        assertEquals(listOf(1.0, listOf(2.0)), map["list"])
        @Suppress("UNCHECKED_CAST")
        assertNull((map["inner"] as Map<String, Any?>)["deep"])
    }

    @Test
    fun aCycleStopsAtTheObjectThatClosedIt() = engine { js ->
        val map = js.evaluate("var a = { name: 'root' }; a.self = a; a").asObject().toMap()
        assertEquals("root", map["name"])
        assertTrue(map["self"] is JsObject)
    }

    // ---- Objects, arrays, functions -----------------------------------------------------------

    @Test
    fun objectsReadAndWriteThrough() = engine { js ->
        val o = js.evaluate("({ a: 1, b: 'two' })").asObject()
        assertEquals(1.0, o["a"].asDouble())
        assertEquals("two", o["b"].asString())
        assertEquals(listOf("a", "b"), o.keys)
        o["c"] = 3
        assertEquals(3.0, o["c"].asDouble())
        js.global["probe"] = o
        assertEquals("a,b,c", js.evaluate("Object.keys(probe).join()").asString())
        assertTrue(o.has("c"))
        o.delete("c")
        assertTrue(!o.has("c"))
    }

    @Test
    fun arraysBehaveLikeLists() = engine { js ->
        val a = js.evaluate("[1, 2, 3]").asArray()
        assertEquals(3, a.size)
        assertEquals(2.0, a[1].asDouble())
        a[1] = 20
        assertEquals(listOf(1.0, 20.0, 3.0), a.toList())
        a.add(4)
        assertEquals(4, a.size)
        assertEquals(listOf(1.0, 20.0, 3.0, 4.0), a.toList())
    }

    @Test
    fun functionsCallConstructAndBind() = engine { js ->
        val add = js.evaluate("(function (a, b) { return a + b })").asFunction()
        assertEquals(3.0, add(1, 2).asDouble())
        assertEquals(2, add.arity)

        val addFive = add.bind(null, 5)
        assertEquals(9.0, addFive(4).asDouble())

        val greet = js.evaluate("(function () { return 'hi ' + this.name })").asFunction()
        val who = js.evaluate("({ name: 'ada' })").asObject()
        assertEquals("hi ada", greet.callOn(who).asString())

        val point = js.evaluate("(function Point(x) { this.x = x })").asFunction()
        assertEquals(7.0, point.construct(7)["x"].asDouble())
    }

    @Test
    fun aMethodCanBeCalledByName(): Unit = engine { js ->
        val o = js.evaluate("({ n: 3, twice: function () { return this.n * 2 } })").asObject()
        assertEquals(6.0, o.call("twice").asDouble())
        assertFailsWith<JsError> { o.call("nope") }
        Unit
    }

    // ---- Host bindings ------------------------------------------------------------------------

    @Test
    fun aHostFunctionIsCallableFromScript() = engine { js ->
        js.global.function("shout") { args -> args.first().asString().uppercase() }
        assertEquals("HI", js.evaluate("shout('hi')").asString())
        assertEquals("function", js.evaluate("typeof shout").asString())
    }

    @Test
    fun typedHostFunctionsConvertBothWays() = engine { js ->
        js.global.function<Double, Double, Double>("hypot") { a, b -> sqrt(a * a + b * b) }
        js.global.function<String, Int>("len") { s -> s.length }
        js.global.function<List<Any?>, Double>("total") { xs -> xs.sumOf { it as Double } }
        assertEquals(5.0, js.evaluate("hypot(3, 4)").asDouble())
        assertEquals(3.0, js.evaluate("len('abc')").asDouble())
        assertEquals(6.0, js.evaluate("total([1, 2, 3])").asDouble())
    }

    @Test
    fun aHostFunctionSeesTheThisItWasCalledOn() = engine { js ->
        js.global.method("describe") { self, _ -> "n=" + self.asObject()["n"].asString() }
        assertEquals("n=4", js.evaluate("var o = { n: 4, d: describe }; o.d()").asString())
    }

    @Test
    fun propertiesGettersAndSettersWork() = engine { js ->
        var stored = "start"
        js.global.obj("document") {
            property("title", "Untitled")
            getter("readyState") { "complete" }
            accessor("body", read = { stored }, write = { v -> stored = v.asString() })
            function("getElementById") { args -> mapOf("id" to args.first().asString()) }
        }
        assertEquals("Untitled", js.evaluate("document.title").asString())
        assertEquals("complete", js.evaluate("document.readyState").asString())
        assertEquals("start", js.evaluate("document.body").asString())
        js.evaluate("document.body = 'changed'")
        assertEquals("changed", stored)
        assertEquals("x1", js.evaluate("document.getElementById('x1').id").asString())
    }

    @Test
    fun aConstantCannotBeWrittenOver() = engine { js ->
        js.global.constant("VERSION", "1.0")
        js.evaluate("VERSION = 'nope'")
        assertEquals("1.0", js.evaluate("VERSION").asString())
    }

    @Test
    fun aHostConstructorWorksWithNew() = engine { js ->
        js.global.constructor("Point", 2) { obj, args ->
            obj["x"] = args.getOrElse(0) { JsValue.undefined }.asDouble()
            obj["y"] = args.getOrElse(1) { JsValue.undefined }.asDouble()
        }
        assertEquals(7.0, js.evaluate("new Point(3, 4).x + new Point(0, 0).x + 4").asDouble())
        assertEquals(4.0, js.evaluate("new Point(3, 4).y").asDouble())
    }

    @Test
    fun aKotlinObjectBindsByItsMembers() = engine { js ->
        val counter = Counter()
        js.global.bind("counter", counter) {
            property("value", Counter::value)
            method("bump") { by -> value += (by.firstOrNull() ?: JsValue.undefined).asInt(); value }
        }
        assertEquals(0.0, js.evaluate("counter.value").asDouble())
        assertEquals(5.0, js.evaluate("counter.bump(5)").asDouble())
        assertEquals(5, counter.value)
        assertEquals(5.0, js.evaluate("counter.value").asDouble())
    }

    private class Counter {
        var value: Int = 0
    }

    // ---- Conversions from Kotlin --------------------------------------------------------------

    @Test
    fun kotlinValuesCrossIntoTheEngine() = engine { js ->
        js.global["n"] = 42
        js.global["big"] = Long.MAX_VALUE
        js.global["s"] = "text"
        js.global["flag"] = true
        js.global["nothing"] = null
        js.global["list"] = listOf(1, 2, 3)
        js.global["map"] = mapOf("k" to "v")
        assertEquals("number", js.evaluate("typeof n").asString())
        assertEquals(42.0, js.evaluate("n").asDouble())
        assertEquals("bigint", js.evaluate("typeof big").asString())
        assertEquals("9223372036854775807", js.evaluate("big.toString()").asString())
        assertEquals("text", js.evaluate("s").asString())
        assertEquals(true, js.evaluate("flag").asBoolean())
        assertEquals("undefined", js.evaluate("typeof nothing").asString())
        assertEquals(3.0, js.evaluate("list.length").asDouble())
        assertEquals(true, js.evaluate("Array.isArray(list)").asBoolean())
        assertEquals("v", js.evaluate("map.k").asString())
    }

    @Test
    fun newObjectAndNewArrayBuildRealScriptValues() = engine { js ->
        val o = js.newObject()
        o["a"] = 1
        js.global["made"] = o
        assertEquals(1.0, js.evaluate("made.a").asDouble())

        val a = js.newArray(1, "two", true)
        js.global["arr"] = a
        assertEquals("1,two,true", js.evaluate("arr.join(',')").asString())
    }

    // ---- Errors -------------------------------------------------------------------------------

    @Test
    fun aThrownErrorArrivesAsJsError() = engine { js ->
        val e = assertFailsWith<JsError> { js.evaluate("throw new TypeError('bad thing')") }
        assertEquals("TypeError", e.name)
        assertEquals("bad thing", e.errorMessage)
        assertEquals("TypeError: bad thing", e.message)
        assertEquals(JsType.OBJECT, e.value.type)
    }

    @Test
    fun aScriptCanThrowSomethingThatIsNotAnError() = engine { js ->
        val e = assertFailsWith<JsError> { js.evaluate("throw 42") }
        assertEquals(JsType.NUMBER, e.value.type)
        assertEquals(42.0, e.value.asDouble())
    }

    @Test
    fun aRuntimeErrorCarriesItsNameAndFrames() = engine { js ->
        val e = assertFailsWith<JsError> { js.evaluate("function boom() { null.x } boom()", "boom.js") }
        assertEquals("TypeError", e.name)
        assertTrue(e.scriptStack.isNotEmpty(), "no frames")
    }

    @Test
    fun badSourceArrivesAsJsSyntaxError() = engine { js ->
        val e = assertFailsWith<JsSyntaxError> { js.evaluate("function (", "broken.js") }
        assertEquals("broken.js", e.fileName)
        assertTrue(e.lineNumber >= 1)
    }

    @Test
    fun readingAValueAsTheWrongThingSaysSo() = engine { js ->
        val e = assertFailsWith<JsError> { js.evaluate("5").asArray() }
        assertContains(e.message ?: "", "expected an array")
    }

    @Test
    fun aClosedEngineRefusesToRun() {
        val js = KiteJs()
        js.close()
        assertFailsWith<JsEngineError> { js.evaluate("1") }
    }

    @Test
    fun twoEnginesAtOnceIsRefusedClearly() = engine { _ ->
        val e = assertFailsWith<JsEngineError> { KiteJs() }
        assertContains(e.message ?: "", "already open")
    }

    // ---- The budget ---------------------------------------------------------------------------

    @Test
    fun aRunawayScriptIsStopped() = engine({ instructionBudget = 100_000 }) { js ->
        val e = assertFailsWith<JsEngineError> { js.evaluate("while (true) {}") }
        assertContains(e.message ?: "", "instructions")
    }

    @Test
    fun theBudgetLeavesNormalScriptsAlone() = engine({ instructionBudget = 5_000_000 }) { js ->
        assertEquals(499500.0, js.evaluate("var t = 0; for (var i = 0; i < 1000; i++) t += i; t").asDouble())
    }

    @Test
    fun eachCallGetsTheBudgetAgain() = engine({ instructionBudget = 500_000 }) { js ->
        repeat(3) {
            assertEquals(4950.0, js.evaluate("var t = 0; for (var i = 0; i < 100; i++) t += i; t").asDouble())
        }
    }

    // ---- Console ------------------------------------------------------------------------------

    @Test
    fun consoleGoesWhereTheEmbedderSaid() {
        val lines = mutableListOf<ConsoleMessage>()
        KiteJs { console = ConsolePrinters.collecting(lines) }.use { js ->
            js.evaluate("console.log('hello', 1, true)")
            js.evaluate("console.warn('careful')")
            js.evaluate("console.error('%s went wrong at %d', 'thing', 5)")
        }
        assertEquals(3, lines.size)
        assertEquals(ConsoleLevel.INFO, lines[0].level)
        assertEquals("hello 1 true", lines[0].text)
        assertEquals(ConsoleLevel.WARN, lines[1].level)
        assertEquals("careful", lines[1].text)
        assertEquals(ConsoleLevel.ERROR, lines[2].level)
        assertEquals("thing went wrong at 5", lines[2].text)
    }

    @Test
    fun consoleCountsAndTimes() {
        val lines = mutableListOf<ConsoleMessage>()
        KiteJs { console = ConsolePrinters.collecting(lines) }.use { js ->
            js.evaluate("console.count('a'); console.count('a'); console.count()")
            js.evaluate("console.time('t'); console.timeEnd('t')")
            js.evaluate("console.assert(false, 'nope')")
        }
        assertEquals("a: 1", lines[0].text)
        assertEquals("a: 2", lines[1].text)
        assertEquals("default: 1", lines[2].text)
        assertTrue(lines[3].text.startsWith("t: "), lines[3].text)
        assertEquals("Assertion failed: nope", lines[4].text)
    }

    @Test
    fun withNoPrinterThereIsNoConsole() = engine { js ->
        assertEquals("undefined", js.evaluate("typeof console").asString())
    }

    // ---- Configuration ------------------------------------------------------------------------

    @Test
    fun aFixedClockMakesDateStable() = engine({ clock = { 1_700_000_000_000.0 }; timeZone = TimeZone.UTC }) { js ->
        assertEquals(1_700_000_000_000.0, js.evaluate("Date.now()").asDouble())
        assertEquals("2023-11-14T22:13:20.000Z", js.evaluate("new Date().toISOString()").asString())
    }

    @Test
    fun theTimeZoneIsTheOneConfigured() = engine({ clock = { 0.0 }; timeZone = TimeZone.of("Europe/Berlin") }) { js ->
        assertEquals(1.0, js.evaluate("new Date(0).getHours()").asDouble())
    }

    @Test
    fun anOlderLanguageVersionLeavesOutTheNewSyntax(): Unit = engine({ languageVersion = LanguageVersion.ES5 }) { js ->
        assertFailsWith<JsSyntaxError> { js.evaluate("class A {}") }
        Unit
    }

    @Test
    fun sealedBuiltinsCannotBeRedefined() = engine({ sealBuiltins = true }) { js ->
        js.evaluate("try { Array.prototype.push = null } catch (e) {}")
        assertEquals("function", js.evaluate("typeof Array.prototype.push").asString())
    }

    // ---- Compiling and microtasks --------------------------------------------------------------

    @Test
    fun aCompiledScriptRunsMoreThanOnce() = engine { js ->
        val script = js.compile("count = (typeof count === 'undefined' ? 0 : count) + 1")
        script.run()
        script.run()
        assertEquals(3.0, script.run().asDouble())
    }

    @Test
    fun promisesSettleByTheTimeEvaluateReturns() = engine { js ->
        js.evaluate("var log = []; Promise.resolve(1).then(function (v) { log.push(v) })")
        assertEquals("1", js.evaluate("log.join()").asString())
    }

    @Test
    fun workQueuedFromAHostCallbackRunsOnDemand() = engine { js ->
        js.evaluate("var seen = []; var resolveIt; var p = new Promise(function (r) { resolveIt = r })")
        js.evaluate("p.then(function (v) { seen.push(v) })")
        assertEquals("", js.evaluate("seen.join()").asString())
        js.evaluate("resolveIt('done')")
        js.runMicrotasks()
        assertEquals("done", js.evaluate("seen.join()").asString())
    }

    @Test
    fun theEngineReportsItsVersion() = engine { js ->
        assertContains(js.version, "KiteJS")
    }
}
