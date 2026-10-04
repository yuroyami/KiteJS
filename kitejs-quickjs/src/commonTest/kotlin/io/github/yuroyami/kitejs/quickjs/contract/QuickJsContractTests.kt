/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.quickjs.contract

import io.github.yuroyami.kitejs.quickjs.QuickJs
import io.github.yuroyami.kitejs.quickjs.QuickJsConfig
import io.github.yuroyami.kitejs.testkit.ConfigContract
import io.github.yuroyami.kitejs.testkit.ErrorsContract
import io.github.yuroyami.kitejs.testkit.HandlesContract
import io.github.yuroyami.kitejs.testkit.HostBindingContract
import io.github.yuroyami.kitejs.testkit.PromisesContract
import io.github.yuroyami.kitejs.testkit.ValuesContract

/* The contract every engine keeps, run against QuickJS. */

class QuickJsValuesTest : ValuesContract<QuickJsConfig>(QuickJs)

class QuickJsHandlesTest : HandlesContract<QuickJsConfig>(QuickJs)

class QuickJsHostBindingTest : HostBindingContract<QuickJsConfig>(QuickJs)

class QuickJsErrorsTest : ErrorsContract<QuickJsConfig>(QuickJs)

class QuickJsPromisesTest : PromisesContract<QuickJsConfig>(QuickJs)

class QuickJsConfigTest : ConfigContract<QuickJsConfig>(QuickJs)
