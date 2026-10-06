/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.quickjs.bridge

import io.github.yuroyami.kitejs.api.JsEngineError
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Loads the JNI library. Setting the system property `kitejs.quickjs.library` to a path loads that
 * file, for a platform nothing else covers.
 *
 * On Android the library ships in the AAR's jni folder, one per ABI, and the app installs the one
 * its device needs, so it is loaded by name. Anywhere else this is a desktop JVM: the jar carries
 * one library per platform under `/jni/<os>-<arch>/`, and the right one is copied to a temporary
 * file, since a library has to be a file to be loaded. Android's host tests run on a desktop JVM
 * too, which is why the choice is made here at run time rather than by the source set.
 */
internal fun loadNativeLibrary() {
    System.getProperty("kitejs.quickjs.library")?.let { path ->
        System.load(File(path).absolutePath)
        return
    }
    if (System.getProperty("java.vm.vendor").orEmpty().contains("Android", ignoreCase = true) ||
        System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true)
    ) {
        System.loadLibrary("kitejs_quickjs")
        return
    }
    val os = System.getProperty("os.name").lowercase()
    val arch = when (val a = System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> a
    }
    val (platform, file) = when {
        os.contains("linux") -> "linux" to "libkitejs_quickjs.so"
        os.contains("mac") || os.contains("darwin") -> "macos" to "libkitejs_quickjs.dylib"
        os.contains("windows") -> "windows" to "kitejs_quickjs.dll"
        else -> throw JsEngineError("QuickJS has no JNI library for $os; set kitejs.quickjs.library to one built for it")
    }
    val resource = "/jni/$platform-$arch/$file"
    val stream = HandleCleaner::class.java.getResourceAsStream(resource)
        ?: throw JsEngineError(
            "QuickJS has no JNI library for $platform on $arch; set kitejs.quickjs.library to one built for it. " +
                "An Android host test needs the JVM artifact of kitejs-quickjs on its classpath: " +
                "https://yuroyami.github.io/KiteJS/engines/#android-host-tests",
        )
    val dir = Files.createTempDirectory("kitejs-quickjs").toFile()
    val target = File(dir, file)
    stream.use { Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    target.deleteOnExit()
    dir.deleteOnExit()
    System.load(target.absolutePath)
}
