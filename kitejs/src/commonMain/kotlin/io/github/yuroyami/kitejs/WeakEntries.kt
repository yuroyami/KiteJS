/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The entries of one `WeakMap` or `WeakSet`, kept with their keys rather than in the collection.
 *
 * Each key carries a [WeakKeyTable] of what the collections it is in hold for it. That is the
 * inverted representation the spec's note on WeakMap describes for an engine whose collector has
 * no ephemerons: nothing in a collection leads to a key, so a value that refers back to its own
 * key, or entries that refer to each other's keys, cannot keep themselves alive, and an entry goes
 * when its key does. Upstream keeps a `WeakHashMap`, whose values hold such keys forever (D-82). It
 * also means a key is released on a target with no weak references at all, where the collection
 * stays reachable from its keys instead.
 *
 * A key that cannot carry a table falls back to a [WeakKeyMap] of the collection's own.
 */
internal class WeakEntries(private val owner: ScriptableObject) {

    /** The keys that carry no table: built-in symbols, which live forever, and host objects. */
    private var others: WeakKeyMap<Any>? = null

    fun get(key: Any): Any? =
        if (carriesTable(key)) tableOf(key, create = false)?.get(owner) else others?.get(key)

    fun put(key: Any, value: Any) {
        if (carriesTable(key)) tableOf(key, create = true)!!.put(owner, value)
        else (others ?: WeakKeyMap<Any>().also { others = it }).put(key, value)
    }

    /** True when [key] was there to remove. */
    fun remove(key: Any): Boolean =
        if (carriesTable(key)) tableOf(key, create = false)?.remove(owner) ?: false
        else others?.remove(key) ?: false

    private companion object {
        /** Where an object keeps its [WeakKeyTable] among its associated values. */
        val TABLE = Any()

        /**
         * An object made by the engine, or a symbol made by `Symbol()`, belongs to one engine and
         * can die, so it carries its own table. A built-in symbol is shared by every engine and
         * lives forever, and a host object such as a `Delegator` has nowhere to put one.
         */
        fun carriesTable(key: Any): Boolean =
            key is ScriptableObject || (key is SymbolKey && key.kind == Symbol.Kind.REGULAR)

        fun tableOf(key: Any, create: Boolean): WeakKeyTable? = when (key) {
            is ScriptableObject -> key.getAssociatedValue(TABLE) as WeakKeyTable?
                ?: if (create) key.associateValue(TABLE, WeakKeyTable()) as WeakKeyTable else null
            is SymbolKey -> key.weakTable ?: if (create) WeakKeyTable().also { key.weakTable = it } else null
            else -> null
        }
    }
}

/**
 * What one key holds for the weak collections it is in, each found by its collection, which is
 * held weakly.
 *
 * Only the key leads here, so when the key goes, all of it goes. A collection that goes first
 * leaves its entry behind until the key is next looked up, which sweeps it out, or until the key
 * goes too. A key is in few collections, so a list scanned in full beats hashing.
 */
internal class WeakKeyTable {

    private val owners = ArrayList<WeakRef<Any>>(1)
    private val values = ArrayList<Any>(1)

    fun get(owner: Any): Any? {
        val i = indexOf(owner)
        return if (i < 0) null else values[i]
    }

    fun put(owner: Any, value: Any) {
        val i = indexOf(owner)
        if (i >= 0) {
            values[i] = value
        } else {
            owners.add(WeakRef(owner))
            values.add(value)
        }
    }

    /** True when [owner] had an entry here to remove. */
    fun remove(owner: Any): Boolean {
        val i = indexOf(owner)
        if (i < 0) return false
        owners.removeAt(i)
        values.removeAt(i)
        return true
    }

    /** Where [owner] is, dropping on the way the entries of collections that have been collected. */
    private fun indexOf(owner: Any): Int {
        var found = -1
        var i = 0
        while (i < owners.size) {
            val o = owners[i].get()
            if (o == null) {
                owners.removeAt(i)
                values.removeAt(i)
                continue
            }
            if (o === owner) found = i
            i++
        }
        return found
    }
}
