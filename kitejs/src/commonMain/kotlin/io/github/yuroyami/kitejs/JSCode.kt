/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** Runs compiled code. */
public fun interface JSCodeExec<T : ScriptOrFn<T>> {
    public fun execute(cx: Context, executableObject: T, newTarget: Any?, scope: Scriptable, thisObj: Any?, args: Array<Any?>): Any?
}

/** Resumes a suspended generator. */
public fun interface JSCodeResume<T : ScriptOrFn<T>> {
    public fun resume(cx: Context, executableObject: T, state: Any?, scope: Scriptable, operation: Int, value: Any?): Any?

    public companion object {
        /** What a function that is not a generator carries for its resume path. */
        public fun <T : ScriptOrFn<T>> nullResumable(): JSCodeResume<T> = JSCodeResume { _, _, _, _, _, _ ->
            throw Kit.codeBug("Attempt to resume a non-generator function")
        }
    }
}

/** The compiled form of a script or function. The interpreter's version is `InterpreterData`. */
public abstract class JSCode<T : ScriptOrFn<T>> : JSCodeExec<T>, JSCodeResume<T> {

    /** Builds the code object once the descriptor around it is finished. */
    public abstract class Builder<U : ScriptOrFn<U>> {
        public abstract fun build(): JSCode<U>?
    }

    /** Builds nothing, for a function that cannot be constructed. */
    public class NullBuilder<V : ScriptOrFn<V>> : Builder<V>() {
        override fun build(): JSCode<V>? = null
    }
}
