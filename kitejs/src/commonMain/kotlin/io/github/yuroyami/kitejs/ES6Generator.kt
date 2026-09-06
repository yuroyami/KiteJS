/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The ES6 generator object: `next`, `return`, `throw` and `Symbol.iterator`.
 *
 * A generator is a frozen interpreter frame plus a state. [resumeLocal] runs the generator's own
 * body; when the body hits `yield*` it hands back a [YieldStarResult] and everything from then on
 * is forwarded to the delegee until that iterator is done.
 */
class ES6Generator : ScriptableObject {

    private var function: JSFunction? = null
    private var savedState: Any? = null
    private var lineSource: String? = null
    private var lineNumber: Int = 0
    private var state: State = State.SUSPENDED_START
    private var delegee: Any? = null

    /** Only for building the prototype object. */
    private constructor() : super()

    constructor(scope: Scriptable, function: JSFunction, savedState: Any?) : super() {
        this.function = function
        this.savedState = savedState
        val top = getTopLevelScope(scope)
        this.parentScope = top
        // The spec wants the generator function's own .prototype; anything else falls back to the
        // intrinsic %GeneratorPrototype% kept on the scope.
        val functionPrototype = getProperty(function, "prototype")
        this.prototype = functionPrototype as? Scriptable
            ?: getTopScopeValue(top, GENERATOR_TAG) as? Scriptable
    }

    override val className: String
        get() = "Generator"

    // ---- The delegee side, which is everything after a yield* ---------------------------------

    private fun resumeDelegee(cx: Context, scope: Scriptable, value: Any?): Scriptable {
        try {
            // Only pass an argument to next() when there is one to pass.
            val nextArgs: Array<Any?> = if (Undefined.isUndefined(value)) ScriptRuntime.emptyArgs else arrayOf(value)
            val nextFn = ScriptRuntime.getPropAndThis(delegee, ES6Iterator.NEXT_METHOD, cx, scope)!!
            val nr = nextFn.call(cx, scope, nextArgs)

            val nextResult = ensureScriptable(nr)
            if (ScriptRuntime.isIteratorDone(cx, nextResult)) {
                delegee = null
                return resumeLocal(cx, scope, getProperty(nextResult, ES6Iterator.VALUE_PROPERTY))
            }
            return nextResult
        } catch (re: RhinoException) {
            // Anything the delegee throws, including a missing method, belongs to the generator.
            delegee = null
            return resumeAbruptLocal(cx, scope, NativeGenerator.GENERATOR_THROW, re)
        }
    }

    private fun resumeDelegeeThrow(cx: Context, scope: Scriptable, value: Any?): Scriptable {
        var returnCalled = false
        try {
            val throwFn = ScriptRuntime.getPropAndThis(delegee, "throw", cx, scope)!!
            val throwResult = throwFn.call(cx, scope, arrayOf(value))

            if (ScriptRuntime.isIteratorDone(cx, throwResult)) {
                try {
                    returnCalled = true
                    callReturnOptionally(cx, scope, Undefined.instance)
                } finally {
                    delegee = null
                }
                return resumeLocal(cx, scope, ScriptRuntime.getObjectProp(throwResult, ES6Iterator.VALUE_PROPERTY, cx, scope))
            }
            return ensureScriptable(throwResult)
        } catch (re: RhinoException) {
            try {
                if (!returnCalled) {
                    try {
                        callReturnOptionally(cx, scope, Undefined.instance)
                    } catch (re2: RhinoException) {
                        return resumeAbruptLocal(cx, scope, NativeGenerator.GENERATOR_THROW, re2)
                    }
                }
            } finally {
                delegee = null
            }
            return resumeAbruptLocal(cx, scope, NativeGenerator.GENERATOR_THROW, re)
        }
    }

    private fun resumeDelegeeReturn(cx: Context, scope: Scriptable, value: Any?): Scriptable {
        try {
            // A missing "return" is not an error, and neither is one that answers undefined.
            val retResult = callReturnOptionally(cx, scope, value)
            if (retResult != null && !Undefined.isUndefined(retResult)) {
                if (ScriptRuntime.isIteratorDone(cx, retResult)) {
                    delegee = null
                    return resumeAbruptLocal(
                        cx,
                        scope,
                        NativeGenerator.GENERATOR_CLOSE,
                        ScriptRuntime.getObjectPropNoWarn(retResult, ES6Iterator.VALUE_PROPERTY, cx, scope),
                    )
                }
                // Not done after all.
                return ensureScriptable(retResult)
            }
            delegee = null
            return resumeAbruptLocal(cx, scope, NativeGenerator.GENERATOR_CLOSE, value)
        } catch (re: RhinoException) {
            delegee = null
            return resumeAbruptLocal(cx, scope, NativeGenerator.GENERATOR_THROW, re)
        }
    }

    // ---- The generator's own body --------------------------------------------------------------

    private fun resumeLocal(cx: Context, scope: Scriptable, value: Any?): Scriptable {
        if (state == State.COMPLETED) return ES6Iterator.makeIteratorResult(cx, scope, true)
        if (state == State.EXECUTING) throw ScriptRuntime.typeErrorById("msg.generator.executing")

        val result = ES6Iterator.makeIteratorResult(cx, scope, false)
        state = State.EXECUTING

        try {
            val r = function!!.resumeGenerator(cx, scope, NativeGenerator.GENERATOR_SEND, savedState, value)

            if (r is YieldStarResult) {
                // The body reached a "yield *". Everything after this goes to the delegee.
                state = State.SUSPENDED_YIELD
                try {
                    delegee = ScriptRuntime.callIterator(r.result, cx, scope)
                } catch (re: RhinoException) {
                    return resumeAbruptLocal(cx, scope, NativeGenerator.GENERATOR_THROW, re)
                }

                val delResult: Scriptable
                try {
                    // The spec says the first value handed to the delegee is undefined.
                    delResult = resumeDelegee(cx, scope, Undefined.instance)
                } finally {
                    state = State.EXECUTING
                }
                if (ScriptRuntime.isIteratorDone(cx, delResult)) state = State.COMPLETED
                return delResult
            }

            putProperty(result, ES6Iterator.VALUE_PROPERTY, r)
        } catch (gce: NativeGenerator.GeneratorClosedException) {
            state = State.COMPLETED
        } catch (jse: JavaScriptException) {
            state = State.COMPLETED
            val thrown = jse.value
            if (thrown is NativeIterator.StopIteration) {
                putProperty(result, ES6Iterator.VALUE_PROPERTY, thrown.value)
            } else {
                lineNumber = jse.lineNumber
                lineSource = jse.lineSource
                if (thrown is RhinoException) throw thrown
                throw jse
            }
        } catch (re: RhinoException) {
            lineNumber = re.lineNumber
            lineSource = re.lineSource
            throw re
        } finally {
            if (state == State.COMPLETED) {
                putProperty(result, ES6Iterator.DONE_PROPERTY, true)
            } else {
                state = State.SUSPENDED_YIELD
            }
        }
        return result
    }

    private fun resumeAbruptLocal(cx: Context, scope: Scriptable, op: Int, value: Any?): Scriptable {
        if (state == State.EXECUTING) throw ScriptRuntime.typeErrorById("msg.generator.executing")
        if (state == State.SUSPENDED_START) {
            // Never started, so there is nothing to resume into.
            state = State.COMPLETED
        }

        val result = ES6Iterator.makeIteratorResult(cx, scope, false)
        if (state == State.COMPLETED) {
            if (op == NativeGenerator.GENERATOR_THROW) throw JavaScriptException(value, lineSource, lineNumber)
            putProperty(result, ES6Iterator.DONE_PROPERTY, true)
            return result
        }

        state = State.EXECUTING

        var throwValue = value
        if (op == NativeGenerator.GENERATOR_CLOSE) {
            if (value !is NativeGenerator.GeneratorClosedException) {
                throwValue = NativeGenerator.GeneratorClosedException(value)
            }
        } else {
            if (value is JavaScriptException) {
                throwValue = value.value
            } else if (value is RhinoException) {
                throwValue = ScriptRuntime.wrapException(value, scope, cx)
            }
        }

        try {
            val r = function!!.resumeGenerator(cx, scope, op, savedState, throwValue)
            putProperty(result, ES6Iterator.VALUE_PROPERTY, r)
            // No exception means the generator can still run.
            state = State.SUSPENDED_YIELD
        } catch (gce: NativeGenerator.GeneratorClosedException) {
            state = State.COMPLETED
            putProperty(result, ES6Iterator.VALUE_PROPERTY, gce.value)
        } catch (jse: JavaScriptException) {
            state = State.COMPLETED
            val thrown = jse.value
            if (thrown is NativeIterator.StopIteration) {
                putProperty(result, ES6Iterator.VALUE_PROPERTY, thrown.value)
            } else {
                lineNumber = jse.lineNumber
                lineSource = jse.lineSource
                if (thrown is RhinoException) throw thrown
                throw jse
            }
        } catch (re: RhinoException) {
            state = State.COMPLETED
            lineNumber = re.lineNumber
            lineSource = re.lineSource
            throw re
        } finally {
            // An abrupt completion always ends the generator, delegee included.
            if (state == State.COMPLETED) {
                delegee = null
                putProperty(result, ES6Iterator.DONE_PROPERTY, true)
            }
        }
        return result
    }

    /** Calls the delegee's `return` when it has one. A missing or nullish one is not an error. */
    private fun callReturnOptionally(cx: Context, scope: Scriptable, value: Any?): Any? {
        val retArgs: Array<Any?> = if (Undefined.isUndefined(value)) ScriptRuntime.emptyArgs else arrayOf(value)
        val retFnObj = ScriptRuntime.getObjectPropNoWarn(delegee, ES6Iterator.RETURN_METHOD, cx, scope)
        if (retFnObj != null && !Undefined.isUndefined(retFnObj)) {
            if (retFnObj !is Callable) {
                throw ScriptRuntime.typeErrorById("msg.isnt.function", ES6Iterator.RETURN_METHOD, ScriptRuntime.typeOf(retFnObj))
            }
            return retFnObj.call(cx, scope, ensureScriptable(delegee), retArgs)
        }
        return null
    }

    private enum class State {
        SUSPENDED_START,
        SUSPENDED_YIELD,
        EXECUTING,
        COMPLETED,
    }

    /** Marks a value yielded by `yield*`, which the generator forwards rather than wraps. */
    class YieldStarResult(val result: Any?)

    companion object {
        /** The key `%GeneratorPrototype%` is cached under on the top scope. */
        internal val GENERATOR_TAG: Any = "Generator"

        internal fun init(scope: ScriptableObject, sealed: Boolean): ES6Generator {
            val prototype = ES6Generator()
            prototype.parentScope = scope
            prototype.prototype = getObjectPrototype(scope)

            defineProperty(prototype, "next", LambdaFunction(scope, "next", 1, SerializableCallable { cx, s, thisObj, args -> js_next(cx, s, thisObj, args) }), DONTENUM)
            defineProperty(prototype, "return", LambdaFunction(scope, "return", 1, SerializableCallable { cx, s, thisObj, args -> js_return(cx, s, thisObj, args) }), DONTENUM)
            defineProperty(prototype, "throw", LambdaFunction(scope, "throw", 1, SerializableCallable { cx, s, thisObj, args -> js_throw(cx, s, thisObj, args) }), DONTENUM)
            prototype.defineProperty(
                SymbolKey.ITERATOR,
                LambdaFunction(scope, "[Symbol.iterator]", 0, SerializableCallable { _, _, thisObj, _ -> thisObj }),
                DONTENUM,
            )
            prototype.defineProperty(SymbolKey.TO_STRING_TAG, "Generator", DONTENUM or READONLY)

            if (sealed) prototype.sealObject()

            // Generator instances find this prototype through the scope, since there is no
            // constructor to read it from.
            scope.associateValue(GENERATOR_TAG, prototype)
            return prototype
        }

        private fun realThis(thisObj: Scriptable?): ES6Generator =
            LambdaConstructor.convertThisObject<ES6Generator>(thisObj)

        private fun js_next(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val generator = realThis(thisObj)
            val value = if (args.isNotEmpty()) args[0] else Undefined.instance
            return if (generator.delegee == null) {
                generator.resumeLocal(cx, scope, value)
            } else {
                generator.resumeDelegee(cx, scope, value)
            }
        }

        private fun js_return(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val generator = realThis(thisObj)
            val value = if (args.isNotEmpty()) args[0] else Undefined.instance
            return if (generator.delegee == null) {
                generator.resumeAbruptLocal(cx, scope, NativeGenerator.GENERATOR_CLOSE, value)
            } else {
                generator.resumeDelegeeReturn(cx, scope, value)
            }
        }

        private fun js_throw(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val generator = realThis(thisObj)
            val value = if (args.isNotEmpty()) args[0] else Undefined.instance
            return if (generator.delegee == null) {
                generator.resumeAbruptLocal(cx, scope, NativeGenerator.GENERATOR_THROW, value)
            } else {
                generator.resumeDelegeeThrow(cx, scope, value)
            }
        }
    }
}
