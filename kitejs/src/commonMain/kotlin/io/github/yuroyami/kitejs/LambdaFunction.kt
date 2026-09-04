/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * One JavaScript function, implemented by a single Kotlin lambda. It has the built-in `Function`
 * prototype and no parent scope; binding it into a scope is the caller's job.
 */
open class LambdaFunction : BaseFunction {

    protected val target: SerializableCallable?
    private val name: String
    private val length: Int

    /** [defaultPrototype] gives the new function a `prototype` property of its own. */
    constructor(
        scope: Scriptable,
        name: String,
        length: Int,
        target: SerializableCallable?,
        defaultPrototype: Boolean = true,
    ) {
        this.target = target
        this.name = name
        this.length = length
        ScriptRuntime.setFunctionProtoAndParent(this, Context.getCurrentContext(), scope)
        if (defaultPrototype) setupDefaultPrototype(scope)
    }

    constructor(
        scope: Scriptable,
        name: String,
        length: Int,
        prototype: Any?,
        target: SerializableCallable?,
    ) {
        this.target = target
        this.name = name
        this.length = length
        ScriptRuntime.setFunctionProtoAndParent(this, Context.getCurrentContext(), scope)
        setPrototypeProperty(prototype)
    }

    /** A built-in function: no name, no prototype of its own. */
    constructor(scope: Scriptable, length: Int, target: SerializableCallable) {
        this.target = target
        this.length = length
        this.name = ""
        ScriptRuntime.setFunctionProtoAndParent(this, Context.getCurrentContext(), scope)
    }

    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
        target!!.call(cx, declarationScope!!, thisObj, args)

    override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable =
        throw ScriptRuntime.typeErrorById("msg.no.new", getFunctionName())

    override fun getLength(): Int = length

    override fun getArity(): Int = length

    override fun getFunctionName(): String = name

    internal fun getTarget(): Callable? = target
}
