/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.buildplugins.quickjsandroid

import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively

/** The ABIs the library ships for, with the clang target triple of each. */
private val abis = listOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7a-linux-androideabi",
    "x86_64" to "x86_64-linux-android",
    "x86" to "i686-linux-android",
)

private val engineSources = listOf(
    "quickjs/quickjs.c", "quickjs/dtoa.c", "quickjs/libregexp.c", "quickjs/libunicode.c", "kitejs_quickjs.c",
    "jni/kitejs_quickjs_jni.c",
)

/**
 * Builds `lib/<abi>/libkitejs_quickjs.so` for every ABI with the NDK's clang. A jar dependency with this
 * layout lands in the app's APK as real native libraries: the Android Gradle plugin extracts `lib/<abi>/`
 * from plain jars, so `System.loadLibrary` finds them.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
@TaskAction
fun buildAndroidLibraries(
    @Input nativeDir: Path,
    minSdk: Int,
    @Output outputDir: Path,
) {
    val clang = ndkClang()
    outputDir.deleteRecursively()
    val pool = Executors.newFixedThreadPool(4)
    try {
        abis.map { (abi, triple) ->
            pool.submit {
                val library = outputDir.resolve("lib/$abi").createDirectories().resolve("libkitejs_quickjs.so")
                run(
                    listOf(clang, "--target=$triple$minSdk", "-o", library.toString()) +
                        // 16 KB pages, which newer Android devices use and Google Play requires of native code.
                        listOf("-shared", "-fPIC", "-fvisibility=hidden", "-g0", "-s", "-Wl,-z,max-page-size=16384") +
                        listOf("-O2", "-include", nativeDir.resolve("kitejs_config.h").toString(), "-I$nativeDir", "-lm") +
                        engineSources.map { nativeDir.resolve(it).toString() },
                )
            }
        }.forEach { it.get() }
    } finally {
        pool.shutdown()
        pool.awaitTermination(1, TimeUnit.MINUTES)
    }
}

/** The NDK's clang: ANDROID_NDK_HOME (or its other names) first, else the newest NDK in the Android SDK. */
private fun ndkClang(): String {
    val os = System.getProperty("os.name").lowercase()
    val ndk = listOfNotNull(System.getenv("ANDROID_NDK_HOME"), System.getenv("ANDROID_NDK_LATEST_HOME"), System.getenv("ANDROID_NDK_ROOT"))
        .firstOrNull { it.isNotBlank() }?.let(::File)
        ?: listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
            .map { File(it, "ndk") }
            .flatMap { it.listFiles()?.toList().orEmpty() }
            .maxByOrNull { it.name.substringBefore('.').toIntOrNull() ?: 0 }
        ?: error("Building QuickJS for Android needs the NDK: set ANDROID_NDK_HOME, or install an NDK in ANDROID_HOME")
    val host = when {
        os.startsWith("mac") -> "darwin-x86_64"
        os.startsWith("windows") -> "windows-x86_64"
        else -> "linux-x86_64"
    }
    return ndk.resolve("toolchains/llvm/prebuilt/$host/bin/clang" + if (os.startsWith("windows")) ".exe" else "").path
}

private fun run(command: List<String>) {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    val code = process.waitFor()
    check(code == 0) { "${command.first()} failed with exit code $code:\n$output" }
}
