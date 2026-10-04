/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every asm.js module here runs twice: once compiled to typed code, once as ordinary JavaScript.
 * The two answers have to match, character for character.
 *
 * That is the whole safety argument for the typed path. It holds integers in integer slots and
 * wraps them at 32 bits, which plain JavaScript would not do, and validation is what proves the
 * difference cannot be seen. A module that runs the same both ways is a module where that proof
 * held.
 *
 * Each case also asserts the module really did compile, because a module that quietly fell back
 * would pass the comparison while testing nothing.
 */
class AsmParityTest {

    /** Runs [source] with the typed path on and off, and answers what each one said. */
    private fun bothWays(source: String): Pair<String, String> {
        var compiled = ""
        var reports = ""
        KiteJs { asmJs = true }.use { js ->
            compiled = js.evaluate(source, "asm").toString()
            reports = js.asmReports.joinToString("; ")
        }
        if (!reports.contains(": compiled")) fail("the module did not compile: $reports")
        if (reports.contains("not linked")) fail("the module did not link: $reports")
        val plain = KiteJs { asmJs = false }.use { js -> js.evaluate(source, "asm").toString() }
        return compiled to plain
    }

    private fun parity(name: String, source: String) {
        val (compiled, plain) = bothWays(source)
        assertEquals(plain, compiled, "$name differs between the typed path and plain JavaScript")
    }

    /** Wraps [body] in a module over a one megabyte heap and runs its `main`. */
    private fun module(globals: String = "", functions: String, call: String): String = """
        var heap = new ArrayBuffer(1 << 20);
        var stdlib = { Math: Math, Int8Array: Int8Array, Uint8Array: Uint8Array,
            Int16Array: Int16Array, Uint16Array: Uint16Array, Int32Array: Int32Array,
            Uint32Array: Uint32Array, Float32Array: Float32Array, Float64Array: Float64Array,
            Infinity: Infinity, NaN: NaN };
        var m = (function (stdlib, foreign, heap) {
          "use asm";
          var H8 = new stdlib.Int8Array(heap);
          var HU8 = new stdlib.Uint8Array(heap);
          var H16 = new stdlib.Int16Array(heap);
          var HU16 = new stdlib.Uint16Array(heap);
          var H32 = new stdlib.Int32Array(heap);
          var HU32 = new stdlib.Uint32Array(heap);
          var HF32 = new stdlib.Float32Array(heap);
          var HF64 = new stdlib.Float64Array(heap);
          var imul = stdlib.Math.imul;
          var fround = stdlib.Math.fround;
          var abs = stdlib.Math.abs;
          var floor = stdlib.Math.floor;
          var sqrt = stdlib.Math.sqrt;
          var pow = stdlib.Math.pow;
          var minf = stdlib.Math.min;
          var maxf = stdlib.Math.max;
          var clz = stdlib.Math.clz32;
          var PI = stdlib.Math.PI;
          var log = foreign.log;
          var base = foreign.base | 0;
          var scale = +foreign.scale;
          $globals
          $functions
          return { main: main };
        })(stdlib, { log: function (x) { return x + 1; }, base: 7, scale: 0.25 }, heap);
        $call
    """.trimIndent()

    @Test
    fun integer_arithmetic_wraps_the_same_way() = parity(
        "integer arithmetic",
        module(
            functions = """
              function main(n) {
                n = n | 0;
                var i = 0, acc = 0, out = 0;
                for (i = 0; (i | 0) < (n | 0); i = (i + 1) | 0) {
                  acc = (imul(acc, 1103515245) + 12345) | 0;
                  out = (out + (acc >> 7)) | 0;
                  out = (out - (acc << 3)) | 0;
                  out = out ^ (acc >>> 11);
                  out = (out | 0) & 2147483647;
                }
                return out | 0;
              }
            """,
            call = "m.main(5000)",
        ),
    )

    @Test
    fun division_and_remainder_match_at_the_edges() = parity(
        "division",
        module(
            functions = """
              function main(n) {
                n = n | 0;
                var i = 0, out = 0, a = 0, b = 0;
                for (i = 0; (i | 0) < (n | 0); i = (i + 1) | 0) {
                  a = (i - 8) | 0;
                  b = (i - 4) | 0;
                  out = (out + ((a | 0) / (b | 0) | 0)) | 0;
                  out = (out + ((a | 0) % (b | 0) | 0)) | 0;
                  out = (out + ((a >>> 0) / (b >>> 0) | 0)) | 0;
                  out = (out + ((a >>> 0) % (b >>> 0) | 0)) | 0;
                }
                out = (out + ((-2147483648 | 0) / (-1 | 0) | 0)) | 0;
                out = (out + ((-2147483648 | 0) % (-1 | 0) | 0)) | 0;
                return out | 0;
              }
            """,
            call = "m.main(20)",
        ),
    )

    @Test
    fun signed_and_unsigned_comparisons_differ_the_same_way() = parity(
        "comparisons",
        module(
            functions = """
              function main(n) {
                n = n | 0;
                var i = 0, out = 0, a = 0, b = 0;
                for (i = 0; (i | 0) < (n | 0); i = (i + 1) | 0) {
                  a = (i - 5) | 0;
                  b = 3;
                  if ((a | 0) < (b | 0)) { out = (out + 1) | 0; }
                  if ((a >>> 0) < (b >>> 0)) { out = (out + 2) | 0; }
                  if ((a | 0) >= (b | 0)) { out = (out + 4) | 0; }
                  if ((a >>> 0) >= (b >>> 0)) { out = (out + 8) | 0; }
                  if ((a | 0) == (b | 0)) { out = (out + 16) | 0; }
                  if ((a | 0) != (b | 0)) { out = (out + 32) | 0; }
                }
                return out | 0;
              }
            """,
            call = "m.main(12)",
        ),
    )

    @Test
    fun every_heap_view_reads_back_what_was_written() = parity(
        "heap views",
        module(
            functions = """
              function main(n) {
                n = n | 0;
                var i = 0, out = 0, d = 0.0;
                for (i = 0; (i | 0) < (n | 0); i = (i + 1) | 0) {
                  H8[i >> 0] = (i * 37) | 0;
                  H16[(256 + (i << 1)) >> 1] = (i * 517) | 0;
                  H32[(1024 + (i << 2)) >> 2] = (i * 131071) | 0;
                  HF32[(4096 + (i << 2)) >> 2] = fround(+(i | 0) * 1.5);
                  HF64[(8192 + (i << 3)) >> 3] = +(i | 0) / 7.0;
                  out = (out + (H8[i >> 0] | 0)) | 0;
                  out = (out + (HU8[i >> 0] | 0)) | 0;
                  out = (out + (H16[(256 + (i << 1)) >> 1] | 0)) | 0;
                  out = (out + (HU16[(256 + (i << 1)) >> 1] | 0)) | 0;
                  out = (out + (H32[(1024 + (i << 2)) >> 2] | 0)) | 0;
                  out = (out + (HU32[(1024 + (i << 2)) >> 2] | 0)) | 0;
                  d = d + +HF32[(4096 + (i << 2)) >> 2] + +HF64[(8192 + (i << 3)) >> 3];
                }
                return (out + (~~d | 0)) | 0;
              }
            """,
            call = "m.main(64)",
        ),
    )

    @Test
    fun reading_past_the_end_of_the_heap_answers_zero() = parity(
        "out of range heap",
        module(
            functions = """
              function main(n) {
                n = n | 0;
                var out = 0;
                H32[1073741820 >> 2] = 99;
                out = (out + (H32[1073741820 >> 2] | 0)) | 0;
                out = (out + (HU8[-1 >> 0] | 0)) | 0;
                out = (out + (H16[-4 >> 1] | 0)) | 0;
                return out | 0;
              }
            """,
            call = "m.main(0)",
        ),
    )

    @Test
    fun doubles_and_the_math_library_agree() = parity(
        "doubles",
        module(
            functions = """
              function main(n) {
                n = n | 0;
                var i = 0, d = 0.0, out = 0.0;
                for (i = 0; (i | 0) < (n | 0); i = (i + 1) | 0) {
                  d = +(i | 0) + 0.5;
                  out = out + sqrt(d) + floor(d * PI) + abs(-d) + pow(d, 2.0);
                  out = out + minf(d, 3.0) + maxf(d, 3.0) + d % 2.5;
                }
                return +out;
              }
            """,
            call = "m.main(40)",
        ),
    )

    @Test
    fun the_edges_of_min_and_max_match() = parity(
        "min and max",
        module(
            functions = """
              function main(n) {
                n = n | 0;
                var out = 0.0;
                out = out + minf(0.0, -0.0) + maxf(0.0, -0.0);
                out = out + minf(1.0, 2.0) + maxf(1.0, 2.0);
                out = out + (minf(+0.0 / 0.0, 1.0) == minf(+0.0 / 0.0, 1.0) ? 1.0 : 2.0);
                out = out + +(minf(5 | 0, 3 | 0) | 0) + +(maxf(5 | 0, 3 | 0) | 0);
                return +out;
              }
            """,
            call = "m.main(0)",
        ),
    )

    @Test
    fun fround_rounds_to_a_float_at_every_step() = parity(
        "fround",
        module(
            globals = "var fone = fround(1.1);",
            functions = """
              function main(n) {
                n = n | 0;
                var i = 0, f = fround(0.0), out = 0.0;
                f = fround(0.1);
                for (i = 0; (i | 0) < (n | 0); i = (i + 1) | 0) {
                  f = fround(fround(f * fround(1.0000001)) + fround(+(i | 0)));
                  out = out + +f;
                }
                out = out + +fone;
                return +out;
              }
            """,
            call = "m.main(50)",
        ),
    )

    @Test
    fun control_flow_takes_the_same_path() = parity(
        "control flow",
        module(
            functions = """
              function main(n) {
                n = n | 0;
                var i = 0, j = 0, out = 0;
                outer: for (i = 0; (i | 0) < (n | 0); i = (i + 1) | 0) {
                  j = 0;
                  while ((j | 0) < 10) {
                    j = (j + 1) | 0;
                    if ((j | 0) == 3) { continue; }
                    if ((j | 0) == 8) { break; }
                    if (((i + j) | 0) == 11) { continue outer; }
                    out = (out + j) | 0;
                  }
                  do {
                    out = (out + 1) | 0;
                  } while (0);
                  switch (i & 3) {
                    case 0: { out = (out + 100) | 0; break; }
                    case 1:
                    case 2: { out = (out + 200) | 0; break; }
                    default: { out = (out - 50) | 0; }
                  }
                  out = ((i & 1) ? (out + 1) | 0 : (out - 1) | 0) | 0;
                }
                return out | 0;
              }
            """,
            call = "m.main(20)",
        ),
    )

    @Test
    fun calls_inside_the_module_and_out_of_it_agree() = parity(
        "calls",
        module(
            globals = "var counter = 0;",
            functions = """
              function twice(x) { x = x | 0; return (x + x) | 0; }
              function thrice(x) { x = x | 0; return ((twice(x) | 0) + x) | 0; }
              function dbl(a, b) { a = +a; b = +b; return +(a * b); }
              function main(n) {
                n = n | 0;
                var i = 0, out = 0;
                for (i = 0; (i | 0) < (n | 0); i = (i + 1) | 0) {
                  counter = (counter + 1) | 0;
                  out = (out + (thrice(i) | 0)) | 0;
                  out = (out + (TBL[i & 1](i) | 0)) | 0;
                  out = (out + (log(i | 0) | 0)) | 0;
                  out = (out + (~~(+dbl(+(i | 0), 1.5)))) | 0;
                }
                return (out + counter + base + (~~(scale * 100.0))) | 0;
              }
              var TBL = [twice, thrice];
            """,
            call = "m.main(25)",
        ),
    )

    @Test
    fun recursion_reaches_the_same_answer() = parity(
        "recursion",
        module(
            functions = """
              function fib(x) {
                x = x | 0;
                if ((x | 0) < 2) { return x | 0; }
                return ((fib((x - 1) | 0) | 0) + (fib((x - 2) | 0) | 0)) | 0;
              }
              function main(n) { n = n | 0; return fib(n) | 0; }
            """,
            call = "m.main(22)",
        ),
    )

    @Test
    fun a_module_that_returns_one_function_works() = parity(
        "single export",
        """
        var m = (function (stdlib) {
          "use asm";
          var imul = stdlib.Math.imul;
          function square(x) { x = x | 0; return imul(x, x) | 0; }
          return square;
        })({ Math: Math });
        m(46341) + ',' + m(-3);
        """.trimIndent(),
    )

    @Test
    fun a_module_the_compiler_turns_down_still_runs() {
        KiteJs { asmJs = true }.use { js ->
            val answer = js.evaluate(
                """
                var m = (function (stdlib) {
                  "use asm";
                  function f(x) { return x.length; }
                  return { f: f };
                })({ Math: Math });
                m.f("abcd");
                """.trimIndent(),
                "asm",
            )
            assertEquals(4.0, answer.asDouble())
            val report = js.asmReports.single()
            assertTrue(!report.compiled, "the module should not have compiled")
            assertTrue(report.reason.isNotEmpty(), "a module that is turned down says why")
        }
    }

    @Test
    fun a_module_given_the_wrong_standard_library_still_runs() {
        KiteJs { asmJs = true }.use { js ->
            val answer = js.evaluate(
                """
                var m = (function (stdlib) {
                  "use asm";
                  var imul = stdlib.Math.imul;
                  function f(x) { x = x | 0; return imul(x, 2) | 0; }
                  return { f: f };
                })({ Math: { imul: function (a, b) { return a * b + 1; } } });
                m.f(5);
                """.trimIndent(),
                "asm",
            )
            // The fake imul is what runs, because the module could not be linked.
            assertEquals(11.0, answer.asDouble())
            val report = js.asmReports.single()
            assertTrue(report.compiled, "the module compiled: ${report.reason}")
            assertTrue(!report.linked, "the module should not have linked")
        }
    }

    @Test
    fun the_compiled_module_writes_through_to_a_view_outside_it() {
        KiteJs { asmJs = true }.use { js ->
            val answer = js.evaluate(
                """
                var heap = new ArrayBuffer(1024);
                var view = new Int32Array(heap);
                var m = (function (stdlib, foreign, heap) {
                  "use asm";
                  var H32 = new stdlib.Int32Array(heap);
                  function put(i, v) { i = i | 0; v = v | 0; H32[(i << 2) >> 2] = v; }
                  return { put: put };
                })({ Int32Array: Int32Array }, {}, heap);
                m.put(3, 123456789);
                view[3];
                """.trimIndent(),
                "asm",
            )
            assertEquals(123456789.0, answer.asDouble())
            assertTrue(js.asmReports.single().linked, "the module linked")
        }
    }
}
