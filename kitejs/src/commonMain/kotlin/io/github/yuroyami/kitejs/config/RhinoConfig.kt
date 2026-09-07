/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.config

/**
 * The engine's tuning flags. Upstream reads them from JVM system properties and environment
 * variables; common Kotlin has neither, so every flag answers its compile-time default.
 */
public object RhinoConfig {

    public fun get(property: String, defaultValue: Boolean): Boolean = defaultValue

    public fun get(property: String, defaultValue: Int): Int = defaultValue

    public fun get(property: String): String? = null
}
