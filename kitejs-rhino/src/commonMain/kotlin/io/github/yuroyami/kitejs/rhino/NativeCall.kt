/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

/**
 * The activation record of one function call: its parameters, its variables and its `arguments`
 * object, kept as properties so closures and `eval` can reach them.
 */
public class NativeCall : IdScriptableObject {

    internal val function: JSFunction?
    internal val originalArgs: Array<Any?>
    internal val isStrict: Boolean
    internal var parentActivationCall: NativeCall? = null
    /** Parameters still in their temporal dead zone; null for the common call that has none, so a name read skips the lookup. */
    private var uninitializedParameters: MutableSet<String>? = null
    private var rawParameterValues: Array<Any?> = ScriptRuntime.emptyArgs
    internal var isParameterEnvironment: Boolean = false
        private set
    internal var isFormalParameterEnvironment: Boolean = false
        private set
    internal var parameterVariableScope: NativeCall? = null
        private set

    /** The new.target of the call, for direct eval code to see; null outside every function. */
    internal var newTarget: Any? = null

    /** The `this` binding of the derived class constructor the call runs in, for direct eval code. */
    internal var thisBinding: ThisBinding? = null

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
        val desc = function.descriptor
        if (desc.hasParameterInitialization) {
            isParameterEnvironment = true
            isFormalParameterEnvironment = true
            if (!isStrict && desc.hasParameterExpressions) {
                val variables = NativeCall(this)
                variables.parentScope = scope
                variables.isParameterEnvironment = true
                parameterVariableScope = variables
                parentScope = variables
            }
            val names = desc.parameterBindingNames + desc.parameterSlotNames
            for (name in names) defineProperty(name, Undefined.instance, PERMANENT)
            for (name in desc.parameterLocalNames) defineProperty(name, Undefined.instance, PERMANENT)
            if (names.isNotEmpty()) uninitializedParameters = HashSet(names)
            rawParameterValues = Array(desc.parameterSlotNames.size) { i ->
                if (argsHasRest && i == desc.parameterSlotNames.lastIndex) {
                    cx.newArray(scope, if (i < a.size) a.copyOfRange(i, a.size) else ScriptRuntime.emptyArgs)
                } else if (i < a.size) a[i] else Undefined.instance
            }
            if (!isArrow && !has("arguments", this)) {
                val holder = parameterVariableScope ?: this
                holder.defineProperty("arguments", Arguments(this, cx), PERMANENT)
            }
            return
        }

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

    private constructor(parameters: NativeCall) : super() {
        function = parameters.function
        originalArgs = parameters.originalArgs
        isStrict = parameters.isStrict
        parentScope = parameters
        newTarget = parameters.newTarget
        thisBinding = parameters.thisBinding
    }

    internal fun parameterValue(index: Int): Any? = rawParameterValues[index]

    internal fun initializeParameter(name: String, value: Any?) {
        uninitializedParameters?.let { if (it.remove(name) && it.isEmpty()) uninitializedParameters = null }
        super.put(name, this, value)
    }

    internal fun enterBody(): NativeCall {
        val desc = function!!.descriptor
        val body = if (desc.hasParameterExpressions) NativeCall(this) else this
        body.isParameterEnvironment = false
        body.isFormalParameterEnvironment = false
        val bodyVars = desc.bodyVarNames.toMutableSet()
        for (i in 0 until desc.functionCount) {
            val fn = desc.getFunction(i)
            if (fn.functionType == io.github.yuroyami.kitejs.rhino.ast.FunctionNode.FUNCTION_STATEMENT) bodyVars.add(fn.functionName)
        }
        for (name in bodyVars) {
            if (!body.has(name, body)) {
                val value = if (name in desc.parameterBindingNames || name == "arguments") {
                    parameterBindingValue(name)
                } else Undefined.instance
                body.defineProperty(name, value, PERMANENT)
            }
        }
        for (i in desc.paramCount until desc.paramAndVarCount) {
            val name = desc.getParamOrVarName(i)
            if (name in desc.parameterBindingNames || name in desc.parameterSlotNames || body.has(name, body)) continue
            if (desc.getParamOrVarConst(i)) body.defineProperty(name, Undefined.instance, CONST)
            else body.defineProperty(name, Undefined.instance, PERMANENT)
        }
        return body
    }

    internal fun parameterBindingValue(name: String): Any? {
        var holder: Scriptable? = this
        while (holder != null) {
            if (holder.has(name, holder)) return holder.get(name, holder)
            holder = holder.parentScope
        }
        return Undefined.instance
    }

    override fun get(name: String, start: Scriptable): Any? {
        if (uninitializedParameters?.contains(name) == true) throw ScriptRuntime.constructError("ReferenceError", "Cannot access '$name' before initialization")
        return super.get(name, start)
    }

    override fun put(name: String, start: Scriptable, value: Any?) {
        if (uninitializedParameters?.contains(name) == true) throw ScriptRuntime.constructError("ReferenceError", "Cannot access '$name' before initialization")
        super.put(name, start, value)
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
            if (!f.isConstructingCall) throw Context.reportRuntimeErrorById("msg.only.from.new", "Call")
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
