/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino.asm

/**
 * Adds accounting to the fused code only when an instruction observer is in use.
 *
 * Every control-flow entry starts a slice, as does the instruction after a call. Long blocks
 * are split further, so neither recursive calls nor straight-line code can avoid accounting.
 * A slice charges its typed instructions before running them; exceptions can leave part of that
 * charge unused. The unobserved code keeps its original instructions and branch targets.
 */
internal object AsmMeter {
    private const val MAX_SLICE = 256

    fun instrument(code: IntArray): IntArray {
        if (code.isEmpty()) return code
        val boundaries = BooleanArray(code.size + 1)
        boundaries[0] = true
        var at = 0
        while (at < code.size) {
            val op = code[at]
            val next = at + 1 + AsmOp.operandCount(op, code, at)
            when (op) {
                AsmOp.JMP, AsmOp.JZ, AsmOp.JNZ -> {
                    boundaries[code[at + 1]] = true
                    boundaries[next] = true
                }
                AsmOp.SWITCH -> {
                    boundaries[code[at + 1]] = true
                    for (i in 0 until code[at + 2]) boundaries[code[at + 4 + i * 2]] = true
                    boundaries[next] = true
                }
                AsmOp.CALL_DIRECT, AsmOp.CALL_INDIRECT, AsmOp.CALL_FFI, AsmOp.CALL_FFI_VOID,
                AsmOp.RET_I, AsmOp.RET_D, AsmOp.RET_V,
                -> boundaries[next] = true
            }
            at = next
        }

        // How many typed instructions each slice will charge. Splits never land on an operand.
        val costs = IntArray(code.size)
        var slice = 0
        var count = 0
        at = 0
        while (at < code.size) {
            if (boundaries[at] || count == MAX_SLICE) {
                if (count > 0) costs[slice] = count
                slice = at
                count = 0
            }
            count++
            at += 1 + AsmOp.operandCount(code[at], code, at)
        }
        costs[slice] = count

        // Map a branch target to its accounting instruction, so entering a block always pays.
        val moved = IntArray(code.size + 1)
        var size = 0
        at = 0
        while (at < code.size) {
            moved[at] = size
            if (costs[at] != 0) size += 2
            val width = 1 + AsmOp.operandCount(code[at], code, at)
            size += width
            at += width
        }
        moved[code.size] = size

        val result = IntArray(size)
        var write = 0
        at = 0
        while (at < code.size) {
            if (costs[at] != 0) {
                result[write++] = AsmOp.POLL
                result[write++] = costs[at]
            }
            val op = code[at]
            val operands = AsmOp.operandCount(op, code, at)
            result[write++] = op
            when (op) {
                AsmOp.JMP, AsmOp.JZ, AsmOp.JNZ -> result[write++] = moved[code[at + 1]]
                AsmOp.SWITCH -> {
                    result[write++] = moved[code[at + 1]]
                    val cases = code[at + 2]
                    result[write++] = cases
                    for (i in 0 until cases) {
                        result[write++] = code[at + 3 + i * 2]
                        result[write++] = moved[code[at + 4 + i * 2]]
                    }
                }
                else -> for (i in 1..operands) result[write++] = code[at + i]
            }
            at += 1 + operands
        }
        return result
    }
}
