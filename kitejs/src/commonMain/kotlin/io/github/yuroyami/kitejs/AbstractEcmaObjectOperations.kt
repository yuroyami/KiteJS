/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The spec's abstract operations on objects. Phase 3.8 fills this out; the runtime needs
 * [setIntegrityLevel] first, for the frozen template literal call sites.
 */
internal object AbstractEcmaObjectOperations {

    enum class INTEGRITY_LEVEL { FROZEN, SEALED }

    /** SetIntegrityLevel: seals or freezes [o]. Returns false when it cannot be made non-extensible. */
    fun setIntegrityLevel(cx: Context, o: Any?, level: INTEGRITY_LEVEL): Boolean {
        val obj = ScriptableObject.ensureScriptableObject(o)
        if (!obj.preventExtensions()) return false
        val ids = obj.startCompoundOp(false).use { obj.getIds(it, true, true) }
        for (key in ids) {
            val desc = obj.getOwnPropertyDescriptor(cx, key)!!
            if (level == INTEGRITY_LEVEL.SEALED) {
                if (desc.isConfigurable()) {
                    desc.configurable = false
                    obj.defineOwnProperty(cx, key, desc, false)
                }
            } else {
                if (desc.isDataDescriptor() && desc.isWritable()) desc.writable = false
                if (desc.isConfigurable()) desc.configurable = false
                obj.defineOwnProperty(cx, key, desc, false)
            }
        }
        return true
    }
}
