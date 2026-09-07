/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/** The JavaScript `Error` object, and the prototype the other error kinds hang off. */
class NativeError : IdScriptableObject() {

    private var stackProvider: RhinoException? = null
    private var stack: Any? = null

    override fun fillConstructorProperties(ctor: IdFunctionObject) {
        addIdFunctionProperty(ctor, ERROR_TAG, ConstructorId_captureStackTrace, "captureStackTrace", 2)
        addIdFunctionProperty(ctor, ERROR_TAG, ConstructorId_isError, "isError", 1)
        val protoProps = ProtoProps()
        associateValue(ProtoProps.KEY, protoProps)
        ctor.defineProperty("stackTraceLimit", { protoProps.stackTraceLimit }, { protoProps.setStackTraceLimit(it) }, 0)
        ctor.defineProperty("prepareStackTrace", { protoProps.prepareStackTrace }, { protoProps.setPrepareStackTrace(it) }, 0)
        super.fillConstructorProperties(ctor)
    }

    override val className: String
        get() = "Error"

    override fun toString(): String {
        val toString = js_toString(this)
        return if (toString is String) toString else super.toString()
    }

    override fun initPrototypeId(id: Int) {
        val s: String
        val arity: Int
        when (id) {
            Id_constructor -> { arity = 1; s = "constructor" }
            Id_toString -> { arity = 0; s = "toString" }
            Id_toSource -> { arity = 0; s = "toSource" }
            else -> throw IllegalArgumentException(id.toString())
        }
        initPrototypeMethod(ERROR_TAG, id, s, arity)
    }

    override fun execIdCall(f: IdFunctionObject, cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        if (!f.hasTag(ERROR_TAG)) {
            return super.execIdCall(f, cx, scope, thisObj, args)
        }
        when (val id = f.methodId()) {
            Id_constructor -> return make(cx, scope, f, args)
            Id_toString -> {
                if (thisObj !== scope && thisObj is NativeObject) {
                    return js_toString(thisObj)
                }
                return js_toString(realThis(thisObj, f))
            }
            Id_toSource -> return js_toSource(cx, scope, thisObj!!)
            ConstructorId_captureStackTrace -> {
                js_captureStackTrace(cx, scope, thisObj!!, args)
                return Undefined.instance
            }
            ConstructorId_isError -> return js_isError(args)
            else -> throw IllegalArgumentException(id.toString())
        }
    }

    fun setStackProvider(re: RhinoException) {
        if (stackProvider == null) {
            defineProperty(STACK_TAG, { getStackDelegated() }, { setStackDelegated(it) }, DONTENUM)
        }
        stackProvider = re
    }

    fun getStackDelegated(): Any? {
        if (stack != null) return stack
        val provider = stackProvider ?: return Scriptable.NOT_FOUND
        var limit = DEFAULT_STACK_LIMIT
        var prepare: Function? = null
        val cons = prototype as NativeError
        val pp = cons.getAssociatedValue(ProtoProps.KEY) as ProtoProps?
        if (pp != null) {
            limit = pp.limit
            prepare = pp.prepare
        }
        val hideFunc = getAssociatedValue(STACK_HIDE_KEY) as String?
        val stackTrace = provider.getScriptStack(limit, hideFunc)
        val value: Any? = if (prepare == null) {
            RhinoException.formatStackTrace(stackTrace, provider.details())
        } else {
            callPrepareStack(prepare, stackTrace)
        }
        stack = value
        return value
    }

    fun setStackDelegated(value: Any?) {
        stackProvider = null
        stack = value
    }

    private fun callPrepareStack(prepare: Function, stack: Array<ScriptStackElement>): Any? {
        val cx = Context.getCurrentContext()!!
        val elts = arrayOfNulls<Any?>(stack.size)
        for (i in stack.indices) {
            val site = cx.newObject(this, "CallSite") as NativeCallSite
            site.setElement(stack[i])
            elts[i] = site
        }
        val eltArray = cx.newArray(this, elts)
        return prepare.call(cx, prepare, this, arrayOf(this, eltArray))
    }

    override fun findPrototypeId(name: String): Int = when (name) {
        "constructor" -> Id_constructor
        "toString" -> Id_toString
        "toSource" -> Id_toSource
        else -> 0
    }

    /** `Error.stackTraceLimit` and `Error.prepareStackTrace`, kept on the prototype. */
    private class ProtoProps {
        /** -1 stands for no limit, which scripts see as Infinity. */
        var limit = DEFAULT_STACK_LIMIT
        var prepare: Function? = null

        val stackTraceLimit: Any get() = if (limit >= 0) limit else Double.POSITIVE_INFINITY

        fun setStackTraceLimit(value: Any?) {
            val n = Context.toNumber(value)
            limit = if (n.isNaN() || n.isInfinite()) -1 else n.toInt()
        }

        val prepareStackTrace: Any get() = prepare ?: Undefined.instance

        fun setPrepareStackTrace(value: Any?) {
            if (value == null || Undefined.isUndefined(value)) {
                prepare = null
            } else if (value is Function) {
                prepare = value
            }
        }

        companion object {
            const val KEY = "_ErrorPrototypeProps"
        }
    }

    companion object {
        private const val ERROR_TAG = "Error"
        private const val STACK_TAG = "stack"
        const val DEFAULT_STACK_LIMIT = -1
        private const val STACK_HIDE_KEY = "_stackHide"

        internal fun init(scope: Scriptable, sealed: Boolean) {
            val obj = NativeError()
            putProperty(obj, "name", "Error")
            putProperty(obj, "message", "")
            putProperty(obj, "fileName", "")
            putProperty(obj, "lineNumber", 0)
            obj.setAttributes("name", DONTENUM)
            obj.setAttributes("message", DONTENUM)
            obj.exportAsJSClass(MAX_PROTOTYPE_ID, scope, sealed)
            NativeCallSite.init(scope, sealed)
        }

        internal fun makeProto(scope: Scriptable, ctorObj: Function): NativeError {
            val proto = ctorObj.get("prototype", ctorObj) as Scriptable
            val obj = NativeError()
            obj.prototype = proto
            obj.parentScope = scope
            return obj
        }

        internal fun make(cx: Context, scope: Scriptable, ctorObj: Function, args: Array<Any?>): NativeError {
            val obj = makeProto(scope, ctorObj)
            val arglen = args.size
            if (arglen >= 1) {
                if (!Undefined.isUndefined(args[0])) {
                    putProperty(obj, "message", ScriptRuntime.toString(args[0]))
                    obj.setAttributes("message", DONTENUM)
                }
                if (arglen >= 2) {
                    val options = args[1]
                    if (options is NativeObject) {
                        installCause(options, obj)
                    } else {
                        putProperty(obj, "fileName", ScriptRuntime.toString(args[1]))
                        if (arglen >= 3) {
                            putProperty(obj, "lineNumber", ScriptRuntime.toInt32(args[2]))
                        }
                    }
                }
            }
            obj.setStackProvider(EvaluatorException(""))
            return obj
        }

        internal fun makeAggregate(cx: Context, scope: Scriptable, ctorObj: Function, args: Array<Any?>): NativeError {
            val obj = makeProto(scope, ctorObj)
            val arglen = args.size
            if (arglen >= 1) {
                if (arglen >= 2) {
                    if (!Undefined.isUndefined(args[1])) {
                        putProperty(obj, "message", ScriptRuntime.toString(args[1]))
                        obj.setAttributes("message", DONTENUM)
                    }
                    if (arglen >= 3) {
                        val options = args[2]
                        if (options is NativeObject) {
                            installCause(options, obj)
                        } else {
                            putProperty(obj, "fileName", ScriptRuntime.toString(args[2]))
                            if (arglen >= 4) {
                                putProperty(obj, "lineNumber", ScriptRuntime.toInt32(args[3]))
                            }
                        }
                    }
                }
                val iterator = ScriptRuntime.callIterator(args[0], cx, scope)
                IteratorLikeIterable(cx, scope, iterator).use { it ->
                    val errors = mutableListOf<Any?>()
                    for (o in it) errors.add(o)
                    val newArray = cx.newArray(scope, errors.toTypedArray())
                    obj.defineProperty("errors", newArray, DONTENUM)
                }
            } else {
                throw ScriptRuntime.typeErrorById("msg.iterable.expected")
            }
            obj.setStackProvider(EvaluatorException(""))
            return obj
        }

        internal fun installCause(options: NativeObject, obj: NativeError) {
            val cause = getProperty(options, "cause")
            if (cause !== Scriptable.NOT_FOUND) {
                putProperty(obj, "cause", cause)
                obj.setAttributes("cause", DONTENUM)
            }
        }

        private fun realThis(thisObj: Scriptable?, f: IdFunctionObject): NativeError =
            ensureType<NativeError>(thisObj, f.functionName)

        private fun js_toString(thisObj: Scriptable): Any? {
            val nameObj = getProperty(thisObj, "name")
            val name = if (nameObj === Scriptable.NOT_FOUND || Undefined.isUndefined(nameObj)) "Error" else ScriptRuntime.toString(nameObj)
            val msgObj = getProperty(thisObj, "message")
            val msg = if (msgObj === Scriptable.NOT_FOUND || Undefined.isUndefined(msgObj)) "" else ScriptRuntime.toString(msgObj)
            return when {
                name.isEmpty() -> msg
                msg.isEmpty() -> name
                else -> "$name: $msg"
            }
        }

        private fun js_toSource(cx: Context, scope: Scriptable, thisObj: Scriptable): String {
            var name = getProperty(thisObj, "name")
            var message = getProperty(thisObj, "message")
            var fileName = getProperty(thisObj, "fileName")
            val lineNumber = getProperty(thisObj, "lineNumber")
            val sb = StringBuilder()
            sb.append("(new ")
            if (name === Scriptable.NOT_FOUND) name = Undefined.instance
            sb.append(ScriptRuntime.toString(name))
            sb.append("(")
            if (message !== Scriptable.NOT_FOUND || fileName !== Scriptable.NOT_FOUND || lineNumber !== Scriptable.NOT_FOUND) {
                if (message === Scriptable.NOT_FOUND) message = ""
                sb.append(ScriptRuntime.uneval(cx, scope, message))
                if (fileName !== Scriptable.NOT_FOUND || lineNumber !== Scriptable.NOT_FOUND) {
                    sb.append(", ")
                    if (fileName === Scriptable.NOT_FOUND) fileName = ""
                    sb.append(ScriptRuntime.uneval(cx, scope, fileName))
                    if (lineNumber !== Scriptable.NOT_FOUND) {
                        val line = ScriptRuntime.toInt32(lineNumber)
                        if (line != 0) {
                            sb.append(", ")
                            sb.append(ScriptRuntime.toString(line))
                        }
                    }
                }
            }
            sb.append("))")
            return sb.toString()
        }

        private fun js_captureStackTrace(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>) {
            val obj = ScriptRuntime.toObject(cx, scope, args[0]) as ScriptableObject
            var func: Function? = null
            if (args.size > 1) {
                func = ScriptRuntime.toObjectOrNull(cx, args[1], scope) as Function?
            }
            val err = cx.newObject(thisObj, "Error") as NativeError
            err.setStackProvider(EvaluatorException("[object Object]"))
            if (func != null) {
                val funcName = func.get("name", func)
                if (funcName != null && !Undefined.isUndefined(funcName)) {
                    err.associateValue(STACK_HIDE_KEY, ScriptRuntime.toString(funcName))
                }
            }
            obj.defineProperty(STACK_TAG, err.get(STACK_TAG, err), DONTENUM)
        }

        private fun js_isError(args: Array<Any?>): Boolean {
            val arg = if (args.isNotEmpty()) args[0] else Undefined.instance
            return arg is NativeError
        }

        private const val Id_constructor = 1
        private const val Id_toString = 2
        private const val Id_toSource = 3
        private const val ConstructorId_captureStackTrace = -1
        private const val ConstructorId_isError = -2
        private const val MAX_PROTOTYPE_ID = 3
    }
}
