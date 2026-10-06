/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.quickjs

import io.github.yuroyami.kitejs.api.InternalKiteJsApi
import io.github.yuroyami.kitejs.api.JsEngine
import io.github.yuroyami.kitejs.api.KiteJs
import io.github.yuroyami.kitejs.api.KiteJsConfig
import io.github.yuroyami.kitejs.quickjs.bridge.bridgeLoaded
import io.github.yuroyami.kitejs.quickjs.bridge.loadBridge
import io.github.yuroyami.kitejs.quickjs.facade.QuickJsKiteJs

/**
 * QuickJS-ng, the C engine, bound directly: the same engine on every target, compiled for each
 * one, with the whole of ECMAScript 2024 and most of what came after.
 *
 * ```
 * KiteJs(QuickJs) { memoryLimit = 64L shl 20 }.use { js -> js.evaluate("1 + 1") }
 * ```
 *
 * In a browser or on Node, call [load] once before the first engine opens: it compiles the
 * WebAssembly build, which a browser will not do synchronously on its main thread. Everywhere
 * else the engine is linked in and [load] returns at once.
 */
public object QuickJs : JsEngine<QuickJsConfig> {

    override val name: String get() = "QuickJS"

    override suspend fun load() {
        loadBridge()
    }

    override val isLoaded: Boolean get() = bridgeLoaded()

    @InternalKiteJsApi
    override fun newConfig(): QuickJsConfig = QuickJsConfig()

    @InternalKiteJsApi
    override fun open(config: QuickJsConfig): KiteJs = QuickJsKiteJs.open(config)
}

/** What a QuickJS engine takes on top of what every engine does. */
public class QuickJsConfig @InternalKiteJsApi constructor() : KiteJsConfig() {

    /**
     * The most memory, in bytes, the engine may allocate. Past it an allocation fails and the
     * call that made it throws a JsEngineError. Zero, the default, means no limit.
     */
    public var memoryLimit: Long = 0

    /**
     * How deep, in bytes of native stack, a script may recurse before it gets a RangeError. It
     * has to fit in the stack of the thread the engine runs on, with room left for the host's
     * own frames: the default suits the 1 MiB a JVM or Android thread has, and a thread with a
     * smaller stack, such as a secondary thread on iOS at 512 KiB, needs a smaller value. On
     * JavaScript and WebAssembly the browser's own stack runs out first, so the limit there is
     * 256 KiB at most, about 800 plain calls.
     */
    public var maxStackSize: Long = 512L * 1024
}
