/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.asm

import io.github.yuroyami.kitejs.CompilerEnvirons
import io.github.yuroyami.kitejs.Context
import io.github.yuroyami.kitejs.Parser
import io.github.yuroyami.kitejs.ast.FunctionNode
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * Counts what a real module compiles to: which instructions it holds, and which pairs of them sit
 * next to each other.
 *
 * A pair that turns up everywhere is worth one instruction of its own, because fusing the two
 * removes a dispatch and a round trip through the stack. Guessing which pairs those are is how an
 * interpreter grows instructions nobody uses, so they are counted instead.
 *
 * Point `KITEJS_ASM_FILE` at a script holding an asm.js module. Opt in with `KITEJS_PROFILE=true`.
 */
class AsmCodeHistogram {

    @Test
    fun count_the_instructions_a_real_module_compiles_to() {
        assumeTrue("Run with KITEJS_PROFILE=true.", System.getenv("KITEJS_PROFILE") == "true")
        val path = System.getenv("KITEJS_ASM_FILE")
        assumeTrue("Set KITEJS_ASM_FILE to a script holding an asm.js module.", path != null)
        val file = File(path!!)
        assumeTrue("$path does not exist", file.exists())

        val environs = CompilerEnvirons()
        environs.languageVersion = Context.VERSION_ES6
        val ast = Parser(environs).parse(file.readText(), file.name, 1)
        val reports = ArrayList<AsmDiagnostic>()
        AsmCompiler.compileAll(ast, reports)

        var module: AsmModule? = null
        ast.visit { node ->
            if (node is FunctionNode && node.asmModule != null) module = node.asmModule
            module == null
        }
        val compiled = module ?: error("no module compiled: ${reports.joinToString { it.compileReason }}")

        val single = HashMap<Int, Int>()
        val pairs = HashMap<Long, Int>()
        var instructions = 0
        for (function in compiled.functions) {
            val code = function.code
            var pc = 0
            var previous = -1
            while (pc < code.size) {
                val op = code[pc]
                instructions++
                single.merge(op, 1, Int::plus)
                if (previous >= 0) pairs.merge(previous.toLong() * 256 + op, 1, Int::plus)
                previous = op
                pc += 1 + AsmOp.operandCount(op, code, pc)
            }
        }

        println("functions: ${compiled.functions.size}, instructions: $instructions")
        println("--- the instructions it uses ---")
        single.entries.sortedByDescending { it.value }.take(25).forEach { (op, n) ->
            println("  ${percent(n, instructions)}  $n  ${AsmOp.name(op)}")
        }
        println("--- the pairs that sit next to each other ---")
        pairs.entries.sortedByDescending { it.value }.take(25).forEach { (key, n) ->
            val first = AsmOp.name((key / 256).toInt())
            val second = AsmOp.name((key % 256).toInt())
            println("  ${percent(n, instructions)}  $n  $first then $second")
        }
    }

    private fun percent(n: Int, total: Int): String =
        ((n * 1000.0 / total).toInt() / 10.0).toString().padStart(5) + "%"

}
