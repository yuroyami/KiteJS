/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kitejs.api

import kotlin.js.JsAny
import kotlin.js.JsReference
import kotlin.js.toJsReference

private external interface ObjectIdentities : JsAny {
    fun has(key: JsReference<Any>): Boolean
    fun get(key: JsReference<Any>): Int
    fun set(key: JsReference<Any>, value: Int)
}

private fun newObjectIdentities(): ObjectIdentities = js("new WeakMap()")
private val identities = newObjectIdentities()
private var nextIdentity = 0

internal actual fun identityHash(value: Any): Int {
    val reference = value.toJsReference()
    if (!identities.has(reference)) identities.set(reference, nextIdentity++)
    return identities.get(reference)
}
