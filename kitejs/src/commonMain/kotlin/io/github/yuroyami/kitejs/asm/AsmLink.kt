/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.asm

import io.github.yuroyami.kitejs.BaseFunction
import io.github.yuroyami.kitejs.Callable
import io.github.yuroyami.kitejs.Context
import io.github.yuroyami.kitejs.ScriptRuntime
import io.github.yuroyami.kitejs.Scriptable
import io.github.yuroyami.kitejs.ScriptableObject
import io.github.yuroyami.kitejs.Undefined
import io.github.yuroyami.kitejs.typedarrays.NativeArrayBuffer

/**
 * Connects a compiled module to the standard library, the imports and the heap it is called with.
 *
 * Compiling proved the module is asm.js. Linking proves that this particular call gives it what
 * asm.js says it must have: the real `Math.imul` rather than something that only shares the name,
 * real typed array constructors, and an `ArrayBuffer` to use as memory. A call that does not is
 * not an error. The module simply runs as ordinary JavaScript, the same as in a browser.
 */
internal object AsmLink {

    /** The exports object, or null when this call cannot use the compiled code. */
    fun link(cx: Context, scope: Scriptable, module: AsmModule, args: Array<Any?>): Any? = try {
        val exports = bind(cx, scope, module, args)
        module.diagnostic.linked = true
        module.diagnostic.linkReason = ""
        exports
    } catch (e: AsmReject) {
        module.diagnostic.linked = false
        module.diagnostic.linkReason = e.reason
        null
    }

    private fun bind(cx: Context, scope: Scriptable, module: AsmModule, args: Array<Any?>): Any {
        // The compiled heap access reads and writes bytes in little-endian order, which is what
        // asm.js means and what every browser does. An engine set the other way has to use the
        // general interpreter, so that the module and the views around it agree.
        if (!cx.hasFeature(Context.FEATURE_LITTLE_ENDIAN)) reject("the engine is set to big-endian")

        val stdlib = args.getOrNull(0) as? Scriptable
        val foreign = args.getOrNull(1) as? Scriptable
        val top = ScriptableObject.getTopLevelScope(scope)

        val buffer = if (module.heapParam == null) {
            NativeArrayBuffer(0)
        } else {
            args.getOrNull(2) as? NativeArrayBuffer ?: reject("the heap is not an ArrayBuffer")
        }
        if (buffer.isDetached) reject("the heap has been detached")

        val globalInts = IntArray(module.globalIntCount)
        val globalDbls = DoubleArray(module.globalDblCount)
        val ffi = arrayOfNulls<Callable>(module.ffiNames.size)
        val ffiThis = arrayOfNulls<Scriptable>(module.ffiNames.size)

        for ((name, global) in module.globals) {
            when (global) {
                is AsmGlobal.IntVar -> globalInts[global.slot] = global.init
                is AsmGlobal.DblVar -> globalDbls[global.slot] = global.init
                is AsmGlobal.View -> {
                    val wanted = viewConstructorName(global.view)
                    val given = readProperty(stdlib ?: reject("the module was given no standard library"), wanted)
                    if (given !== readProperty(top, wanted)) reject("$wanted is not the engine's own")
                }
                is AsmGlobal.MathFn -> {
                    val math = readProperty(stdlib ?: reject("the module was given no standard library"), "Math")
                        as? Scriptable ?: reject("the standard library has no Math")
                    val realMath = readProperty(top, "Math") as? Scriptable ?: reject("the engine has no Math")
                    if (readProperty(math, global.field) !== readProperty(realMath, global.field)) {
                        reject("Math.${global.field} is not the engine's own")
                    }
                }
                is AsmGlobal.MathConst -> {
                    val value = readMathConstant(stdlib ?: reject("the module was given no standard library"), global.field)
                    // A NaN never equals itself, so the bits are compared instead.
                    if (value.toRawBits() != global.value.toRawBits()) reject("${global.field} is not its usual value")
                }
                is AsmGlobal.Ffi -> {
                    val value = readProperty(foreign ?: reject("the module was given no imports"), global.field)
                    ffi[global.index] = value as? Callable ?: reject("${global.field} is not a function")
                    ffiThis[global.index] = value as? Scriptable
                }
                is AsmGlobal.ImportedInt -> {
                    val value = readProperty(foreign ?: reject("the module was given no imports"), global.field)
                    globalInts[global.slot] = ScriptRuntime.toInt32(value)
                }
                is AsmGlobal.ImportedDbl -> {
                    val value = ScriptRuntime.toNumber(
                        readProperty(foreign ?: reject("the module was given no imports"), global.field),
                    )
                    globalDbls[global.slot] = if (global.isFloat) froundOf(value) else value
                }
                is AsmGlobal.Fn, is AsmGlobal.Table -> Unit
            }
            check(name.isNotEmpty())
        }

        val instance = AsmInstance(module, buffer, globalInts, globalDbls, ffi, ffiThis, scope)
        val runner = AsmRunner(instance)
        if (module.singleExport) {
            return AsmExportFunction(instance, runner, module.exports[0].function, scope)
        }
        val exports = cx.newObject(scope)
        for (export in module.exports) {
            ScriptableObject.putProperty(exports, export.name, AsmExportFunction(instance, runner, export.function, scope))
        }
        return exports
    }

    private fun reject(reason: String): Nothing = throw AsmReject(reason)

    private fun readProperty(owner: Scriptable, name: String): Any? {
        val value = ScriptableObject.getProperty(owner, name)
        return if (value === Scriptable.NOT_FOUND) null else value
    }

    private fun readMathConstant(stdlib: Scriptable, field: String): Double {
        if (field == "Infinity" || field == "NaN") {
            return ScriptRuntime.toNumber(readProperty(stdlib, field) ?: reject("the standard library has no $field"))
        }
        val math = readProperty(stdlib, "Math") as? Scriptable ?: reject("the standard library has no Math")
        return ScriptRuntime.toNumber(readProperty(math, field) ?: reject("Math has no $field"))
    }

    private fun viewConstructorName(view: Int): String = when (view) {
        AsmView.I8 -> "Int8Array"
        AsmView.U8 -> "Uint8Array"
        AsmView.I16 -> "Int16Array"
        AsmView.U16 -> "Uint16Array"
        AsmView.I32 -> "Int32Array"
        AsmView.U32 -> "Uint32Array"
        AsmView.F32 -> "Float32Array"
        else -> "Float64Array"
    }
}

/**
 * One function a module hands back.
 *
 * It takes ordinary JavaScript values, turns each one into the type its parameter was declared
 * with, runs the typed code and turns the answer back. That conversion is the whole boundary:
 * inside it there are no JavaScript values at all.
 */
internal class AsmExportFunction(
    private val instance: AsmInstance,
    private val runner: AsmRunner,
    private val index: Int,
    scope: Scriptable,
) : BaseFunction() {

    private val fn: AsmFunction = instance.module.functions[index]

    init {
        ScriptRuntime.setFunctionProtoAndParent(this, Context.getCurrentContext(), scope)
    }

    override val functionName: String get() = fn.name

    override val length: Int get() = fn.paramTypes.size

    override val arity: Int get() = fn.paramTypes.size

    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        runner.enter()
        var ints = 0
        var dbls = 0
        for (i in fn.paramTypes.indices) {
            val given = args.getOrNull(i)
            if (AsmType.isDbl(fn.paramTypes[i])) {
                val value = ScriptRuntime.toNumber(given)
                runner.pushArgDbl(dbls++, if (fn.paramTypes[i] == AsmType.FLOAT) froundOf(value) else value)
            } else {
                runner.pushArgInt(ints++, ScriptRuntime.toInt32(given))
            }
        }
        runner.run(cx, index, 0, 0)
        return when {
            fn.returnType == AsmType.VOID -> Undefined.instance
            AsmType.isDbl(fn.returnType) -> runner.retDbl
            else -> runner.retInt
        }
    }

    override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable =
        throw ScriptRuntime.typeError("${fn.name} is not a constructor")
}
