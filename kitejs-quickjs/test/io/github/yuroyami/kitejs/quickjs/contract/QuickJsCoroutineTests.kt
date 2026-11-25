/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.quickjs.contract

import io.github.yuroyami.kitejs.quickjs.QuickJs
import io.github.yuroyami.kitejs.quickjs.QuickJsConfig
import io.github.yuroyami.kitejs.testkit.AsyncKiteJsContract
import io.github.yuroyami.kitejs.testkit.AwaitContract
import io.github.yuroyami.kitejs.testkit.ShutdownContract

/* The suites of kitejs-coroutines, run against QuickJS. */

class QuickJsAsyncKiteJsTest : AsyncKiteJsContract<QuickJsConfig>(QuickJs)

class QuickJsAwaitTest : AwaitContract<QuickJsConfig>(QuickJs)

class QuickJsShutdownTest : ShutdownContract<QuickJsConfig>(QuickJs)
