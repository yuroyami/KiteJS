/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kitejs.rhino

import kotlin.native.ref.createCleaner
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import platform.windows.CONTEXT
import platform.windows.GetModuleHandleW
import platform.windows.GetProcAddress
import platform.windows.MEMORY_BASIC_INFORMATION
import platform.windows.RtlCaptureContext
import platform.windows.VirtualQuery

internal actual fun nativeStackProbe(): (() -> Boolean)? = WindowsStackProbe()::hasRoom

private typealias StackLimits = CFunction<(CPointer<ULongVar>?, CPointer<ULongVar>?) -> Unit>

/** Reused storage, released when the owning context and its bound probe become unreachable. */
private class StackStorage {
    val registers = nativeHeap.alloc<CONTEXT>()
    val bounds = nativeHeap.allocArray<ULongVar>(2)
    val region = nativeHeap.alloc<MEMORY_BASIC_INFORMATION>()

    fun release() {
        nativeHeap.free(registers.ptr.rawValue)
        nativeHeap.free(bounds.rawValue)
        nativeHeap.free(region.ptr.rawValue)
    }
}

internal class WindowsStackProbe(useSystemLimits: Boolean = true) {
    private val storage = StackStorage()
    @Suppress("unused")
    private val cleaner = createCleaner(storage) { it.release() }

    // Kotlin's Windows headers target Windows 7. Resolve the Windows 8 API rather than making
    // it a mandatory import; VirtualQuery supplies the allocation boundary on older systems.
    private val limits = if (useSystemLimits) {
        GetProcAddress(GetModuleHandleW("kernel32.dll"), "GetCurrentThreadStackLimits")?.reinterpret<StackLimits>()
    } else null

    fun hasRoom(): Boolean {
        RtlCaptureContext(storage.registers.ptr)
        val stackPointer = storage.registers.Rsp
        val low: ULong
        if (limits != null) {
            limits(storage.bounds, storage.bounds + 1)
            low = storage.bounds[0]
            val high = storage.bounds[1]
            if (stackPointer > high) return false
        } else {
            val address = stackPointer.toLong().toCPointer<ByteVar>()
            if (VirtualQuery(address, storage.region.ptr, sizeOf<MEMORY_BASIC_INFORMATION>().toULong()) == 0uL) {
                return false
            }
            low = storage.region.AllocationBase?.rawValue?.toLong()?.toULong() ?: return false
        }
        // Leave room for a native execution frame, error construction and unwinding. A logical
        // depth limit cannot measure those: host callbacks and caller-owned threads vary.
        return stackPointer > low && stackPointer - low >= STACK_HEADROOM
    }

    private companion object {
        const val STACK_HEADROOM = 262_144uL
    }
}
