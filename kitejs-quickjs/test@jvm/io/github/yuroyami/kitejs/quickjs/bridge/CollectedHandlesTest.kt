/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.quickjs.bridge

import io.github.yuroyami.kitejs.api.KiteJs
import io.github.yuroyami.kitejs.quickjs.QuickJs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The engine frees the handles of wrappers that the collector took in time that grows with their
 * number, not its square. The handles waited in a list copied on each one added, so 300,000 found
 * in one collection held the engine thread for minutes on its next call.
 */
class CollectedHandlesTest {

    @Test
    fun many_collected_handles_are_freed_quickly() {
        KiteJs(QuickJs).use { js ->
            js.evaluate("var objs = []; for (var i = 0; i < 200000; i++) objs.push({ i: i })")
            val objs = js.evaluate("objs").asObject()
            var sum = 0L
            // Each read hands Kotlin a wrapper of its own, which is dropped at once.
            for (i in 0 until 200_000) sum += objs[i].asObject()["i"].asLong()
            assertEquals(199_999L * 200_000L / 2, sum)
            repeat(5) { System.gc(); Thread.sleep(50) }
            val start = TimeSource.Monotonic.markNow()
            assertEquals(2, js.evaluate("1 + 1").asInt())
            val took = start.elapsedNow()
            assertTrue(took < 3.seconds, "freeing the collected handles took $took")
        }
    }
}
