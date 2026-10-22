/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.buildplugins.test262

import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Fills the generated test source folders of kitejs-rhino for the run that KITEJS_TEST262 names:
 *
 * - unset or empty: nothing, so ordinary tests never compile the test262 code;
 * - `parity`: test262/parity into the JVM tests;
 * - `replay`: test262/replay with the generated Test262Paths.kt into the common tests, and the readers of
 *   test262/file, test262/js and test262/wasm into the platforms that use them. The bundle is
 *   KITEJS_TEST262_BUNDLE, else [defaultBundle].
 *
 * The task runs on every build, because its input is an environment variable. It rewrites a file only when the
 * text changes, so an unchanged mode does not recompile the tests.
 */
@TaskAction(ExecutionAvoidance.Disabled)
fun addTest262Sources(
    sourcesDir: String,
    defaultBundle: String,
    corpusTool: String,
    @Output commonDir: Path,
    @Output jvmDir: Path,
    @Output androidDir: Path,
    @Output nativeDir: Path,
    @Output jsDir: Path,
    @Output wasmJsDir: Path,
) {
    val sources = Path.of(sourcesDir)
    val wanted = mutableMapOf<Path, MutableMap<String, String>>()
    for (dir in listOf(commonDir, jvmDir, androidDir, nativeDir, jsDir, wasmJsDir)) wanted[dir] = mutableMapOf()
    fun copy(from: String, to: Path) {
        Files.walk(sources.resolve(from)).use { paths ->
            paths.filter { it.isRegularFile() && it.name.endsWith(".kt") }.forEach { file ->
                wanted.getValue(to)[sources.resolve(from).relativize(file).toString()] = file.readText()
            }
        }
    }
    when (val mode = System.getenv("KITEJS_TEST262").orEmpty()) {
        "" -> {}
        "parity" -> copy("parity", jvmDir)
        "replay" -> {
            copy("replay", commonDir)
            for (dir in listOf(jvmDir, androidDir, nativeDir)) copy("file", dir)
            copy("js", jsDir)
            copy("wasm", wasmJsDir)
            val bundle = System.getenv("KITEJS_TEST262_BUNDLE")?.takeIf { it.isNotBlank() } ?: defaultBundle
            val generated = Files.createTempDirectory("test262-paths")
            val python = if (System.getProperty("os.name").lowercase().startsWith("windows")) "python" else "python3"
            val process = ProcessBuilder(python, corpusTool, "generate", "--bundle", bundle, "--output", generated.toString())
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "test262-corpus.py generate failed:\n$output" }
            val paths = generated.resolve("Test262Paths.kt")
            wanted.getValue(commonDir)["io/github/yuroyami/kitejs/rhino/Test262Paths.kt"] = paths.readText()
            paths.deleteIfExists()
            generated.deleteIfExists()
        }
        else -> error("KITEJS_TEST262 is '$mode'. Use 'parity', 'replay', or leave it unset.")
    }
    for ((dir, files) in wanted) sync(dir, files)
}

/** Makes [dir] hold exactly [files] (relative path to text), touching only the files that change. */
private fun sync(dir: Path, files: Map<String, String>) {
    dir.createDirectories()
    Files.walk(dir).use { paths -> paths.filter { it.isRegularFile() }.toList() }.forEach { file ->
        if (dir.relativize(file).toString() !in files) file.deleteIfExists()
    }
    for ((relative, text) in files) {
        val target = dir.resolve(relative)
        if (target.exists() && target.readText() == text) continue
        target.parent.createDirectories()
        target.writeText(text)
    }
    // Empty folders left by a previous mode.
    Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).toList() }.forEach {
        if (it != dir && Files.isDirectory(it) && it.listDirectoryEntries().isEmpty()) Files.delete(it)
    }
}
