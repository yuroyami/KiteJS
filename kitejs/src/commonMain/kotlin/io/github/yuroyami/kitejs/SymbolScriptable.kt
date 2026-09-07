/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** The symbol-keyed half of the property protocol, implemented alongside [Scriptable]. */
public interface SymbolScriptable {

    public fun get(key: Symbol, start: Scriptable): Any?

    public fun has(key: Symbol, start: Scriptable): Boolean

    public fun put(key: Symbol, start: Scriptable, value: Any?)

    public fun delete(key: Symbol)
}
