/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import io.github.yuroyami.kitejs.rhino.RhinoConfig

/** Opens a Rhino engine, which is what `KiteJs { }` did before there was more than one engine. */
@Deprecated(
    "Name the engine: KiteJs(Rhino) { }. This goes away at 1.0.",
    ReplaceWith("KiteJs(Rhino, configure)", "io.github.yuroyami.kitejs.rhino.Rhino"),
)
public fun KiteJs(configure: RhinoConfig.() -> Unit = {}): KiteJs = KiteJs(Rhino, configure)

/** Where [io.github.yuroyami.kitejs.rhino.LanguageVersion] used to live. */
@Deprecated(
    "LanguageVersion is Rhino's now. This goes away at 1.0.",
    ReplaceWith("LanguageVersion", "io.github.yuroyami.kitejs.rhino.LanguageVersion"),
)
public typealias LanguageVersion = io.github.yuroyami.kitejs.rhino.LanguageVersion
