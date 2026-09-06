/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The half that only the JVM can check: that the reference really is weak. No other target lets a
 * test ask for a collection, so this is the one place the promise is proved rather than assumed.
 */
class WeakRefGcTest {

    @Test
    fun theReferentGoesAwayOnceNothingElseHoldsIt() {
        var target: StringBuilder? = StringBuilder("collect me")
        val weak = WeakRef(target!!)
        assertNotNull(weak.get())

        target = null
        // A single System.gc() is a hint, not a promise, so this asks a few times before giving up.
        var collected = false
        repeat(20) {
            if (weak.get() == null) {
                collected = true
                return@repeat
            }
            System.gc()
            Thread.sleep(10)
        }
        assertTrue(collected || weak.get() == null, "the referent was never collected")
        assertNull(weak.get())
    }

    @Test
    fun aHeldReferentSurvivesCollection() {
        val target = StringBuilder("keep me")
        val weak = WeakRef(target)
        repeat(5) {
            System.gc()
            Thread.sleep(5)
        }
        assertNotNull(weak.get())
        // Reading `target` here is what keeps it alive across the loop above.
        assertTrue(target.isNotEmpty())
    }

    @Test
    fun weakSupportIsRealOnTheJvm() {
        assertTrue(WeakRef.isWeakSupported)
    }
}
