/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The table behind `Map` and `Set`: a hash map for lookup plus a doubly linked list for insertion
 * order.
 *
 * The linked list is the reason this is not a `LinkedHashMap`. JavaScript lets a script add to,
 * delete from or clear a collection while an iterator is walking it, and the iterator has to keep
 * going from where it was. A deleted node keeps its `next` pointer so any iterator sitting on it
 * can still walk forward; only the `prev` pointers are unlinked, so new iterators never see it.
 */
public class Hashtable : Iterable<Hashtable.Entry> {

    private val map = HashMap<Entry, Entry>()
    private var first: Entry? = null
    private var last: Entry? = null

    /**
     * One entry, and at the same time one node of the list. `equals` and `hashCode` follow
     * JavaScript's SameValueZero, not Java's rules.
     */
    public class Entry {
        internal var key: Any?
        internal var value: Any?
        internal var deleted: Boolean = false
        internal var next: Entry? = null
        internal var prev: Entry? = null
        private val hash: Int

        internal constructor() {
            key = null
            value = null
            hash = 0
        }

        internal constructor(k: Any?, value: Any?) {
            // Numbers all normalise to Double so that 1 and 1.0 hash to the same bucket. A big
            // integer keeps its own type, because SameValueZero says values of different types are
            // never equal. Upstream reaches the same three cases through java.lang.Number.
            key = when {
                k is KBigInt -> k
                k is Number -> k.toDouble()
                k is ConsString -> k.toString()
                else -> k
            }
            val key = key
            hash = when {
                key == null -> 0
                // Both zeroes have to land in the same bucket, since SameValueZero equates them.
                key is Double && key == 0.0 -> 0
                else -> key.hashCode()
            }
            this.value = value
        }

        public fun key(): Any? = key

        public fun value(): Any? = value

        /** Blanks the node so any iterator standing on it skips past. */
        internal fun clear() {
            key = Undefined.instance
            value = Undefined.instance
            deleted = true
        }

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (other !is Entry) return false
            return ScriptRuntime.sameZero(key, other.key)
        }
    }

    public val size: Int
        get() = map.size

    public fun put(key: Any?, value: Any?) {
        val nv = Entry(key, value)
        val existing = map[nv]
        if (existing == null) {
            // New key: append to the list.
            val tail = last
            if (tail == null) {
                first = nv
                last = nv
            } else {
                tail.next = nv
                nv.prev = tail
                last = nv
            }
            map[nv] = nv
        } else {
            // Known key: the value changes, the position does not.
            existing.value = value
        }
    }

    public fun getEntry(key: Any?): Entry? = map[Entry(key, null)]

    public fun has(key: Any?): Boolean = map.containsKey(Entry(key, null))

    public fun deleteEntry(key: Any?): Boolean {
        val v = map.remove(Entry(key, null)) ?: return false
        unlink(v)
        // Blanked even though it is unlinked, in case an iterator is standing on it.
        v.clear()
        return true
    }

    private fun unlink(v: Entry) {
        if (v === first) {
            if (v === last) {
                // The only element. It stays as a blank node, or live iterators never stop.
                v.clear()
                v.prev = null
            } else {
                val next = v.next!!
                first = next
                next.prev = null
                next.next?.prev = next
            }
        } else {
            val prev = v.prev!!
            prev.next = v.next
            v.prev = null
            val next = v.next
            if (next != null) {
                next.prev = prev
            } else {
                last = prev
            }
        }
    }

    public fun clear() {
        // Blank every node so live iterators walk straight past them.
        for (entry in this) entry.clear()

        // The list is replaced by a single blank node at the end of the old one. An iterator that
        // is mid-walk runs into it and then into whatever is added next.
        if (first != null) {
            val dummy = makeDummy()
            last!!.next = dummy
            first = dummy
            last = dummy
        }

        map.clear()
    }

    override fun iterator(): Iterator<Entry> = Iter(first)

    /** Walks the linked list, not the map, which is what gives JavaScript's iteration rules. */
    private class Iter(start: Entry?) : Iterator<Entry> {
        private var pos: Entry

        init {
            // A blank node in front keeps the walk logic to one case.
            val dummy = makeDummy()
            dummy.next = start
            pos = dummy
        }

        private fun skipDeleted() {
            // Steps over anything a delete or a clear blanked after this iterator was made.
            while (pos.next?.deleted == true) {
                pos = pos.next!!
            }
        }

        override fun hasNext(): Boolean {
            skipDeleted()
            return pos.next != null
        }

        override fun next(): Entry {
            skipDeleted()
            val e = pos.next ?: throw NoSuchElementException()
            pos = e
            return e
        }
    }

    public companion object {
        private fun makeDummy(): Entry {
            val d = Entry()
            d.clear()
            return d
        }
    }
}
