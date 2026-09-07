/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * A [SlotMap] built on a hash table that holds the slots directly rather than wrapping each one in
 * another object. That saves an allocation per property, which measurably pays off.
 */
public open class EmbeddedSlotMap : SlotMap {

    private var slots: Array<Slot?>? = null

    // Where the definition-order list starts and ends.
    private var firstAdded: Slot? = null
    private var lastAdded: Slot? = null

    private var count: Int = 0
    private var hasIndex: Boolean = false

    public constructor()

    public constructor(capacity: Int) {
        var n = -1 ushr (capacity - 1).countLeadingZeroBits()
        n = if (n < 0) 1 else n + 1
        slots = arrayOfNulls(n)
    }

    private class Iter(private var next: Slot?) : Iterator<Slot> {
        override fun hasNext(): Boolean = next != null

        override fun next(): Slot {
            val ret = next ?: throw NoSuchElementException()
            next = ret.orderedNext
            return ret
        }
    }

    override fun size(): Int = count

    override fun isEmpty(): Boolean = count == 0

    override fun iterator(): Iterator<Slot> = Iter(firstAdded)

    override fun query(key: Any?, index: Int): Slot? {
        val table = slots ?: return null
        if (key == null && !hasIndex) return null
        val indexOrHash = key?.hashCode() ?: index
        var slot = table[getSlotIndex(table.size, indexOrHash)]
        while (slot != null) {
            if (indexOrHash == slot.indexOrHash && slot.name == key) return slot
            slot = slot.next
        }
        return null
    }

    override fun modify(owner: SlotMapOwner?, key: Any?, index: Int, attributes: Int): Slot {
        val indexOrHash = key?.hashCode() ?: index
        val table = slots
        if (table != null) {
            var slot = table[getSlotIndex(table.size, indexOrHash)]
            while (slot != null) {
                if (indexOrHash == slot.indexOrHash && slot.name == key) return slot
                slot = slot.next
            }
        }
        val newSlot = Slot(key, index, attributes)
        createNewSlot(owner, newSlot)
        return newSlot
    }

    private fun createNewSlot(owner: SlotMapOwner?, newSlot: Slot) {
        if (count == 0 && slots == null) {
            // An insert into an empty map always throws away whatever table was there.
            slots = arrayOfNulls(INITIAL_SLOT_SIZE)
        }
        var table = slots!!
        // Grow before inserting if the table is getting full.
        if (4 * (count + 1) > 3 * table.size) {
            if (count >= SlotMapOwner.LARGE_HASH_SIZE) {
                promoteMap(owner, newSlot)
                return
            }
            // The size has to stay a power of two, so it always doubles.
            val newSlots = arrayOfNulls<Slot>(table.size * 2)
            copyTable(table, newSlots)
            slots = newSlots
            table = newSlots
        }
        insertNewSlot(table, newSlot)
    }

    protected open fun promoteMap(owner: SlotMapOwner?, newSlot: Slot) {
        owner!!.map = HashSlotMap(this, newSlot)
    }

    override fun <S : Slot> compute(
        owner: SlotMapOwner?,
        mutableMap: CompoundOperationMap,
        key: Any?,
        index: Int,
        compute: SlotMap.SlotComputer<S>,
    ): S? {
        val indexOrHash = key?.hashCode() ?: index
        val table = slots
        if (table != null) {
            val slotIndex = getSlotIndex(table.size, indexOrHash)
            var prev = table[slotIndex]
            var slot = prev
            while (slot != null) {
                if (indexOrHash == slot.indexOrHash && slot.name == key) break
                prev = slot
                slot = slot.next
            }
            if (slot != null) {
                return computeExisting(owner, mutableMap, key, index, compute, slot, prev!!, slotIndex)
            }
        }
        return computeNew(owner, mutableMap, key, index, compute)
    }

    private fun <S : Slot> computeNew(
        owner: SlotMapOwner?,
        compoundOp: CompoundOperationMap,
        key: Any?,
        index: Int,
        c: SlotMap.SlotComputer<S>,
    ): S? {
        val newSlot = c.compute(key, index, null, compoundOp, owner)
        if (newSlot != null) {
            if (!compoundOp.touched) {
                createNewSlot(owner, newSlot)
            } else {
                owner!!.map.add(owner, newSlot)
            }
        }
        return newSlot
    }

    private fun <S : Slot> computeExisting(
        owner: SlotMapOwner?,
        compoundOp: CompoundOperationMap,
        key: Any?,
        index: Int,
        c: SlotMap.SlotComputer<S>,
        slot: Slot,
        prev: Slot,
        slotIndex: Int,
    ): S? {
        val newSlot = c.compute(key, index, slot, compoundOp, owner)
        if (compoundOp.touched) {
            return compoundOp.compute(owner, compoundOp, key, slotIndex) { _, _, _, _, _ -> newSlot }
        }

        val table = slots!!
        if (newSlot == null) {
            removeSlot(slot, prev, slotIndex)
        } else if (slot != newSlot) {
            // Swap the new slot into the bucket.
            if (prev === slot) table[slotIndex] = newSlot else prev.next = newSlot
            newSlot.next = slot.next
            // And into the definition-order list, in the same position.
            if (slot === firstAdded) {
                firstAdded = newSlot
            } else {
                var ps = firstAdded
                while (ps != null && ps.orderedNext !== slot) ps = ps.orderedNext
                ps?.orderedNext = newSlot
            }
            newSlot.orderedNext = slot.orderedNext
            if (slot === lastAdded) lastAdded = newSlot
        }
        return newSlot
    }

    override fun add(owner: SlotMapOwner?, newSlot: Slot) {
        if (slots == null) slots = arrayOfNulls(INITIAL_SLOT_SIZE)
        createNewSlot(owner, newSlot)
    }

    private fun insertNewSlot(table: Array<Slot?>, newSlot: Slot) {
        ++count
        lastAdded?.orderedNext = newSlot
        if (firstAdded == null) firstAdded = newSlot
        lastAdded = newSlot
        if (newSlot.name == null) hasIndex = true
        addKnownAbsentSlot(table, newSlot)
    }

    private fun removeSlot(slot: Slot, prevInBucket: Slot, ix: Int) {
        count--
        val table = slots!!
        if (prevInBucket === slot) table[ix] = slot.next else prevInBucket.next = slot.next

        // Also drop it from the definition-order list. This used to happen lazily in getIds, but
        // deleting is rare enough that walking the list is fine.
        var prev: Slot?
        if (slot === firstAdded) {
            prev = null
            firstAdded = slot.orderedNext
        } else {
            prev = firstAdded
            while (prev!!.orderedNext !== slot) prev = prev.orderedNext
            prev.orderedNext = slot.orderedNext
        }
        if (slot === lastAdded) lastAdded = prev
    }

    public companion object {
        /** The starting table size. Has to be a power of two. */
        private const val INITIAL_SLOT_SIZE = 4

        private fun copyTable(oldSlots: Array<Slot?>, newSlots: Array<Slot?>) {
            for (bucket in oldSlots) {
                var slot = bucket
                while (slot != null) {
                    val nextSlot = slot.next
                    addKnownAbsentSlot(newSlots, slot)
                    slot = nextSlot
                }
            }
        }

        /**
         * Adds a slot whose key is known not to be in the table. Used when filling an empty table,
         * after growing one, and while copying.
         */
        private fun addKnownAbsentSlot(addSlots: Array<Slot?>, slot: Slot) {
            val insertPos = getSlotIndex(addSlots.size, slot.indexOrHash)
            slot.next = addSlots[insertPos]
            addSlots[insertPos] = slot
        }

        /** Cheap modulo, valid only because the table size is a power of two. */
        private fun getSlotIndex(tableSize: Int, indexOrHash: Int): Int =
            indexOrHash and (tableSize - 1)
    }
}
