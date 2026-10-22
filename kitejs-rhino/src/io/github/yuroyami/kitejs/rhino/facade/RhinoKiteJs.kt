/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino.facade

import io.github.yuroyami.kitejs.api.ConsoleLevel
import io.github.yuroyami.kitejs.api.ConsolePrinter
import io.github.yuroyami.kitejs.api.Converters
import io.github.yuroyami.kitejs.api.JsArray
import io.github.yuroyami.kitejs.api.JsEngine
import io.github.yuroyami.kitejs.api.JsEngineError
import io.github.yuroyami.kitejs.api.JsError
import io.github.yuroyami.kitejs.api.JsException
import io.github.yuroyami.kitejs.api.JsFunction
import io.github.yuroyami.kitejs.api.JsObject
import io.github.yuroyami.kitejs.api.JsScript
import io.github.yuroyami.kitejs.api.JsStackFrame
import io.github.yuroyami.kitejs.api.JsSymbol
import io.github.yuroyami.kitejs.api.JsSyntaxError
import io.github.yuroyami.kitejs.api.JsUndefined
import io.github.yuroyami.kitejs.api.JsValue
import io.github.yuroyami.kitejs.api.KBigInt
import io.github.yuroyami.kitejs.api.KiteJs
import io.github.yuroyami.kitejs.rhino.AsmReport
import io.github.yuroyami.kitejs.rhino.Callable
import io.github.yuroyami.kitejs.rhino.Context
import io.github.yuroyami.kitejs.rhino.ContextFactory
import io.github.yuroyami.kitejs.rhino.EcmaError
import io.github.yuroyami.kitejs.rhino.EvaluatorException
import io.github.yuroyami.kitejs.rhino.Intrinsics
import io.github.yuroyami.kitejs.rhino.JavaScriptException
import io.github.yuroyami.kitejs.rhino.LambdaConstructor
import io.github.yuroyami.kitejs.rhino.LambdaFunction
import io.github.yuroyami.kitejs.rhino.NativeArray
import io.github.yuroyami.kitejs.rhino.NativeConsole
import io.github.yuroyami.kitejs.rhino.NativeError
import io.github.yuroyami.kitejs.rhino.NativePromise
import io.github.yuroyami.kitejs.rhino.Rhino
import io.github.yuroyami.kitejs.rhino.RhinoConfig
import io.github.yuroyami.kitejs.rhino.RhinoException
import io.github.yuroyami.kitejs.rhino.Script
import io.github.yuroyami.kitejs.rhino.ScriptRuntime
import io.github.yuroyami.kitejs.rhino.Scriptable
import io.github.yuroyami.kitejs.rhino.ScriptableObject
import io.github.yuroyami.kitejs.rhino.SerializableCallable
import io.github.yuroyami.kitejs.rhino.SerializableConstructable
import io.github.yuroyami.kitejs.rhino.SymbolKey
import io.github.yuroyami.kitejs.rhino.TopLevel
import io.github.yuroyami.kitejs.rhino.Undefined
import io.github.yuroyami.kitejs.rhino.typedarrays.NativeTypedArrayView

/**
 * A Rhino engine behind the KiteJS API: one [Context], entered on the thread that opened it, and
 * the global scope that goes with it. Every handle it gives out holds it, so a handle always knows
 * its engine and can refuse to be used anywhere else.
 */
internal class RhinoKiteJs private constructor(
    private val factory: EngineFactory,
    internal val cx: Context,
    internal val scope: ScriptableObject,
) : KiteJs() {

    private var closed = false

    /** How deep the calls from the host into this engine are nested; only the outermost one meters and drains. */
    private var depth = 0

    override val engine: JsEngine<*> get() = Rhino

    override val global: JsObject = RhinoObject(this, scope)

    override val version: String get() = cx.implementationVersion

    /** What the engine did with each `"use asm"` function it has parsed. */
    val asmReports: List<AsmReport>
        get() = cx.asmDiagnostics.map {
            AsmReport(it.name, it.compiled, it.linked, if (!it.compiled) it.compileReason else it.linkReason)
        }

    override fun evaluate(source: String, fileName: String): JsValue =
        call(top = false) { toJs(cx.evaluateString(scope, source, fileName, 1)) }

    override fun compile(source: String, fileName: String): JsScript =
        call(top = false, drain = false) { RhinoScript(this, cx.compileString(source, fileName, 1)) }

    fun run(script: Script): JsValue = call(top = false) { toJs(script.exec(cx, scope, scope)) }

    override fun runMicrotasks() {
        call(top = false) { cx.processMicrotasks() }
    }

    override fun valueOf(value: Any?): JsValue = call(top = false, drain = false) { toJs(toRhino(value)) }

    override fun newObject(): JsObject = call(top = false, drain = false) { RhinoObject(this, cx.newObject(scope)) }

    override fun newArray(vararg elements: Any?): JsArray = call(top = false, drain = false) {
        toJs(cx.newArray(scope, toRhinoArgs(elements))).raw as JsArray
    }

    override fun close() {
        if (closed) return
        if (Context.getCurrentContext() !== cx) throw wrongThread()
        if (depth > 0) throw JsEngineError("this engine is running a script; close it once that call has returned")
        closed = true
        Context.exit()
    }

    override fun newFunction(name: String, arity: Int, body: (self: JsValue, args: List<JsValue>) -> Any?): JsFunction =
        call(top = false, drain = false) {
            val fn = LambdaFunction(
                scope,
                name,
                arity,
                SerializableCallable { _, _, thisObj, args -> toRhino(body(toJs(thisObj), args.map(::toJs))) },
            )
            RhinoFunction(this, fn)
        }

    override fun newConstructor(name: String, arity: Int, build: (JsObject, List<JsValue>) -> Unit): JsFunction =
        call(top = false, drain = false) {
            val ctor = LambdaConstructor(
                scope,
                name,
                arity,
                SerializableConstructable { cx, s, args ->
                    val obj = cx.newObject(s)
                    build(RhinoObject(this, obj), args.map(::toJs))
                    obj
                },
            )
            RhinoFunction(this, ctor)
        }

    override fun thenableCheck(obj: JsObject): Boolean = call(drain = false) {
        val target = targetOf(obj)
        ScriptRuntime.isObject(target) && ScriptableObject.getProperty(target, "then") is Callable
    }

    override fun watchSettlement(value: JsValue, onSettled: (JsValue, JsValue?) -> Unit): Boolean = call {
        NativePromise.awaitValue(
            cx,
            scope,
            toRhino(value),
            hostHandler { onSettled(toJs(it), null) },
            hostHandler { onSettled(JsValue.undefined, toJs(it)) },
        )
    }

    // ---- Calls from the host ------------------------------------------------------------------

    /** True when this engine is open and this is its thread, so its handles can be used here. */
    val isUsableHere: Boolean get() = !closed && Context.getCurrentContext() === cx

    /**
     * Runs [body] as a call from the host: on this engine's thread, with what it throws in the
     * API's shape. The outermost call is one call to the instruction budget and, when [drain] is
     * set, runs the Promise reactions it queued before it returns. [top] sets up the top call
     * scope a function call needs when no script is running; evaluating and running a script set
     * up their own.
     */
    fun <T> call(top: Boolean = true, drain: Boolean = true, body: () -> T): T {
        if (closed) throw JsEngineError("this engine is closed")
        if (Context.getCurrentContext() !== cx) throw wrongThread()
        if (depth >= cx.maximumInterpreterInvocations || !cx.hasNativeStackSpace()) {
            throw translate(ScriptRuntime.rangeError("Maximum call stack size exceeded"))
        }
        val outermost = depth == 0
        depth++
        try {
            if (!outermost) return inTopCall(top, body)
            return metered {
                val out = inTopCall(top, body)
                if (drain) cx.processMicrotasks()
                out
            }
        } catch (e: Throwable) {
            throw translate(e)
        } finally {
            depth--
        }
    }

    /**
     * Runs [body] inside a top call when [top] asks for one and nothing else is running. Inside a
     * script there already is one, and starting a second is not allowed, so this steps aside.
     */
    private fun <T> inTopCall(top: Boolean, body: () -> T): T {
        if (!top || cx.topCallScope != null) return body()
        var out: Any? = null
        ScriptRuntime.doTopCall(
            SerializableCallable { _, _, _, _ -> out = body(); null },
            cx, scope, null, ScriptRuntime.emptyArgs,
        )
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    /**
     * Runs [body] as one call to the instruction budget: the count starts again, and if the engine
     * stops the script for its budget or its interrupt hook, the Promise reactions still queued
     * are dropped along with it (D-77).
     */
    private inline fun <T> metered(body: () -> T): T {
        factory.instructionsUsed = 0
        factory.stopped = false
        cx.instructionCount = 0
        try {
            return body()
        } catch (e: Throwable) {
            if (factory.stopped) {
                factory.stopped = false
                cx.discardMicrotasks()
            }
            throw e
        }
    }

    private fun wrongThread(): JsEngineError =
        JsEngineError("this engine belongs to the thread that opened it; use it and close it there")

    // ---- Values -------------------------------------------------------------------------------

    /** A value from the engine as the API holds it. */
    fun toJs(raw: Any?): JsValue = JsValue(
        when {
            raw == null -> null
            Undefined.isUndefined(raw) || raw === Scriptable.NOT_FOUND -> JsUndefined
            raw is Boolean -> raw
            raw is KBigInt -> raw
            raw is Number -> raw.toDouble()
            raw is CharSequence -> raw.toString()
            raw is Char -> raw.toString()
            raw is SymbolKey -> RhinoSymbol(this, raw)
            raw is NativeArray -> RhinoArray(this, raw)
            raw is Scriptable && raw is Callable -> RhinoFunction(this, raw)
            raw is Scriptable -> RhinoObject(this, raw)
            else -> throw JsEngineError("the engine handed back a ${raw::class.simpleName}, which has no JavaScript form")
        },
    )

    /** A Kotlin value, a [JsValue] or a handle as the engine holds it, collections built in this engine. */
    fun toRhino(value: Any?): Any? = Converters.toEngineGraph(
        value,
        scalar = ::scalarToRhino,
        array = { cx.newArray(scope, 0) },
        obj = { cx.newObject(scope) },
        append = { obj, child ->
            val array = obj as NativeArray
            array.put(array.length.toInt(), array, child)
        },
        put = { obj, key, child ->
            val target = obj as Scriptable
            val id = ScriptRuntime.toStringIdOrIndex(key)
            if (id.stringId == null) target.put(id.index, target, child)
            else target.put(id.stringId, target, child)
        },
        bytes = { bytes ->
            // The realm's own constructor, so a script that replaced the global Uint8Array runs nothing.
            val array = Intrinsics.constructor(cx, scope, "Uint8Array").construct(cx, scope, arrayOf<Any?>(bytes.size)) as NativeTypedArrayView
            bytes.copyInto(array.buffer.buffer!!)
            array
        },
    )

    fun toRhinoArgs(args: Array<out Any?>): Array<Any?> = Array(args.size) { toRhino(args[it]) }

    private fun scalarToRhino(c: Any?): Any? = when (c) {
        JsUndefined -> Undefined.instance
        is RhinoSymbol -> if (c.engine === this) c.key else throw foreign()
        is JsSymbol -> throw foreign()
        is JsObject -> targetOf(c)
        else -> c
    }

    /** The engine object behind [obj], which has to be one of this engine's handles. */
    fun targetOf(obj: JsObject): Scriptable =
        if (obj is RhinoHandle && obj.engine === this) obj.target else throw foreign()

    private fun foreign(): JsEngineError =
        JsEngineError("this value belongs to another engine, and values cannot move between engines")

    /** A handler only the engine calls, never a script, so it needs no function object around it. */
    private fun hostHandler(body: (Any?) -> Unit): Callable = object : Callable {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            body(if (args.isNotEmpty()) args[0] else Undefined.instance)
            return Undefined.instance
        }
    }

    // ---- Errors -------------------------------------------------------------------------------

    /** Turns whatever the engine threw into the API's own shape. */
    private fun translate(e: Throwable): JsException = when (e) {
        is JsException -> e
        is JavaScriptException -> {
            val thrown = e.value
            val name = readString(thrown, "name") ?: ScriptRuntime.typeOf(thrown)
            val message = readString(thrown, "message") ?: describe(thrown)
            JsError(toJs(thrown), name, message, framesOf(e), e)
        }
        is EcmaError -> JsError(errorObjectOf(e), e.name, e.errorMessage, framesOf(e), e)
        is EvaluatorException -> JsSyntaxError(e.details(), e.sourceName, e.lineNumber, e.columnNumber, e.lineSource, e)
        is RhinoException -> JsEngineError(e.details(), e)
        else -> JsEngineError(e.message ?: e::class.simpleName ?: "engine failure", e)
    }

    /** A string property of a thrown object, or null when it has none or reading it fails. */
    private fun readString(thrown: Any?, name: String): String? {
        val obj = thrown as? ScriptableObject ?: return null
        return try {
            val v = ScriptableObject.getProperty(obj, name)
            if (v == null || v === Scriptable.NOT_FOUND) null else ScriptRuntime.toString(v)
        } catch (e: Throwable) {
            null
        }
    }

    /** What a thrown value says it is, or its class when even that fails. */
    private fun describe(thrown: Any?): String = try {
        ScriptRuntime.toString(thrown)
    } catch (e: Throwable) {
        if (thrown is Scriptable) RhinoHandle.inertText(thrown) else ScriptRuntime.typeOf(thrown)
    }

    /**
     * The error object a `catch` clause would see for [e], made the way the interpreter makes it,
     * or undefined when the engine cannot make one.
     */
    private fun errorObjectOf(e: EcmaError): JsValue = try {
        val type = TopLevel.NativeErrors.entries.firstOrNull { it.name == e.name } ?: TopLevel.NativeErrors.Error
        val line = e.lineNumber
        val sourceUri = e.sourceName ?: ""
        val args: Array<Any?> = if (line > 0) arrayOf(e.errorMessage, sourceUri, line) else arrayOf(e.errorMessage, sourceUri)
        val error = ScriptRuntime.newNativeError(cx, scope, type, args)
        if (error is NativeError) error.setStackProvider(e)
        toJs(error)
    } catch (t: Throwable) {
        JsValue.undefined
    }

    private fun framesOf(e: RhinoException): List<JsStackFrame> =
        e.scriptStack.map { JsStackFrame(it.functionName, it.fileName, it.lineNumber) }

    companion object {
        /** How often the interrupt hook is asked when there is no budget to pace it. */
        private const val INTERRUPT_SLICE = 100_000

        fun open(config: RhinoConfig): RhinoKiteJs {
            if (Context.getCurrentContext() != null) {
                throw JsEngineError(
                    "this thread already has an open engine; close it first, use `use { }`, " +
                        "or open the second engine on its own thread",
                )
            }
            val factory = EngineFactory(config)
            val cx = factory.enterContext()
            return try {
                require(config.maxCallDepth >= 0) { "maxCallDepth must be nonnegative" }
                require(config.maxHostCallDepth > 0) { "maxHostCallDepth must be positive" }
                cx.maximumInterpreterStackDepth = if (config.maxCallDepth == 0) Int.MAX_VALUE else config.maxCallDepth
                cx.maximumInterpreterInvocations = config.maxHostCallDepth
                cx.languageVersion = config.languageVersion.code
                cx.timeZone = config.timeZone
                config.clock?.let { cx.clock = it }
                if (config.instructionBudget > 0 || config.interruptWhen != null) {
                    cx.setGenerateObserverCount(true)
                    // With no budget the observer exists only to ask the interrupt hook, so it
                    // needs a slice small enough to notice a stop but large enough to stay cheap.
                    cx.setInstructionObserverThreshold(
                        if (config.instructionBudget > 0) config.instructionBudget else INTERRUPT_SLICE,
                    )
                }
                val scope =
                    if (config.safeBuiltins) cx.initSafeStandardObjects(null, config.sealBuiltins)
                    else cx.initStandardObjects(null, config.sealBuiltins)
                config.console?.let { NativeConsole.init(scope, config.sealBuiltins, consoleOf(it)) }
                RhinoKiteJs(factory, cx, scope)
            } catch (e: Throwable) {
                Context.exit()
                throw when (e) {
                    is JsException -> e
                    else -> JsEngineError(e.message ?: "the engine could not start", e)
                }
            }
        }

        /** The engine's console, writing what the API's printer is given: the arguments already formatted. */
        private fun consoleOf(printer: ConsolePrinter) = NativeConsole.ConsolePrinter { cx, scope, level, args, _ ->
            val apiLevel = when (level) {
                NativeConsole.Level.TRACE -> ConsoleLevel.TRACE
                NativeConsole.Level.DEBUG -> ConsoleLevel.DEBUG
                NativeConsole.Level.INFO -> ConsoleLevel.INFO
                NativeConsole.Level.WARN -> ConsoleLevel.WARN
                NativeConsole.Level.ERROR -> ConsoleLevel.ERROR
            }
            printer.print(apiLevel, NativeConsole.format(cx, scope, args))
        }
    }
}

/** The factory that carries the configuration's features and instruction budget into the running script. */
internal class EngineFactory(private val config: RhinoConfig) : ContextFactory() {

    /** A Long, so a long run without a budget, watched only by the hook, cannot wrap it. */
    var instructionsUsed: Long = 0

    /** Set when the budget or the hook stopped the script, which drops its queued reactions. */
    var stopped: Boolean = false

    override fun hasFeature(cx: Context, featureIndex: Int): Boolean = when (featureIndex) {
        Context.FEATURE_LITTLE_ENDIAN -> config.littleEndian
        Context.FEATURE_ASM_JS -> config.asmJs
        else -> super.hasFeature(cx, featureIndex)
    }

    override fun observeInstructionCount(cx: Context, instructionCount: Int) {
        instructionsUsed += instructionCount
        val budget = config.instructionBudget
        if (budget > 0 && instructionsUsed >= budget) {
            stopped = true
            throw JsEngineError("script used more than $budget instructions and was stopped")
        }
        val hook = config.interruptWhen ?: return
        val interrupt = try {
            hook()
        } catch (e: Throwable) {
            stopped = true
            throw e
        }
        if (interrupt) {
            stopped = true
            throw JsEngineError("script was interrupted")
        }
    }
}
