/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.reflect.KClass

/**
 * The interface every JavaScript object implements.
 *
 * KMP: `getDefaultValue` takes a [KClass] rather than a `java.lang.Class`. The hint is only ever
 * compared by identity against the sentinels on [ScriptRuntime], so the mapping is exact (D-20).
 */
public interface Scriptable {

    /** The class name, as `Object.prototype.toString` reports it. */
    public val className: String

    /** Gets a named property, or [NOT_FOUND] when there is none. */
    public fun get(name: String, start: Scriptable): Any?

    /** Gets an indexed property, or [NOT_FOUND] when there is none. */
    public fun get(index: Int, start: Scriptable): Any?

    public fun has(name: String, start: Scriptable): Boolean

    public fun has(index: Int, start: Scriptable): Boolean

    public fun put(name: String, start: Scriptable, value: Any?)

    public fun put(index: Int, start: Scriptable, value: Any?)

    public fun delete(name: String)

    public fun delete(index: Int)

    public var prototype: Scriptable?

    public var parentScope: Scriptable?

    /** Every property name of this object alone, ignoring the prototype chain. */
    public fun getIds(): Array<Any?>

    /** Converts this object to a primitive, guided by [hint]. */
    public fun getDefaultValue(hint: KClass<*>?): Any?

    /** Backs the `instanceof` operator. */
    public fun hasInstance(instance: Scriptable): Boolean

    public companion object {
        /** Returned by [get] and friends when the property is absent. */
        public val NOT_FOUND: Any = UniqueTag.NOT_FOUND
    }
}
