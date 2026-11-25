/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.quickjs.bridge

import io.github.yuroyami.kitejs.api.JsEngineError
import io.github.yuroyami.kitejs.quickjs.QuickJs
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The copy of the JNI library that later processes load from the same path (#125), and how a load fails. */
class NativeLibraryTest {

    private val root = Files.createTempDirectory("kitejs-native-library-test").toFile()

    @AfterTest
    fun clean() {
        root.walkBottomUp().forEach { it.setWritable(true); it.delete() }
    }

    private val posix = root.toPath().fileSystem.supportedFileAttributeViews().contains("posix")

    @Test
    fun a_second_load_finds_the_same_file_and_leaves_it_as_it_is() {
        val base = File(root, "base")
        val bytes = byteArrayOf(1, 2, 3, 4)
        val first = assertNotNull(libraryCopy(base, "lib.dylib", bytes))
        assertContentEquals(bytes, first.readBytes())
        first.setLastModified(1_000_000)
        val second = assertNotNull(libraryCopy(base, "lib.dylib", bytes))
        assertEquals(first, second)
        assertEquals(1_000_000, second.lastModified(), "the file was written again")
        // The copy under a temporary name moved into place and left nothing behind.
        assertEquals(listOf("lib.dylib"), first.parentFile.list()!!.toList())
    }

    @Test
    fun a_file_cut_short_is_replaced() {
        val base = File(root, "base")
        val bytes = byteArrayOf(5, 6, 7)
        val first = assertNotNull(libraryCopy(base, "lib.so", bytes))
        first.writeBytes(byteArrayOf(5))
        val again = assertNotNull(libraryCopy(base, "lib.so", bytes))
        assertContentEquals(bytes, again.readBytes())
    }

    @Test
    fun another_build_of_the_library_gets_a_folder_of_its_own() {
        val base = File(root, "base")
        val a = assertNotNull(libraryCopy(base, "lib.so", byteArrayOf(1)))
        val b = assertNotNull(libraryCopy(base, "lib.so", byteArrayOf(2)))
        assertNotEquals(a.parentFile, b.parentFile)
        assertContentEquals(byteArrayOf(1), a.readBytes())
    }

    @Test
    fun a_folder_that_others_can_write_is_not_used() {
        val base = File(root, "open").also { it.mkdirs() }
        if (posix) {
            Files.setPosixFilePermissions(base.toPath(), PosixFilePermissions.fromString("rwxrwxrwx"))
            assertNull(libraryCopy(base, "lib.so", byteArrayOf(1)))
        } else {
            // Windows has no such permissions, and its temporary folder belongs to the user.
            assertNotNull(libraryCopy(base, "lib.so", byteArrayOf(1)))
        }
    }

    @Test
    fun a_new_folder_is_open_to_its_owner_only() {
        val base = File(root, "made")
        assertNotNull(libraryCopy(base, "lib.so", byteArrayOf(1)))
        if (posix) assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(base.toPath())))
    }

    @Test
    fun a_load_that_fails_is_an_engine_error_every_time() {
        var tries = 0
        val load = LibraryLoad { tries++; throw UnsatisfiedLinkError("no such file") }
        repeat(2) {
            val e = assertFailsWith<JsEngineError> { load.ensure() }
            assertTrue(e.message!!.contains("no such file"), e.message)
        }
        assertFalse(load.loaded)
        assertEquals(1, tries, "the load is tried once")
        // The loader's own message reaches the caller as it is.
        val own = LibraryLoad { throw JsEngineError("QuickJS has no JNI library for linux on riscv64") }
        assertEquals("QuickJS has no JNI library for linux on riscv64", assertFailsWith<JsEngineError> { own.ensure() }.message)
    }

    @Test
    fun a_shared_copy_that_does_not_load_falls_back_to_a_private_copy() {
        val shared = File(root, "shared.so")
        val fresh = File(root, "fresh.so")
        val loaded = mutableListOf<File>()
        loadCopy(shared, { fresh }) { if (it == shared) throw UnsatisfiedLinkError("already loaded in another classloader") else loaded += it }
        assertEquals(listOf(fresh), loaded)
        loaded.clear()
        loadCopy(null, { fresh }) { loaded += it }
        assertEquals(listOf(fresh), loaded)
    }

    @Test
    fun a_second_class_loader_loads_the_library_too() {
        assertTrue(QuickJs.isLoaded)
        val urls = System.getProperty("java.class.path").split(File.pathSeparator).map { File(it).toURI().toURL() }
        repeat(2) {
            URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
                val quickJs = loader.loadClass(QuickJs::class.java.name)
                assertTrue(quickJs !== QuickJs::class.java, "the class came from the class path again")
                val instance = quickJs.getField("INSTANCE").get(null)
                assertEquals(true, quickJs.getMethod("isLoaded").invoke(instance))
            }
        }
    }
}
