/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

@JsModule("@js-joda/timezone")
@JsNonModule
private external object JsJodaTimeZoneModule

/**
 * Pulls in the IANA zone database on Kotlin/JS.
 *
 * kotlinx-datetime reads zones through `@js-joda/core`, which on its own knows only UTC and fixed
 * offsets. The database is a separate package that registers itself when it is loaded, and nothing
 * loads it unless something references it. The eager initialiser is what keeps that reference from
 * being dropped as dead code. Without this, `DateZoneSliceTest` fails with "Invalid zone ID:
 * Europe/Berlin".
 */
@OptIn(ExperimentalStdlibApi::class)
@EagerInitialization
private val loadJsJodaTimeZoneDatabase: Any = JsJodaTimeZoneModule
