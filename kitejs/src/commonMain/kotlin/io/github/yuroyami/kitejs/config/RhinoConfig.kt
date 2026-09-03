/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.config

// KMP: upstream reads JVM system properties and env vars. Common Kotlin has neither,
// so every flag resolves to its compile-time default (ledger D-6).
object RhinoConfig {

    fun get(property: String, defaultValue: Boolean): Boolean = defaultValue

    fun get(property: String, defaultValue: Int): Int = defaultValue

    fun get(property: String): String? = null
}
