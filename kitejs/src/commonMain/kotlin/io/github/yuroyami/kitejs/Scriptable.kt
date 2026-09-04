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
interface Scriptable {

    /** The class name, as `Object.prototype.toString` reports it. */
    val className: String

    /** Gets a named property, or [NOT_FOUND] when there is none. */
    fun get(name: String, start: Scriptable): Any?

    /** Gets an indexed property, or [NOT_FOUND] when there is none. */
    fun get(index: Int, start: Scriptable): Any?

    fun has(name: String, start: Scriptable): Boolean

    fun has(index: Int, start: Scriptable): Boolean

    fun put(name: String, start: Scriptable, value: Any?)

    fun put(index: Int, start: Scriptable, value: Any?)

    fun delete(name: String)

    fun delete(index: Int)

    var prototype: Scriptable?

    var parentScope: Scriptable?

    /** Every property name of this object alone, ignoring the prototype chain. */
    fun getIds(): Array<Any?>

    /** Converts this object to a primitive, guided by [hint]. */
    fun getDefaultValue(hint: KClass<*>?): Any?

    /** Backs the `instanceof` operator. */
    fun hasInstance(instance: Scriptable): Boolean

    companion object {
        /** Returned by [get] and friends when the property is absent. */
        val NOT_FOUND: Any = UniqueTag.NOT_FOUND
    }
}
