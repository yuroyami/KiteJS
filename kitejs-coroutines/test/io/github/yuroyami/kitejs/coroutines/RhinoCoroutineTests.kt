/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.coroutines

import io.github.yuroyami.kitejs.rhino.Rhino
import io.github.yuroyami.kitejs.rhino.RhinoConfig
import io.github.yuroyami.kitejs.testkit.AsyncKiteJsContract
import io.github.yuroyami.kitejs.testkit.AwaitContract
import io.github.yuroyami.kitejs.testkit.ShutdownContract

/* The coroutine suites, run against Rhino. kitejs-quickjs runs them against QuickJS. */

class RhinoAsyncKiteJsTest : AsyncKiteJsContract<RhinoConfig>(Rhino)

class RhinoAwaitTest : AwaitContract<RhinoConfig>(Rhino)

class RhinoShutdownTest : ShutdownContract<RhinoConfig>(Rhino)
