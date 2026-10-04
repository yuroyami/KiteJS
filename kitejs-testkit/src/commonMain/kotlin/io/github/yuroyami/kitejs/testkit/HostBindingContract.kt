/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.testkit

import io.github.yuroyami.kitejs.api.JsEngine
import io.github.yuroyami.kitejs.api.JsEngineError
import io.github.yuroyami.kitejs.api.JsError
import io.github.yuroyami.kitejs.api.JsType
import io.github.yuroyami.kitejs.api.JsValue
import io.github.yuroyami.kitejs.api.KiteJsConfig
import io.github.yuroyami.kitejs.api.PropertyFlags
import io.github.yuroyami.kitejs.api.accessor
import io.github.yuroyami.kitejs.api.bind
import io.github.yuroyami.kitejs.api.constant
import io.github.yuroyami.kitejs.api.constructor
import io.github.yuroyami.kitejs.api.function
import io.github.yuroyami.kitejs.api.getter
import io.github.yuroyami.kitejs.api.method
import io.github.yuroyami.kitejs.api.obj
import io.github.yuroyami.kitejs.api.property
import io.github.yuroyami.kitejs.api.setter
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestResult

/** What the host puts in front of a script: functions, properties, constructors, bound objects. */
public abstract class HostBindingContract<C : KiteJsConfig>(engine: JsEngine<C>) : EngineContract<C>(engine) {

    @Test
    public fun aHostFunctionIsCallableFromScript(): TestResult = withEngine { js ->
        val shout = js.global.function("shout") { args -> args.first().asString().uppercase() }
        assertEquals("HI", js.evaluate("shout('hi')").asString())
        assertEquals("function", js.evaluate("typeof shout").asString())
        assertEquals("shout", js.evaluate("shout.name").asString())
        // Host functions are not enumerable, as built-ins are not.
        assertEquals("false", js.evaluate("Object.keys(globalThis).indexOf('shout') >= 0").asString())
        // And it can be called from Kotlin too.
        assertEquals("YO", shout("yo").asString())
    }

    @Test
    public fun aHostFunctionGetsTheArgumentsAsTheyCame(): TestResult = withEngine { js ->
        var seen: List<JsValue> = emptyList()
        js.global.function("probe", 3) { args -> seen = args; args.size }
        assertEquals(3.0, js.evaluate("probe.length").asDouble())
        assertEquals(4.0, js.evaluate("probe(1, 'two', null, undefined)").asDouble())
        assertEquals(listOf(JsType.NUMBER, JsType.STRING, JsType.NULL, JsType.UNDEFINED), seen.map { it.type })
        assertEquals(0.0, js.evaluate("probe()").asDouble())
    }

    @Test
    public fun whatAHostFunctionAnswersIsConverted(): TestResult = withEngine { js ->
        js.global.function("answer") { args ->
            when (args.first().asString()) {
                "unit" -> Unit
                "null" -> null
                "list" -> listOf(1, "a")
                "map" -> mapOf("k" to listOf(true))
                "long" -> Long.MAX_VALUE
                "same" -> args[1]
                else -> args.first().asString().length
            }
        }
        assertEquals("undefined", js.evaluate("typeof answer('unit')").asString())
        assertEquals("undefined", js.evaluate("typeof answer('null')").asString())
        assertEquals("1,a", js.evaluate("answer('list').join()").asString())
        assertEquals(true, js.evaluate("answer('map').k[0]").asBoolean())
        assertEquals("bigint", js.evaluate("typeof answer('long')").asString())
        assertEquals(true, js.evaluate("var o = {}; answer('same', o) === o").asBoolean())
        assertEquals(5.0, js.evaluate("answer('fives')").asDouble())
    }

    @Test
    public fun typedHostFunctionsConvertBothWays(): TestResult = withEngine { js ->
        js.global.function<Double, Double, Double>("hypot") { a, b -> sqrt(a * a + b * b) }
        js.global.function<String, Int>("len") { s -> s.length }
        js.global.function<List<Any?>, Double>("total") { xs -> xs.sumOf { it as Double } }
        js.global.function<Int, Int, Int, Int>("sum3") { a, b, c -> a + b + c }
        assertEquals(5.0, js.evaluate("hypot(3, 4)").asDouble())
        assertEquals(3.0, js.evaluate("len('abc')").asDouble())
        assertEquals(6.0, js.evaluate("total([1, 2, 3])").asDouble())
        assertEquals(6.0, js.evaluate("sum3(1, 2, 3)").asDouble())
    }

    @Test
    public fun aHostMethodSeesTheThisItWasCalledOn(): TestResult = withEngine { js ->
        js.global.method("describe") { self, _ -> "n=" + self.asObject()["n"].asString() }
        assertEquals("n=4", js.evaluate("var o = { n: 4, d: describe }; o.d()").asString())
        assertEquals("n=5", js.evaluate("describe.call({ n: 5 })").asString())
    }

    @Test
    public fun propertiesGettersAndSettersWork(): TestResult = withEngine { js ->
        var stored = "start"
        var written: JsValue = JsValue.undefined
        js.global.obj("document") {
            property("title", "Untitled")
            getter("readyState") { "complete" }
            setter("sink") { v -> written = v }
            accessor("body", read = { stored }, write = { v -> stored = v.asString() })
            function("getElementById") { args -> mapOf("id" to args.first().asString()) }
        }
        assertEquals("Untitled", js.evaluate("document.title").asString())
        assertEquals("complete", js.evaluate("document.readyState").asString())
        assertEquals("start", js.evaluate("document.body").asString())
        js.evaluate("document.body = 'changed'")
        assertEquals("changed", stored)
        js.evaluate("document.sink = 42")
        assertEquals(42.0, written.asDouble())
        assertEquals("undefined", js.evaluate("typeof document.sink").asString())
        assertEquals("x1", js.evaluate("document.getElementById('x1').id").asString())
    }

    @Test
    public fun propertyFlagsAreTheDescriptor(): TestResult = withEngine { js ->
        val o = js.newObject()
        o.property("hidden", 1, PropertyFlags(writable = false, enumerable = false, configurable = false))
        js.global["o"] = o
        assertEquals(
            "false,false,false",
            js.evaluate("var d = Object.getOwnPropertyDescriptor(o, 'hidden'); [d.writable, d.enumerable, d.configurable].join()").asString(),
        )
        assertEquals("", js.evaluate("Object.keys(o).join()").asString())
    }

    @Test
    public fun aConstantCannotBeWrittenOver(): TestResult = withEngine { js ->
        js.global.constant("VERSION", "1.0")
        js.evaluate("VERSION = 'nope'")
        assertEquals("1.0", js.evaluate("VERSION").asString())
        assertFailsWith<JsError> { js.evaluate("'use strict'; VERSION = 'nope'") }
    }

    @Test
    public fun aHostConstructorWorksWithNew(): TestResult = withEngine { js ->
        js.global.constructor("Point", 2) { obj, args ->
            obj["x"] = args.getOrElse(0) { JsValue.undefined }.asDouble()
            obj["y"] = args.getOrElse(1) { JsValue.undefined }.asDouble()
        }
        assertEquals(7.0, js.evaluate("new Point(3, 4).x + new Point(0, 0).x + 4").asDouble())
        assertEquals(4.0, js.evaluate("new Point(3, 4).y").asDouble())
        assertEquals("function", js.evaluate("typeof Point").asString())
    }

    @Test
    public fun aKotlinObjectBindsByItsMembers(): TestResult = withEngine { js ->
        val counter = Counter()
        js.global.bind("counter", counter) {
            property("value", Counter::value)
            property("label", Counter::label)
            method("bump") { by -> value += (by.firstOrNull() ?: JsValue.undefined).asInt(); value }
        }
        assertEquals(0.0, js.evaluate("counter.value").asDouble())
        assertEquals(5.0, js.evaluate("counter.bump(5)").asDouble())
        assertEquals(5, counter.value)
        assertEquals(5.0, js.evaluate("counter.value").asDouble())
        js.evaluate("counter.label = 'renamed'")
        assertEquals("renamed", counter.label)
    }

    // ---- Exceptions from the host ---------------------------------------------------------------

    @Test
    public fun aHostExceptionIsNotCatchableByScript(): TestResult = withEngine { js ->
        js.global.function("fail") { throw IllegalStateException("host broke") }
        val e = assertFailsWith<JsEngineError> { js.evaluate("try { fail() } catch (e) { 'caught' }") }
        assertTrue(e.cause is IllegalStateException, "${e.cause}")
        // The engine carries on.
        assertEquals(2.0, js.evaluate("1 + 1").asDouble())
    }

    @Test
    public fun aJsExceptionFromTheHostComesOutAsItself(): TestResult = withEngine { js ->
        val thrown = JsError(JsValue.of("why"), "HostError", "said no", emptyList(), null)
        js.global.function("refuse") { throw thrown }
        val e = assertFailsWith<JsError> { js.evaluate("try { refuse() } catch (e) { 'caught' }") }
        assertSame(thrown, e)
    }

    @Test
    public fun aScriptErrorInsideAHostCallbackReachesTheHost(): TestResult = withEngine { js ->
        var seen: JsError? = null
        js.global.function("callBack") { args ->
            try {
                args.first().asFunction()()
            } catch (e: JsError) {
                seen = e
                "recovered"
            }
        }
        assertEquals("recovered", js.evaluate("callBack(function () { throw new RangeError('inner') })").asString())
        assertEquals("RangeError", seen?.name)
        assertEquals("inner", seen?.errorMessage)
    }

    @Test
    public fun aHostCallbackCanCallBackIntoTheEngine(): TestResult = withEngine { js ->
        js.global.function("twice") { args -> args.first().asFunction()(21).asDouble() * 2 }
        js.global.function("evalInside") { args -> js.evaluate(args.first().asString()) }
        assertEquals(84.0, js.evaluate("twice(function (x) { return x * 2 })").asDouble())
        assertEquals(3.0, js.evaluate("evalInside('1 + 2')").asDouble())
    }

    private class Counter {
        var value: Int = 0
        var label: Any? = "counter"
    }
}
