/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.Context
import io.github.yuroyami.kitejs.RhinoException
import io.github.yuroyami.kitejs.Scriptable
import io.github.yuroyami.kitejs.ScriptableObject

/*
 * A handle belongs to the engine that made it, as a GraalJS `Value` belongs to its context: it
 * works only while that engine is open and only on that engine's thread, and it cannot be handed
 * to another engine. A handle finds its engine through the global scope its object lives in,
 * where the engine parks itself when it opens. Scalars are copies and work anywhere.
 */

/** The key an engine parks itself under on its global scope. */
internal object EngineKey

/** The engine whose global scope [obj] lives in, or null for an object no engine made. */
internal fun ownerOf(obj: Scriptable): KiteJs? =
    (ScriptableObject.getTopLevelScope(obj) as? ScriptableObject)?.getAssociatedValue(EngineKey) as? KiteJs

/**
 * The context to work on [obj] in. Throws [JsEngineError] unless the engine that made it is open
 * and this is its thread, which happens before anything else can, a getter included.
 */
internal fun contextFor(obj: Scriptable): Context = ownerOf(obj)?.usableContext() ?: liveContext()

/** Whether [obj] can be touched here, for the readers that must not throw, such as `toString`. */
internal fun usableHere(obj: Scriptable): Boolean =
    ownerOf(obj)?.isUsableHere ?: (Context.getCurrentContext() != null)

/**
 * What a handle prints as where it cannot be touched: its class, without running any script. A
 * revoked proxy has no class to give, and prints as a plain object.
 */
internal fun inertText(obj: Scriptable): String {
    val className = try {
        obj.className
    } catch (e: RhinoException) {
        "Object"
    }
    return "[object $className]"
}

/**
 * Refuses to hand [value] to the engine [scope] belongs to when another engine made it. A value
 * from an engine runs against that engine's global scope, so it cannot move between engines.
 */
internal fun adopt(value: Any?, scope: Scriptable): Any? {
    if (value !is Scriptable) return value
    val owner = ownerOf(value) ?: return value
    val receiver = ownerOf(scope)
    if (receiver != null && receiver !== owner) {
        throw JsEngineError("this value belongs to another engine, and values cannot move between engines")
    }
    owner.usableContext()
    return value
}
