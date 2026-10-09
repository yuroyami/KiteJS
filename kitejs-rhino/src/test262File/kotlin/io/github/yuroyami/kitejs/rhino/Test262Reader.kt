/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

internal actual fun readTest262Bundle(name: String): String =
    SystemFileSystem.source(Path("$TEST262_BUNDLE_ROOT/$name")).buffered().use { it.readString() }

// Next to the bundle, so a run that kills the process still names the case it was on.
internal actual fun markTest262Case(name: String) {
    SystemFileSystem.sink(Path("$TEST262_BUNDLE_ROOT/../current-case.txt")).buffered().use { it.writeString(name) }
}
