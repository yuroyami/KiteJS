/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import io.github.yuroyami.kitejs.rhino.CompilerEnvirons
import io.github.yuroyami.kitejs.rhino.Context
import io.github.yuroyami.kitejs.rhino.Parser
import kotlin.test.Test
import kotlin.test.assertEquals

class OptionalChainCallTest {
    private fun check(expected: String, source: String) {
        KiteJs(Rhino).use { js ->
            assertEquals(expected, js.evaluate(source, "optional-chain-call.js").asString())
            assertEquals(2, js.evaluate("1 + 1").asInt())
        }
    }

    @Test
    fun nullishPropertyCallsSkipKeysAndArguments() {
        for (base in listOf("null", "undefined")) for (expression in listOf(
            "base?.m(effect())", "base?.[effect()](effect())",
        )) check("undefined|0", """
            var base = $base, effects = 0;
            function effect() { effects++; return 'm'; }
            var result = $expression;
            typeof result + '|' + effects;
        """.trimIndent())
    }

    @Test
    fun explicitOptionalCallsSkipArguments() {
        for (expression in listOf("null?.(effect())", "undefined?.(effect())", "({ m: null }).m?.(effect())"))
            check("undefined|0", """
                var effects = 0;
                function effect() { effects++; return 1; }
                var result = $expression;
                typeof result + '|' + effects;
            """.trimIndent())
    }

    @Test
    fun computedNullishCallSkipsDistinctKeyAndArgumentEffects() = check("undefined|", """
        var base = null, events = [];
        function key() { events.push('key'); return 'method'; }
        function arg() { events.push('arg'); return 1; }
        var result = base?.[key()](arg());
        typeof result + '|' + events.join();
    """.trimIndent())

    @Test
    fun optionalCallDoesNotSuppressANullishPropertyBase() = check("TypeError|0", """
        var events = 0, name = 'missing';
        function arg() { events++; return 1; }
        try { null.method?.(arg()); } catch (e) { name = e instanceof TypeError ? 'TypeError' : 'other'; }
        name + '|' + events;
    """.trimIndent())

    @Test
    fun shortCircuitSkipsEveryLaterCallAndProperty() = check("undefined|0", """
        var base = null, effects = 0;
        function effect() { effects++; return 'm'; }
        var result = base?.m(effect())[effect()](effect()).last;
        typeof result + '|' + effects;
    """.trimIndent())

    @Test
    fun spreadExpressionsAndIteratorsAreSkipped() {
        for (expression in listOf("base?.m(...effect())", "base?.[effect()](...effect())", "base?.(...effect())"))
            check("undefined|0", """
                var base = null, effects = 0;
                function effect() { effects++; throw {}; }
                var result = $expression;
                typeof result + '|' + effects;
            """.trimIndent())
    }

    @Test
    fun throwingArgumentsAreSkipped() = check("undefined", """
        function fail() { throw {}; }
        typeof null?.method(fail());
    """.trimIndent())

    @Test
    fun nonNullMethodPreservesReceiverAndEvaluationOrder() = check("7|key,get,arg,call:true", """
        var events = [], object = { get method() {
          events.push('get'); return function (x) { events.push('call:' + (this === object)); return x; };
        } };
        function key() { events.push('key'); return 'method'; }
        function arg() { events.push('arg'); return 7; }
        var result = object?.[key()](arg());
        result + '|' + events.join();
    """.trimIndent())

    @Test
    fun nullishMethodInANonNullObjectStillThrows() {
        for (method in listOf("null", "undefined", "3")) check("TypeError|1", """
            var effects = 0, name = 'missing', object = { method: $method };
            function arg() { effects++; return 1; }
            try { object?.method(arg()); } catch (e) { name = e instanceof TypeError ? 'TypeError' : 'other'; }
            name + '|' + effects;
        """.trimIndent())
    }

    @Test
    fun ordinaryNullishResultsDoNotShortCircuitTheChain() {
        for (expression in listOf("({ value: null })?.value.method(arg())", "({ method: function () {} })?.method().method(arg())"))
            check("TypeError|0", """
                var effects = 0, name = 'missing';
                function arg() { effects++; return 1; }
                try { $expression; } catch (e) { name = e instanceof TypeError ? 'TypeError' : 'other'; }
                name + '|' + effects;
            """.trimIndent())
    }

    @Test
    fun parenthesesEndTheOptionalChain() {
        for (expression in listOf("(null?.method)(arg())", "(null?.method)(...args())")) check("TypeError|1", """
            var effects = 0, name = 'missing';
            function arg() { effects++; return 1; }
            function args() { effects++; return [1]; }
            try { $expression; } catch (e) { name = e instanceof TypeError ? 'TypeError' : 'other'; }
            name + '|' + effects;
        """.trimIndent())
    }

    @Test
    fun nestedOptionalArgumentsHaveIndependentShortCircuits() = check("9|outer,call", """
        var events = [], outer = { method: function (x) { events.push('call'); return x === undefined ? 9 : 0; } };
        function arg() { events.push('inner'); return 1; }
        var result = (events.push('outer'), outer)?.method(null?.method(arg()));
        result + '|' + events.join();
    """.trimIndent())

    @Test
    fun parenthesizedNonNullMethodKeepsItsReceiver() = check("true,true", """
        var object = { method: function (x) { return this === object && x === 7; } };
        [(object?.method)(7), (object?.method)(...[7])].join();
    """.trimIndent())

    @Test
    fun undeclaredOptionalCallStillThrowsAReferenceError() = check("ReferenceError|0", """
        var effects = 0, name = 'missing';
        function arg() { effects++; return 1; }
        try { missingName?.(arg()); } catch (e) { name = e.name; }
        name + '|' + effects;
    """.trimIndent())

    @Test
    fun nonNullSpreadCallsKeepTheirReceiverAndArgumentOrder() = check("true|key,spread,iterator,arg,call", """
        var events = [], object = { method: function (a, b, c) {
          events.push('call'); return this === object && a === 1 && b === 2 && c === 3;
        } };
        function key() { events.push('key'); return 'method'; }
        function spread() { events.push('spread'); return { [Symbol.iterator]: function () {
          events.push('iterator'); var i = 0;
          return { next: function () { return ++i <= 2 ? { value: i, done: false } : { done: true }; } };
        } }; }
        function arg() { events.push('arg'); return 3; }
        var result = object?.[key()](...spread(), arg());
        result + '|' + events.join();
    """.trimIndent())

    @Test
    fun continuedComputedAccessesAndSeveralOptionalLinksUseTheSameExit() = check("true|0", """
        var effects = 0, base = null, object = { nested: null };
        function key() { effects++; return 'x'; }
        var results = [base?.x[key()]?.(key()), base?.[key()][key()](key()), object?.nested?.[key()](key())];
        results.every(function (value) { return value === undefined; }) + '|' + effects;
    """.trimIndent())

    @Test
    fun optionalDeletionSkipsKeysAndDeletesReferencesWithoutReadingThem() = check("true,true,true|0|false", """
        var effects = 0, object = { get value() { effects++; throw {}; } };
        function key() { effects++; return 'value'; }
        var results = [delete null?.[key()], delete undefined?.x.y, delete object?.value];
        results.join() + '|' + effects + '|' + ('value' in object);
    """.trimIndent())

    @Test
    fun renderedSourceKeepsExplicitLinksAndParentheses() {
        val env = CompilerEnvirons().also { it.languageVersion = Context.VERSION_ES6 }
        for (source in listOf("a?.b(c);", "a?.[b](c);", "a.b?.(c);", "(a?.b)(c);", "a?.b.c(d);")) {
            assertEquals(source, Parser(env).parse(source, "optional-source.js", 1).toSource().trim())
        }
    }
}
