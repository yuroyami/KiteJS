/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

/**
 * Runs one call of an async function (ECMAScript 2017, 25.5.5, AsyncFunctionStart). The body is
 * a frozen interpreter frame, as a generator's is: it runs from the call to its first `await`,
 * and each `await` hands its operand here, which waits for it the way the spec's Await does and
 * resumes the body from the microtask queue with the settled value, or throws the reason into it.
 * What the body returns resolves [promise] and what it throws rejects it (D-97).
 */
internal class AsyncFunctionDriver(
    private val savedState: Any?,
    private val scope: Scriptable,
    private val isStrict: Boolean,
    cx: Context,
) {

    /** What an `await` suspends the body with: the operand to wait for. */
    internal class AwaitRequest(val value: Any?)

    /** The promise the call returns. */
    val promise: NativePromise = NativePromise.newIntrinsic(cx, scope)

    private val onFulfilled = Resumption(NativeGenerator.GENERATOR_SEND)
    private val onRejected = Resumption(NativeGenerator.GENERATOR_THROW)

    /**
     * What an awaited promise calls once it settles, from the microtask queue, which runs outside
     * any script: the body resumes inside a top call of its own, as a function called from the
     * host would.
     */
    private inner class Resumption(private val operation: Int) : Callable {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            if (!ScriptRuntime.hasTopCall(cx)) return ScriptRuntime.doTopCall(this, cx, scope, thisObj, args, isStrict)
            step(cx, operation, if (args.isNotEmpty()) args[0] else Undefined.instance)
            return Undefined.instance
        }
    }

    /** Runs the body from the start of the call to its first `await`, or to its end. */
    fun start(cx: Context) {
        step(cx, NativeGenerator.GENERATOR_SEND, Undefined.instance)
    }

    /**
     * Resumes the body with [operation] and [value] and waits on what it awaits next. Waiting can
     * throw, when reading the `constructor` of an awaited promise does, and that throw goes
     * straight back into the body at the same `await`.
     */
    private fun step(cx: Context, operation: Int, value: Any?) = cx.withRealm(scope) {
        var op = operation
        var v = value
        while (true) {
            val result = try {
                Interpreter.resumeAsync(cx, op, savedState, v)
            } catch (e: JavaScriptException) {
                promise.rejectFromEngine(cx, scope, e.value)
                return@withRealm
            } catch (e: EcmaError) {
                promise.rejectFromEngine(cx, scope, ScriptRuntime.wrapException(e, scope, cx))
                return@withRealm
            } catch (e: EvaluatorException) {
                promise.rejectFromEngine(cx, scope, ScriptRuntime.wrapException(e, scope, cx))
                return@withRealm
            }
            if (result !is AwaitRequest) {
                promise.resolveFromEngine(cx, scope, result)
                return@withRealm
            }
            try {
                NativePromise.await(cx, scope, result.value, onFulfilled, onRejected)
                return@withRealm
            } catch (e: JavaScriptException) {
                v = e.value
            } catch (e: EcmaError) {
                v = ScriptRuntime.wrapException(e, scope, cx)
            } catch (e: EvaluatorException) {
                v = ScriptRuntime.wrapException(e, scope, cx)
            }
            op = NativeGenerator.GENERATOR_THROW
        }
    }
}
