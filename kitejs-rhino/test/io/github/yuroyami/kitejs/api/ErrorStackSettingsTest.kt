/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Context
import io.github.yuroyami.kitejs.rhino.NativeObject
import io.github.yuroyami.kitejs.rhino.Rhino
import io.github.yuroyami.kitejs.rhino.ScriptRuntime
import io.github.yuroyami.kitejs.rhino.ScriptableObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** Shared stack settings belong to the error's realm, independently of its mutable prototype. */
class ErrorStackSettingsTest {
    private fun check(expected: String, source: String) {
        KiteJs(Rhino).use { js ->
            assertEquals(expected, js.evaluate(source, "error-stack-settings.js").asString())
            assertEquals(2, js.evaluate("1 + 1").asInt())
        }
    }

    @Test
    fun subclassAndChangedPrototypeStacksRemainReadable() = check("string,string,string,string", """
        class E extends Error {}
        var plain = new Error('plain'); Object.setPrototypeOf(plain, {});
        var bare = new Error('bare'); Object.setPrototypeOf(bare, null);
        function NewTarget() {} NewTarget.prototype = Object.create(Error.prototype);
        [typeof new E('subclass').stack, typeof plain.stack, typeof bare.stack,
         typeof Reflect.construct(Error, ['custom'], NewTarget).stack].join();
    """.trimIndent())

    @Test
    fun formattingAppliesToBuiltInAndEngineErrors() = check(
        "true|Error,TypeError,RangeError,ReferenceError,SyntaxError,EvalError,URIError,AggregateError,TypeError,ReferenceError,SyntaxError",
        """
            var calls = [], results = [];
            Error.prepareStackTrace = function (e, frames) { calls.push(e.name); return 'custom'; };
            [Error, TypeError, RangeError, ReferenceError, SyntaxError, EvalError, URIError].forEach(function (Ctor) {
              results.push(new Ctor('message').stack);
            });
            results.push(new AggregateError([], 'message').stack);
            try { null.x; } catch (e) { results.push(e.stack); }
            try { missingStackSettingsName; } catch (e) { results.push(e.stack); }
            try { eval('('); } catch (e) { results.push(e.stack); }
            results.every(function (value) { return value === 'custom'; }) + '|' + calls.join();
        """.trimIndent(),
    )

    @Test
    fun theSameLimitAppliesToEveryErrorKind() {
        for (limit in listOf(0, 1, 2)) check("$limit,$limit,$limit,$limit", """
            Error.stackTraceLimit = $limit;
            Error.prepareStackTrace = function (e, frames) { return frames.length; };
            function deep(n, create) { if (n) return deep(n - 1, create); return create(); }
            function engineError() { try { null.x; } catch (e) { return e; } }
            [deep(4, function () { return new Error('base'); }).stack,
             deep(4, function () { return new TypeError('typed'); }).stack,
             deep(4, function () { return new RangeError('range'); }).stack,
             deep(4, engineError).stack].join();
        """.trimIndent())
    }

    @Test
    fun arbitraryFormatterResultsAreCachedOnce() {
        for (ctor in listOf("Error", "TypeError")) for (value in listOf("null", "undefined", "false", "0", "''", "{}")) {
            check("true,true,1", """
                var calls = 0, value = $value;
                Error.prepareStackTrace = function () { calls++; return value; };
                var e = new $ctor('message');
                [e.stack === value, e.stack === value, calls].join();
            """.trimIndent())
        }
    }

    @Test
    fun explicitStackAssignmentsRetainEveryValue() {
        for (value in listOf("null", "undefined", "false", "0", "''", "{}")) check("true,true,0", """
            var calls = 0, value = $value;
            Error.prepareStackTrace = function () { calls++; return 'unexpected'; };
            var e = new TypeError('message'); e.stack = value;
            [e.stack === value, e.stack === value, calls].join();
        """.trimIndent())
    }

    @Test
    fun formattingErrorsStayCatchableAndALaterReadCanSucceed() = check("true,true,2", """
        var token = {}, calls = 0, caught = false, value = {};
        Error.prepareStackTrace = function () { calls++; throw token; };
        var e = new TypeError('message');
        try { e.stack; } catch (x) { caught = x === token; }
        Error.prepareStackTrace = function () { calls++; return value; };
        [caught, e.stack === value, calls].join();
    """.trimIndent())

    @Test
    fun cachedSettingsSurviveGlobalAndPrototypeReplacement() = check("custom|1", """
        var Original = Error, calls = 0;
        Original.prepareStackTrace = function () { calls++; return 'custom'; };
        var e = new TypeError('message'); Object.setPrototypeOf(e, null);
        Error = function () { throw 'replacement'; };
        e.stack + '|' + calls;
    """.trimIndent())

    @Test
    fun anotherRealmReadsTheOwnersStackSettings() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val owner = cx.initStandardObjects(NativeObject(), false)
            val reader = cx.initStandardObjects(NativeObject(), false)
            val error = cx.evaluateString(owner, """
                Error.stackTraceLimit = 2;
                Error.prepareStackTrace = function (e, frames) { return 'owner|' + frames.length; };
                function deep(n) { if (n) return deep(n - 1); return new TypeError('message'); }
                deep(4);
            """.trimIndent(), "owner.js", 1, null)
            ScriptableObject.putProperty(reader, "foreignError", error)
            val result = cx.evaluateString(reader, """
                Error.stackTraceLimit = 1;
                Error.prepareStackTrace = function () { return 'reader'; };
                Object.setPrototypeOf(foreignError, null);
                foreignError.stack;
            """.trimIndent(), "reader.js", 1, null)
            assertEquals("owner|2", ScriptRuntime.toString(result))
        } finally {
            Context.exit()
        }
    }
}
