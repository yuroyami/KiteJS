/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The constructors the spec names as intrinsics, such as `%ArrayBuffer%` and `%Uint8Array%`, kept
 * on the top-level scope as each one is set up. A built-in that makes a new object of one of these
 * types asks here rather than reading the global, so a script that replaces or deletes the global
 * binding does not change what the built-in makes (D-83).
 *
 * Upstream finds them through `ScriptRuntime.getExistingCtor`, which is an ordinary property read
 * on the global object.
 */
internal object Intrinsics {

    private data class Key(val name: String)

    /** Records [constructor] as the intrinsic called [name] for the realm [scope] belongs to. */
    fun register(scope: Scriptable, name: String, constructor: Function) {
        (ScriptableObject.getTopLevelScope(scope) as? ScriptableObject)?.associateValue(Key(name), constructor)
    }

    /**
     * The intrinsic called [name] for the realm [scope] belongs to. A constructor that is loaded
     * lazily is set up by reading its global the first time, which registers it. Only when a script
     * replaced that global before anything loaded it is there no intrinsic to find, and the global
     * is used as upstream does.
     */
    fun constructor(cx: Context, scope: Scriptable, name: String): Function {
        val top = ScriptableObject.getTopLevelScope(scope)
        registered(top, name)?.let { return it }
        ScriptableObject.getProperty(top, name)
        return registered(top, name) ?: ScriptRuntime.getExistingCtor(cx, top, name)
    }

    private fun registered(top: Scriptable, name: String): Function? =
        (top as? ScriptableObject)?.getAssociatedValue(Key(name)) as Function?
}
