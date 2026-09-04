/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * A [Slot] that builds its value the first time it is read. The built-in objects use it so they are
 * not all constructed up front.
 */
class LazyLoadSlot : Slot {

    internal constructor(name: Any?, index: Int) : super(name, index, 0)

    internal constructor(oldSlot: Slot) : super(oldSlot)

    override fun copySlot(): LazyLoadSlot = LazyLoadSlot(this).also {
        it.value = value
        it.next = null
        it.orderedNext = null
    }

    override fun getValue(start: Scriptable?): Any? {
        var v = this.value
        if (v is LazilyLoadedCtor) {
            try {
                v.init()
            } finally {
                v = v.getValue()
                this.value = v
            }
        }
        return v
    }
}
