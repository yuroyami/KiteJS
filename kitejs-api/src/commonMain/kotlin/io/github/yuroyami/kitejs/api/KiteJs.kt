/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * An engine and the global scope that goes with it. One instance belongs to the thread that opened
 * it: use it and close it there.
 *
 * ```
 * KiteJs(Rhino).use { js ->
 *     js.global.function("shout") { args -> args.first().asString().uppercase() }
 *     println(js.evaluate("shout('hi')").asString())   // HI
 * }
 * ```
 *
 * Every outermost call from the host into the engine, `evaluate`, running a [JsScript], calling a
 * [JsFunction] or a method, runs the Promise reactions it queued before it returns. Work queued
 * later, from a host callback, waits for the next such call or for [runMicrotasks].
 *
 * An exception a host function throws is not a JavaScript exception: no `catch` in the script sees
 * it, and it comes out of the call that started the script as itself when it is a [JsException],
 * or inside a [JsEngineError] otherwise.
 */
public abstract class KiteJs @InternalKiteJsApi constructor() : AutoCloseable {

    /** The engine underneath, such as `Rhino` or `QuickJs`. */
    public abstract val engine: JsEngine<*>

    /** The global object. Bind host functions and values onto it. */
    public abstract val global: JsObject

    /** The engine's name and version. */
    public abstract val version: String

    /** Parses and runs [source]. The answer is the last expression's value. */
    public abstract fun evaluate(source: String, fileName: String = "<eval>"): JsValue

    /** Parses [source] once so it can be run many times. */
    public abstract fun compile(source: String, fileName: String = "<script>"): JsScript

    /**
     * A script from [bytecode] that [JsScript.bytecode] wrote in an engine of the same kind and
     * version, ready to run here without a parse. The engine runs bytecode without checking it,
     * so load only bytecode the host wrote itself, never bytes from a document or the network.
     * Throws a [JsEngineError] on an engine that has no bytecode, and for bytes it cannot read.
     */
    public open fun loadBytecode(bytecode: ByteArray): JsScript =
        throw JsEngineError("${engine.name} has no bytecode")

    /**
     * Whether [evaluatePausing] really pauses here. Only QuickJS on a browser or Node with
     * WebAssembly stack switching (JSPI) can; everywhere else it runs like [evaluate].
     */
    public open val canPause: Boolean get() = false

    /**
     * Runs [source] like [evaluate], but where [canPause] is true the script pauses about every
     * [slice] and lets the event loop run before it goes on. A long script then does not freeze
     * the page on a platform with one thread.
     *
     * The script pauses only while no host function runs, and only one such run pauses at a time
     * in a process. While a script is paused, every other call into this engine throws a
     * [JsEngineError]; other engines stay usable. Where the script cannot pause, it runs to the
     * end in one go.
     */
    public open suspend fun evaluatePausing(
        source: String,
        fileName: String = "<eval>",
        slice: Duration = 16.milliseconds,
    ): JsValue = evaluate(source, fileName)

    /**
     * Runs whatever the promise callbacks have queued. Evaluating already drains the queue at the
     * end of the call, so this is only for work queued from a host callback afterwards.
     */
    public abstract fun runMicrotasks()

    /** A Kotlin value as the engine sees it, collections and all. See [Converters]. */
    public abstract fun valueOf(value: Any?): JsValue

    /** A fresh empty object, the same as `{}` in a script. */
    public abstract fun newObject(): JsObject

    /** A fresh array holding [elements]. */
    public abstract fun newArray(vararg elements: Any?): JsArray

    /**
     * Releases the engine. Using it afterwards throws, and so does every handle it gave out.
     * Only the thread that opened it can release it; from another thread this throws and leaves
     * the engine open. Closing it again does nothing.
     */
    abstract override fun close()

    // ---- What the engine provides for the host bindings and the promise helpers -------------------

    /**
     * A host function named [name]. [body] gets `this` and the arguments, and its answer goes
     * back through [Converters].
     */
    @InternalKiteJsApi
    public abstract fun newFunction(
        name: String,
        arity: Int,
        body: (self: JsValue, args: List<JsValue>) -> Any?,
    ): JsFunction

    /** A host constructor: `new` makes a plain object and hands it to [build] to fill in. */
    @InternalKiteJsApi
    public abstract fun newConstructor(
        name: String,
        arity: Int,
        build: (JsObject, List<JsValue>) -> Unit,
    ): JsFunction

    /** Whether [obj] has a callable `then`, read once, as a host call. */
    @InternalKiteJsApi
    public abstract fun thenableCheck(obj: JsObject): Boolean

    /** What [onSettled] does; see there. */
    @InternalKiteJsApi
    public abstract fun watchSettlement(value: JsValue, onSettled: (JsValue, JsValue?) -> Unit): Boolean
}
