/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino.contract

import io.github.yuroyami.kitejs.rhino.Rhino
import io.github.yuroyami.kitejs.rhino.RhinoConfig
import io.github.yuroyami.kitejs.testkit.ConfigContract
import io.github.yuroyami.kitejs.testkit.ErrorsContract
import io.github.yuroyami.kitejs.testkit.HandlesContract
import io.github.yuroyami.kitejs.testkit.HostBindingContract
import io.github.yuroyami.kitejs.testkit.LocaleNumbersContract
import io.github.yuroyami.kitejs.testkit.PromisesContract
import io.github.yuroyami.kitejs.testkit.ValuesContract

/* The contract every engine keeps, from kitejs-testkit, run against Rhino. */

class RhinoValuesTest : ValuesContract<RhinoConfig>(Rhino)

class RhinoHandlesTest : HandlesContract<RhinoConfig>(Rhino)

class RhinoHostBindingTest : HostBindingContract<RhinoConfig>(Rhino)

class RhinoErrorsTest : ErrorsContract<RhinoConfig>(Rhino)

class RhinoPromisesTest : PromisesContract<RhinoConfig>(Rhino)

class RhinoConfigTest : ConfigContract<RhinoConfig>(Rhino)

class RhinoLocaleNumbersTest : LocaleNumbersContract<RhinoConfig>(Rhino)
