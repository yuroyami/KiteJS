/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.quickjs.bridge

/** The library ships in the AAR's jni folder, one per ABI, and the APK installs the right one. */
internal fun loadNativeLibrary() {
    System.loadLibrary("kitejs_quickjs")
}
