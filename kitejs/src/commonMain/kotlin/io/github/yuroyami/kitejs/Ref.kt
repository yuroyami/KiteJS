/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * A reference to a place a value can be read from, written to or deleted, for the cases the plain
 * property protocol does not cover.
 */
abstract class Ref {

    open fun has(cx: Context): Boolean = true

    abstract fun get(cx: Context): Any?

    abstract fun set(cx: Context, value: Any?): Any?

    open fun set(cx: Context, scope: Scriptable?, value: Any?): Any? = set(cx, value)

    open fun delete(cx: Context): Boolean = false
}
