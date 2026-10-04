/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.testkit

import io.github.yuroyami.kitejs.api.JsEngine
import io.github.yuroyami.kitejs.api.KiteJs
import io.github.yuroyami.kitejs.api.KiteJsConfig
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest

/**
 * What every contract suite is built on: the engine under test, and a way to run a test with it
 * loaded. An engine module subclasses each suite once, passing its engine:
 *
 * ```
 * class RhinoValuesTest : ValuesContract<RhinoConfig>(Rhino)
 * ```
 *
 * Every test runs inside [runTest], after [JsEngine.load], so an engine that has to load before it
 * opens, QuickJS in a browser, runs the same suite as one that does not. Nothing in a test body
 * suspends after an engine opens, so the engine stays on the thread that opened it.
 */
public abstract class EngineContract<C : KiteJsConfig>(protected val engine: JsEngine<C>) {

    /** Runs [body] once the engine is loaded. */
    protected fun test(body: () -> Unit): TestResult = runTest {
        engine.load()
        body()
    }

    /** Runs [body] with a fresh engine built with [configure], closed afterwards. */
    protected fun withEngine(configure: C.() -> Unit = {}, body: (KiteJs) -> Unit): TestResult = test {
        open(configure).use(body)
    }

    /** A fresh engine, for a test that has to manage its lifetime itself. */
    protected fun open(configure: C.() -> Unit = {}): KiteJs = KiteJs(engine, configure)
}
