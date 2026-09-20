/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.asm

import io.github.yuroyami.kitejs.Callable
import io.github.yuroyami.kitejs.Context
import io.github.yuroyami.kitejs.ScriptRuntime
import io.github.yuroyami.kitejs.Scriptable
import io.github.yuroyami.kitejs.typedarrays.NativeArrayBuffer
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A linked module: the compiled code, the heap it was given and the imports it resolved to. */
internal class AsmInstance(
    val module: AsmModule,
    val buffer: NativeArrayBuffer,
    val globalInts: IntArray,
    val globalDbls: DoubleArray,
    val ffi: Array<Callable?>,
    val ffiThis: Array<Scriptable?>,
    val scope: Scriptable,
)

/**
 * Runs the typed code of a linked module.
 *
 * There is one of these per instance, and it holds the two stacks every function of the module
 * shares. A call does not build a frame: it takes the slots above the caller's, and because the
 * caller pushed its arguments in the order the callee's parameters are numbered, the arguments
 * are already in the right slots and nothing is copied.
 *
 * Calls use the host's own call stack, so a runaway recursion in the module ends as a
 * `StackOverflowError`, which the export turns into the same error a script would have seen.
 */
internal class AsmRunner(private val instance: AsmInstance) {

    private var ints = IntArray(4096)
    private var dbls = DoubleArray(2048)

    /** The bytes the module reads and writes. Re-read whenever the module is entered. */
    var heap: ByteArray = instance.buffer.buffer ?: ByteArray(0)

    /** Where a returning function leaves its answer. */
    var retInt: Int = 0
    var retDbl: Double = 0.0

    private var depth = 0

    /** Counted on backward jumps, which is where a module that never returns has to be caught. */
    private var backJumps = 0

    /** Re-reads the heap. Called whenever the module is entered from outside. */
    fun enter() {
        heap = instance.buffer.buffer ?: throw ScriptRuntime.typeError("the module's heap was detached")
    }

    private fun room(intsNeeded: Int, dblsNeeded: Int) {
        if (intsNeeded > ints.size) {
            if (intsNeeded > MAX_STACK) throw ScriptRuntime.rangeError("the module ran out of stack")
            ints = ints.copyOf(maxOf(ints.size * 2, intsNeeded))
        }
        if (dblsNeeded > dbls.size) {
            if (dblsNeeded > MAX_STACK) throw ScriptRuntime.rangeError("the module ran out of stack")
            dbls = dbls.copyOf(maxOf(dbls.size * 2, dblsNeeded))
        }
    }

    /** Pushes one integer argument before a call, growing the stack if it has to. */
    fun pushArgInt(at: Int, value: Int) {
        room(at + 1, 0)
        ints[at] = value
    }

    fun pushArgDbl(at: Int, value: Double) {
        room(0, at + 1)
        dbls[at] = value
    }

    /**
     * Runs the function at [fnIndex] with its arguments already in the slots at [ibase] and
     * [dbase]. The answer is left in [retInt] or [retDbl].
     */
    fun run(cx: Context, fnIndex: Int, ibase: Int, dbase: Int) {
        if (++depth > MAX_DEPTH) {
            depth--
            throw ScriptRuntime.rangeError("the module called too deeply")
        }
        try {
            execute(cx, instance.module.functions[fnIndex], ibase, dbase)
        } finally {
            depth--
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
    private fun execute(cx: Context, fn: AsmFunction, ibase: Int, dbase: Int) {
        room(ibase + fn.intFrame, dbase + fn.dblFrame)
        val code = fn.code
        val pool = fn.doubles
        var iv = ints
        var dv = dbls
        var ip = ibase + fn.intLocals
        var dp = dbase + fn.dblLocals
        var pc = 0

        while (true) {
            when (code[pc++]) {
                AsmOp.I_CONST -> iv[ip++] = code[pc++]
                AsmOp.D_CONST -> dv[dp++] = pool[code[pc++]]
                AsmOp.I_LOAD -> iv[ip++] = iv[ibase + code[pc++]]
                AsmOp.I_STORE -> iv[ibase + code[pc++]] = iv[--ip]
                AsmOp.I_STORE_KEEP -> iv[ibase + code[pc++]] = iv[ip - 1]
                AsmOp.D_LOAD -> dv[dp++] = dv[dbase + code[pc++]]
                AsmOp.D_STORE -> dv[dbase + code[pc++]] = dv[--dp]
                AsmOp.D_STORE_KEEP -> dv[dbase + code[pc++]] = dv[dp - 1]

                AsmOp.GI_LOAD -> iv[ip++] = instance.globalInts[code[pc++]]
                AsmOp.GI_STORE -> instance.globalInts[code[pc++]] = iv[--ip]
                AsmOp.GI_STORE_KEEP -> instance.globalInts[code[pc++]] = iv[ip - 1]
                AsmOp.GD_LOAD -> dv[dp++] = instance.globalDbls[code[pc++]]
                AsmOp.GD_STORE -> instance.globalDbls[code[pc++]] = dv[--dp]
                AsmOp.GD_STORE_KEEP -> instance.globalDbls[code[pc++]] = dv[dp - 1]

                AsmOp.I_ADD -> { ip--; iv[ip - 1] = iv[ip - 1] + iv[ip] }
                AsmOp.I_SUB -> { ip--; iv[ip - 1] = iv[ip - 1] - iv[ip] }
                AsmOp.I_MUL -> { ip--; iv[ip - 1] = iv[ip - 1] * iv[ip] }
                AsmOp.I_DIV_S -> { ip--; iv[ip - 1] = divS(iv[ip - 1], iv[ip]) }
                AsmOp.I_DIV_U -> { ip--; iv[ip - 1] = divU(iv[ip - 1], iv[ip]) }
                AsmOp.I_REM_S -> { ip--; iv[ip - 1] = remS(iv[ip - 1], iv[ip]) }
                AsmOp.I_REM_U -> { ip--; iv[ip - 1] = remU(iv[ip - 1], iv[ip]) }
                AsmOp.I_AND -> { ip--; iv[ip - 1] = iv[ip - 1] and iv[ip] }
                AsmOp.I_OR -> { ip--; iv[ip - 1] = iv[ip - 1] or iv[ip] }
                AsmOp.I_XOR -> { ip--; iv[ip - 1] = iv[ip - 1] xor iv[ip] }
                AsmOp.I_SHL -> { ip--; iv[ip - 1] = iv[ip - 1] shl (iv[ip] and 31) }
                AsmOp.I_SHR -> { ip--; iv[ip - 1] = iv[ip - 1] shr (iv[ip] and 31) }
                AsmOp.I_USHR -> { ip--; iv[ip - 1] = iv[ip - 1] ushr (iv[ip] and 31) }
                AsmOp.I_NEG -> iv[ip - 1] = -iv[ip - 1]
                AsmOp.I_NOT -> iv[ip - 1] = iv[ip - 1].inv()
                AsmOp.I_EQZ -> iv[ip - 1] = if (iv[ip - 1] == 0) 1 else 0

                AsmOp.I_EQ -> { ip--; iv[ip - 1] = if (iv[ip - 1] == iv[ip]) 1 else 0 }
                AsmOp.I_NE -> { ip--; iv[ip - 1] = if (iv[ip - 1] != iv[ip]) 1 else 0 }
                AsmOp.I_LT_S -> { ip--; iv[ip - 1] = if (iv[ip - 1] < iv[ip]) 1 else 0 }
                AsmOp.I_LE_S -> { ip--; iv[ip - 1] = if (iv[ip - 1] <= iv[ip]) 1 else 0 }
                AsmOp.I_GT_S -> { ip--; iv[ip - 1] = if (iv[ip - 1] > iv[ip]) 1 else 0 }
                AsmOp.I_GE_S -> { ip--; iv[ip - 1] = if (iv[ip - 1] >= iv[ip]) 1 else 0 }
                AsmOp.I_LT_U -> { ip--; iv[ip - 1] = if (ltU(iv[ip - 1], iv[ip])) 1 else 0 }
                AsmOp.I_LE_U -> { ip--; iv[ip - 1] = if (!ltU(iv[ip], iv[ip - 1])) 1 else 0 }
                AsmOp.I_GT_U -> { ip--; iv[ip - 1] = if (ltU(iv[ip], iv[ip - 1])) 1 else 0 }
                AsmOp.I_GE_U -> { ip--; iv[ip - 1] = if (!ltU(iv[ip - 1], iv[ip])) 1 else 0 }

                AsmOp.D_ADD -> { dp--; dv[dp - 1] = dv[dp - 1] + dv[dp] }
                AsmOp.D_SUB -> { dp--; dv[dp - 1] = dv[dp - 1] - dv[dp] }
                AsmOp.D_MUL -> { dp--; dv[dp - 1] = dv[dp - 1] * dv[dp] }
                AsmOp.D_DIV -> { dp--; dv[dp - 1] = dv[dp - 1] / dv[dp] }
                AsmOp.D_REM -> { dp--; dv[dp - 1] = dv[dp - 1] % dv[dp] }
                AsmOp.D_NEG -> dv[dp - 1] = -dv[dp - 1]
                AsmOp.D_EQ -> { dp -= 2; iv[ip++] = if (dv[dp] == dv[dp + 1]) 1 else 0 }
                AsmOp.D_NE -> { dp -= 2; iv[ip++] = if (dv[dp] != dv[dp + 1]) 1 else 0 }
                AsmOp.D_LT -> { dp -= 2; iv[ip++] = if (dv[dp] < dv[dp + 1]) 1 else 0 }
                AsmOp.D_LE -> { dp -= 2; iv[ip++] = if (dv[dp] <= dv[dp + 1]) 1 else 0 }
                AsmOp.D_GT -> { dp -= 2; iv[ip++] = if (dv[dp] > dv[dp + 1]) 1 else 0 }
                AsmOp.D_GE -> { dp -= 2; iv[ip++] = if (dv[dp] >= dv[dp + 1]) 1 else 0 }

                AsmOp.I2D_S -> dv[dp++] = iv[--ip].toDouble()
                AsmOp.I2D_U -> dv[dp++] = (iv[--ip].toLong() and 0xFFFFFFFFL).toDouble()
                AsmOp.D2I -> iv[ip++] = ScriptRuntime.toInt32(dv[--dp])
                AsmOp.D_FROUND -> dv[dp - 1] = dv[dp - 1].toFloat().toDouble()

                AsmOp.H_LOAD_I8 -> iv[ip - 1] = loadI8(iv[ip - 1])
                AsmOp.H_LOAD_U8 -> iv[ip - 1] = loadI8(iv[ip - 1]) and 0xFF
                AsmOp.H_LOAD_I16 -> iv[ip - 1] = loadI16(iv[ip - 1])
                AsmOp.H_LOAD_U16 -> iv[ip - 1] = loadI16(iv[ip - 1]) and 0xFFFF
                AsmOp.H_LOAD_I32 -> iv[ip - 1] = loadI32(iv[ip - 1])
                AsmOp.H_LOAD_F32 -> dv[dp++] = Float.fromBits(loadI32(iv[--ip])).toDouble()
                AsmOp.H_LOAD_F64 -> dv[dp++] = loadF64(iv[--ip])
                AsmOp.H_STORE_I8 -> { ip -= 2; storeI8(iv[ip], iv[ip + 1]) }
                AsmOp.H_STORE_I16 -> { ip -= 2; storeI16(iv[ip], iv[ip + 1]) }
                AsmOp.H_STORE_I32 -> { ip -= 2; storeI32(iv[ip], iv[ip + 1]) }
                AsmOp.H_STORE_F32 -> { ip--; dp--; storeI32(iv[ip], dv[dp].toFloat().toRawBits()) }
                AsmOp.H_STORE_F64 -> { ip--; dp--; storeF64(iv[ip], dv[dp]) }

                AsmOp.M_IMUL -> { ip--; iv[ip - 1] = iv[ip - 1] * iv[ip] }
                AsmOp.M_ABS_I -> iv[ip - 1] = if (iv[ip - 1] < 0) -iv[ip - 1] else iv[ip - 1]
                AsmOp.M_CLZ32 -> iv[ip - 1] = clz32(iv[ip - 1])
                AsmOp.M_ABS_D -> dv[dp - 1] = abs(dv[dp - 1])
                AsmOp.M_FLOOR -> dv[dp - 1] = floor(dv[dp - 1])
                AsmOp.M_CEIL -> dv[dp - 1] = ceil(dv[dp - 1])
                AsmOp.M_SQRT -> dv[dp - 1] = sqrt(dv[dp - 1])
                AsmOp.M_SIN -> dv[dp - 1] = sin(dv[dp - 1])
                AsmOp.M_COS -> dv[dp - 1] = cos(dv[dp - 1])
                AsmOp.M_TAN -> dv[dp - 1] = tan(dv[dp - 1])
                AsmOp.M_ASIN -> dv[dp - 1] = asin(dv[dp - 1])
                AsmOp.M_ACOS -> dv[dp - 1] = acos(dv[dp - 1])
                AsmOp.M_ATAN -> dv[dp - 1] = atan(dv[dp - 1])
                AsmOp.M_ATAN2 -> { dp--; dv[dp - 1] = atan2(dv[dp - 1], dv[dp]) }
                AsmOp.M_POW -> { dp--; dv[dp - 1] = jsPow(dv[dp - 1], dv[dp]) }
                AsmOp.M_EXP -> dv[dp - 1] = exp(dv[dp - 1])
                AsmOp.M_LOG -> dv[dp - 1] = ln(dv[dp - 1])
                AsmOp.M_MIN_D -> { dp--; dv[dp - 1] = jsMin(dv[dp - 1], dv[dp]) }
                AsmOp.M_MAX_D -> { dp--; dv[dp - 1] = jsMax(dv[dp - 1], dv[dp]) }
                AsmOp.M_MIN_I -> { ip--; if (iv[ip] < iv[ip - 1]) iv[ip - 1] = iv[ip] }
                AsmOp.M_MAX_I -> { ip--; if (iv[ip] > iv[ip - 1]) iv[ip - 1] = iv[ip] }

                AsmOp.JMP -> {
                    val target = code[pc]
                    if (target <= pc) poll(cx)
                    pc = target
                }
                AsmOp.JZ -> {
                    val target = code[pc++]
                    if (iv[--ip] == 0) {
                        if (target <= pc) poll(cx)
                        pc = target
                    }
                }
                AsmOp.JNZ -> {
                    val target = code[pc++]
                    if (iv[--ip] != 0) {
                        if (target <= pc) poll(cx)
                        pc = target
                    }
                }
                AsmOp.SWITCH -> {
                    val defaultTarget = code[pc++]
                    val count = code[pc++]
                    val value = iv[--ip]
                    var next = defaultTarget
                    var i = 0
                    while (i < count) {
                        if (code[pc + i * 2] == value) {
                            next = code[pc + i * 2 + 1]
                            break
                        }
                        i++
                    }
                    pc = next
                }

                AsmOp.RET_I -> { retInt = iv[ip - 1]; return }
                AsmOp.RET_D -> { retDbl = dv[dp - 1]; return }
                AsmOp.RET_V -> return

                AsmOp.CALL_DIRECT -> {
                    val index = code[pc++]
                    val callee = instance.module.functions[index]
                    ip -= callee.intParams
                    dp -= callee.dblParams
                    run(cx, index, ip, dp)
                    iv = ints
                    dv = dbls
                    when {
                        callee.returnType == AsmType.VOID -> Unit
                        AsmType.isDbl(callee.returnType) -> dv[dp++] = retDbl
                        else -> iv[ip++] = retInt
                    }
                }
                AsmOp.CALL_INDIRECT -> {
                    val table = instance.module.tables[code[pc++]]
                    // Every function in one table takes the same arguments, so the first one
                    // says how many slots they fill. The table offset was pushed before them
                    // and so sits just below.
                    val callee = instance.module.functions[table.entries[0]]
                    ip -= callee.intParams
                    dp -= callee.dblParams
                    val entry = table.entries[iv[ip - 1]]
                    run(cx, entry, ip, dp)
                    ip--
                    iv = ints
                    dv = dbls
                    when {
                        callee.returnType == AsmType.VOID -> Unit
                        AsmType.isDbl(callee.returnType) -> dv[dp++] = retDbl
                        else -> iv[ip++] = retInt
                    }
                }
                AsmOp.CALL_FFI -> {
                    val index = code[pc++]
                    val packed = code[pc++]
                    val count = packed and 0xFF
                    val args = arrayOfNulls<Any?>(count)
                    // The arguments were pushed in order onto whichever stack their type uses,
                    // so they come off in reverse.
                    for (i in count - 1 downTo 0) {
                        args[i] = if ((packed and (1 shl (8 + i))) != 0) dv[--dp] else iv[--ip].toDouble()
                    }
                    val answer = callForeign(cx, index, args)
                    room(ip, dp + 1)
                    iv = ints
                    dv = dbls
                    dv[dp++] = answer
                }

                AsmOp.I_DROP -> ip--
                AsmOp.D_DROP -> dp--
                else -> throw IllegalStateException("unknown asm instruction ${code[pc - 1]}")
            }
        }
    }

    private fun callForeign(cx: Context, index: Int, args: Array<Any?>): Double {
        val callable = instance.ffi[index] ?: throw ScriptRuntime.typeError(
            "${instance.module.ffiNames[index]} is not a function",
        )
        val thisObj = instance.ffiThis[index] ?: instance.scope
        val answer = callable.call(cx, instance.scope, thisObj, args)
        // Reading the heap again, because the call may have replaced the buffer's bytes.
        heap = instance.buffer.buffer ?: ByteArray(0)
        return ScriptRuntime.toNumber(answer)
    }

    /** Asks the engine whether the module should keep running. */
    private fun poll(cx: Context) {
        if (++backJumps < POLL_EVERY) return
        backJumps = 0
        if (cx.instructionThreshold <= 0) return
        cx.instructionCount += POLL_EVERY
        if (cx.instructionCount > cx.instructionThreshold) {
            cx.observeInstructionCountInternal(cx.instructionCount)
            cx.instructionCount = 0
        }
    }

    // ---- The heap -----------------------------------------------------------------------------
    //
    // Reading past the end answers zero and writing past it does nothing, which is what a typed
    // array view does and so what the module would have seen through one.

    private fun loadI8(at: Int): Int {
        val h = heap
        if (at < 0 || at >= h.size) return 0
        return h[at].toInt()
    }

    private fun loadI16(at: Int): Int {
        val h = heap
        if (at < 0 || at + 2 > h.size) return 0
        return (h[at].toInt() and 0xFF) or (h[at + 1].toInt() shl 8)
    }

    private fun loadI32(at: Int): Int {
        val h = heap
        if (at < 0 || at + 4 > h.size) return 0
        return (h[at].toInt() and 0xFF) or
            ((h[at + 1].toInt() and 0xFF) shl 8) or
            ((h[at + 2].toInt() and 0xFF) shl 16) or
            (h[at + 3].toInt() shl 24)
    }

    private fun loadF64(at: Int): Double {
        val h = heap
        if (at < 0 || at + 8 > h.size) return Double.NaN
        var bits = 0L
        for (i in 7 downTo 0) bits = (bits shl 8) or (h[at + i].toLong() and 0xFF)
        return Double.fromBits(bits)
    }

    private fun storeI8(at: Int, value: Int) {
        val h = heap
        if (at < 0 || at >= h.size) return
        h[at] = value.toByte()
    }

    private fun storeI16(at: Int, value: Int) {
        val h = heap
        if (at < 0 || at + 2 > h.size) return
        h[at] = value.toByte()
        h[at + 1] = (value shr 8).toByte()
    }

    private fun storeI32(at: Int, value: Int) {
        val h = heap
        if (at < 0 || at + 4 > h.size) return
        h[at] = value.toByte()
        h[at + 1] = (value shr 8).toByte()
        h[at + 2] = (value shr 16).toByte()
        h[at + 3] = (value shr 24).toByte()
    }

    private fun storeF64(at: Int, value: Double) {
        val h = heap
        if (at < 0 || at + 8 > h.size) return
        var bits = value.toRawBits()
        for (i in 0 until 8) {
            h[at + i] = bits.toByte()
            bits = bits shr 8
        }
    }

    private companion object {
        const val MAX_STACK = 1 shl 22
        const val MAX_DEPTH = 8192
        const val POLL_EVERY = 4096
    }
}

// ---- The arithmetic JavaScript asks for, where Kotlin's own differs -----------------------------

/** `(a | 0) / (b | 0) | 0`. JavaScript divides as doubles, so neither case here throws. */
internal fun divS(a: Int, b: Int): Int = when {
    b == 0 -> 0 // a / 0 is an infinity or a NaN, and | 0 turns all three into zero
    a == Int.MIN_VALUE && b == -1 -> Int.MIN_VALUE // the quotient is 2^31, which wraps
    else -> a / b
}

internal fun divU(a: Int, b: Int): Int {
    if (b == 0) return 0
    return ((a.toLong() and 0xFFFFFFFFL) / (b.toLong() and 0xFFFFFFFFL)).toInt()
}

internal fun remS(a: Int, b: Int): Int = when {
    b == 0 -> 0 // a % 0 is NaN, and | 0 makes it zero
    a == Int.MIN_VALUE && b == -1 -> 0
    else -> a % b
}

internal fun remU(a: Int, b: Int): Int {
    if (b == 0) return 0
    return ((a.toLong() and 0xFFFFFFFFL) % (b.toLong() and 0xFFFFFFFFL)).toInt()
}

/** Compares two 32 bit words as counts rather than as signed numbers. */
internal fun ltU(a: Int, b: Int): Boolean = (a xor Int.MIN_VALUE) < (b xor Int.MIN_VALUE)

/** How many zero bits a word starts with, which is what `Math.clz32` answers. */
internal fun clz32(value: Int): Int {
    if (value == 0) return 32
    var v = value
    var n = 0
    if (v ushr 16 == 0) { n += 16; v = v shl 16 }
    if (v ushr 24 == 0) { n += 8; v = v shl 8 }
    if (v ushr 28 == 0) { n += 4; v = v shl 4 }
    if (v ushr 30 == 0) { n += 2; v = v shl 2 }
    if (v ushr 31 == 0) n += 1
    return n
}

/** `Math.pow`, whose answer for a few arguments is not the one Kotlin gives. */
internal fun jsPow(base: Double, exponent: Double): Double {
    if (exponent.isNaN()) return Double.NaN
    // Kotlin answers 1.0 for pow(NaN, 0.0) and for pow(1.0, Infinity); JavaScript answers NaN
    // for the second.
    if (exponent == 0.0) return 1.0
    if ((base == 1.0 || base == -1.0) && exponent.isInfinite()) return Double.NaN
    return base.pow(exponent)
}

/** `Math.min`, which answers NaN when either side is NaN and tells -0 from 0. */
internal fun jsMin(a: Double, b: Double): Double {
    if (a.isNaN() || b.isNaN()) return Double.NaN
    if (a == 0.0 && b == 0.0) return if (a.toRawBits() != 0L) a else b
    return if (a < b) a else b
}

internal fun jsMax(a: Double, b: Double): Double {
    if (a.isNaN() || b.isNaN()) return Double.NaN
    if (a == 0.0 && b == 0.0) return if (a.toRawBits() == 0L) a else b
    return if (a > b) a else b
}
