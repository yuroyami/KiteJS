/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.buildplugins.quickjsandroid

import org.jetbrains.amper.plugins.Configurable
import java.nio.file.Path

@Configurable
interface QuickJsAndroidSettings {
    /** The native/ folder of kitejs-quickjs, with the C code. */
    val nativeDir: Path

    /** The lowest Android API level the libraries load on. Keep it equal to the minSdk of kitejs-quickjs. */
    val minSdk: Int get() = 21
}
