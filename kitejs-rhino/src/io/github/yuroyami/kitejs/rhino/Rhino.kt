/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import io.github.yuroyami.kitejs.api.InternalKiteJsApi
import io.github.yuroyami.kitejs.api.JsEngine
import io.github.yuroyami.kitejs.api.KiteJs
import io.github.yuroyami.kitejs.api.KiteJsConfig
import io.github.yuroyami.kitejs.rhino.facade.RhinoKiteJs

/**
 * The Rhino engine, ported to common Kotlin: no native code, the same answers on every target.
 *
 * ```
 * KiteJs(Rhino) { languageVersion = LanguageVersion.ES6 }.use { js ->
 *     println(js.evaluate("[1, 2, 3].map(x => x * 2).join()").asString())   // 2,4,6
 * }
 * ```
 *
 * One thread holds one Rhino engine at a time: open a second only after closing the first, or on
 * another thread.
 */
public object Rhino : JsEngine<RhinoConfig> {

    override val name: String get() = "Rhino"

    override val oneEnginePerThread: Boolean get() = true

    @InternalKiteJsApi
    override fun newConfig(): RhinoConfig = RhinoConfig()

    @InternalKiteJsApi
    override fun open(config: RhinoConfig): KiteJs = RhinoKiteJs.open(config)
}

/** Which JavaScript the engine speaks. */
public enum class LanguageVersion(internal val code: Int) {
    /** ES6 and the parts of later editions this engine implements. The usual choice. */
    ES6(Context.VERSION_ES6),

    /** The newest the engine has, which today behaves the same as [ES6]. */
    LATEST(Context.VERSION_ECMASCRIPT),

    /** ES5, before `let`, classes, generators and the rest. */
    ES5(Context.VERSION_1_8),
}

/** How to build a Rhino engine: everything [KiteJsConfig] has, and what only Rhino can do. */
public class RhinoConfig @InternalKiteJsApi constructor() : KiteJsConfig() {

    /** Which JavaScript the engine speaks. */
    public var languageVersion: LanguageVersion = LanguageVersion.LATEST

    /** Leaves out the built-ins a sandbox does not want. Today that is only the old `Packages` hooks. */
    public var safeBuiltins: Boolean = false

    /** Maximum nested script-call depth, 10,000 by default; zero disables the frame limit. */
    public var maxCallDepth: Int = 10_000

    /**
     * Maximum nested host calls and interpreter reentries, 64 by default. Must be positive.
     * Getters, built-in callbacks and compiled asm.js calls count too: they use the native stack.
     */
    public var maxHostCallDepth: Int = 64

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

/**
 * One entry per `"use asm"` function a Rhino engine has parsed, in the order it met them. Empty
 * for any other engine, which compiles no asm.js ahead of time.
 *
 * Use it to find out why an Emscripten module is running slowly: the reason names the first
 * thing in it that asm.js does not allow.
 */
public val KiteJs.asmReports: List<AsmReport>
    get() = (this as? RhinoKiteJs)?.asmReports ?: emptyList()
