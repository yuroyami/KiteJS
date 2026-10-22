/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.math.abs

/**
 * The doubles upstream prints with two digits where ECMAScript, and V8, print one (D-100): the
 * Schubfach formatter Java's Double.toString uses keeps at least two. All are tiny subnormals.
 */
internal object ShortestSubnormals {
    private val V8 = mapOf(
        1L to "5e-324", 2L to "1e-323", 10L to "5e-323", 12L to "6e-323",
        14L to "7e-323", 16L to "8e-323", 18L to "9e-323", 20L to "1e-322",
    )

    /** What the port prints for [d] where upstream differs, or null where the two agree. */
    fun shortest(d: Double): String? = V8[abs(d).toRawBits()]?.let { if (d < 0) "-$it" else it }
}
