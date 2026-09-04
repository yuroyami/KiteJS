/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * Holds off building a built-in class until something asks for it, which cuts startup time and the
 * memory a scope costs when most of the globals go untouched.
 *
 * Upstream also has a constructor that finds an `init` method by reflection. There is no reflection
 * here, so only the lambda form exists.
 */
class LazilyLoadedCtor(
    scope: ScriptableObject,
    private val propertyName: String,
    private val sealed: Boolean,
    private val initializer: Initializable,
) {

    private val scope: Scriptable = scope
    private var initializedValue: Any? = null
    private var state: Int = STATE_BEFORE_INIT

    init {
        scope.addLazilyInitializedValue(propertyName, 0, this, ScriptableObject.DONTENUM)
    }

    internal fun init() {
        check(state != STATE_INITIALIZING) { "Recursive initialization for $propertyName" }
        if (state != STATE_BEFORE_INIT) return

        state = STATE_INITIALIZING
        // Set now so the finally block has something to store if building throws.
        var value: Any? = Scriptable.NOT_FOUND
        try {
            value = buildValue()
        } finally {
            initializedValue = value
            state = STATE_WITH_VALUE
        }
    }

    internal fun getValue(): Any? {
        check(state == STATE_WITH_VALUE) { propertyName }
        return initializedValue
    }

    private fun buildValue(): Any? {
        val cx = Context.getCurrentContext()!!
        // A null result means the initializer put the property into the scope itself, which some
        // of the older initializers do when they register several objects at once.
        return initializer.initialize(cx, scope, sealed) ?: scope.get(propertyName, scope)
    }

    private companion object {
        const val STATE_BEFORE_INIT = 0
        const val STATE_INITIALIZING = 1
        const val STATE_WITH_VALUE = 2
    }
}
