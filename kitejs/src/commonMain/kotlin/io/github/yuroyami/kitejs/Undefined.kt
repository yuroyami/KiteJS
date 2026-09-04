/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.reflect.KClass

/**
 * The JavaScript `undefined` value.
 *
 * There are two representations of it, so a direct identity test is not enough. Use [isUndefined].
 */
class Undefined private constructor() {

    override fun equals(other: Any?): Boolean = isUndefined(other) || other === this

    // Every instance of Undefined is equivalent.
    override fun hashCode(): Int = INSTANCE_HASH

    companion object {
        val instance: Any = Undefined()

        private val INSTANCE_HASH = 0x756E_6465 // "unde", a stable stand-in for an identity hash

        /** The Scriptable-shaped `undefined`, used where an object is required. */
        val SCRIPTABLE_UNDEFINED: Scriptable = object : Scriptable {

            override val className: String
                get() = "undefined"

            override fun get(name: String, start: Scriptable): Any? = Scriptable.NOT_FOUND

            override fun get(index: Int, start: Scriptable): Any? = Scriptable.NOT_FOUND

            override fun has(name: String, start: Scriptable): Boolean = false

            override fun has(index: Int, start: Scriptable): Boolean = false

            override fun put(name: String, start: Scriptable, value: Any?) {}

            override fun put(index: Int, start: Scriptable, value: Any?) {}

            override fun delete(name: String) {}

            override fun delete(index: Int) {}

            override var prototype: Scriptable?
                get() = null
                set(value) {}

            override var parentScope: Scriptable?
                get() = null
                set(value) {}

            override fun getIds(): Array<Any?> = ScriptRuntime.emptyArgs

            override fun getDefaultValue(hint: KClass<*>?): Any? {
                if (hint == null || hint == ScriptRuntime.StringClass) {
                    return toString()
                }
                return null
            }

            override fun hasInstance(instance: Scriptable): Boolean = false

            override fun toString(): String = "undefined"

            override fun equals(other: Any?): Boolean = isUndefined(other) || other === this

            override fun hashCode(): Int = INSTANCE_HASH
        }

        /**
         * The safe way to test for undefined. A direct identity test is wrong, because the engine
         * has two representations of the value.
         */
        fun isUndefined(obj: Any?): Boolean =
            instance === obj || SCRIPTABLE_UNDEFINED === obj
    }
}
