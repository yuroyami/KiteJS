/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * A map whose keys do not keep their entries alive, which is what `WeakMap` and `WeakSet` are
 * built on. Upstream uses `java.util.WeakHashMap`; common Kotlin has no equivalent, so this is the
 * port's own over [WeakRef] (D-55).
 *
 * Keys are matched by identity, not by `equals`, which is what JavaScript asks for. `hashCode` is
 * used only to pick a bucket, so a key type that gives two objects the same hash costs a longer
 * bucket and nothing else.
 *
 * Entries whose key has been collected are swept out as the map grows, and again whenever it is
 * asked how big it is. There is no reference queue in common Kotlin, so cleanup is amortised
 * rather than immediate.
 */
internal class WeakKeyMap<V : Any> {

    private class Entry<V : Any>(key: Any, var value: V) {
        val ref = WeakRef(key)
        val hash = key.hashCode()
    }

    private val buckets = HashMap<Int, MutableList<Entry<V>>>()

    /** Entries added since the last sweep, which is what decides when the next one runs. */
    private var addedSinceSweep = 0

    fun get(key: Any): V? = findEntry(key)?.value

    fun containsKey(key: Any): Boolean = findEntry(key) != null

    fun put(key: Any, value: V) {
        val existing = findEntry(key)
        if (existing != null) {
            existing.value = value
            return
        }
        val hash = key.hashCode()
        buckets.getOrPut(hash) { mutableListOf() }.add(Entry(key, value))
        addedSinceSweep++
        if (addedSinceSweep >= SWEEP_EVERY) sweep()
    }

    /** True when the key was there to remove. */
    fun remove(key: Any): Boolean {
        val bucket = buckets[key.hashCode()] ?: return false
        val i = bucket.indexOfFirst { it.ref.get() === key }
        if (i < 0) return false
        bucket.removeAt(i)
        if (bucket.isEmpty()) buckets.remove(key.hashCode())
        return true
    }

    /** How many keys are still alive. Sweeps first, so the answer is not stale. */
    fun size(): Int {
        sweep()
        var n = 0
        for (bucket in buckets.values) n += bucket.size
        return n
    }

    private fun findEntry(key: Any): Entry<V>? =
        buckets[key.hashCode()]?.firstOrNull { it.ref.get() === key }

    /** Drops every entry whose key has been collected. */
    private fun sweep() {
        addedSinceSweep = 0
        val emptied = mutableListOf<Int>()
        for ((hash, bucket) in buckets) {
            bucket.retainAll { it.ref.get() != null }
            if (bucket.isEmpty()) emptied.add(hash)
        }
        for (hash in emptied) buckets.remove(hash)
    }

    private companion object {
        /** Sweeping on every write would cost more than the entries it reclaims. */
        const val SWEEP_EVERY = 64
    }
}
