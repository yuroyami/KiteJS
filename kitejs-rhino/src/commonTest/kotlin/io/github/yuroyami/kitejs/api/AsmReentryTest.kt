/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import io.github.yuroyami.kitejs.rhino.asmReports
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** An export may be entered again while its argument conversions or typed caller are active. */
class AsmReentryTest {

    private fun parity(expected: String, source: String) {
        for (enabled in listOf(false, true)) {
            KiteJs(Rhino) { asmJs = enabled }.use { js ->
                assertEquals(expected, js.evaluate(source, "asm-reentry.js").asString(), "asmJs=$enabled")
                if (enabled) assertCompiled(js)
                assertEquals(2, js.evaluate("1 + 1").asInt())
            }
        }
    }

    private fun assertCompiled(js: KiteJs) {
        val report = js.asmReports.single()
        assertTrue(report.compiled, "module must compile: $report")
        assertTrue(report.linked, "module must link: $report")
    }

    @Test
    fun callbacksIntoTheSameAndOtherExportsKeepMixedLocals() = parity(
        "8.5,3.5|149.25,88.25,149.25,88.25|6",
        """
        var inside = false, answers = [];
        var m = (function (s, f) {
          "use asm";
          var cb = f.cb;
          var calls = 0;
          function inner(x, y) {
            x = x | 0; y = +y;
            var a = 0, d = 0.0;
            a = x; d = y;
            calls = (calls + 1) | 0;
            cb();
            return +(+(a | 0) + d);
          }
          function g(x, y) {
            x = x | 0; y = +y;
            var a = 0, d = 0.0;
            a = x; d = y;
            inner(x | 0, +y);
            return +(+(a | 0) + d);
          }
          function count() { return calls | 0; }
          return { g: g, inner: inner, count: count };
        })({}, { cb: function () {
          if (!inside) {
            inside = true;
            try { answers.push(m.g(99, 50.25), m.inner(88, 0.25)); }
            finally { inside = false; }
          }
          return 0;
        } });
        var first = m.g(7, 1.5), second = m.g(3, 0.5);
        first + ',' + second + '|' + answers.join() + '|' + m.count();
        """.trimIndent(),
    )

    @Test
    fun laterArgumentConversionsKeepEarlierArgumentsAndSignedZero() = parity(
        "10.5,13.5|-Infinity|dnumber",
        """
        var events = '';
        var m = (function () {
          "use asm";
          function g(x, y, z) {
            x = x | 0; y = +y; z = z | 0;
            return +(+(x | 0) + y + +(z | 0));
          }
          function sign(x, y) { x = +x; y = y | 0; return +x; }
          return { g: g, sign: sign };
        })();
        var first = m.g(7, {
          valueOf: function () { events += 'd'; m.g(99, 44, 22); return 1.5; }
        }, {
          [Symbol.toPrimitive]: function (hint) { events += hint; m.g(88, 33, 11); return 2; }
        });
        var zero = m.sign(-0, { valueOf: function () { m.sign(0, 99); return 0; } });
        first + ',' + m.g(3, 4.5, 6) + '|' + 1 / zero + '|' + events;
        """.trimIndent(),
    )

    @Test
    fun convertingAnImportedResultMayReenterASingleExport() = parity(
        "8,4",
        """
        var inside = false;
        var m = (function (s, f) {
          "use asm";
          var cb = f.cb;
          function g(x) {
            x = x | 0;
            var a = 0, result = 0;
            a = x; result = cb() | 0;
            return (a + result) | 0;
          }
          return g;
        })({}, { cb: function () {
          return { valueOf: function () {
            if (!inside) {
              inside = true;
              try { m(99); } finally { inside = false; }
            }
            return 1;
          } };
        } });
        m(7) + ',' + m(3);
        """.trimIndent(),
    )

    @Test
    fun nestedIndirectCallsShareGlobalsAndHeapWhileKeepingTheirFrames() = parity(
        "8.5|151.25,153.25,155.25|100,101,102,7|4",
        """
        var heap = new ArrayBuffer(1024), view = new Int32Array(heap), answers = [];
        var m = (function (s, f, heap) {
          "use asm";
          var H32 = new s.Int32Array(heap);
          var cb = f.cb;
          var calls = 0;
          function g(n, x, y) {
            n = n | 0; x = x | 0; y = +y;
            var a = 0, d = 0.0;
            a = x; d = y;
            calls = (calls + 1) | 0;
            H32[(n << 2) >> 2] = x;
            cb(n | 0);
            return +(+(a | 0) + d);
          }
          function through(n, x, y) {
            n = n | 0; x = x | 0; y = +y;
            return +TBL[n & 1](n | 0, x | 0, +y);
          }
          function count() { return calls | 0; }
          var TBL = [g, g];
          return { g: through, count: count };
        })({ Int32Array: Int32Array }, { cb: function (n) {
          if (n > 0) answers.push(m.g(n - 1, 99 + n, 50.25 + n));
          return 0;
        } }, heap);
        var first = m.g(3, 7, 1.5);
        first + '|' + answers.join() + '|' + Array.from(view).slice(0, 4).join() + '|' + m.count();
        """.trimIndent(),
    )

    @Test
    fun reentrantRunnersCanGrowBothPrimitiveStacks() {
        val intLocals = (0 until 4100).joinToString(",") { "i" + it + "=0" }
        val doubleLocals = (0 until 2200).joinToString(",") { "d" + it + "=0.0" }
        parity(
            "8.5,13.5|149.25,149.25",
            """
            var inside = false, answers = [];
            var m = (function (s, f) {
              "use asm";
              var cb = f.cb;
              function g(x, y) {
                x = x | 0; y = +y;
                var $intLocals, $doubleLocals;
                i0 = x; d0 = y;
                cb();
                return +(+(i0 | 0) + d0);
              }
              return { g: g };
            })({}, { cb: function () {
              if (!inside) {
                inside = true;
                try { answers.push(m.g(99, 50.25)); } finally { inside = false; }
              }
              return 0;
            } });
            m.g(7, 1.5) + ',' + m.g(9, 4.5) + '|' + answers.join();
            """.trimIndent(),
        )
    }

    @Test
    fun nestedThrowsAndConversionFailuresReleaseTheEntry() {
        for (enabled in listOf(false, true)) {
            KiteJs(Rhino) { asmJs = enabled }.use { js ->
                js.evaluate(
                    """
                    var inside = false, escape = true, events = [];
                    var m = (function (s, f) {
                      "use asm";
                      var cb = f.cb;
                      function g(x) { x = x | 0; var a = 0; a = x; cb(x | 0); return a | 0; }
                      return { g: g };
                    })({}, { cb: function (x) {
                      if (x === 99) throw new TypeError('nested');
                      if (!inside) {
                        inside = true;
                        try { m.g(99); }
                        catch (e) { if (escape) throw e; events.push(e.message); }
                        finally { inside = false; }
                      }
                      return 0;
                    } });
                    """.trimIndent(),
                )
                val nested = assertFailsWith<JsError> { js.evaluate("m.g(7)") }
                assertEquals("TypeError", nested.name)
                assertEquals("nested", nested.errorMessage)
                val conversion = assertFailsWith<JsError> {
                    js.evaluate("m.g({ valueOf: function () { throw new RangeError('conversion'); } })")
                }
                assertEquals("RangeError", conversion.name)
                assertEquals("conversion", conversion.errorMessage)
                assertEquals("7,3|nested,nested", js.evaluate("escape = false; m.g(7) + ',' + m.g(3) + '|' + events.join()").asString())
                if (enabled) assertCompiled(js)
                assertEquals(2, js.evaluate("1 + 1").asInt())
            }
        }
    }

    @Test
    fun anInstructionObserverMayReenterTheActiveExport() {
        for (enabled in listOf(false, true)) {
            var entry: JsFunction? = null
            var inside = false
            var observed = 0
            KiteJs(Rhino) {
                asmJs = enabled
                interruptWhen = {
                    if (entry != null && !inside) {
                        inside = true
                        try { entry!!(0, 99, 50.25); observed++ }
                        finally { inside = false }
                    }
                    false
                }
            }.use { js ->
                js.evaluate(
                    """
                    var m = (function () {
                      "use asm";
                      function g(n, x, y) {
                        n = n | 0; x = x | 0; y = +y;
                        var i = 0, a = 0, d = 0.0;
                        a = x; d = y;
                        for (i = 0; (i | 0) < (n | 0); i = (i + 1) | 0) {}
                        return +(+(a | 0) + d);
                      }
                      return { g: g };
                    })();
                    """.trimIndent(),
                )
                val export = js.global["m"].asObject()["g"].asFunction()
                entry = export
                assertEquals(8.5, export(110_000, 7, 1.5).asDouble(), "asmJs=$enabled")
                assertTrue(observed > 0, "the observer must actually reenter the export")
                if (enabled) assertCompiled(js)
                assertEquals(2, js.evaluate("1 + 1").asInt())
                entry = null
            }
        }
    }
}
