/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

/** What `Function.prototype.bind` returns. ECMAScript 5 section 15.3.4.5. */
public class BoundFunction(
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
        if (cx.languageVersion >= Context.VERSION_ES6) {
            if (targetFunction is Scriptable) prototype = targetFunction.prototype
        } else {
            val thrower = ScriptRuntime.typeErrorThrower(scope)
            val throwing = DescriptorInfo(false, Scriptable.NOT_FOUND, false, thrower, thrower, Scriptable.NOT_FOUND)
            defineOwnProperty(cx, "caller", throwing, false)
            defineOwnProperty(cx, "arguments", throwing, false)
        }
    }

    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
        targetFunction.call(cx, scope, getCallThis(cx, scope), concat(boundArgs, args))

    override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
        if (targetFunction is Constructable) {
            return targetFunction.construct(cx, scope, concat(boundArgs, args))
        }
        throw ScriptRuntime.typeErrorById("msg.not.ctor")
    }

    /** BoundFunctionCreate gives the bound function a [[Construct]] only when its target has one. */
    override val isConstructor: Boolean get() = AbstractEcmaObjectOperations.isConstructor(targetFunction)

    /** A bound function's [[Construct]]: a newTarget that is the bound function itself becomes the target. */
    override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>, newTarget: Scriptable): Scriptable {
        if (!isConstructor) throw ScriptRuntime.typeErrorById("msg.not.ctor", functionName)
        val target = targetFunction as Constructable
        return AbstractEcmaObjectOperations.construct(
            cx, scope, target, concat(boundArgs, args), if (newTarget === this) target as Scriptable else newTarget,
        )
    }

    override val functionName: String
        get() = if (targetFunction is BaseFunction) "bound " + targetFunction.functionName else ""

    internal fun getCallThis(cx: Context, scope: Scriptable): Scriptable? =
        ScriptRuntime.getApplyOrCallThis(
            cx, scope, if (Undefined.isUndefined(boundThis)) Undefined.instance else boundThis, 1, targetFunction,
        )

    private companion object {
        fun concat(first: Array<Any?>, second: Array<Any?>): Array<Any?> {
            val args = arrayOfNulls<Any?>(first.size + second.size)
            first.copyInto(args, 0)
            second.copyInto(args, first.size)
            return args
        }
    }
}
