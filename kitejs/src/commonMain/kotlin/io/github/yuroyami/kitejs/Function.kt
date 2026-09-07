/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** A JavaScript function: an object that can also be called and constructed. */
public interface Function : Scriptable, Callable, Constructable {

    override fun call(
        cx: Context,
        scope: Scriptable,
        thisObj: Scriptable?,
        args: Array<Any?>,
    ): Any?

    override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable

    /** The scope this function was declared in. */
    public val declarationScope: Scriptable?
        get() = this.parentScope
}
