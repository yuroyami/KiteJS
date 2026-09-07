/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

@JsModule("@js-joda/timezone")
private external object JsJodaTimeZoneModule : JsAny

/**
 * Pulls in the IANA zone database on Kotlin/Wasm, for the same reason the JS target does.
 *
 * kotlinx-datetime reads zones through `@js-joda/core`, which on its own knows only UTC and fixed
 * offsets. The database is a separate package that registers itself when it is loaded, and nothing
 * loads it unless something references it. The eager initialiser keeps that reference from being
 * dropped as dead code.
 */
@OptIn(ExperimentalStdlibApi::class)
@EagerInitialization
private val loadJsJodaTimeZoneDatabase: JsAny = JsJodaTimeZoneModule
