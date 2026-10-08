/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

private fun browserRead(name: String): String? = js("""(function(fileName) {
    if (typeof XMLHttpRequest === 'undefined') return null;
    var request = new XMLHttpRequest();
    request.open('GET', '/test262/' + fileName, false);
    request.send();
    if (request.status !== 200) throw new Error('test262 fetch failed: ' + fileName + ' (' + request.status + ')');
    return request.responseText;
})(name)""")

internal actual fun readTest262Bundle(name: String): String = browserRead(name)
    ?: SystemFileSystem.source(Path("$TEST262_BUNDLE_ROOT/$name")).buffered().use { it.readString() }
