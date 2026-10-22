/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Context
import io.github.yuroyami.kitejs.rhino.NativeObject
import io.github.yuroyami.kitejs.rhino.ScriptRuntime
import io.github.yuroyami.kitejs.rhino.Scriptable
import io.github.yuroyami.kitejs.rhino.ScriptableObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A declined link must not replay any part of observable module initialization. */
class AsmLinkEffectsTest {
    @Test
    fun hostPropertyImplementationsRunOnlyDuringOrdinaryInitialization() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            for (customObject in listOf(false, true)) {
                var reads = 0
                var hashes = 0
                val foreign = if (customObject) object : NativeObject() {
                    override fun get(name: String, start: Scriptable): Any? {
                        if (name == "n") { reads++; return 7 }
                        return super.get(name, start)
                    }
                    override fun hashCode(): Int { hashes++; return 1 }
                } else NativeObject().also {
                    it.defineProperty("n", { reads++; 7 }, null, ScriptableObject.EMPTY)
                }
                ScriptableObject.putProperty(scope, "foreign", foreign)
                val source = """
                    var m = (function (s, imports) {
                      "use asm";
                      var n = imports.n | 0;
                      function f() { return n | 0; }
                      return { f: f };
                    })({}, foreign);
                    String(m.f());
                """.trimIndent()
                assertEquals("7", ScriptRuntime.toString(cx.evaluateString(scope, source, "host-asm.js", 1, null)))
                assertEquals(1, reads)
                assertEquals(0, hashes)
                val diagnostic = cx.asmDiagnostics.last()
                assertTrue(diagnostic.compiled)
                assertFalse(diagnostic.linked)
                assertTrue(diagnostic.linkReason.isNotEmpty())
            }
        } finally {
            Context.exit()
        }
    }

    private fun mathModule(stdlib: String, imports: String = "{}", declarations: String = ""): String = """
        var m = (function (s, imports) {
          "use asm";
          $declarations
          var im = s.Math.imul;
          function f() { return im(1, 2) | 0; }
          return { f: f };
        })($stdlib, $imports);
        m.f() + '|' + events.join();
    """.trimIndent()

    @Test
    fun standardLibraryAccessorsAreReadOnceOnlyByOrdinaryInitialization() {
        for (setup in listOf(
            "var supplied = {}; Object.defineProperty(supplied, 'Math', { get: function () { events.push('Math'); return { imul: function () { return 9; } }; } });",
            "var supplied = Object.create({}); Object.defineProperty(Object.getPrototypeOf(supplied), 'Math', { get: function () { events.push('Math'); return { imul: function () { return 9; } }; } });",
        )) {
            checkAsmLink("9|Math", "var events = []; $setup\n" + mathModule("supplied"), linked = false)
        }
        checkAsmLink("9|imul", """
            var events = [], supplied = { Math: {} };
            Object.defineProperty(supplied.Math, 'imul', {
              get: function () { events.push('imul'); return function () { return 9; }; }
            });
        """.trimIndent() + "\n" + mathModule("supplied"), linked = false)
        checkAsmLink("2|Math", """
            var events = [], supplied = {}, original = Math;
            Object.defineProperty(supplied, 'Math', {
              get: function () { events.push('Math'); return original; }
            });
        """.trimIndent() + "\n" + mathModule("supplied"), linked = false)
    }

    @Test
    fun aForeignFunctionGetterBeforeALaterFailureIsNotReplayed() {
        for (library in listOf("{ Math: { imul: function () { return 9; } } }", "{ Math: Math }")) {
            val expected = if (library == "{ Math: Math }") "2|get,call" else "9|get,call"
            checkAsmLink(expected, """
                var events = [], supplied = {};
                Object.defineProperty(supplied, 'cb', {
                  get: function () { events.push('get'); return function () { events.push('call'); }; }
                });
                var m = (function (s, imports) {
                  "use asm";
                  var cb = imports.cb;
                  var im = s.Math.imul;
                  function f() { cb(); return im(1, 2) | 0; }
                  return { f: f };
                })($library, supplied);
                m.f() + '|' + events.join();
            """.trimIndent(), linked = false)
        }
    }

    @Test
    fun numericObjectConversionsBeforeALaterFailureRunOnce() = checkAsmLink(
        "106|number,math", """
            var events = [];
            var m = (function (s, imports) {
              "use asm";
              var n = imports.n | 0;
              var im = s.Math.imul;
              function f() { return (n + im(1, 2)) | 0; }
              return { f: f };
            })({ Math: { imul: function () { events.push('math'); return 99; } } }, {
              n: { valueOf: function () { events.push('number'); return 7; } }
            });
            m.f() + '|' + events.join();
        """.trimIndent(), linked = false,
    )

    @Test
    fun getterMutationKeepsTheOriginalValueAndEffectOrder() = checkAsmLink(
        "10|2|Math", """
            var events = [], supplied = {}, imports = { n: 1 };
            Object.defineProperty(supplied, 'Math', {
              get: function () { events.push('Math'); imports.n++; return { imul: function () { return 9; } }; }
            });
            var m = (function (s, imports) {
              "use asm";
              var n = imports.n | 0;
              var im = s.Math.imul;
              function f() { return (n + im(1, 2)) | 0; }
              return { f: f };
            })(supplied, imports);
            m.f() + '|' + imports.n + '|' + events.join();
        """.trimIndent(), linked = false,
    )

    @Test
    fun throwingGettersRetainTheirThrownValueAndEarlierEffects() = checkAsmLink(
        "true|number,get", """
            var events = [], supplied = {}, token = {}, caught = false;
            Object.defineProperty(supplied, 'cb', { get: function () { events.push('get'); throw token; } });
            supplied.n = { valueOf: function () { events.push('number'); return 7; } };
            try {
              var m = (function (s, imports) {
                "use asm";
                var n = imports.n | 0;
                var cb = imports.cb;
                function f() { cb(); return n | 0; }
                return { f: f };
              })({}, supplied);
            } catch (e) { caught = e === token; }
            caught + '|' + events.join();
        """.trimIndent(), linked = false,
    )

    @Test
    fun directAndInheritedProxiesAreNotProbedBeforeFallback() {
        for (wrap in listOf("proxy", "Object.create(proxy)")) {
            checkAsmLink("9|get:Math", """
                var events = [], proxy = new Proxy({ Math: { imul: function () { return 9; } } }, {
                  get: function (target, key) { events.push('get:' + key); return target[key]; },
                  getPrototypeOf: function (target) { events.push('prototype'); return Object.getPrototypeOf(target); }
                });
            """.trimIndent() + "\n" + mathModule(wrap), linked = false)
        }
    }

    @Test
    fun primitiveAndMissingNumericImportsKeepLinkingWithTheirExactCoercions() {
        for ((imports, expected) in listOf(
            "{ n: 7 }" to "7", "{ n: null }" to "0", "{ n: undefined }" to "NaN",
            "{}" to "NaN", "{ n: '7.5' }" to "7.5", "{ n: true }" to "1",
            "Object.create({ n: 7 })" to "7",
        )) {
            checkAsmLink(expected, """
                var m = (function (s, imports) {
                  "use asm";
                  var n = +imports.n;
                  function f() { return +n; }
                  return { f: f };
                })({}, $imports);
                String(m.f());
            """.trimIndent(), linked = true)
        }
    }

    @Test
    fun numericObjectsAndAccessorImportsFallBackEvenWithNoLaterFailure() {
        for (setup in listOf(
            "var imports = { n: { valueOf: function () { events.push('number'); return 7; } } };",
            "var imports = {}; Object.defineProperty(imports, 'n', { get: function () { events.push('number'); return 7; } });",
        )) {
            checkAsmLink("7|number", """
                var events = [];
                $setup
                var m = (function (s, imports) {
                  "use asm";
                  var n = +imports.n;
                  function f() { return +n; }
                  return { f: f };
                })({}, imports);
                m.f() + '|' + events.join();
            """.trimIndent(), linked = false)
        }
    }
}
