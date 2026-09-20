/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.asm

/**
 * The instruction set the typed path runs.
 *
 * There are two operand stacks, one of integers and one of doubles, and an instruction knows
 * which one it works on before it runs. That is the difference from the general interpreter,
 * where every stack slot is an object reference and every instruction has to ask what it holds.
 *
 * The code is an `IntArray`, so an operand is just the next integer and nothing has to be decoded.
 * Jump targets are absolute positions in that array.
 */
internal object AsmOp {
    // Constants and locals.
    const val I_CONST = 0 // operand: the value
    const val D_CONST = 1 // operand: index into the double pool
    const val I_LOAD = 2 // operand: integer slot
    const val I_STORE = 3 // operand: integer slot, pops
    const val I_STORE_KEEP = 4 // operand: integer slot, leaves the value on the stack
    const val D_LOAD = 5
    const val D_STORE = 6
    const val D_STORE_KEEP = 7

    // Module level globals.
    const val GI_LOAD = 8
    const val GI_STORE = 9
    const val GI_STORE_KEEP = 10
    const val GD_LOAD = 11
    const val GD_STORE = 12
    const val GD_STORE_KEEP = 13

    // Integer arithmetic. All of these wrap to 32 bits, which validation proved is unobservable.
    const val I_ADD = 14
    const val I_SUB = 15
    const val I_MUL = 16
    const val I_DIV_S = 17
    const val I_DIV_U = 18
    const val I_REM_S = 19
    const val I_REM_U = 20
    const val I_AND = 21
    const val I_OR = 22
    const val I_XOR = 23
    const val I_SHL = 24
    const val I_SHR = 25
    const val I_USHR = 26
    const val I_NEG = 27
    const val I_NOT = 28 // bitwise complement
    const val I_EQZ = 29 // logical not

    // Integer comparisons, leaving 0 or 1.
    const val I_EQ = 30
    const val I_NE = 31
    const val I_LT_S = 32
    const val I_LE_S = 33
    const val I_GT_S = 34
    const val I_GE_S = 35
    const val I_LT_U = 36
    const val I_LE_U = 37
    const val I_GT_U = 38
    const val I_GE_U = 39

    // Double arithmetic and comparisons. A comparison pushes onto the integer stack.
    const val D_ADD = 40
    const val D_SUB = 41
    const val D_MUL = 42
    const val D_DIV = 43
    const val D_REM = 44
    const val D_NEG = 45
    const val D_EQ = 46
    const val D_NE = 47
    const val D_LT = 48
    const val D_LE = 49
    const val D_GT = 50
    const val D_GE = 51

    // Conversions between the two stacks.
    const val I2D_S = 52
    const val I2D_U = 53
    const val D2I = 54 // ToInt32, the `~~x` and `x | 0` of a double
    const val D_FROUND = 55 // round to the nearest 32 bit float, staying in a double slot

    // Heap access. The operand on the stack is a byte address.
    const val H_LOAD_I8 = 56
    const val H_LOAD_U8 = 57
    const val H_LOAD_I16 = 58
    const val H_LOAD_U16 = 59
    const val H_LOAD_I32 = 60
    const val H_LOAD_F32 = 61
    const val H_LOAD_F64 = 62
    const val H_STORE_I8 = 63
    const val H_STORE_I16 = 64
    const val H_STORE_I32 = 65
    const val H_STORE_F32 = 66
    const val H_STORE_F64 = 67

    // The `stdlib.Math` functions a module may import.
    const val M_IMUL = 68
    const val M_ABS_I = 69
    const val M_CLZ32 = 70
    const val M_ABS_D = 71
    const val M_FLOOR = 72
    const val M_CEIL = 73
    const val M_SQRT = 74
    const val M_SIN = 75
    const val M_COS = 76
    const val M_TAN = 77
    const val M_ASIN = 78
    const val M_ACOS = 79
    const val M_ATAN = 80
    const val M_ATAN2 = 81
    const val M_POW = 82
    const val M_EXP = 83
    const val M_LOG = 84
    const val M_MIN_D = 85
    const val M_MAX_D = 86
    const val M_MIN_I = 87
    const val M_MAX_I = 88

    // Control flow. Every target is an absolute position in the code array.
    const val JMP = 89
    const val JZ = 90 // jumps when the integer on top is zero, and pops it
    const val JNZ = 91
    const val SWITCH = 92 // operands: default target, case count, then value/target pairs
    const val RET_I = 93
    const val RET_D = 94
    const val RET_V = 95

    // Calls. A direct call names a function of this module; an indirect one reads a table.
    const val CALL_DIRECT = 96 // operand: function index
    const val CALL_INDIRECT = 97 // operands: table index, signature index; pops the table offset
    const val CALL_FFI = 98 // operands: import index, signature index

    // Stack housekeeping.
    const val I_DROP = 99
    const val D_DROP = 100

    /** The name to print when dumping code. */
    fun name(op: Int): String = NAMES.getOrElse(op) { "op$op" }

    private val NAMES = arrayOf(
        "I_CONST", "D_CONST", "I_LOAD", "I_STORE", "I_STORE_KEEP", "D_LOAD", "D_STORE",
        "D_STORE_KEEP", "GI_LOAD", "GI_STORE", "GI_STORE_KEEP", "GD_LOAD", "GD_STORE",
        "GD_STORE_KEEP", "I_ADD", "I_SUB", "I_MUL", "I_DIV_S", "I_DIV_U", "I_REM_S", "I_REM_U",
        "I_AND", "I_OR", "I_XOR", "I_SHL", "I_SHR", "I_USHR", "I_NEG", "I_NOT", "I_EQZ", "I_EQ",
        "I_NE", "I_LT_S", "I_LE_S", "I_GT_S", "I_GE_S", "I_LT_U", "I_LE_U", "I_GT_U", "I_GE_U",
        "D_ADD", "D_SUB", "D_MUL", "D_DIV", "D_REM", "D_NEG", "D_EQ", "D_NE", "D_LT", "D_LE",
        "D_GT", "D_GE", "I2D_S", "I2D_U", "D2I", "D_FROUND", "H_LOAD_I8", "H_LOAD_U8",
        "H_LOAD_I16", "H_LOAD_U16", "H_LOAD_I32", "H_LOAD_F32", "H_LOAD_F64", "H_STORE_I8",
        "H_STORE_I16", "H_STORE_I32", "H_STORE_F32", "H_STORE_F64", "M_IMUL", "M_ABS_I",
        "M_CLZ32", "M_ABS_D", "M_FLOOR", "M_CEIL", "M_SQRT", "M_SIN", "M_COS", "M_TAN", "M_ASIN",
        "M_ACOS", "M_ATAN", "M_ATAN2", "M_POW", "M_EXP", "M_LOG", "M_MIN_D", "M_MAX_D", "M_MIN_I",
        "M_MAX_I", "JMP", "JZ", "JNZ", "SWITCH", "RET_I", "RET_D", "RET_V", "CALL_DIRECT",
        "CALL_INDIRECT", "CALL_FFI", "I_DROP", "D_DROP",
    )
}
