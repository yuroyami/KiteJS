/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * A sequence of operations run against one map. It does as little as possible: no locking, just a
 * note of whether the map was changed, so the map underneath knows when to hand work off.
 *
 * Upstream also has a locking variant for thread-safe objects. This port has none (D-3).
 */
open class CompoundOperationMap(protected val owner: SlotMapOwner) : SlotMap, AutoCloseable {

    protected var map: SlotMap = owner.map

    internal var touched: Boolean = false

    /** Picks up the owner's current map if this operation already changed it. */
    protected fun updateMap(resetTouched: Boolean) {
        if (touched) {
            map = owner.map
            if (resetTouched) touched = false
        }
    }

    val isTouched: Boolean get() = touched

    override fun add(owner: SlotMapOwner?, newSlot: Slot) {
        map.add(owner, newSlot)
        touched = true
    }

    override fun <S : Slot> compute(
        owner: SlotMapOwner,
        key: Any?,
        index: Int,
        compute: SlotMap.SlotComputer<S>,
    ): S? {
        updateMap(true)
        val res = map.compute(owner, this, key, index, compute)
        touched = true
        return res
    }

    override fun <S : Slot> compute(
        owner: SlotMapOwner?,
        mutableMap: CompoundOperationMap,
        key: Any?,
        index: Int,
        compute: SlotMap.SlotComputer<S>,
    ): S? {
        check(mutableMap === this)
        updateMap(true)
        val res = map.compute(owner, this, key, index, compute)
        touched = true
        return res
    }

    override fun dirtySize(): Int {
        updateMap(false)
        return map.dirtySize()
    }

    override fun isEmpty(): Boolean {
        updateMap(false)
        return map.isEmpty()
    }

    override fun modify(owner: SlotMapOwner?, key: Any?, index: Int, attributes: Int): Slot {
        updateMap(true)
        val res = map.modify(owner, key, index, attributes)
        touched = true
        return res
    }

    override fun query(key: Any?, index: Int): Slot? {
        updateMap(false)
        return map.query(key, index)
    }

    override fun size(): Int {
        updateMap(false)
        return map.size()
    }

    override fun iterator(): Iterator<Slot> {
        updateMap(false)
        return map.iterator()
    }

    /** Nothing to undo without locks. */
    override fun close() {}
}
