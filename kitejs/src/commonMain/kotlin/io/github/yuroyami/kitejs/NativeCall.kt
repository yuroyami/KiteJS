/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The activation record of one function call: its parameters, its variables and its `arguments`
 * object, kept as properties so closures and `eval` can reach them.
 */
public class NativeCall : IdScriptableObject {

    internal val function: JSFunction?
    internal val originalArgs: Array<Any?>
    internal val isStrict: Boolean
    internal var parentActivationCall: NativeCall? = null

    internal constructor() : super() {
        function = null
        originalArgs = ScriptRuntime.emptyArgs
        isStrict = false
    }

    internal constructor(
        function: JSFunction,
        cx: Context,
        scope: Scriptable,
        args: Array<Any?>?,
        isArrow: Boolean,
        isStrict: Boolean,
        argsHasRest: Boolean,
        requiresArgumentObject: Boolean,
    ) : super() {
        this.function = function
        parentScope = scope
        this.originalArgs = args ?: ScriptRuntime.emptyArgs
        this.isStrict = isStrict
        val a = this.originalArgs

        val paramAndVarCount = function.paramAndVarCount
        val paramCount = function.paramCount
        if (paramAndVarCount != 0) {
            if (argsHasRest) {
                val vals: Array<Any?> =
                    if (a.size >= paramCount) a.copyOfRange(paramCount, a.size) else ScriptRuntime.emptyArgs
                for (i in 0 until paramCount) {
                    val name = function.getParamOrVarName(i)
                    val v = if (i < a.size) a[i] else Undefined.instance
                    defineProperty(name, v, PERMANENT)
                }
                defineProperty(function.getParamOrVarName(paramCount), cx.newArray(scope, vals), PERMANENT)
            } else {
                for (i in 0 until paramCount) {
                    val name = function.getParamOrVarName(i)
                    val v = if (i < a.size) a[i] else Undefined.instance
                    defineProperty(name, v, PERMANENT)
                }
            }
        }
        if (requiresArgumentObject && !isArrow && !super.has("arguments", this)) {
            defineProperty("arguments", Arguments(this, cx), PERMANENT)
        }
        if (paramAndVarCount != 0) {
            for (i in paramCount until paramAndVarCount) {
                val name = function.getParamOrVarName(i)
                if (!super.has(name, this)) {
                    if (function.getParamOrVarConst(i)) {
                        defineProperty(name, Undefined.instance, CONST)
                    } else if (function.hasFunctionNamed(name)) {
                        defineProperty(name, Undefined.instance, PERMANENT)
                    }
                }
            }
        }
    }

    override val className: String
        get() = "Call"

    override fun findPrototypeId(name: String): Int = if (name == "constructor") Id_constructor else 0

    override fun initPrototypeId(id: Int) {
        if (id != Id_constructor) throw IllegalArgumentException("$id")
        initPrototypeMethod(CALL_TAG, id, "constructor", 1)
    }

    override fun execIdCall(f: IdFunctionObject, cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        if (!f.hasTag(CALL_TAG)) return super.execIdCall(f, cx, scope, thisObj, args)
        val id = f.methodId()
        if (id == Id_constructor) {
            if (thisObj != null) throw Context.reportRuntimeErrorById("msg.only.from.new", "Call")
            ScriptRuntime.checkDeprecated(cx, "Call")
            val result = NativeCall()
            result.prototype = getObjectPrototype(scope)
            return result
        }
        throw IllegalArgumentException("$id")
    }

    public val homeObject: Scriptable? get() = function!!.homeObject

    public companion object {
        private val CALL_TAG: Any = "Call"
        private const val Id_constructor = 1
        private const val MAX_PROTOTYPE_ID = 1

        internal fun init(scope: Scriptable, sealed: Boolean) {
            NativeCall().exportAsJSClass(MAX_PROTOTYPE_ID, scope, sealed)
        }
    }
}
