/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

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

    /** Direct eval preserves a strict caller's null this binding. */
    internal fun execEval(cx: Context, scope: Scriptable, thisObj: Scriptable?): Any? {
        if (thisObj != null) return exec(cx, scope, thisObj)
        if (ScriptRuntime.hasTopCall(cx)) return code.execute(cx, this, null, scope, null, ScriptRuntime.emptyArgs)
        val result = ScriptRuntime.doTopCall(SerializableCallable { callCx, callScope, _, _ ->
            code.execute(callCx, this, null, callScope, null, ScriptRuntime.emptyArgs)
        }, cx, scope, null, ScriptRuntime.emptyArgs, descriptor.isStrict)
        cx.processMicrotasks()
        return result
    }
}
