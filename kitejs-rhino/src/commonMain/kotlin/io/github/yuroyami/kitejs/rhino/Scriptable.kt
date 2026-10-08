/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

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

    /**
     * [[OwnPropertyKeys]], including non-enumerable names and symbols. Keys use the engine's
     * String, Int-index or Symbol representation. The legacy default lists [getIds].
     */
    public fun ownPropertyKeys(): Array<Any?> = getIds()

    /**
     * [[GetOwnProperty]]. The legacy default describes a mutable, enumerable, configurable
     * data property. Hosts with accessors or other attributes override this operation.
     */
    public fun getOwnPropertyDescriptor(cx: Context, id: Any?): ScriptableObject.DescriptorInfo? =
        AbstractEcmaObjectOperations.defaultGetOwnPropertyDescriptor(this, id)

    /**
     * [[DefineOwnProperty]], returning false when the host cannot make the requested definition.
     * The legacy default supports its data-property attributes and refuses accessors or flags
     * that [put] cannot represent. It does not add storage alongside the host's own properties.
     */
    public fun defineOwnPropertyOrFalse(cx: Context, id: Any?, desc: ScriptableObject.DescriptorInfo): Boolean =
        AbstractEcmaObjectOperations.defaultDefineOwnProperty(cx, this, id, desc)

    /** [[IsExtensible]]. Legacy hosts remain extensible until they supply a different contract. */
    public val isExtensible: Boolean get() = true

    /** [[PreventExtensions]]. A legacy host cannot enforce this through [put], so it refuses. */
    public fun preventExtensions(): Boolean = false

    /** [[SetPrototypeOf]], with extensibility and ordinary prototype-cycle checks. */
    public fun setPrototypeOf(cx: Context, proto: Scriptable?): Boolean =
        AbstractEcmaObjectOperations.defaultSetPrototypeOf(this, proto)

    /** Converts this object to a primitive, guided by [hint]. */
    public fun getDefaultValue(hint: KClass<*>?): Any?

    /** Backs the `instanceof` operator. */
    public fun hasInstance(instance: Scriptable): Boolean

    public companion object {
        /** Returned by [get] and friends when the property is absent. */
        public val NOT_FOUND: Any = UniqueTag.NOT_FOUND
    }
}
