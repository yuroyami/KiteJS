/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * Holds the property table for whatever owns it, and swaps that table for a bigger one as the
 * object gains properties. [ScriptableObject] is the only thing that extends it.
 *
 * The table starts as a shared empty map, becomes a single-entry map on the first property, then an
 * [EmbeddedSlotMap], and finally a [HashSlotMap] once there are enough properties for hash
 * collisions to matter.
 */
abstract class SlotMapOwner {

    /**
     * The table. It changes shape as the object grows.
     *
     * KMP: upstream reads and writes this through a `VarHandle` when thread-safe objects are on.
     * This port is single-thread confined (D-3), so it is a plain field.
     */
    internal var map: SlotMap

    protected constructor() {
        map = createSlotMap(0)
    }

    protected constructor(capacity: Int) {
        map = createSlotMap(capacity)
    }

    protected constructor(map: SlotMap) {
        this.map = map
    }

    /**
     * Opens a run of operations over the table. Close it when done, or hand it to `use`:
     *
     * ```
     * obj.startCompoundOp(true).use { op ->
     *     val slot = op.compute(obj, "myKey", 0, ::complexOperation)
     * }
     * ```
     *
     * [forWriting] tells a locking implementation which lock to take. There is no locking here, so
     * it is carried for parity and otherwise ignored.
     */
    internal fun startCompoundOp(forWriting: Boolean): CompoundOperationMap =
        map.startCompoundOp(this, forWriting)

    /** The table an object with no properties yet shares with every other such object. */
    private class EmptySlotMap : SlotMap {

        override fun iterator(): Iterator<Slot> = emptyList<Slot>().iterator()

        override fun size(): Int = 0

        override fun isEmpty(): Boolean = true

        override fun modify(owner: SlotMapOwner?, key: Any?, index: Int, attributes: Int): Slot {
            val newSlot = Slot(key, index, attributes)
            owner!!.map = SingleEntrySlotMap(newSlot)
            return newSlot
        }

        override fun query(key: Any?, index: Int): Slot? = null

        override fun add(owner: SlotMapOwner?, newSlot: Slot) {
            owner!!.map = SingleEntrySlotMap(newSlot)
        }

        override fun <S : Slot> compute(
            owner: SlotMapOwner?,
            mutableMap: CompoundOperationMap,
            key: Any?,
            index: Int,
            compute: SlotMap.SlotComputer<S>,
        ): S? {
            val newSlot = compute.compute(key, index, null, mutableMap, owner)
            if (newSlot != null) {
                if (!mutableMap.isTouched) {
                    owner!!.map = SingleEntrySlotMap(newSlot)
                } else {
                    // The map already moved on, so hand the add over instead of computing again,
                    // which would recurse.
                    mutableMap.add(owner, newSlot)
                }
            }
            return newSlot
        }
    }

    private class Iter(private var next: Slot?) : Iterator<Slot> {
        override fun hasNext(): Boolean = next != null

        override fun next(): Slot {
            val ret = next ?: throw NoSuchElementException()
            next = ret.orderedNext
            return ret
        }
    }

    /** The table for an object with exactly one property. */
    internal open class SingleEntrySlotMap(protected val slot: Slot) : SlotMap {

        override fun iterator(): Iterator<Slot> = Iter(slot)

        override fun size(): Int = 1

        override fun isEmpty(): Boolean = false

        override fun modify(owner: SlotMapOwner?, key: Any?, index: Int, attributes: Int): Slot {
            if (matches(key, index)) return slot
            val newSlot = Slot(key, index, attributes)
            add(owner, newSlot)
            return newSlot
        }

        override fun query(key: Any?, index: Int): Slot? = if (matches(key, index)) slot else null

        override fun add(owner: SlotMapOwner?, newSlot: Slot) {
            checkNotNull(owner)
            val newMap = EmbeddedSlotMap()
            owner.map = newMap
            newMap.add(owner, slot)
            newMap.add(owner, newSlot)
        }

        override fun <S : Slot> compute(
            owner: SlotMapOwner?,
            mutableMap: CompoundOperationMap,
            key: Any?,
            index: Int,
            compute: SlotMap.SlotComputer<S>,
        ): S? {
            val newMap = EmbeddedSlotMap()
            owner!!.map = newMap
            newMap.add(owner, slot)
            return newMap.compute(owner, mutableMap, key, index, compute)
        }

        private fun matches(key: Any?, index: Int): Boolean {
            val indexOrHash = key?.hashCode() ?: index
            return indexOrHash == slot.indexOrHash && slot.name == key
        }
    }

    companion object {
        /**
         * How big an [EmbeddedSlotMap] gets before it becomes a [HashSlotMap]. It has to be three
         * quarters of a power of two: the embedded map's table is a power of two and grows when it
         * is three quarters full.
         */
        internal const val LARGE_HASH_SIZE = (1 shl 10) + (1 shl 9)

        internal val EMPTY_SLOT_MAP: SlotMap = EmptySlotMap()

        internal fun createSlotMap(initialSize: Int): SlotMap = when {
            initialSize == 0 -> EMPTY_SLOT_MAP
            initialSize > LARGE_HASH_SIZE -> HashSlotMap()
            else -> EmbeddedSlotMap()
        }
    }
}
