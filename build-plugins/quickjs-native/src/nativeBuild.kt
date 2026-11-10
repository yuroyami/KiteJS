/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.buildplugins.quickjs

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

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
    konanDataDir().resolve("kotlin-native-prebuilt-$host-$kotlinVersion").takeIf { it.resolve("bin").isDirectory() }?.let { return it }
    error(
        "Kotlin/Native $kotlinVersion is not downloaded yet. Build one native platform of kitejs-api first, " +
            "for example `./kotlin build -m kitejs-api -p linuxX64`, then build again.",
    )
}

/** `KONAN_DATA_DIR`, else `~/.konan`. Kotlin/Native keeps its LLVM and sysroots there, under `dependencies/`. */
internal fun konanDataDir(): Path =
    System.getenv("KONAN_DATA_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of) ?: Path.of(System.getProperty("user.home"), ".konan")

/** This machine, named as konan.properties names a host. */
private val konanHost: String = when {
    hostOs.startsWith("mac") -> if (hostArch == "aarch64") "macos_arm64" else "macos_x64"
    isWindows -> "mingw_x64"
    else -> if (hostArch == "aarch64") "linux_arm64" else "linux_x64"
}

/**
 * Makes Kotlin/Native download the LLVM and the sysroot that `run_konan clang` needs for [konanTarget].
 * Kotlin/Native downloads them only when it links a program, and `run_konan` never does that. So on a
 * fresh machine this links an empty program for the target. konan.properties names the folders it needs.
 */
internal fun ensureKonanDependencies(konanHome: Path, konanTarget: String) {
    val properties = Properties().apply { Files.newInputStream(konanHome.resolve("konan/konan.properties")).use { load(it) } }
    fun expand(value: String?): List<String> = value.orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }
        .flatMap { if (it.startsWith('$')) expand(properties.getProperty(it.drop(1))) else listOf(it) }
    val key = if (konanTarget == konanHost) "dependencies.$konanHost" else "dependencies.$konanHost-$konanTarget"
    val needed = expand(properties.getProperty("llvm.$konanHost.user")) + expand(properties.getProperty(key))
    val dependencies = konanDataDir().resolve("dependencies")
    fun missing() = needed.filterNot { dependencies.resolve(it).isDirectory() }
    if (missing().isEmpty()) return
    // Another task may have downloaded them while this one waited for the lock.
    withKonanLock(konanDataDir()) {
        if (missing().isNotEmpty()) linkWarmupProgram(konanHome, konanTarget)
    }
    val left = missing()
    check(left.isEmpty()) {
        "Kotlin/Native linked a program for $konanTarget, but $dependencies still has no ${left.joinToString()}. The log above shows the link."
    }
}

/** Links an empty program for [konanTarget], which makes Kotlin/Native download what the target needs. */
private fun linkWarmupProgram(konanHome: Path, konanTarget: String) {
    val konanc = konanHome.resolve("bin/" + if (isWindows) "konanc.bat" else "konanc").toString()
    val work = Files.createTempDirectory("kitejs-konan-warmup")
    try {
        val source = work.resolve("main.kt").apply { writeText("fun main() {}\n") }
        println("[quickjs-native] downloading the Kotlin/Native dependencies for $konanTarget")
        val process = ProcessBuilder(konanc, "-target", konanTarget, "-o", work.resolve("warmup").toString(), source.toString())
            .redirectErrorStream(true)
            .apply { environment()["JAVA_HOME"] = System.getProperty("java.home") }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        // The downloads can be complete even when the link fails, so the caller checks the folders.
        if (process.waitFor() != 0) println("[quickjs-native] the warm-up link for $konanTarget failed:\n$output")
    } finally {
        work.toFile().deleteRecursively()
    }
}

/** The lock file of [withKonanLock]. KitePlayer and KiteFFmpeg use the same name, so their builds wait too. */
internal const val KONAN_LOCK_FILE: String = ".kite-konan-provision.lock"

/**
 * Runs [block] while this process holds the provisioning lock of [konanDataDir]. Two tasks of one
 * build, or two builds on one machine, then never download the same Kotlin/Native files at once.
 * Never call it again from inside [block]: the second call waits for the first for ever.
 */
internal fun <T> withKonanLock(konanDataDir: Path, block: () -> T): T {
    konanDataDir.createDirectories()
    FileChannel.open(konanDataDir.resolve(KONAN_LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
        val lock = acquireLock(channel)
        try {
            return block()
        } finally {
            lock.release()
        }
    }
}

private fun acquireLock(channel: FileChannel): FileLock {
    while (true) {
        // Another thread of this JVM gets OverlappingFileLockException, another process gets null.
        val lock = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        if (lock != null) return lock
        Thread.sleep(250)
    }
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
