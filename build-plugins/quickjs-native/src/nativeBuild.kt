/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.buildplugins.quickjs

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/** The C files of the engine, relative to native/. kitejs_quickjs.c is the surface the Kotlin side drives. */
internal val engineSources = listOf(
    "quickjs/quickjs.c", "quickjs/dtoa.c", "quickjs/libregexp.c", "quickjs/libunicode.c", "kitejs_quickjs.c",
)

/** The flags every compiler gets. The settings live in native/kitejs_config.h, which is included first. */
internal fun commonFlags(nativeDir: Path): List<String> =
    listOf("-O2", "-include", nativeDir.resolve("kitejs_config.h").toString())

internal val hostOs: String = System.getProperty("os.name").lowercase()
internal val isWindows: Boolean = hostOs.startsWith("windows")
internal val hostArch: String = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "aarch64" else "x86_64"

/** zig from the ZIG environment variable, else from the PATH. */
internal fun zig(): String = System.getenv("ZIG")?.takeIf { it.isNotBlank() } ?: "zig"

/** Runs [command] and fails with its output when it exits with an error. */
internal fun run(command: List<String>, workDir: Path? = null) {
    val process = ProcessBuilder(command)
        .apply { workDir?.let { directory(it.toFile()) } }
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    val code = process.waitFor()
    check(code == 0) { "${command.first()} failed with exit code $code:\n$output" }
}

/** Runs the jobs on at most four threads, so a C build never takes the whole machine. */
internal fun parallel(jobs: List<() -> Unit>) {
    val pool = Executors.newFixedThreadPool(minOf(4, jobs.size.coerceAtLeast(1)))
    try {
        jobs.map { pool.submit(it) }.forEach { it.get() }
    } finally {
        pool.shutdown()
        pool.awaitTermination(1, TimeUnit.MINUTES)
    }
}

/** The Kotlin Toolchain's download folder. The `kotlin` script uses the same places. */
internal fun toolchainCache(): Path {
    val home = Path.of(System.getProperty("user.home"))
    return when {
        hostOs.startsWith("mac") -> home.resolve("Library/Caches/JetBrains/Kotlin")
        isWindows -> Path.of(System.getenv("LOCALAPPDATA") ?: error("LOCALAPPDATA is not set")).resolve("JetBrains/Kotlin")
        else -> System.getenv("XDG_CACHE_HOME")?.let { Path.of(it, "JetBrains/Kotlin") } ?: home.resolve(".cache/JetBrains/Kotlin")
    }
}

/** The include folder of a JDK, for jni.h: JAVA_HOME first, else a JDK that the toolchain downloaded. */
internal fun jdkInclude(): Path {
    val roots = buildList {
        System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }?.let { add(Path.of(it)) }
        add(Path.of(System.getProperty("java.home")))
        val downloads = toolchainCache().resolve("extract.cache")
        if (downloads.isDirectory()) addAll(downloads.listDirectoryEntries().filter { it.isDirectory() && "jdk" in it.name.lowercase() })
    }
    for (root in roots) {
        if (root.resolve("include/jni.h").isRegularFile()) return root.resolve("include")
        // A downloaded JDK has its home up to three folders down, for example jdk-21/Contents/Home.
        Files.walk(root, 6).use { paths ->
            paths.filter { it.name == "jni.h" && it.parent.name == "include" }.findFirst().orElse(null)
        }?.let { return it.parent }
    }
    error("No JDK with include/jni.h found. Set JAVA_HOME to a JDK.")
}

/** The Kotlin/Native distribution of [kotlinVersion]: the toolchain's own download, else the Kotlin Gradle plugin's. */
internal fun konanHome(kotlinVersion: String): Path {
    val host = when {
        hostOs.startsWith("mac") -> "macos-$hostArch"
        isWindows -> "windows-x86_64"
        else -> "linux-$hostArch"
    }
    val downloads = toolchainCache().resolve("extract.cache")
    if (downloads.isDirectory()) {
        downloads.listDirectoryEntries("*kotlin-native-prebuilt-$kotlinVersion-$host*")
            .firstOrNull { it.isDirectory() && it.resolve("bin").isDirectory() }
            ?.let { return it }
    }
    val konanData = System.getenv("KONAN_DATA_DIR")?.let(Path::of) ?: Path.of(System.getProperty("user.home"), ".konan")
    konanData.resolve("kotlin-native-prebuilt-$host-$kotlinVersion").takeIf { it.resolve("bin").isDirectory() }?.let { return it }
    error(
        "Kotlin/Native $kotlinVersion is not downloaded yet. Build one native platform of kitejs-api first, " +
            "for example `./kotlin build -m kitejs-api -p linuxX64`, then build again.",
    )
}

/** The functions that native/kitejs_quickjs.h declares, which the WebAssembly module exports. */
internal fun wasmExports(nativeDir: Path): List<String> =
    Regex("""\b(kite_[a-z_]+)\(""").findAll(nativeDir.resolve("kitejs_quickjs.h").readText())
        .map { it.groupValues[1] }
        .filter { it != "kite_set_host" }
        .distinct()
        .toList()

/** The WebAssembly shadow stack from native/kitejs_config.h, so the link and the engine's limit agree. */
internal fun wasmStackSize(nativeDir: Path): Int =
    Regex("""#define KITEJS_WASM_STACK_SIZE \((\d+) \* 1024 \* 1024\)""")
        .find(nativeDir.resolve("kitejs_config.h").readText())!!
        .groupValues[1].toInt() * 1024 * 1024

internal fun Path.ensureDirectory(): Path = apply { createDirectories() }
