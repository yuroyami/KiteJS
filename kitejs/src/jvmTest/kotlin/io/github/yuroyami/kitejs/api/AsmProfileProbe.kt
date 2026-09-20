package io.github.yuroyami.kitejs.api

import org.junit.Assume.assumeTrue
import kotlin.test.Test

/**
 * Where the time goes when the engine runs Emscripten shaped code.
 *
 * A second thread reads the running thread's stack a thousand times a second and counts what it
 * finds. The count is not a measurement of the whole program: it says which methods the engine is
 * inside, which is what decides whether a faster path is worth building and where it has to sit.
 *
 * Opt in with `KITEJS_PROFILE=true`. It never fails.
 */
class AsmProfileProbe {

    private val kernel = """
        var buffer = new ArrayBuffer(1 << 20);
        var mod = (function (global, env, buffer) {
          "use asm";
          var H32 = new global.Int32Array(buffer);
          var HU8 = new global.Uint8Array(buffer);
          var imul = global.Math.imul;
          function fill(n) {
            n = n | 0;
            var i = 0, x = 0, acc = 0;
            for (i = 0; (i | 0) < (n | 0); i = (i + 1) | 0) {
              x = imul(x, 1103515245) + 12345 | 0;
              H32[(i & 65535) << 2 >> 2] = x;
              HU8[(x >>> 12) & 1048575] = (x >>> 24) & 255;
              acc = (acc + (HU8[(i * 7) & 1048575] | 0)) | 0;
            }
            return acc | 0;
          }
          return { fill: fill };
        })({ Int32Array: Int32Array, Uint8Array: Uint8Array, Math: Math }, {}, buffer);
        mod.fill(3000000);
    """.trimIndent()

    /** The same kernel with the typed path off and on, back to back on one machine. */
    @Test
    fun the_typed_path_against_the_general_interpreter() {
        assumeTrue("Run with KITEJS_PROFILE=true.", System.getenv("KITEJS_PROFILE") == "true")
        // Interleaved rather than one after the other, so a machine that gets busier partway
        // through loads both sides rather than only the second.
        val plain = ArrayList<Long>()
        val typed = ArrayList<Long>()
        repeat(4) {
            plain.add(timeOnePass(asm = false))
            typed.add(timeOnePass(asm = true))
        }
        println("plain (ms): $plain  median ${plain.sorted()[plain.size / 2]}")
        println("typed (ms): $typed  median ${typed.sorted()[typed.size / 2]}")
    }

    private fun timeOnePass(asm: Boolean): Long = KiteJs {
        instructionBudget = 0
        asmJs = asm
    }.use { js ->
        js.evaluate(kernel, "warm")
        val started = System.nanoTime()
        js.evaluate(kernel, "asm")
        (System.nanoTime() - started) / 1_000_000
    }

    @Test
    fun where_the_asm_kernel_spends_its_time() {
        assumeTrue("Run with KITEJS_PROFILE=true.", System.getenv("KITEJS_PROFILE") == "true")

        val passes = ArrayList<Long>()
        val worker = Thread {
            KiteJs { instructionBudget = 0 }.use { js ->
                // One warm pass, then the passes the sampler sees.
                repeat(4) {
                    val t0 = System.nanoTime()
                    js.evaluate(kernel, "asm")
                    passes.add((System.nanoTime() - t0) / 1_000_000)
                }
            }
        }
        val leaves = HashMap<String, Int>()
        val inclusive = HashMap<String, Int>()
        var samples = 0

        worker.start()
        while (worker.isAlive) {
            val stack = worker.stackTrace
            if (stack.isNotEmpty()) {
                samples++
                val leaf = frameName(stack[0])
                // The caller too, because a leaf like `Intrinsics.areEqual` says nothing on its own.
                leaves.merge(if (stack.size > 1) "$leaf  <- ${frameName(stack[1])}" else leaf, 1, Int::plus)
                val seen = HashSet<String>()
                for (f in stack) {
                    val name = frameName(f)
                    if (seen.add(name)) inclusive.merge(name, 1, Int::plus)
                }
            }
            Thread.sleep(1)
        }
        worker.join()

        println("passes (ms): $passes")
        println("samples: $samples")
        println("--- leaf (where the thread actually was) ---")
        report(leaves, samples)
        println("--- inclusive (somewhere on the stack) ---")
        report(inclusive, samples)
    }

    private fun report(counts: Map<String, Int>, samples: Int) {
        counts.entries.sortedByDescending { it.value }.take(25).forEach { (name, n) ->
            val pct = (n * 1000.0 / samples).toInt() / 10.0
            println("  ${pct.toString().padStart(5)}%  $n  $name")
        }
    }

    private fun frameName(f: StackTraceElement): String =
        "${f.className.substringAfterLast('.')}.${f.methodName}"
}
