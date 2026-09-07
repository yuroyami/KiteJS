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
enum class LanguageVersion(internal val code: Int) {
    /** ES6 and the parts of later editions this engine implements. The usual choice. */
    ES6(Context.VERSION_ES6),

    /** The newest the engine has, which today behaves the same as [ES6]. */
    LATEST(Context.VERSION_ECMASCRIPT),

    /** ES5, before `let`, classes, generators and the rest. */
    ES5(Context.VERSION_1_8),
}

/** How to build an engine. Everything has a working default. */
class KiteJsConfig internal constructor() {

    /** Which JavaScript the engine speaks. */
    var languageVersion: LanguageVersion = LanguageVersion.LATEST

    /** The zone `Date` reads local time in. */
    var timeZone: TimeZone = TimeZone.currentSystemDefault()

    /** Where `Date.now()` reads the time from, in epoch milliseconds. Fix it to make tests stable. */
    var clock: (() -> Double)? = null

    /** Where `console.log` and its neighbours go. Null leaves `console` out of the global scope. */
    var console: ConsolePrinter? = null

    /**
     * How many interpreter instructions one call may run before the engine gives up. Zero, the
     * default, means no limit. This is how you stop a script that never returns.
     */
    var instructionBudget: Int = 0

    /** Leaves out the built-ins a sandbox does not want. Today that is only the old `Packages` hooks. */
    var safeBuiltins: Boolean = false

    /** Makes the built-ins read-only, so a script cannot redefine `Array.prototype.push`. */
    var sealBuiltins: Boolean = false
}

/** A parsed script, ready to run more than once. */
class JsScript internal constructor(private val engine: KiteJs, private val script: Script) {

    /** Runs it in the engine's global scope. */
    fun run(): JsValue = engine.runScript(script)
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
class KiteJs internal constructor(
    internal val factory: EngineFactory,
    internal val cx: Context,
    private val scopeObject: ScriptableObject,
) : AutoCloseable {

    private var closed = false

    /** The global object. Bind host functions and values onto it. */
    val global: JsObject = JsObject(scopeObject)

    /** The engine's version string. */
    val version: String get() = cx.implementationVersion

    /** Parses and runs [source]. The answer is the last expression's value. */
    fun evaluate(source: String, fileName: String = "<eval>"): JsValue = guarded {
        JsValue(cx.evaluateString(scopeObject, source, fileName, 1))
    }

    /** Parses [source] once so it can be run many times. */
    fun compile(source: String, fileName: String = "<script>"): JsScript = guarded {
        JsScript(this, cx.compileString(source, fileName, 1))
    }

    internal fun runScript(script: Script): JsValue = guarded { JsValue(script.exec(cx, scopeObject)) }

    /**
     * Runs whatever the promise callbacks have queued. Evaluating already drains the queue at the
     * end of the call, so this is only for work queued from a host callback afterwards.
     */
    fun runMicrotasks() {
        checkOpen()
        try {
            cx.processMicrotasks()
        } catch (e: Throwable) {
            throw translate(e)
        }
    }

    /** A Kotlin value as the engine sees it, collections and all. */
    fun valueOf(value: Any?): JsValue = JsValue(Converters.toEngine(value, cx, scopeObject))

    /** A fresh empty object, the same as `{}` in a script. */
    fun newObject(): JsObject = JsObject(cx.newObject(scopeObject))

    /** A fresh array holding [elements]. */
    fun newArray(vararg elements: Any?): JsArray =
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

    companion object {
        internal fun build(config: KiteJsConfig): KiteJs {
            if (Context.getCurrentContext() != null) {
                throw JsEngineError(
                    "another engine is already open on this thread; close it first, or use `use { }`",
                )
            }
            val factory = EngineFactory(config)
            val cx = factory.enterContext()
            return try {
                cx.languageVersion = config.languageVersion.code
                cx.timeZone = config.timeZone
                config.clock?.let { cx.clock = it }
                if (config.instructionBudget > 0) {
                    cx.setGenerateObserverCount(true)
                    cx.setInstructionObserverThreshold(config.instructionBudget)
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

    override fun observeInstructionCount(cx: Context, instructionCount: Int) {
        instructionsUsed += instructionCount
        if (config.instructionBudget in 1..instructionsUsed) {
            throw JsEngineError(
                "script used more than ${config.instructionBudget} instructions and was stopped",
            )
        }
    }
}

/** Builds an engine. See [KiteJsConfig] for what you can set. */
fun KiteJs(configure: KiteJsConfig.() -> Unit = {}): KiteJs =
    KiteJs.build(KiteJsConfig().apply(configure))

internal fun scopeObjectOf(obj: Scriptable): Scriptable = ScriptableObject.getTopLevelScope(obj)
