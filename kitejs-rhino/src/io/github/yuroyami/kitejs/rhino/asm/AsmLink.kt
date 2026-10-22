/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino.asm

import io.github.yuroyami.kitejs.rhino.BaseFunction
import io.github.yuroyami.kitejs.rhino.Callable
import io.github.yuroyami.kitejs.rhino.ConsString
import io.github.yuroyami.kitejs.rhino.Context
import io.github.yuroyami.kitejs.rhino.LazilyLoadedCtor
import io.github.yuroyami.kitejs.rhino.LazyLoadSlot
import io.github.yuroyami.kitejs.rhino.NativeArray
import io.github.yuroyami.kitejs.rhino.NativeMath
import io.github.yuroyami.kitejs.rhino.NativeObject
import io.github.yuroyami.kitejs.rhino.ScriptRuntime
import io.github.yuroyami.kitejs.rhino.Scriptable
import io.github.yuroyami.kitejs.rhino.ScriptableObject
import io.github.yuroyami.kitejs.rhino.Slot
import io.github.yuroyami.kitejs.rhino.TopLevel
import io.github.yuroyami.kitejs.rhino.Undefined
import io.github.yuroyami.kitejs.rhino.typedarrays.NativeArrayBuffer

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
    fun link(cx: Context, scope: Scriptable, module: AsmModule, args: Array<Any?>, isStrict: Boolean): Any? = try {
        val exports = bind(cx, scope, module, args, isStrict)
        module.diagnostic.linked = true
        module.diagnostic.linkReason = ""
        exports
    } catch (e: AsmReject) {
        module.diagnostic.linked = false
        module.diagnostic.linkReason = e.reason
        null
    }

    private fun bind(cx: Context, scope: Scriptable, module: AsmModule, args: Array<Any?>, isStrict: Boolean): Any {
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

        // Check every whole-buffer view before an import getter or conversion can run. The
        // ordinary module then owns any construction error, with its original effects and order.
        for (global in module.globals.values) if (global is AsmGlobal.View) {
            val width = 1 shl AsmView.shift(global.view)
            if (buffer.length % width != 0) reject("the heap length is not a multiple of $width")
        }

        val globalInts = IntArray(module.globalIntCount)
        val globalDbls = DoubleArray(module.globalDblCount)
        val ffi = arrayOfNulls<Callable>(module.ffiNames.size)

        for ((name, global) in module.globals) {
            when (global) {
                is AsmGlobal.IntVar -> globalInts[global.slot] = global.init
                is AsmGlobal.DblVar -> globalDbls[global.slot] = global.init
                is AsmGlobal.View -> {
                    val builtin = viewBuiltin(global.view)
                    val wanted = builtin.name
                    val given = readProperty(stdlib ?: reject("the module was given no standard library"), wanted)
                    val original = TopLevel.cachedBuiltinCtor(top, builtin) ?: reject("$wanted has no retained intrinsic")
                    if (given !== original) reject("$wanted is not the engine's own")
                }
                is AsmGlobal.MathFn -> {
                    val math = readProperty(stdlib ?: reject("the module was given no standard library"), "Math")
                        as? Scriptable ?: reject("the standard library has no Math")
                    val original = TopLevel.cachedMathFunction(top, global.field)
                        ?: reject("Math.${global.field} has no retained intrinsic")
                    if (readProperty(math, global.field) !== original) {
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
                }
                is AsmGlobal.ImportedInt -> {
                    val value = numericImport(foreign ?: reject("the module was given no imports"), global.field)
                    globalInts[global.slot] = ScriptRuntime.toInt32(value)
                }
                is AsmGlobal.ImportedDbl -> {
                    val value = ScriptRuntime.toNumber(
                        numericImport(foreign ?: reject("the module was given no imports"), global.field),
                    )
                    globalDbls[global.slot] = if (global.isFloat) froundOf(value) else value
                }
                is AsmGlobal.Fn, is AsmGlobal.Table -> Unit
            }
            check(name.isNotEmpty())
        }

        val instance = AsmInstance(module, buffer, globalInts, globalDbls, ffi, scope)
        val runner = AsmRunner(instance)
        if (module.singleExport) {
            return AsmExportFunction(instance, runner, module.exports[0].function, scope, isStrict)
        }
        val exports = cx.newObject(scope)
        for (export in module.exports) {
            ScriptableObject.putProperty(exports, export.name, AsmExportFunction(instance, runner, export.function, scope, isStrict))
        }
        return exports
    }

    private fun reject(reason: String): Nothing = throw AsmReject(reason)

    /** Read only stored values whose lookup cannot execute a getter, proxy or host callback. */
    private fun readProperty(owner: Scriptable, name: String): Any? {
        var current: Scriptable? = owner
        val visited = HashSet<Scriptable>()
        while (current != null) {
            val ordinary = current as? ScriptableObject ?: reject("$name has a host-backed owner")
            val type = ordinary::class
            if (type != NativeObject::class && type != NativeArray::class &&
                type != NativeMath::class && type != TopLevel::class
            ) reject("$name has an observable property lookup")
            if (!visited.add(ordinary)) reject("$name has a cyclic prototype chain")
            val slot = ordinary.map.query(name, 0)
            if (slot != null) {
                if (slot::class != Slot::class &&
                    !(slot is LazyLoadSlot && slot.value !is LazilyLoadedCtor)
                ) reject("$name is not a stored data property")
                return slot.value
            }
            current = ordinary.prototype
        }
        return Undefined.instance
    }

    /** Only primitive conversions are safe to perform before declining a later dependency. */
    private fun numericImport(owner: Scriptable, name: String): Any? {
        val value = readProperty(owner, name)
        if (value == null || Undefined.isUndefined(value) || value is String || value is ConsString ||
            value is Boolean || value is Double || value is Float || value is Int || value is Long ||
            value is Short || value is Byte
        ) return value
        reject("$name requires an observable numeric conversion")
    }

    private fun readMathConstant(stdlib: Scriptable, field: String): Double {
        val value = if (field == "Infinity" || field == "NaN") readProperty(stdlib, field) else {
            val math = readProperty(stdlib, "Math") as? Scriptable ?: reject("the standard library has no Math")
            readProperty(math, field)
        }
        return when (value) {
            is Double -> value
            is Float -> value.toDouble()
            is Int -> value.toDouble()
            is Long -> value.toDouble()
            is Short -> value.toDouble()
            is Byte -> value.toDouble()
            else -> reject("$field is not a numeric constant")
        }
    }

    private fun viewBuiltin(view: Int): TopLevel.Builtins = when (view) {
        AsmView.I8 -> TopLevel.Builtins.Int8Array
        AsmView.U8 -> TopLevel.Builtins.Uint8Array
        AsmView.I16 -> TopLevel.Builtins.Int16Array
        AsmView.U16 -> TopLevel.Builtins.Uint16Array
        AsmView.I32 -> TopLevel.Builtins.Int32Array
        AsmView.U32 -> TopLevel.Builtins.Uint32Array
        AsmView.F32 -> TopLevel.Builtins.Float32Array
        else -> TopLevel.Builtins.Float64Array
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
    instance: AsmInstance,
    private val runner: AsmRunner,
    private val index: Int,
    scope: Scriptable,
    moduleIsStrict: Boolean,
) : BaseFunction() {

    private val fn: AsmFunction = instance.module.functions[index]

    init {
        ScriptRuntime.setFunctionProtoAndParent(this, Context.getCurrentContext(), scope)
        if (!fn.isStrict && !moduleIsStrict) {
            if (!has("arity", this)) createLegacyProperties()
            if (Context.getContext().languageVersion >= Context.VERSION_ES6) {
                setStandardPropertyAttributes(READONLY or DONTENUM)
                defineProperty("caller", null, DONTENUM or READONLY or PERMANENT)
            }
        } else {
            for (name in arrayOf("arity", "arguments")) if (has(name, this)) {
                setAttributes(name, DONTENUM)
                delete(name)
            }
        }
    }

    override val functionName: String get() = fn.name

    override val length: Int get() = fn.paramTypes.size

    override val arity: Int get() = fn.paramTypes.size

    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
        cx.withRealm(declarationScope!!) { runner.callExport(cx, index, args) }

    override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable =
        throw ScriptRuntime.typeError("${fn.name} is not a constructor")

    override val isConstructor: Boolean get() = false
}
