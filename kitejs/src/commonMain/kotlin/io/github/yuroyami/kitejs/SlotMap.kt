/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The property table behind a [ScriptableObject]. It is iterable but is not a `Map`, because a real
 * map brings overhead this does not need.
 *
 * The shape of this interface is odd and its dealings with [ScriptableObject] are involved. Upstream
 * says plainly that tidier versions of it cost real performance, so it stays as it is.
 */
interface SlotMap : Iterable<Slot> {

    /** Decides what a key maps to, given whatever is already there. Returning null removes it. */
    fun interface SlotComputer<S : Slot> {
        fun compute(
            key: Any?,
            index: Int,
            existing: Slot?,
            mutableMap: CompoundOperationMap,
            owner: SlotMapOwner?,
        ): S?
    }

    /** How many slots there are. */
    fun size(): Int

    /** Whether there are no slots at all. */
    fun isEmpty(): Boolean

    /**
     * The slot for [key], or for [index] when [key] is null, creating one if it is not there yet.
     *
     * [attributes] applies only to a slot this call creates. An existing slot is left alone.
     */
    fun modify(owner: SlotMapOwner?, key: Any?, index: Int, attributes: Int): Slot

    /** The slot for [key], or for [index] when [key] is null, or null if there is none. */
    fun query(key: Any?, index: Int): Slot?

    /**
     * Replaces what [key] maps to with whatever [compute] returns: null removes the mapping, and
     * anything else replaces or creates it. If [compute] throws, nothing changes.
     *
     * This mirrors `Map.compute`, which keeps callers from making several calls in a row.
     */
    fun <S : Slot> compute(
        owner: SlotMapOwner,
        key: Any?,
        index: Int,
        compute: SlotComputer<S>,
    ): S? = owner.startCompoundOp(true).use { mutableMap ->
        mutableMap.compute(owner, mutableMap, key, index, compute)
    }

    fun <S : Slot> compute(
        owner: SlotMapOwner?,
        mutableMap: CompoundOperationMap,
        key: Any?,
        index: Int,
        compute: SlotComputer<S>,
    ): S?

    /**
     * Puts a slot straight in. Both its name and its index hash have to be set already.
     * [ScriptableObject] normally goes through [modify] instead.
     */
    fun add(owner: SlotMapOwner?, newSlot: Slot)

    /** The size before any pending compound operation is folded back in. */
    fun dirtySize(): Int = size()

    /** Opens a compound operation over this map. */
    fun startCompoundOp(owner: SlotMapOwner, forWriting: Boolean): CompoundOperationMap =
        CompoundOperationMap(owner)
}
