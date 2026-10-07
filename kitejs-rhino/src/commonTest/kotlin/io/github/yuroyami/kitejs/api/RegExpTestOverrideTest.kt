/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Context
import io.github.yuroyami.kitejs.rhino.IdFunctionCall
import io.github.yuroyami.kitejs.rhino.IdFunctionObject
import io.github.yuroyami.kitejs.rhino.Rhino
import io.github.yuroyami.kitejs.rhino.Scriptable
import io.github.yuroyami.kitejs.rhino.ScriptableObject
import kotlin.test.Test
import kotlin.test.assertEquals

class RegExpTestOverrideTest {
    private fun check(expected: String, source: String) {
        KiteJs(Rhino).use { js ->
            assertEquals(expected, js.evaluate(source, "regexp-test-override.js").asString())
            assertEquals(2, js.evaluate("1 + 1").asInt())
        }
    }

    @Test
    fun instanceSubclassAndPrototypeOverridesAreCalled() = check("false,false,false|3", """
        var calls = 0, re = /x/;
        re.exec = function () { calls++; return null; };
        class R extends RegExp { exec() { calls++; return null; } }
        var a = re.test('x'), b = new R('x').test('x');
        RegExp.prototype.exec = function () { calls++; return null; };
        [a, b, /x/.test('x')].join() + '|' + calls;
    """.trimIndent())

    @Test
    fun genericReceiversAcceptAnyObjectResult() = check("true|true", """
        var receiver = { exec: function () { return {}; } };
        var a = RegExp.prototype.test.call(receiver, 'x');
        receiver.exec = function () { return []; };
        a + '|' + RegExp.prototype.test.call(receiver, 'x');
    """.trimIndent())

    @Test
    fun stringConversionPrecedesASingleExecLookupAndCall() = check("false|convert,get,call:true:string:1", """
        var events = [], value = { toString: function () { events.push('convert'); return 'text'; } };
        var receiver = { get exec() {
          events.push('get');
          return function (s) { events.push('call:' + (this === receiver) + ':' + typeof s + ':' + arguments.length); return null; };
        } };
        RegExp.prototype.test.call(receiver, value) + '|' + events.join();
    """.trimIndent())

    @Test
    fun primitiveExecResultsThrowCatchableTypeErrors() = check("true", """
        var values = [undefined, false, true, 0, 1, '', 'x', Symbol('s'), 1n];
        String(values.every(function (value) {
          var re = /x/; re.exec = function () { return value; };
          try { re.test('x'); return false; } catch (e) { return e instanceof TypeError; }
        }));
    """.trimIndent())

    @Test
    fun symbolMethodsShareTheSameExecResultValidation() = check("true", """
        var methods = [Symbol.match, Symbol.search, Symbol.replace];
        String(methods.every(function (key) {
          var re = /x/; re.exec = function () { return 0; };
          try { RegExp.prototype[key].call(re, 'x', 'y'); return false; }
          catch (e) { return e instanceof TypeError; }
        }));
    """.trimIndent())

    @Test
    fun builtinAndNoncallableExecPreserveLastIndex() = check("true:2,false:0,true:2,false:0", """
        var out = [], re = /x/g;
        out.push(re.test('ax') + ':' + re.lastIndex, re.test('ax') + ':' + re.lastIndex);
        re.exec = 0;
        out.push(re.test('ax') + ':' + re.lastIndex, re.test('ax') + ':' + re.lastIndex);
        out.join();
    """.trimIndent())

    @Test
    fun originalExecGetterRunsOnceAfterStringConversion() = check("true|2|convert,get", """
        var events = [], re = /x/g, original = RegExp.prototype.exec;
        Object.defineProperty(re, 'exec', { get: function () { events.push('get'); return original; } });
        var value = { toString: function () { events.push('convert'); re.lastIndex = 1; return 'xx'; } };
        re.test(value) + '|' + re.lastIndex + '|' + events.join();
    """.trimIndent())

    @Test
    fun conversionGetterAndCallExceptionsPreserveThrownValues() = check("true,true,true", """
        var token = {}, out = [];
        try { /x/.test({ toString: function () { throw token; } }); } catch (e) { out.push(e === token); }
        var re = /x/;
        Object.defineProperty(re, 'exec', { configurable: true, get: function () { throw token; } });
        try { re.test('x'); } catch (e) { out.push(e === token); }
        Object.defineProperty(re, 'exec', { value: function () { throw token; } });
        try { re.test('x'); } catch (e) { out.push(e === token); }
        out.join();
    """.trimIndent())

    @Test
    fun hostFunctionsWithBuiltinMetadataAreStillCalled() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            val regexp = cx.evaluateString(scope, "/x/", "host.js", 1, null) as Scriptable
            val original = ScriptableObject.getProperty(regexp, "exec") as IdFunctionObject
            var calls = 0
            val master = object : IdFunctionCall {
                override fun execIdCall(
                    f: IdFunctionObject, cx: Context, scope: Scriptable,
                    thisObj: Scriptable?, args: Array<Any?>,
                ): Any? { calls++; return null }
            }
            val replacement = IdFunctionObject(master, original.tag, original.methodId(), "exec", 1, scope)
            ScriptableObject.putProperty(regexp, "exec", replacement)
            ScriptableObject.putProperty(scope, "regexp", regexp)
            assertEquals(false, cx.evaluateString(scope, "regexp.test('x')", "host.js", 1, null))
            assertEquals(1, calls)
        } finally {
            Context.exit()
        }
    }

    @Test
    fun builtinMetadataOnAHostSubclassDoesNotBypassItsCall() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            val regexp = cx.evaluateString(scope, "/x/", "subclass.js", 1, null) as Scriptable
            val original = ScriptableObject.getProperty(regexp, "exec") as IdFunctionObject
            var calls = 0
            val master = object : IdFunctionCall {
                override fun execIdCall(
                    f: IdFunctionObject, cx: Context, scope: Scriptable,
                    thisObj: Scriptable?, args: Array<Any?>,
                ): Any? = throw AssertionError("replacement call was bypassed")
            }
            val replacement = object : IdFunctionObject(master, original.tag, original.methodId(), "exec", 1, scope) {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
                    calls++
                    return null
                }
            }
            ScriptableObject.putProperty(regexp, "exec", replacement)
            ScriptableObject.putProperty(scope, "regexp", regexp)
            assertEquals(false, cx.evaluateString(scope, "regexp.test('x')", "subclass.js", 1, null))
            assertEquals(1, calls)
        } finally {
            Context.exit()
        }
    }
}
