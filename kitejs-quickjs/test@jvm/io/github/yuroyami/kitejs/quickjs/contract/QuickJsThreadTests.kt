/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.quickjs.contract

import io.github.yuroyami.kitejs.quickjs.QuickJs
import io.github.yuroyami.kitejs.quickjs.QuickJsConfig
import io.github.yuroyami.kitejs.testkit.CancellationContract
import io.github.yuroyami.kitejs.testkit.EngineThreadContract

/* The suites of kitejs-coroutines that need threads, run against QuickJS. */

class QuickJsCancellationTest : CancellationContract<QuickJsConfig>(QuickJs)

class QuickJsEngineThreadTest : EngineThreadContract<QuickJsConfig>(QuickJs)
