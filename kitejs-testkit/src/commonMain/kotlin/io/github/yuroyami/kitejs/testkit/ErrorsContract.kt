/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.testkit

import io.github.yuroyami.kitejs.api.JsEngine
import io.github.yuroyami.kitejs.api.JsEngineError
import io.github.yuroyami.kitejs.api.JsError
import io.github.yuroyami.kitejs.api.JsSyntaxError
import io.github.yuroyami.kitejs.api.JsType
import io.github.yuroyami.kitejs.api.KiteJsConfig
import io.github.yuroyami.kitejs.api.function
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestResult

/** What reaches the host when something goes wrong, and in what shape. */
public abstract class ErrorsContract<C : KiteJsConfig>(engine: JsEngine<C>) : EngineContract<C>(engine) {

    @Test
    public fun aThrownErrorArrivesAsJsError(): TestResult = withEngine { js ->
        val e = assertFailsWith<JsError> { js.evaluate("throw new TypeError('bad thing')") }
        assertEquals("TypeError", e.name)
        assertEquals("bad thing", e.errorMessage)
        assertEquals("TypeError: bad thing", e.message)
        assertEquals(JsType.OBJECT, e.value.type)
        assertEquals("bad thing", e.value.asObject()["message"].asString())
    }

    @Test
    public fun anErrorTheEngineRaisesIsAnErrorObjectToo(): TestResult = withEngine { js ->
        val e = assertFailsWith<JsError> { js.evaluate("null.x") }
        assertEquals("TypeError", e.name)
        assertEquals(JsType.OBJECT, e.value.type)
        js.global["caught"] = e.value
        assertTrue(js.evaluate("caught instanceof TypeError").asBoolean())
        val r = assertFailsWith<JsError> { js.evaluate("undefinedName") }
        assertEquals("ReferenceError", r.name)
    }

    @Test
    public fun aCustomErrorKeepsItsNameAndValue(): TestResult = withEngine { js ->
        val e = assertFailsWith<JsError> {
            js.evaluate("class MyError extends Error { constructor(m) { super(m); this.name = 'MyError'; this.code = 7 } }; throw new MyError('custom')")
        }
        assertEquals("MyError", e.name)
        assertEquals("custom", e.errorMessage)
        assertEquals(7.0, e.value.asObject()["code"].asDouble())
    }

    @Test
    public fun aScriptCanThrowSomethingThatIsNotAnError(): TestResult = withEngine { js ->
        val e = assertFailsWith<JsError> { js.evaluate("throw 42") }
        assertEquals(JsType.NUMBER, e.value.type)
        assertEquals(42.0, e.value.asDouble())
        assertContains(e.message ?: "", "42")
        val s = assertFailsWith<JsError> { js.evaluate("throw 'plain'") }
        assertEquals("plain", s.value.asString())
        assertContains(s.message ?: "", "plain")
    }

    @Test
    public fun deepRecursionIsARangeErrorTheScriptCanCatch(): TestResult = withEngine({ instructionBudget = 2_000_000 }) { js ->
        val caught = js.evaluate("try { (function f() { return f() + 1 })() } catch (e) { e instanceof RangeError }")
        assertTrue(caught.asBoolean())
        assertEquals(2, js.evaluate("1 + 1").asInt())
    }

    @Test
    public fun aRecursionThroughTheHostEndsInTheRangeError(): TestResult = withEngine { js ->
        js.global.function("viaHost", 1) { args -> args[0].asFunction()() }
        val error = assertFailsWith<JsError> { js.evaluate("function f() { viaHost(f); } f()") }
        assertEquals("RangeError", error.name)
        assertEquals(2, js.evaluate("1 + 1").asInt())
    }

    @Test
    public fun functionDeclarationsNeedNamesBeforeAnythingRuns(): TestResult = withEngine { js ->
        js.global["declarationEffects"] = 0
        for (source in listOf(
            "function () {}", "if (true) function () {}", "{ function () {} }",
            "function outer() { function () {} }", "function* () { yield 1 }", "async function () {}",
            "var y = async\nfunction () {}", "'use strict'; function () {}",
        )) {
            val program = "declarationEffects++; $source"
            assertFailsWith<JsSyntaxError>(source) { js.evaluate(program, "anonymous-declaration.js") }
            assertFailsWith<JsSyntaxError>(source) { js.compile(program, "anonymous-declaration.js") }
            js.global["invalidDeclaration"] = program
            assertEquals("SyntaxError", js.evaluate("try { eval(invalidDeclaration); 'none' } catch (e) { e.name }").asString(), source)
            assertEquals(0, js.global["declarationEffects"].asInt(), source)
        }
        assertEquals(7, js.evaluate("(function (a) { return a + 1 })(6)").asInt())
        assertEquals(4, js.evaluate("(function* () { yield 4 })().next().value").asInt())
        assertEquals("function", js.evaluate("typeof (async function () {})").asString())
        assertEquals(9, js.evaluate("Function('a', 'return a + 2')(7)").asInt())
        assertEquals(3, js.evaluate("({ method() { return 3 } }).method()").asInt())
        assertEquals(2, js.evaluate("function namedDeclaration() { return 2 }; namedDeclaration()").asInt())
    }

    @Test
    public fun aRuntimeErrorCarriesItsFrames(): TestResult = withEngine { js ->
        val e = assertFailsWith<JsError> {
            js.evaluate("function inner() { null.x }\nfunction outer() { inner() }\nouter()", "boom.js")
        }
        assertEquals("TypeError", e.name)
        assertTrue(e.scriptStack.isNotEmpty(), "no frames")
        val top = e.scriptStack.first()
        assertEquals("boom.js", top.fileName)
        assertEquals(1, top.lineNumber)
        assertEquals("inner", top.functionName)
        assertTrue(e.scriptStack.any { it.functionName == "outer" && it.lineNumber == 2 }, "${e.scriptStack}")
    }

    @Test
    public fun everyFacadeEntryTranslatesScriptErrorsAndKeepsTheirFrames(): TestResult = withEngine { js ->
        js.evaluate(
            """
            function failEntry() {
                var e = new TypeError('facade failure'); globalThis.lastThrown = e; throw e;
            }
            var objectEntry = { method: failEntry, get read() { return failEntry(); }, set write(v) { failEntry(); } };
            objectEntry[Symbol.toPrimitive] = failEntry;
            function ConstructorEntry() { failEntry(); }
            var proxyEntry = new Proxy({}, {
                get: failEntry, set: failEntry, has: failEntry, deleteProperty: failEntry, ownKeys: failEntry
            });
            """.trimIndent(), "facade-errors.js",
        )
        val function = js.global["failEntry"].asFunction()
        val bound = function.bind(null)
        val obj = js.global["objectEntry"].asObject()
        val constructor = js.global["ConstructorEntry"].asFunction()
        val proxy = js.global["proxyEntry"].asObject()
        val compiled = js.compile("failEntry()", "compiled-entry.js")
        val entries: List<Pair<String, () -> Any?>> = listOf(
            "evaluate" to { js.evaluate("failEntry()") },
            "compiled script" to { compiled.run() },
            "function" to { function() },
            "bound function" to { bound() },
            "method" to { obj.call("method") },
            "constructor" to { constructor.construct() },
            "getter" to { obj["read"] },
            "setter" to { obj["write"] = 1 },
            "number coercion" to { obj.value.asDouble() },
            "string coercion" to { obj.value.asString() },
            "proxy getter" to { proxy["field"] },
            "proxy setter" to { proxy["field"] = 1 },
            "proxy has" to { proxy.has("field") },
            "proxy delete" to { proxy.delete("field") },
            "proxy keys" to { proxy.keys },
            "toMap" to { obj.toMap() },
            "toKotlin" to { obj.value.toKotlin() },
        )
        for ((entry, invoke) in entries) {
            val error = assertFailsWith<JsError>(entry) { invoke() }
            assertEquals("TypeError", error.name, entry)
            assertEquals("facade failure", error.errorMessage, entry)
            assertEquals(js.global["lastThrown"], error.value, entry)
            val top = error.scriptStack.firstOrNull()
            assertEquals("failEntry", top?.functionName, "$entry: ${error.scriptStack}")
            assertEquals("facade-errors.js", top?.fileName, entry)
            assertEquals(2, top?.lineNumber, entry)
            assertEquals(2, js.evaluate("1 + 1").asInt(), entry)
        }
    }

    @Test
    public fun facadeThrowsKeepTheOriginalNonErrorValue(): TestResult = withEngine { js ->
        js.evaluate(
            """
            function throwAny() { throw thrownValue; }
            var nonErrorEntry = { method: throwAny, get value() { throw thrownValue; } };
            function NonErrorConstructor() { throw thrownValue; }
            """.trimIndent(),
        )
        val function = js.global["throwAny"].asFunction()
        val obj = js.global["nonErrorEntry"].asObject()
        val constructor = js.global["NonErrorConstructor"].asFunction()
        val entries: List<() -> Any?> = listOf(
            { js.evaluate("throw thrownValue") }, { function() }, { obj.call("method") },
            { constructor.construct() }, { obj["value"] },
        )
        for (source in listOf("42", "'plain'", "null", "undefined", "1n", "Symbol('s')", "({ code: 7 })", "[1, 2]")) {
            val value = js.evaluate(source)
            js.global["thrownValue"] = value
            for (invoke in entries) {
                val error = assertFailsWith<JsError>(source) { invoke() }
                assertEquals(value.type, error.value.type, source)
                assertEquals(value, error.value, source)
                assertEquals(2, js.evaluate("1 + 1").asInt(), source)
            }
        }
    }

    @Test
    public fun badSourceArrivesAsJsSyntaxError(): TestResult = withEngine { js ->
        val e = assertFailsWith<JsSyntaxError> { js.evaluate("var ok = 1;\nfunction (", "broken.js") }
        assertEquals("broken.js", e.fileName)
        assertEquals(2, e.lineNumber)
        assertTrue((e.message ?: "").isNotEmpty())
    }

    @Test
    public fun aSyntaxErrorFromEvalIsAScriptError(): TestResult = withEngine { js ->
        // Only the source handed to evaluate is the host's; eval inside it throws a SyntaxError object.
        val e = assertFailsWith<JsError> { js.evaluate("eval('(')") }
        assertEquals("SyntaxError", e.name)
        assertEquals("SyntaxError", js.evaluate("try { eval('(') } catch (e) { e.name }").asString())
    }

    @Test
    public fun readingAValueAsTheWrongThingSaysSo(): TestResult = withEngine { js ->
        val e = assertFailsWith<JsError> { js.evaluate("5").asArray() }
        assertEquals("TypeError", e.name)
        assertContains(e.message ?: "", "expected an array")
        assertFailsWith<JsError> { js.evaluate("'x'").asFunction() }
        assertFailsWith<JsError> { js.evaluate("5").asBigInt() }
        assertNull(js.evaluate("5").asObjectOrNull())
    }

    @Test
    public fun aClosedEngineRefusesToRun(): TestResult = test {
        val js = open()
        js.close()
        val e = assertFailsWith<JsEngineError> { js.evaluate("1") }
        assertContains(e.message ?: "", "closed")
    }

    @Test
    public fun fromReadsWhatItCanOffAnyValue(): TestResult = withEngine { js ->
        fun from(source: String) = JsError.from(js.evaluate(source))

        val plain = from("new RangeError('r')")
        assertEquals("RangeError", plain.name)
        assertEquals("r", plain.errorMessage)

        val badName = from("({ get name() { throw new Error('bad name') }, message: 'm' })")
        assertEquals("Error", badName.name)
        assertEquals("m", badName.errorMessage)

        val badMessage = from("({ name: 'Custom', get message() { throw new Error('bad message') } })")
        assertEquals("Custom", badMessage.name)
        assertEquals("[object Object]", badMessage.errorMessage)

        val badString = from("({ toString: function () { throw new Error('bad string') } })")
        assertEquals("[object Object]", badString.errorMessage)

        assertEquals("Symbol(s)", from("Symbol('s')").errorMessage)

        val revoked = from("var r = Proxy.revocable({}, {}); r.revoke(); r.proxy")
        assertEquals("Error", revoked.name)

        val scalar = from("42")
        assertEquals("42", scalar.errorMessage)
        assertEquals(42.0, scalar.value.asDouble())
        assertNull(scalar.cause)
    }
}
