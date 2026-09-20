/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.asm

/**
 * Folds pairs of instructions that always appear together into one.
 *
 * Counting what a real module compiles to says where the work goes. In Doom's module, a quarter of
 * all instructions are `I_CONST`, and most of them exist only to hand a number to the instruction
 * after them. Every single `I_STORE_KEEP` is followed by `I_DROP`, because an assignment written
 * as a statement keeps its value and then throws it away.
 *
 * Folding a pair removes a dispatch and a round trip through the stack. It has to leave the
 * program doing the same thing, so a pair is only folded when nothing can jump between the two:
 * the second instruction must not be the target of any jump.
 *
 * Folding moves everything after it, so this runs in two passes. The first decides what to fold
 * and works out where each instruction ends up; the second writes the code out with every jump
 * pointing at the new position.
 */
internal object AsmFuse {

    /** Runs the folding until nothing more folds, since one fold can expose the next. */
    fun fuse(code: IntArray): IntArray {
        var current = code
        repeat(MAX_ROUNDS) {
            val next = onePass(current)
            if (next.size == current.size) return next
            current = next
        }
        return current
    }

    /** How many times to go round. Two rounds cover every pair the counting found. */
    private const val MAX_ROUNDS = 3

    private fun onePass(code: IntArray): IntArray {
        if (code.isEmpty()) return code
        val starts = instructionStarts(code)
        val isTarget = jumpTargets(code, starts)

        // Pass one: decide, and map every old position to its new one.
        val folded = arrayOfNulls<IntArray>(starts.size)
        val moved = HashMap<Int, Int>(starts.size * 2)
        var out = 0
        var index = 0
        while (index < starts.size) {
            val at = starts[index]
            moved[at] = out
            val next = if (index + 1 < starts.size) starts[index + 1] else -1
            val fused = if (next >= 0 && !isTarget[index + 1]) fold(code, at, next) else null
            if (fused != null) {
                folded[index] = fused
                moved[next] = out
                out += fused.size
                index += 2
            } else {
                out += 1 + AsmOp.operandCount(code[at], code, at)
                index++
            }
        }
        if (out == code.size) return code
        moved[code.size] = out

        // Pass two: write it out, with every jump aimed at where its target moved to.
        val result = IntArray(out)
        var write = 0
        index = 0
        while (index < starts.size) {
            val at = starts[index]
            val fused = folded[index]
            if (fused != null) {
                for (word in fused) result[write++] = word
                index += 2
                continue
            }
            val op = code[at]
            val operands = AsmOp.operandCount(op, code, at)
            result[write++] = op
            when (op) {
                AsmOp.JMP, AsmOp.JZ, AsmOp.JNZ -> result[write++] = moved.getValue(code[at + 1])
                AsmOp.SWITCH -> {
                    result[write++] = moved.getValue(code[at + 1])
                    val count = code[at + 2]
                    result[write++] = count
                    for (i in 0 until count) {
                        result[write++] = code[at + 3 + i * 2]
                        result[write++] = moved.getValue(code[at + 4 + i * 2])
                    }
                }
                else -> for (i in 1..operands) result[write++] = code[at + i]
            }
            index++
        }
        return result
    }

    /** The instruction the pair at [first] and [second] folds into, or null when it is not one. */
    private fun fold(code: IntArray, first: Int, second: Int): IntArray? {
        val a = code[first]
        val b = code[second]
        return when {
            // `x = ...` as a statement: keep the value, then drop it. A plain store does both.
            a == AsmOp.I_STORE_KEEP && b == AsmOp.I_DROP -> intArrayOf(AsmOp.I_STORE, code[first + 1])
            a == AsmOp.D_STORE_KEEP && b == AsmOp.D_DROP -> intArrayOf(AsmOp.D_STORE, code[first + 1])
            a == AsmOp.GI_STORE_KEEP && b == AsmOp.I_DROP -> intArrayOf(AsmOp.GI_STORE, code[first + 1])
            a == AsmOp.GD_STORE_KEEP && b == AsmOp.D_DROP -> intArrayOf(AsmOp.GD_STORE, code[first + 1])


            // A constant handed straight to the instruction after it.
            a == AsmOp.I_CONST && AsmOp.withConstant(b) >= 0 -> intArrayOf(AsmOp.withConstant(b), code[first + 1])



            else -> null
        }
    }

    /** Where every instruction begins, in order. */
    private fun instructionStarts(code: IntArray): IntArray {
        val starts = ArrayList<Int>(code.size / 2)
        var at = 0
        while (at < code.size) {
            starts.add(at)
            at += 1 + AsmOp.operandCount(code[at], code, at)
        }
        return starts.toIntArray()
    }

    /** True at the index of every instruction some jump can land on. */
    private fun jumpTargets(code: IntArray, starts: IntArray): BooleanArray {
        val indexOfStart = HashMap<Int, Int>(starts.size * 2)
        for (i in starts.indices) indexOfStart[starts[i]] = i
        val isTarget = BooleanArray(starts.size)
        fun mark(position: Int) {
            indexOfStart[position]?.let { isTarget[it] = true }
        }
        for (at in starts) {
            when (code[at]) {
                AsmOp.JMP, AsmOp.JZ, AsmOp.JNZ -> mark(code[at + 1])
                AsmOp.SWITCH -> {
                    mark(code[at + 1])
                    for (i in 0 until code[at + 2]) mark(code[at + 4 + i * 2])
                }
            }
        }
        return isTarget
    }
}
