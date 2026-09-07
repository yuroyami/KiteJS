/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** A compiled script. */
public class JSScript(
    override val descriptor: JSDescriptor<JSScript>,
    override val homeObject: Scriptable?,
) : Script, ScriptOrFn<JSScript> {

    internal val code: JSCode<JSScript>
        get() = descriptor.code!!

    override fun exec(cx: Context, scope: Scriptable, thisObj: Scriptable): Any? {
        return if (!ScriptRuntime.hasTopCall(cx)) {
            val ret = ScriptRuntime.doTopCall(this, cx, scope, thisObj, descriptor.isStrict)
            cx.processMicrotasks()
            ret
        } else {
            descriptor.code!!.execute(cx, this, null, scope, thisObj, ScriptRuntime.emptyArgs)
        }
    }
}
