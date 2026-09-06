/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What [WeakKeyMap] promises while its keys are still held. Collection cannot be forced from common
 * code, so `WeakKeyMapGcTest` on the JVM is where the weak half is proved.
 */
class WeakKeyMapTest {

    /** Two of these are never the same key, however alike they look. */
    private class Key(val name: String) {
        override fun equals(other: Any?): Boolean = other is Key && other.name == name
        override fun hashCode(): Int = name.hashCode()
    }

    @Test
    fun storesAndReadsBack() {
        val map = WeakKeyMap<String>()
        val a = Key("a")
        val b = Key("b")
        map.put(a, "one")
        map.put(b, "two")
        assertEquals("one", map.get(a))
        assertEquals("two", map.get(b))
        assertEquals(2, map.size())
    }

    @Test
    fun keysAreMatchedByIdentityNotEquality() {
        val map = WeakKeyMap<String>()
        val a = Key("same")
        val twin = Key("same")
        map.put(a, "mine")
        // The twin is equal and hashes the same, but it is a different object.
        assertNull(map.get(twin))
        assertFalse(map.containsKey(twin))
        assertTrue(map.containsKey(a))
        map.put(twin, "theirs")
        assertEquals("mine", map.get(a))
        assertEquals("theirs", map.get(twin))
        assertEquals(2, map.size())
    }

    @Test
    fun writingTwiceReplaces() {
        val map = WeakKeyMap<String>()
        val a = Key("a")
        map.put(a, "first")
        map.put(a, "second")
        assertEquals("second", map.get(a))
        assertEquals(1, map.size())
    }

    @Test
    fun removeReportsWhetherItWasThere() {
        val map = WeakKeyMap<String>()
        val a = Key("a")
        val b = Key("b")
        map.put(a, "one")
        assertTrue(map.remove(a))
        assertFalse(map.remove(a))
        assertFalse(map.remove(b))
        assertNull(map.get(a))
        assertEquals(0, map.size())
    }

    @Test
    fun survivesMoreEntriesThanTheSweepThreshold() {
        val map = WeakKeyMap<Int>()
        // Held in a list, so nothing here is collectable and every entry has to stay.
        val keys = (0 until 500).map { Key("k$it") }
        for ((i, k) in keys.withIndex()) map.put(k, i)
        assertEquals(500, map.size())
        for ((i, k) in keys.withIndex()) assertEquals(i, map.get(k))
    }
}
