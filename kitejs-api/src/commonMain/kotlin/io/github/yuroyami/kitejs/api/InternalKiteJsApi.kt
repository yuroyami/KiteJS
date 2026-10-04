/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

/**
 * Marks the parts of this API that exist for the engines implementing it, not for the code that
 * embeds an engine. An application builds a [KiteJs] from an engine and works with the handles it
 * gives out; it never needs these.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is for engine implementations. Application code builds a KiteJs from an engine " +
        "and uses the values and handles it gives out.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class InternalKiteJsApi
