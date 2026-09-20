/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.asm

/**
 * The static types of the asm.js type system, as far as this compiler needs them.
 *
 * Every value in a validated module has one of these types, known before the module runs. That is
 * the whole point of asm.js: an engine can hold an `int` in a machine integer and add two of them
 * with one instruction, because validation already proved that nothing else can arrive there.
 *
 * The integer types all sit in a 32 bit integer slot and differ only in how their bits are read:
 *
 * - [SIGNED] reads them as a two's complement number, so `0xFFFFFFFF` is -1.
 * - [UNSIGNED] reads them as a plain count, so `0xFFFFFFFF` is 4294967295.
 * - [FIXNUM] is a literal small enough to be read either way, so it fits wherever either fits.
 * - [INTISH] is the result of `+`, `-`, `*` or `~` on integers. Its bits are right but its value
 *   may have overflowed, so it may only be used where the overflow cannot be seen: under `|`,
 *   `&`, `^`, `<<`, `>>`, `>>>`, as a byte heap index, or inside another `+`, `-` or `*`.
 *
 * That last rule is what makes wrapping arithmetic safe. Plain JavaScript would turn `a + b` into
 * a double and keep every bit of the sum. This compiler wraps it to 32 bits instead, which is a
 * different number, and validation is what proves no one can ever read that difference.
 *
 * [FLOAT] is held in a double slot and is always a value that a 32 bit float can hold exactly.
 * [EXTERN] is whatever came back from a foreign call, and may only be coerced, never used.
 */
internal object AsmType {
    const val VOID: Int = 0
    const val FIXNUM: Int = 1
    const val SIGNED: Int = 2
    const val UNSIGNED: Int = 3
    const val INTISH: Int = 4
    const val DOUBLE: Int = 5
    const val FLOAT: Int = 6
    const val EXTERN: Int = 7

    /**
     * The result of arithmetic on floats. Like [INTISH] it is a value that is not yet a float,
     * because the operation was carried out with more precision than a float holds. Only
     * `Math.fround` may read one, which is what rounds it back down to a float.
     */
    const val FLOATISH: Int = 8

    /** True when [t] lives in an integer slot. */
    fun isInt(t: Int): Boolean = t == FIXNUM || t == SIGNED || t == UNSIGNED || t == INTISH

    /** True when [t] lives in a double slot. */
    fun isDbl(t: Int): Boolean = t == DOUBLE || t == FLOAT || t == FLOATISH

    /** True when [t] may be used where `signed` is wanted, for example `/` or `<`. */
    fun isSigned(t: Int): Boolean = t == SIGNED || t == FIXNUM

    /** True when [t] may be used where `unsigned` is wanted. */
    fun isUnsigned(t: Int): Boolean = t == UNSIGNED || t == FIXNUM

    /** The name to print in a rejection message. */
    fun name(t: Int): String = when (t) {
        VOID -> "void"
        FIXNUM -> "fixnum"
        SIGNED -> "signed"
        UNSIGNED -> "unsigned"
        INTISH -> "intish"
        DOUBLE -> "double"
        FLOAT -> "float"
        EXTERN -> "extern"
        FLOATISH -> "floatish"
        else -> "type$t"
    }
}

/** The kinds a module level name can have. */
internal object AsmGlobalKind {
    /** A mutable `int` global, held in the instance's integer globals. */
    const val INT_VAR: Int = 0

    /** A mutable `double` global. */
    const val DOUBLE_VAR: Int = 1

    /** A mutable `float` global. */
    const val FLOAT_VAR: Int = 2

    /** A typed array view over the heap, for example `new stdlib.Int32Array(heap)`. */
    const val HEAP_VIEW: Int = 3

    /** A function from `stdlib.Math`, for example `stdlib.Math.imul`. */
    const val MATH_FN: Int = 4

    /** A value from `stdlib`, which is only `Math.PI` and its neighbours. */
    const val MATH_CONST: Int = 5

    /** A function imported from the foreign object. */
    const val FFI_FN: Int = 6

    /** One of the module's own functions. */
    const val FUNCTION: Int = 7

    /** A table of the module's own functions, for `TBL[i & 7](x)`. */
    const val TABLE: Int = 8
}

/** The heap views a module may declare, and the shift each one's index uses. */
internal object AsmView {
    const val I8: Int = 0
    const val U8: Int = 1
    const val I16: Int = 2
    const val U16: Int = 3
    const val I32: Int = 4
    const val U32: Int = 5
    const val F32: Int = 6
    const val F64: Int = 7

    /** How far an index is shifted for this view: 0 for a byte, 2 for a 32 bit word. */
    fun shift(view: Int): Int = when (view) {
        I8, U8 -> 0
        I16, U16 -> 1
        I32, U32, F32 -> 2
        else -> 3
    }

    /** True when the view holds doubles or floats rather than integers. */
    fun isFloating(view: Int): Boolean = view == F32 || view == F64

    /** The view a constructor name asks for, or -1 when the name is not a view. */
    fun ofConstructor(name: String): Int = when (name) {
        "Int8Array" -> I8
        "Uint8Array" -> U8
        "Int16Array" -> I16
        "Uint16Array" -> U16
        "Int32Array" -> I32
        "Uint32Array" -> U32
        "Float32Array" -> F32
        "Float64Array" -> F64
        else -> -1
    }
}
