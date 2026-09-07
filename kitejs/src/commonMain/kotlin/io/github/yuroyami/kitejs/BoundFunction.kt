/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** What `Function.prototype.bind` returns. ECMAScript 5 section 15.3.4.5. */
class BoundFunction(
    cx: Context,
    scope: Scriptable,
    internal val targetFunction: Callable,
    private val boundThis: Scriptable?,
    internal val boundArgs: Array<Any?>,
) : BaseFunction() {

    final override val length: Int

    init {
        length =
            if (targetFunction is BaseFunction) maxOf(0, targetFunction.length - boundArgs.size)
            else 0
        ScriptRuntime.setFunctionProtoAndParent(this, cx, scope, false)
        val thrower = ScriptRuntime.typeErrorThrower(cx)
        val throwing = DescriptorInfo(false, Scriptable.NOT_FOUND, false, thrower, thrower, Scriptable.NOT_FOUND)
        defineOwnProperty(cx, "caller", throwing, false)
        defineOwnProperty(cx, "arguments", throwing, false)
    }

    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
        targetFunction.call(cx, scope, getCallThis(cx, scope), concat(boundArgs, args))

    override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
        if (targetFunction is Constructable) {
            return targetFunction.construct(cx, scope, concat(boundArgs, args))
        }
        throw ScriptRuntime.typeErrorById("msg.not.ctor")
    }

    override fun hasInstance(instance: Scriptable): Boolean {
        if (targetFunction is Function) return targetFunction.hasInstance(instance)
        throw ScriptRuntime.typeErrorById("msg.not.ctor")
    }

    override val functionName: String
        get() = if (targetFunction is BaseFunction) "bound " + targetFunction.functionName else ""

    internal fun getCallThis(cx: Context, scope: Scriptable): Scriptable {
        var callThis = boundThis
        if (callThis == null && ScriptRuntime.hasTopCall(cx)) callThis = ScriptRuntime.getTopCallScope(cx)
        return callThis ?: getTopLevelScope(scope)
    }

    private companion object {
        fun concat(first: Array<Any?>, second: Array<Any?>): Array<Any?> {
            val args = arrayOfNulls<Any?>(first.size + second.size)
            first.copyInto(args, 0)
            second.copyInto(args, first.size)
            return args
        }
    }
}
