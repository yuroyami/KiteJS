/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.yuroyami.kitejs.rhino

import io.github.yuroyami.kitejs.api.JsError
import io.github.yuroyami.kitejs.api.KiteJs
import io.github.yuroyami.kitejs.api.function
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import platform.windows.CloseHandle
import platform.windows.CreateThread
import platform.windows.STACK_SIZE_PARAM_IS_A_RESERVATION
import platform.windows.WAIT_OBJECT_0
import platform.windows.WaitForSingleObject

/** The depth guard must leave enough native stack to report its error on Windows. */
class WindowsStackGuardTest {

    @Test
    fun defaultAndLargerHostLimitsStopAndRecoverRepeatedly() {
        for (limit in listOf(64, 4096)) {
            KiteJs(Rhino) { maxHostCallDepth = limit }.use { js ->
                var callbacks = 0
                js.global.function("viaHost", 1) { args -> callbacks++; args[0].asFunction()() }
                repeat(3) {
                    callbacks = 0
                    val error = assertFailsWith<JsError> {
                        js.evaluate("function f() { viaHost(f); } f()")
                    }
                    assertEquals("RangeError", error.name)
                    assertEquals("Maximum call stack size exceeded", error.errorMessage)
                    assertTrue(callbacks in 1 until limit, "limit=$limit callbacks=$callbacks")
                    assertEquals(0, Context.getContext().interpreterInvocationDepth)
                    assertEquals(2, js.evaluate("1 + 1").asInt())
                }
            }
        }
    }

    @Test
    fun gettersBuiltinsAndTypedCallsKeepRoomForTheError() {
        KiteJs(Rhino) { maxHostCallDepth = 4096 }.use { js ->
            val scripts = listOf(
                "var o = { get x() { return o.x; } }; o.x;",
                "function mapAgain() { [0].map(mapAgain); } mapAgain();",
                """
                    var m = (function () {
                      "use asm";
                      function f(n) {
                        n = n | 0;
                        if ((n | 0) <= 0) return 1 | 0;
                        return f((n - 1) | 0) | 0;
                      }
                      return { f: f };
                    })();
                    m.f(1000);
                """.trimIndent(),
            )
            for (script in scripts) {
                assertEquals("RangeError", js.evaluate("try { $script } catch (e) { e.name }").asString(), script)
                assertEquals(0, Context.getContext().interpreterInvocationDepth)
                assertEquals(2, js.evaluate("1 + 1").asInt())
            }
            val report = js.asmReports.single()
            assertTrue(report.compiled && report.linked, "typed recursion must run: $report")
        }
    }

    @Test
    fun theCompatibilityProbeFindsTheStackAllocation() {
        val fallback = WindowsStackProbe(useSystemLimits = false)
        assertTrue(fallback.hasRoom(), "the current stack must have room before recursion")
        fun grow(depth: Int): Int {
            if (!fallback.hasRoom()) return depth
            check(depth < 20_000) { "the compatibility probe never reached the stack reserve" }
            val deepest = grow(depth + 1)
            return maxOf(depth, deepest)
        }
        assertTrue(grow(0) > 0)
        assertTrue(fallback.hasRoom(), "unwinding must release the native stack")
    }

    @Test
    fun aCallerOwnedThreadWithASmallerStackCanRecover() {
        val result = StableRef.create(AtomicReference<String?>(null))
        val thread = CreateThread(
            null, 524_288uL, staticCFunction(::runOnSmallStack), result.asCPointer(),
            STACK_SIZE_PARAM_IS_A_RESERVATION.toUInt(), null,
        ) ?: error("could not create the test thread")
        val wait = WaitForSingleObject(thread, 30_000u)
        CloseHandle(thread)
        // A timeout must not free a reference a running native callback still owns.
        assertEquals(WAIT_OBJECT_0.toUInt(), wait, "the bounded recursion test must finish")
        try {
            assertEquals("ok", result.get().load())
        } finally {
            result.dispose()
        }
    }
}

private fun runOnSmallStack(parameter: COpaquePointer?): UInt {
    val result = parameter!!.asStableRef<AtomicReference<String?>>().get()
    try {
        KiteJs(Rhino) { maxHostCallDepth = 4096 }.use { js ->
            js.global.function("viaHost", 1) { args -> args[0].asFunction()() }
            val error = assertFailsWith<JsError> { js.evaluate("function f() { viaHost(f); } f()") }
            assertEquals("RangeError", error.name)
            assertEquals(0, Context.getContext().interpreterInvocationDepth)
            assertEquals(2, js.evaluate("1 + 1").asInt())
        }
        result.store("ok")
    } catch (error: Throwable) {
        result.store(error.toString())
    }
    return 0u
}
