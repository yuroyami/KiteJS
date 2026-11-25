/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.coroutines

import io.github.yuroyami.kitejs.rhino.Rhino
import io.github.yuroyami.kitejs.rhino.RhinoConfig
import io.github.yuroyami.kitejs.testkit.CancellationContract
import io.github.yuroyami.kitejs.testkit.EngineThreadContract

/* The coroutine suites that need threads, run against Rhino. kitejs-quickjs runs them against QuickJS. */

class RhinoCancellationTest : CancellationContract<RhinoConfig>(Rhino)

class RhinoEngineThreadTest : EngineThreadContract<RhinoConfig>(Rhino)
