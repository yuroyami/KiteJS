/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * A [SlotMap] built on a linked hash map. It costs more than [EmbeddedSlotMap], mostly because each
 * slot sits inside another object, but it copes far better with a lot of hash collisions, so it
 * takes over once an object has many properties.
 */
public class HashSlotMap : SlotMap {

    private val map: LinkedHashMap<Any, Slot>

    public constructor() {
        map = LinkedHashMap()
    }

    internal constructor(capacity: Int) {
        map = LinkedHashMap(capacity)
    }

    public constructor(oldMap: SlotMap) {
        map = LinkedHashMap(oldMap.size())
        for (n in oldMap) add(null, n.copySlot())
    }

    public constructor(oldMap: SlotMap, newSlot: Slot) {
        map = LinkedHashMap(oldMap.dirtySize() + 1)
        for (n in oldMap) add(null, n.copySlot())
        add(null, newSlot)
    }

    override fun size(): Int = map.size

    override fun isEmpty(): Boolean = map.isEmpty()

    override fun query(key: Any?, index: Int): Slot? = map[makeKey(key, index)]

    override fun modify(owner: SlotMapOwner?, key: Any?, index: Int, attributes: Int): Slot {
        val name = makeKey(key, index)
        return map.getOrPut(name) { Slot(key, index, attributes) }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <S : Slot> compute(
        owner: SlotMapOwner?,
        mutableMap: CompoundOperationMap,
        key: Any?,
        index: Int,
        compute: SlotMap.SlotComputer<S>,
    ): S? {
        val name = makeKey(key, index)
        val existing = map[name]
        val result = compute.compute(key, index, existing, mutableMap, owner)
        // A re-put keeps the entry where it already was, which is what enumeration order needs.
        if (result == null) map.remove(name) else map[name] = result
        return result
    }

    override fun add(owner: SlotMapOwner?, newSlot: Slot) {
        map[makeKey(newSlot)] = newSlot
    }

    override fun iterator(): Iterator<Slot> = map.values.iterator()

    private fun makeKey(name: Any?, index: Int): Any = name ?: index.toString()

    private fun makeKey(slot: Slot): Any = slot.name ?: slot.indexOrHash.toString()
}
