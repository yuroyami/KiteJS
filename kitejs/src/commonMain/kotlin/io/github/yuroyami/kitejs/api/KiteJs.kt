/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.Context
import io.github.yuroyami.kitejs.ContextFactory
import io.github.yuroyami.kitejs.NativeConsole
import io.github.yuroyami.kitejs.Script
import io.github.yuroyami.kitejs.Scriptable
import io.github.yuroyami.kitejs.ScriptableObject
import kotlinx.datetime.TimeZone

/** Which JavaScript the engine speaks. */
public enum class LanguageVersion(internal val code: Int) {
    /** ES6 and the parts of later editions this engine implements. The usual choice. */
    ES6(Context.VERSION_ES6),

    /** The newest the engine has, which today behaves the same as [ES6]. */
    LATEST(Context.VERSION_ECMASCRIPT),

    /** ES5, before `let`, classes, generators and the rest. */
    ES5(Context.VERSION_1_8),
}

/** How to build an engine. Everything has a working default. */
public class KiteJsConfig internal constructor() {

    /** Which JavaScript the engine speaks. */
    public var languageVersion: LanguageVersion = LanguageVersion.LATEST

    /** The zone `Date` reads local time in. */
    public var timeZone: TimeZone = TimeZone.currentSystemDefault()

    /** Where `Date.now()` reads the time from, in epoch milliseconds. Fix it to make tests stable. */
    public var clock: (() -> Double)? = null

    /** Where `console.log` and its neighbours go. Null leaves `console` out of the global scope. */
    public var console: ConsolePrinter? = null

    /**
     * How many interpreter instructions one call may run before the engine gives up. Zero, the
     * default, means no limit. This is how you stop a script that never returns.
     */
    public var instructionBudget: Int = 0

    /**
     * Asked now and then while a script runs. Answer true, or throw, to stop it. This is how an
     * outside signal reaches a running script: a deadline, a cancelled coroutine, a stop button.
     * Setting it turns the instruction observer on even without a budget.
     */
    public var interruptWhen: (() -> Boolean)? = null

    /** Leaves out the built-ins a sandbox does not want. Today that is only the old `Packages` hooks. */
    public var safeBuiltins: Boolean = false

    /** Makes the built-ins read-only, so a script cannot redefine `Array.prototype.push`. */
    public var sealBuiltins: Boolean = false

    /**
     * The byte order a typed array view uses, for example `Int32Array` over an `ArrayBuffer`.
     * Little-endian is what every browser and Node answer, and what Emscripten output needs.
     * Set it to false only for a script written against Rhino's old order. `DataView` is
     * unaffected: it takes the order on every call.
     */
    public var littleEndian: Boolean = true

    /**
     * Whether a function whose body starts with `"use asm"` is compiled ahead of time to typed
     * code. On by default.
     *
     * asm.js is the subset of JavaScript that Emscripten writes, and its types are all known
     * before it runs, so the engine can hold an integer as an integer instead of as an object.
     * A module the engine cannot validate runs as ordinary JavaScript either way, so this only
     * changes speed, never answers. Turn it off to compare the two.
     */
    public var asmJs: Boolean = true
}

/**
 * What the engine did with one function whose body starts with `"use asm"`.
 *
 * Such a function is compiled ahead of time to typed code, which is much faster than running it
 * as ordinary JavaScript. Compiling can decline, and so can the linking that happens when the
 * module is called. Either way the module still runs and still gives the same answers, so this is
 * only for finding out why one is slower than expected.
 */
public class AsmReport internal constructor(
    /** The module function's name, or an empty string when it has none. */
    public val name: String,
    /** True when the module compiled to typed code. */
    public val compiled: Boolean,
    /** True when the last call to the module could use that code. */
    public val linked: Boolean,
    /** Why it did not, in one sentence. Empty when everything worked. */
    public val reason: String,
) {
    override fun toString(): String = when {
        compiled && linked -> "$name: compiled"
        compiled -> "$name: compiled, not linked ($reason)"
        else -> "$name: not compiled ($reason)"
    }
}

/** A parsed script, ready to run more than once. */
public class JsScript internal constructor(private val engine: KiteJs, private val script: Script) {

    /** Runs it in the engine's global scope. */
    public fun run(): JsValue = engine.runScript(script)
}

/**
 * An engine and the global scope that goes with it. One instance belongs to one thread.
 *
 * ```
 * KiteJs { languageVersion = LanguageVersion.ES6 }.use { js ->
 *     js.global.function("shout") { args -> args.first().asString().uppercase() }
 *     println(js.evaluate("shout('hi')").asString())   // HI
 * }
 * ```
 */
public class KiteJs internal constructor(
    internal val factory: EngineFactory,
    internal val cx: Context,
    private val scopeObject: ScriptableObject,
) : AutoCloseable {

    private var closed = false

    /** The global object. Bind host functions and values onto it. */
    public val global: JsObject = JsObject(scopeObject)

    /** The engine's version string. */
    public val version: String get() = cx.implementationVersion

    /**
     * One entry per `"use asm"` function the engine has parsed, in the order it met them.
     *
     * Use it to find out why an Emscripten module is running slowly: the reason names the first
     * thing in it that asm.js does not allow.
     */
    public val asmReports: List<AsmReport>
        get() = cx.asmDiagnostics.map {
            AsmReport(
                it.name,
                it.compiled,
                it.linked,
                if (!it.compiled) it.compileReason else it.linkReason,
            )
        }

    /** Parses and runs [source]. The answer is the last expression's value. */
    public fun evaluate(source: String, fileName: String = "<eval>"): JsValue = guarded {
        JsValue(cx.evaluateString(scopeObject, source, fileName, 1))
    }

    /** Parses [source] once so it can be run many times. */
    public fun compile(source: String, fileName: String = "<script>"): JsScript = guarded {
        JsScript(this, cx.compileString(source, fileName, 1))
    }

    internal fun runScript(script: Script): JsValue = guarded { JsValue(script.exec(cx, scopeObject)) }

    /**
     * Runs whatever the promise callbacks have queued. Evaluating already drains the queue at the
     * end of the call, so this is only for work queued from a host callback afterwards.
     */
    public fun runMicrotasks() {
        checkOpen()
        try {
            cx.processMicrotasks()
        } catch (e: Throwable) {
            throw translate(e)
        }
    }

    /** A Kotlin value as the engine sees it, collections and all. */
    public fun valueOf(value: Any?): JsValue = JsValue(Converters.toEngine(value, cx, scopeObject))

    /** A fresh empty object, the same as `{}` in a script. */
    public fun newObject(): JsObject = JsObject(cx.newObject(scopeObject))

    /** A fresh array holding [elements]. */
    public fun newArray(vararg elements: Any?): JsArray =
        JsValue(cx.newArray(scopeObject, Converters.toEngineAll(elements, cx, scopeObject))).asArray()

    /** Releases the engine. Using it afterwards throws. */
    override fun close() {
        if (closed) return
        closed = true
        Context.exit()
    }

    private inline fun <T> guarded(body: () -> T): T {
        checkOpen()
        factory.instructionsUsed = 0
        cx.instructionCount = 0
        try {
            return body()
        } catch (e: Throwable) {
            throw translate(e)
        }
    }

    private fun checkOpen() {
        if (closed) throw JsEngineError("this engine is closed")
    }

    public companion object {
        /** How often the interrupt hook is asked when there is no budget to pace it. */
        private const val INTERRUPT_SLICE = 100_000

        internal fun build(config: KiteJsConfig): KiteJs {
            if (Context.getCurrentContext() != null) {
                throw JsEngineError(
                    "this thread already has an open engine; close it first, use `use { }`, " +
                        "or open the second engine on its own thread",
                )
            }
            val factory = EngineFactory(config)
            val cx = factory.enterContext()
            return try {
                cx.languageVersion = config.languageVersion.code
                cx.timeZone = config.timeZone
                config.clock?.let { cx.clock = it }
                val watching = config.instructionBudget > 0 || config.interruptWhen != null
                if (watching) {
                    cx.setGenerateObserverCount(true)
                    // With no budget the observer exists only to ask the interrupt hook, so it
                    // needs a slice small enough to notice a stop but large enough to stay cheap.
                    val slice =
                        if (config.instructionBudget > 0) config.instructionBudget else INTERRUPT_SLICE
                    cx.setInstructionObserverThreshold(slice)
                }
                val scope =
                    if (config.safeBuiltins) cx.initSafeStandardObjects(null, config.sealBuiltins)
                    else cx.initStandardObjects(null, config.sealBuiltins)
                config.console?.let { NativeConsole.init(scope, config.sealBuiltins, it) }
                KiteJs(factory, cx, scope)
            } catch (e: Throwable) {
                Context.exit()
                throw translate(e)
            }
        }
    }
}

/** The factory that carries the instruction budget into the running script. */
internal class EngineFactory(private val config: KiteJsConfig) : ContextFactory() {

    var instructionsUsed: Int = 0

    override fun hasFeature(cx: Context, featureIndex: Int): Boolean = when (featureIndex) {
        Context.FEATURE_LITTLE_ENDIAN -> config.littleEndian
        Context.FEATURE_ASM_JS -> config.asmJs
        else -> super.hasFeature(cx, featureIndex)
    }

    override fun observeInstructionCount(cx: Context, instructionCount: Int) {
        instructionsUsed += instructionCount
        if (config.instructionBudget in 1..instructionsUsed) {
            throw JsEngineError(
                "script used more than ${config.instructionBudget} instructions and was stopped",
            )
        }
        if (config.interruptWhen?.invoke() == true) throw JsEngineError("script was interrupted")
    }
}

/** Builds an engine. See [KiteJsConfig] for what you can set. */
public fun KiteJs(configure: KiteJsConfig.() -> Unit = {}): KiteJs =
    KiteJs.build(KiteJsConfig().apply(configure))

internal fun scopeObjectOf(obj: Scriptable): Scriptable = ScriptableObject.getTopLevelScope(obj)
