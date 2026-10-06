/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.testkit

import io.github.yuroyami.kitejs.api.JsEngine
import io.github.yuroyami.kitejs.api.JsEngineError
import io.github.yuroyami.kitejs.api.JsError
import io.github.yuroyami.kitejs.api.JsType
import io.github.yuroyami.kitejs.api.KiteJsConfig
import io.github.yuroyami.kitejs.api.function
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestResult

/**
 * Objects, arrays, functions and scripts seen from Kotlin, and who owns them: a handle works only
 * while the engine that made it is open, and cannot be handed to another engine. The cases on
 * other threads need threads, so each engine keeps those in its own JVM tests.
 */
public abstract class HandlesContract<C : KiteJsConfig>(engine: JsEngine<C>) : EngineContract<C>(engine) {

    @Test
    public fun objectsReadAndWriteThrough(): TestResult = withEngine { js ->
        val o = js.evaluate("({ a: 1, b: 'two' })").asObject()
        assertEquals(1.0, o["a"].asDouble())
        assertEquals("two", o["b"].asString())
        assertEquals(JsType.UNDEFINED, o["missing"].type)
        assertEquals(listOf("a", "b"), o.keys)
        o["c"] = 3
        assertEquals(3.0, o["c"].asDouble())
        js.global["probe"] = o
        assertEquals("a,b,c", js.evaluate("Object.keys(probe).join()").asString())
        assertTrue(o.has("c"))
        o.delete("c")
        assertFalse(o.has("c"))
        // `has` is the `in` operator, so it sees inherited names too.
        assertTrue(o.has("toString"))
    }

    @Test
    public fun keysAreTheOwnEnumerableOnes(): TestResult = withEngine { js ->
        val o = js.evaluate(
            "var o = Object.create({ inherited: 1 }); o.own = 2; o[Symbol('s')] = 3;" +
                " Object.defineProperty(o, 'hidden', { value: 4, enumerable: false }); o",
        ).asObject()
        assertEquals(listOf("own"), o.keys)
    }

    @Test
    public fun accessorsAndProxiesRunWhenReadThroughAHandle(): TestResult = withEngine { js ->
        val o = js.evaluate("var reads = 0; ({ get n() { reads++; return 9 } })").asObject()
        assertEquals(9.0, o["n"].asDouble())
        assertEquals(1.0, js.evaluate("reads").asDouble())
        val p = js.evaluate("new Proxy({}, { get: function (t, k) { return 'trap:' + String(k) } })").asObject()
        assertEquals("trap:x", p["x"].asString())
    }

    @Test
    public fun arraysBehaveLikeLists(): TestResult = withEngine { js ->
        val a = js.evaluate("[1, 2, 3]").asArray()
        assertEquals(3, a.size)
        assertEquals(2.0, a[1].asDouble())
        a[1] = 20
        assertEquals(listOf(1.0, 20.0, 3.0), a.toList())
        a.add(4)
        assertEquals(4, a.size)
        assertEquals(listOf(1.0, 20.0, 3.0, 4.0), a.toList())
        assertEquals(listOf(1.0, 20.0, 3.0, 4.0), a.values().map { it.asDouble() })
        assertEquals(JsType.UNDEFINED, a[10].type)
    }

    @Test
    public fun functionsCallConstructAndBind(): TestResult = withEngine { js ->
        val add = js.evaluate("(function (a, b) { return a + b })").asFunction()
        assertEquals(3.0, add(1, 2).asDouble())
        assertEquals(2, add.arity)

        val addFive = add.bind(null, 5)
        assertEquals(9.0, addFive(4).asDouble())
        assertEquals(1, addFive.arity)

        val greet = js.evaluate("(function () { return 'hi ' + this.name })").asFunction()
        val who = js.evaluate("({ name: 'ada' })").asObject()
        assertEquals("hi ada", greet.callOn(who).asString())
        assertEquals("hi bob", greet.bind(js.valueOf(mapOf("name" to "bob")))().asString())

        val point = js.evaluate("(function Point(x) { this.x = x })").asFunction()
        val made = point.construct(7)
        assertEquals(7.0, made["x"].asDouble())
        js.global["made"] = made
        js.global["Point"] = point
        assertTrue(js.evaluate("made instanceof Point").asBoolean())

        val klass = js.evaluate("(class Box { constructor(v) { this.v = v } get twice() { return this.v * 2 } })").asFunction()
        assertEquals(6.0, klass.construct(3)["twice"].asDouble())
        assertFailsWith<JsError> { klass() }
    }

    @Test
    public fun aFunctionCalledWithNoThisSeesWhatAScriptCallWould(): TestResult = withEngine { js ->
        val sloppy = js.evaluate("(function () { return this === globalThis })").asFunction()
        val strict = js.evaluate("(function () { 'use strict'; return this === undefined })").asFunction()
        assertTrue(sloppy().asBoolean())
        assertTrue(strict().asBoolean())
        // A primitive `this` reaches the function as `f.call(5)` hands it over in that engine.
        val kind = js.evaluate("var kind = function () { 'use strict'; return typeof this }; kind").asFunction()
        assertEquals(js.evaluate("kind.call(5)").asString(), kind.callOn(5).asString())
    }

    @Test
    public fun somethingThatIsNotAConstructorRefusesNew(): TestResult = withEngine { js ->
        val arrow = js.evaluate("(() => 1)").asFunction()
        val e = assertFailsWith<JsError> { arrow.construct() }
        assertEquals("TypeError", e.name)
    }

    @Test
    public fun aMethodCanBeCalledByName(): TestResult = withEngine { js ->
        val o = js.evaluate("({ n: 3, twice: function () { return this.n * 2 } })").asObject()
        assertEquals(6.0, o.call("twice").asDouble())
        assertEquals("1,2", js.evaluate("[2, 1]").asObject().call("sort").asString())
        val e = assertFailsWith<JsError> { o.call("nope") }
        assertEquals("TypeError", e.name)
    }

    @Test
    public fun aCompiledScriptRunsMoreThanOnce(): TestResult = withEngine { js ->
        val script = js.compile("count = (typeof count === 'undefined' ? 0 : count) + 1")
        script.run()
        script.run()
        assertEquals(3.0, script.run().asDouble())
    }

    @Test
    public fun compilingReportsBadSourceWithoutRunningAnything(): TestResult = withEngine { js ->
        assertFailsWith<io.github.yuroyami.kitejs.api.JsSyntaxError> { js.compile("ran = true; (", "bad.js") }
        assertEquals("undefined", js.evaluate("typeof ran").asString())
    }

    @Test
    public fun bytecodeRunsInAnotherEngineWithoutTheSource(): TestResult = test {
        val source = "var greet = function (n) { return 'héllo ' + n; };\n" +
            "function fail() { throw new Error('in a loaded script'); }\n" +
            "count = (typeof count === 'undefined' ? 0 : count) + 1;\n" +
            "greet('wörld') + ' ' + count"
        val bytes = open().use { first -> first.compile(source, "made.js").bytecode() }
        if (bytes == null) {
            // An engine without bytecode says so instead of loading anything.
            open().use { js -> assertFailsWith<JsEngineError> { js.loadBytecode(byteArrayOf(1, 2, 3)) } }
            return@test
        }
        open().use { js ->
            val script = js.loadBytecode(bytes)
            assertEquals("héllo wörld 1", script.run().asString())
            assertEquals("héllo wörld 2", script.run().asString())
            val e = assertFailsWith<JsError> { js.evaluate("fail()") }
            assertEquals("in a loaded script", e.errorMessage)
            // The bytecode keeps the file name and lines, so a stack still points at the source.
            assertTrue(e.scriptStack.any { it.fileName == "made.js" && it.lineNumber == 2 }, e.scriptStack.toString())
            // A loaded script writes the same bytecode again.
            assertEquals(bytes.size, script.bytecode()!!.size)
        }
    }

    @Test
    public fun bytesThatAreNotBytecodeAreRefused(): TestResult = withEngine { js ->
        assertFailsWith<JsEngineError> { js.loadBytecode(ByteArray(0)) }
        val bytes = js.compile("40 + 2").bytecode() ?: return@withEngine
        assertFailsWith<JsEngineError> { js.loadBytecode(bytes.copyOf(bytes.size / 2)) }
        // A value that is not a script, here a string, is refused as well.
        assertFailsWith<JsEngineError> { js.loadBytecode(byteArrayOf(2, 0)) }
        assertEquals(42.0, js.loadBytecode(bytes).run().asDouble())
    }

    // ---- Ownership ------------------------------------------------------------------------------

    @Test
    public fun handlesFromAClosedEngineRefuseToRun(): TestResult = test {
        val first = open()
        val f = first.evaluate("(function () { return 9 })").asFunction()
        val o = first.evaluate("({ n: 1 })").asObject()
        val a = first.evaluate("[1, 2]").asArray()
        val boxed = first.evaluate("({ valueOf: function () { return 4 } })")
        val script = first.compile("1")
        val global = first.global
        first.close()

        assertFailsWith<JsEngineError> { o["n"] }
        assertFailsWith<JsEngineError> { o["n"] = 2 }
        assertFailsWith<JsEngineError> { o.has("n") }
        assertFailsWith<JsEngineError> { o.keys }
        assertFailsWith<JsEngineError> { o.toMap() }
        assertFailsWith<JsEngineError> { o.call("toString") }
        assertFailsWith<JsEngineError> { f() }
        assertFailsWith<JsEngineError> { f.arity }
        assertFailsWith<JsEngineError> { f.construct() }
        assertFailsWith<JsEngineError> { f.bind(null) }
        assertFailsWith<JsEngineError> { a.size }
        assertFailsWith<JsEngineError> { boxed.asDouble() }
        assertFailsWith<JsEngineError> { script.run() }
        assertFailsWith<JsEngineError> { global["x"] }
        assertFailsWith<JsEngineError> { o.function("late") { 0 } }

        // A second engine does not bring them back.
        open().use {
            assertFailsWith<JsEngineError> { f() }
            assertFailsWith<JsEngineError> { o["n"] = 2 }
        }
    }

    @Test
    public fun aRefusedHandleRunsNoneOfItsScript(): TestResult = test {
        val first = open()
        val o = first.evaluate("var hits = 0; ({ get n() { hits++; return 1 }, toString: function () { hits++; return 'x' } })").asObject()
        first.close()
        assertFailsWith<JsEngineError> { o["n"] }
        // toString never throws, and where the engine is gone it prints the class without calling anything.
        assertEquals("[object Object]", o.toString())
        assertEquals("[object Object]", o.value.toString())
    }

    @Test
    public fun aRevokedProxyStillPrintsWhereItsEngineIsGone(): TestResult = test {
        val first = open()
        val revoked = first.evaluate("var r = Proxy.revocable({}, {}); r.revoke(); r.proxy")
        first.close()
        assertEquals("[object Object]", revoked.toString())
    }

    @Test
    public fun scalarsOutliveTheirEngine(): TestResult = test {
        val first = open()
        val n = first.evaluate("6 * 7")
        val s = first.evaluate("'kite'")
        val b = first.evaluate("true")
        val big = first.evaluate("2n ** 70n")
        first.close()
        assertEquals(42.0, n.asDouble())
        assertEquals("kite", s.asString())
        assertEquals(true, b.asBoolean())
        assertEquals("42", n.toString())
        assertEquals("1180591620717411303424", big.asBigInt().toString())
    }

    @Test
    public fun aHandleCannotMoveToAnotherEngine(): TestResult = test {
        val first = open()
        val o = first.evaluate("({ n: 1 })").asObject()
        val s = first.evaluate("Symbol('mine')")
        first.close()
        open().use { second ->
            assertFailsWith<JsEngineError> { second.global["borrowed"] = o }
            assertFailsWith<JsEngineError> { second.global["borrowed"] = s }
            assertFailsWith<JsEngineError> { second.valueOf(listOf(o)) }
            assertFailsWith<JsEngineError> { second.newArray(o.value) }
            assertEquals("undefined", second.evaluate("typeof borrowed").asString())
        }
    }

    @Test
    public fun aClosedEngineRefusesNewValuesAndClosesOnce(): TestResult = test {
        val js = open()
        js.close()
        assertFailsWith<JsEngineError> { js.newObject() }
        assertFailsWith<JsEngineError> { js.newArray(1, 2) }
        assertFailsWith<JsEngineError> { js.valueOf(mapOf("a" to 1)) }
        assertFailsWith<JsEngineError> { js.evaluate("1") }
        assertFailsWith<JsEngineError> { js.compile("1") }
        assertFailsWith<JsEngineError> { js.runMicrotasks() }
        js.close()
        // The thread is free for the next engine.
        open().use { assertEquals(2.0, it.evaluate("1 + 1").asDouble()) }
    }

    @Test
    public fun anEngineCannotCloseWhileItRunsAScript(): TestResult = withEngine { js ->
        js.global.function("closeMe") { js.close(); null }
        assertFailsWith<JsEngineError> { js.evaluate("closeMe()") }
        assertEquals(2.0, js.evaluate("1 + 1").asDouble())
    }

    @Test
    public fun handlesWorkWhileTheirEngineIsOpen(): TestResult = withEngine { js ->
        val o = js.evaluate("({ n: 1 })").asObject()
        o["n"] = 2
        js.global["kept"] = o
        assertEquals(2.0, js.evaluate("kept.n").asDouble())
        assertTrue(o.has("n"))
        assertEquals(listOf("n"), o.keys)
        assertTrue(o.isUsableHere())
    }
}
