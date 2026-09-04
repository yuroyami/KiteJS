/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The JavaScript `Object` builtin.
 *
 * Phase 2 slice: only the two magic property names the IR generator checks for. The real object,
 * with its constructor and prototype methods, arrives in phase 3 with the object model.
 */
object NativeObject {

    const val CLASS_NAME = "Object"

    const val PROTO_PROPERTY = "__proto__"

    const val PARENT_PROPERTY = "__parent__"
}
