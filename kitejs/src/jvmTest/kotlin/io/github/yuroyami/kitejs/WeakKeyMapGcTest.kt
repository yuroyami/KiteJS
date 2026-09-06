/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The half only the JVM can check: that a [WeakKeyMap] really lets its keys go. Nothing in
 * JavaScript can observe a collection, so no conformance suite would ever catch a `WeakMap` that
 * quietly held everything forever. This is the one place the promise is tested rather than assumed.
 */
class WeakKeyMapGcTest {

    private class Key(val name: String)

    private fun collect() {
        repeat(20) {
            System.gc()
            Thread.sleep(10)
        }
    }

    @Test
    fun anEntryGoesAwayOnceItsKeyDoes() {
        val map = WeakKeyMap<String>()
        var key: Key? = Key("temporary")
        map.put(key!!, "value")
        assertEquals("value", map.get(key!!))

        val held = Key("held")
        map.put(held, "kept")

        key = null
        collect()

        // size() sweeps, so the collected key should be gone and the held one should not.
        assertEquals(1, map.size())
        assertEquals("kept", map.get(held))
    }

    @Test
    fun aHeldKeyIsNeverDropped() {
        val map = WeakKeyMap<String>()
        val keys = (0 until 200).map { Key("k$it") }
        for ((i, k) in keys.withIndex()) map.put(k, "v$i")
        collect()
        assertEquals(200, map.size())
        for ((i, k) in keys.withIndex()) assertEquals("v$i", map.get(k))
    }

    @Test
    fun manyDeadKeysAreAllReclaimed() {
        val map = WeakKeyMap<String>()
        repeat(500) { map.put(Key("dead$it"), "v$it") }
        val survivor = Key("survivor")
        map.put(survivor, "alive")
        collect()
        // A handful may survive a single collection cycle, so this checks the bulk went.
        assertTrue(map.size() < 50, "expected most entries to be reclaimed, got ${map.size()}")
        assertEquals("alive", map.get(survivor))
    }

    @Test
    fun aWeakRefToACollectedKeyReadsNull() {
        var key: Key? = Key("gone")
        val ref = WeakRef(key!!)
        key = null
        collect()
        assertNull(ref.get())
    }
}
