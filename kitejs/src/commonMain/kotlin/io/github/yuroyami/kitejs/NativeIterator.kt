/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The legacy iterator protocol. Phase 4 ports the `Iterator` and `StopIteration` builtins; this is
 * the part the runtime and the generator machinery need first.
 */
object NativeIterator {
    const val ITERATOR_PROPERTY_NAME = "__iterator__"
    internal val ITERATOR_TAG: Any = "Iterator"

    /** The `StopIteration` value cached on the top scope, or null before the builtin is installed. */
    fun getStopIterationObject(scope: Scriptable): Any? =
        ScriptableObject.getTopScopeValue(ScriptableObject.getTopLevelScope(scope), ITERATOR_TAG)

    /** What a legacy generator throws when it is done. */
    open class StopIteration(val value: Any? = Undefined.instance) : NativeObject() {
        override val className: String
            get() = "StopIteration"
    }
}
