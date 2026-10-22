/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.quickjs.bridge

import io.github.yuroyami.kitejs.api.JsEngineError
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/**
 * Loads the JNI library. Setting the system property `kitejs.quickjs.library` to a path loads that
 * file, for a platform nothing else covers.
 *
 * On Android the library ships in the AAR's jni folder, one per ABI, and the app installs the one
 * its device needs, so it is loaded by name. Anywhere else this is a desktop JVM: the jar carries
 * one library per platform under `/jni/<os>-<arch>/`, and the right one is copied to a file, since
 * a library has to be a file to be loaded. Android's host tests run on a desktop JVM too, which is
 * why the choice is made here at run time rather than by the source set.
 *
 * The copy keeps one path for each build of the library, so a later process loads the same file.
 * macOS checks each library file it has not seen before, which made the first engine of every
 * process take about 350 ms longer.
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
    val bytes = stream.use { it.readBytes() }
    val user = System.getProperty("user.name").orEmpty().filter { it.isLetterOrDigit() || it == '-' || it == '_' }
    val base = File(System.getProperty("java.io.tmpdir"), "kitejs-quickjs-$user")
    val target = libraryCopy(base, file, bytes) ?: freshCopy(file, bytes)
    System.load(target.absolutePath)
}

/**
 * The library [bytes] as the file [name] in a folder under [base] named for their SHA-256: the
 * file already there when it has their size, else a new copy. Answers null when [base] is
 * not a folder that only this user can write, since another user could swap the library there.
 */
internal fun libraryCopy(base: File, name: String, bytes: ByteArray): File? = try {
    val digest = sha256(bytes)
    if (privateFolder(base)) {
        val dir = File(base, digest.substring(0, 16))
        dir.mkdirs()
        val target = File(dir, name)
        when {
            // The folder is named for the bytes, and a file only gets there whole, so its size is enough.
            target.isFile && target.length() == bytes.size.toLong() -> target
            else -> {
                // A copy under another name moves into place at once, so a process that starts at
                // the same time never loads half a file.
                val part = File.createTempFile(name, ".part", dir)
                try {
                    part.writeBytes(bytes)
                    try {
                        Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: IOException) {
                        // Windows refuses to replace a library that another process has loaded.
                    }
                } finally {
                    part.delete()
                }
                target.takeIf { it.isFile && sha256(it.readBytes()) == digest }
            }
        }
    } else {
        null
    }
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}

/** Makes [dir] when it is missing, and answers whether it is a folder that only this user can write. */
private fun privateFolder(dir: File): Boolean {
    val path = dir.toPath()
    val posix = path.fileSystem.supportedFileAttributeViews().contains("posix")
    if (!dir.exists()) {
        if (posix) Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        else Files.createDirectories(path)
    }
    if (!dir.isDirectory || Files.isSymbolicLink(path)) return false
    // Without POSIX permissions this is Windows, whose temporary folder belongs to the user.
    if (!posix) return true
    if (Files.getOwner(path).name != System.getProperty("user.name")) return false
    val permissions = Files.getPosixFilePermissions(path)
    return PosixFilePermission.GROUP_WRITE !in permissions && PosixFilePermission.OTHERS_WRITE !in permissions
}

/** A copy of the library in a new temporary folder, deleted when the process ends. */
private fun freshCopy(name: String, bytes: ByteArray): File {
    val dir = Files.createTempDirectory("kitejs-quickjs").toFile()
    val target = File(dir, name)
    target.writeBytes(bytes)
    target.deleteOnExit()
    dir.deleteOnExit()
    return target
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
