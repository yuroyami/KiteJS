/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GlobalVarDeclarationTest {
    private fun check(expected: String, source: String) {
        KiteJs(Rhino).use { js ->
            assertEquals(expected, js.evaluate(source, "global-var.js").asString())
            assertEquals(2, js.evaluate("1 + 1").asInt())
        }
    }

    @Test
    fun readonlyGlobalsAllowVarDeclarationsAndIgnoreSloppyInitializers() = check("undefined|NaN|Infinity", """
        var undefined; var NaN; var Infinity = 5;
        [typeof undefined, String(NaN), Infinity].join('|');
    """.trimIndent())

    @Test
    fun directEvalAllowsTheSameVarDeclarations() = check("undefined|NaN|Infinity", """
        eval('var undefined;'); eval('var NaN;'); eval('var Infinity = 5;');
        [typeof undefined, String(NaN), Infinity].join('|');
    """.trimIndent())

    @Test
    fun strictVarDeclarationsAreAllowedButReadonlyWritesStillThrow() = check("undefined|NaN|Infinity|true", """
        'use strict'; var undefined; var NaN; var Infinity;
        var threw = false;
        try { Infinity = 5; } catch (e) { threw = e instanceof TypeError; }
        [typeof undefined, String(NaN), Infinity, threw].join('|');
    """.trimIndent())

    @Test
    fun functionDeclarationsCannotReplaceReadonlyGlobals() = check("TypeError,TypeError,TypeError|Infinity", """
        var out = [];
        try { eval('function undefined() {}'); out.push('missing'); } catch (e) { out.push(e.name); }
        try { eval('function NaN() {}'); out.push('missing'); } catch (e) { out.push(e.name); }
        try { eval('function Infinity() {}'); out.push('missing'); } catch (e) { out.push(e.name); }
        out.join() + '|' + Infinity;
    """.trimIndent())

    @Test
    fun realConstBindingsStillRejectVarRedeclarations() {
        KiteJs(Rhino).use { js ->
            js.evaluate("const locked = 7;")
            assertFailsWith<JsError> { js.evaluate("var locked;") }
            assertEquals(7, js.evaluate("locked").asInt())
        }
    }

    @Test
    fun existingReadonlyPropertiesAllowVarWithoutGetterEffects() = check("7|0", """
        var effects = 0;
        Object.defineProperty(globalThis, 'locked', { value: 7, writable: false, configurable: false });
        Object.defineProperty(globalThis, 'accessed', { get: function () { effects++; return 9; }, configurable: false });
        eval('var locked; var accessed;');
        locked + '|' + effects;
    """.trimIndent())

    @Test
    fun lexicalDeclarationsRetainTheirConflictChecks() {
        for (declaration in listOf("let NaN;", "const NaN = 5;", "class NaN {}")) {
            KiteJs(Rhino).use { js ->
                assertFailsWith<JsError> { js.evaluate(declaration) }
                assertEquals("NaN", js.evaluate("String(NaN)").asString())
            }
        }
    }

    @Test
    fun aFunctionDeclarationStillWinsOverAVarOfTheSameName() {
        for (source in listOf("var NaN; function NaN() {}", "function NaN() {} var NaN;")) {
            KiteJs(Rhino).use { js ->
                assertFailsWith<JsError> { js.evaluate(source) }
                assertEquals("NaN", js.evaluate("String(NaN)").asString())
            }
        }
    }

    @Test
    fun namedFunctionExpressionsDoNotBecomeGlobalDeclarations() = check("NaN|function", """
        var NaN;
        var callback = function NaN() {};
        String(NaN) + '|' + typeof callback;
    """.trimIndent())
}
