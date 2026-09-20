/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.asm

/**
 * One compiled function of a module.
 *
 * The frame is two arrays of slots: integers and doubles. Parameters come first, in the order
 * they appear among the parameters of their own kind, so a caller that pushed its arguments in
 * order has already put them where the callee expects them and nothing has to be copied.
 */
internal class AsmFunction(
    val name: String,
    val code: IntArray,
    val doubles: DoubleArray,
    /** The type of each parameter, in source order. */
    val paramTypes: IntArray,
    val returnType: Int,
    /** How many integer parameters there are, which is also how many the caller pushed. */
    val intParams: Int,
    val dblParams: Int,
    /** Integer slots for parameters and local variables. The operand stack sits above them. */
    val intLocals: Int,
    val dblLocals: Int,
    /** Slots this call needs altogether: locals plus the deepest the operand stack goes. */
    val intFrame: Int,
    val dblFrame: Int,
    /** Functions with the same signature index take the same arguments and return the same type. */
    val signature: Int,
)

/** A module level name, and what it stands for. Resolved when the module is linked. */
internal sealed class AsmGlobal {
    /** `var x = 0;` */
    class IntVar(val slot: Int, val init: Int) : AsmGlobal()

    /** `var x = 0.0;` or `var x = fround(0);` */
    class DblVar(val slot: Int, val init: Double, val isFloat: Boolean) : AsmGlobal()

    /** `var x = foreign.f | 0;` */
    class ImportedInt(val slot: Int, val field: String) : AsmGlobal()

    /** `var x = +foreign.f;` or `var x = fround(foreign.f);` */
    class ImportedDbl(val slot: Int, val field: String, val isFloat: Boolean) : AsmGlobal()

    /** `var H32 = new stdlib.Int32Array(heap);` */
    class View(val view: Int) : AsmGlobal()

    /**
     * `var imul = stdlib.Math.imul;`. Only the name is kept, because several of these are
     * overloaded: `abs`, `min` and `max` work on integers and on doubles, and which instruction
     * they compile to is decided at the call site, from the types of the arguments.
     */
    class MathFn(val field: String) : AsmGlobal()

    /** `var pi = stdlib.Math.PI;` */
    class MathConst(val value: Double, val field: String) : AsmGlobal()

    /** `var log = foreign.log;`, a function that lives outside the module. */
    class Ffi(val index: Int, val field: String) : AsmGlobal()

    /** One of the module's own functions, named before or after its declaration. */
    class Fn(val index: Int) : AsmGlobal()

    /** `var TBL = [f, g];`, whose entries are all module functions of one signature. */
    class Table(val index: Int) : AsmGlobal()
}

/** A table of the module's own functions, for `TBL[i & 3](x)`. Its length is a power of two. */
internal class AsmTable(val entries: IntArray, val signature: Int)

/** One name the module hands back, and the function behind it. */
internal class AsmExport(val name: String, val function: Int)

/**
 * A validated asm.js module, compiled but not yet linked.
 *
 * Compiling happens once, when the engine first sees the module's source. Linking happens every
 * time the module function is called, because that call is what supplies the standard library,
 * the foreign imports and the heap, and any of the three can be wrong.
 */
internal class AsmModule(
    val name: String,
    /** The module function's parameter names: standard library, foreign imports, heap. */
    val stdlibParam: String?,
    val foreignParam: String?,
    val heapParam: String?,
    val functions: Array<AsmFunction>,
    val globals: Map<String, AsmGlobal>,
    val globalIntCount: Int,
    val globalDblCount: Int,
    val tables: Array<AsmTable>,
    val ffiNames: Array<String>,
    val exports: Array<AsmExport>,
    /** True when the module returns one function rather than an object of them. */
    val singleExport: Boolean,
    /** Signature per index, as `[returnType, paramTypes...]`, for error messages and checking. */
    val signatures: Array<IntArray>,
    /** Where this module's outcome is written, for anyone asking why it is slow. */
    val diagnostic: AsmDiagnostic,
)

/**
 * What the engine did with one `"use asm"` function, so an embedder can find out why a module is
 * running slowly. A module is compiled once and linked on every call, and either step can decline.
 */
internal class AsmDiagnostic(val name: String) {
    var compiled: Boolean = false
    var compileReason: String = ""
    var linked: Boolean = false
    var linkReason: String = "the module has not been called yet"
}

/** Thrown while compiling to say the module is not asm.js. It never leaves the compiler. */
internal class AsmReject(val reason: String) : Exception(reason) {
    // The stack trace costs more than the message is worth: rejection is a normal outcome.
    override fun toString(): String = "not asm.js: $reason"
}
