/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** Implemented by objects that can hold `const` bindings. */
public interface ConstProperties {

    public fun putConst(name: String, start: Scriptable, value: Any?)

    public fun defineConst(name: String, start: Scriptable)

    public fun isConst(name: String): Boolean
}
