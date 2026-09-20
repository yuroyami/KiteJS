/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * Compiles an asm.js module from a file and says what happened.
 *
 * It exists for real Emscripten output, which is far larger and stranger than anything a test
 * writes by hand. Point `KITEJS_ASM_FILE` at a script holding one, and the report names the first
 * thing in it the compiler does not accept.
 */
class AsmRealModuleProbe {

    @Test
    fun compile_a_module_from_a_file() {
        val path = System.getenv("KITEJS_ASM_FILE")
        assumeTrue("Set KITEJS_ASM_FILE to a script holding an asm.js module.", path != null)
        val file = File(path!!)
        assumeTrue("$path does not exist", file.exists())
        val source = file.readText()
        println("source: ${source.length} chars")

        KiteJs { asmJs = true }.use { js ->
            val started = System.nanoTime()
            js.compile(source, file.name)
            val took = (System.nanoTime() - started) / 1_000_000
            println("parse and compile: $took ms")
            if (js.asmReports.isEmpty()) println("no \"use asm\" function was found")
            for (report in js.asmReports) println(report)
        }
    }
}
