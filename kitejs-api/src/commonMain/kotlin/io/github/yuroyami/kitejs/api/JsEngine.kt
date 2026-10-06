/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlinx.datetime.TimeZone

/**
 * A JavaScript engine a [KiteJs] can run on. Each engine module has one, an object you pass to
 * [KiteJs]: `Rhino` from kitejs-rhino, `QuickJs` from kitejs-quickjs. [C] is the engine's own
 * configuration, which has everything [KiteJsConfig] has plus what only that engine can do.
 *
 * ```
 * KiteJs(Rhino) { timeZone = TimeZone.UTC }.use { js -> js.evaluate("1 + 1") }
 * KiteJs(QuickJs) { memoryLimit = 64L shl 20 }.use { js -> js.evaluate("1 + 1") }
 * ```
 */
public interface JsEngine<C : KiteJsConfig> {

    /** The engine's name, such as `Rhino` or `QuickJS`. */
    public val name: String

    /**
     * Gets the engine ready, where that takes work before the first [KiteJs] can open. QuickJS in
     * a browser compiles its WebAssembly here, which a browser will not do synchronously on its
     * main thread for a module that size. Everywhere else this returns at once. Calling it again
     * once it is done costs nothing.
     */
    public suspend fun load() {}

    /** True once [load] has finished, and from the start where the engine has nothing to load. */
    public val isLoaded: Boolean get() = true

    /**
     * Whether a thread holds one open engine at a time. Where it does, a second engine opens only
     * after the first closes, or on another thread; where it does not, one thread holds several
     * engines, each with a global scope of its own.
     */
    public val oneEnginePerThread: Boolean get() = false

    /** A configuration holding this engine's defaults, for [KiteJs] to hand to the caller. */
    @InternalKiteJsApi
    public fun newConfig(): C

    /** Opens an engine on this thread with [config]. */
    @InternalKiteJsApi
    public fun open(config: C): KiteJs
}

/**
 * Builds an engine on [engine], on this thread. See [KiteJsConfig] for what every engine takes and
 * the engine's own configuration for the rest.
 */
public fun <C : KiteJsConfig> KiteJs(engine: JsEngine<C>, configure: C.() -> Unit = {}): KiteJs =
    engine.open(engine.newConfig().apply(configure))

/**
 * How to build an engine: what every engine understands. Everything has a working default, and an
 * engine's own configuration adds what only it can do.
 */
public open class KiteJsConfig @InternalKiteJsApi constructor() {

    /** The zone `Date` reads local time in. */
    public var timeZone: TimeZone = TimeZone.currentSystemDefault()

    /** Where `Date.now()` reads the time from, in epoch milliseconds. Fix it to make tests stable. */
    public var clock: (() -> Double)? = null

    /** Where `console.log` and its neighbours go. Null leaves `console` out of the global scope. */
    public var console: ConsolePrinter? = null

    /**
     * How much work one call may do before the engine gives up. Zero, the default, means no
     * limit. This is how you stop a script that never returns. The Promise reactions a call queues
     * run within the same budget, and when the engine stops a script the reactions still queued
     * are dropped, so the abandoned work does not run on the next call.
     *
     * What one unit is belongs to the engine. Rhino counts interpreter instructions; QuickJS counts
     * the checks it makes at every loop iteration and call, which come about one for every ten of
     * Rhino's instructions. Either way a budget in the hundreds of thousands stops a runaway loop
     * in well under a second and leaves ordinary scripts alone.
     */
    public var instructionBudget: Int = 0

    /**
     * Asked now and then while a script runs, its Promise reactions included. Answer true, or
     * throw, to stop it. This is how an outside signal reaches a running script: a deadline, a
     * cancelled coroutine, a stop button. As with the budget, a stop drops the reactions still
     * queued.
     */
    public var interruptWhen: (() -> Boolean)? = null

    /** Makes the built-ins read-only, so a script cannot redefine `Array.prototype.push`. */
    public var sealBuiltins: Boolean = false
}
