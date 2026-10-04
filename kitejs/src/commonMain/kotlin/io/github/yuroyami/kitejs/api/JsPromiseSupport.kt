/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.Callable
import io.github.yuroyami.kitejs.Context
import io.github.yuroyami.kitejs.NativePromise
import io.github.yuroyami.kitejs.ScriptRuntime
import io.github.yuroyami.kitejs.Scriptable
import io.github.yuroyami.kitejs.ScriptableObject
import io.github.yuroyami.kitejs.Undefined

/** A promise the host controls: hand [promise] to the script, settle it when you are ready. */
public class JsPromiseHandle internal constructor(
    public val promise: JsObject,
    private val resolveFn: JsFunction,
    private val rejectFn: JsFunction,
) {
    /** Settles the promise with [value]. Later calls do nothing, as in a script. */
    public fun resolve(value: Any?) {
        resolveFn(value)
    }

    /** Rejects the promise with [reason]. */
    public fun reject(reason: Any?) {
        rejectFn(reason)
    }
}

private const val PROMISE_MAKER =
    "(function () { var r, j; var p = new Promise(function (res, rej) { r = res; j = rej });" +
        " return { promise: p, resolve: r, reject: j } })()"

/** A promise the host settles later. */
public fun KiteJs.newPromise(): JsPromiseHandle {
    val parts = evaluate(PROMISE_MAKER, "<promise>").asObject()
    return JsPromiseHandle(parts["promise"].asObject(), parts["resolve"].asFunction(), parts["reject"].asFunction())
}

/**
 * True when this value is thenable, which is what `await` looks for: an object, an array or a
 * function whose `then` is callable. Reading `then` can run a getter, so for those this needs the
 * value's engine open and on this thread, and a getter that throws reaches you as a [JsError].
 * Every other value answers false without touching an engine.
 */
public val JsValue.isThenable: Boolean
    get() {
        val obj = raw as? Scriptable ?: return false
        if (!ScriptRuntime.isObject(obj)) return false
        val cx = contextFor(obj)
        return try {
            topCall(cx, scopeOf(obj)) { ScriptableObject.getProperty(obj, "then") is Callable }
        } catch (e: Throwable) {
            throw translate(e)
        }
    }

/**
 * Registers [onSettled] on [value] the way `await value` attaches to it. It is called once, with
 * the value and a null error on success, or with undefined and the reason on failure.
 *
 * A promise is watched directly, without calling its `then`. For any other thenable, `then` is
 * read once and called from a microtask, as the Promise resolve functions do, and a promise or
 * thenable it resolves with is followed to the end, so the callback is never handed one. A `then`
 * getter or a `then` call that throws is a failure. Returns false, registering nothing, when
 * [value] is not thenable at all, which `await` would hand straight back.
 *
 * The callback runs from the microtask queue, which every outermost call into the engine drains
 * as it returns, this one included, so for a promise that has already settled it runs before
 * this returns. An exception it throws comes out of the call that drained the queue.
 */
public fun KiteJs.onSettled(value: JsValue, onSettled: (JsValue, JsValue?) -> Unit): Boolean =
    hostCall { cx, scope ->
        NativePromise.awaitValue(
            cx,
            scope,
            adopt(value.raw, scope),
            hostHandler { onSettled(JsValue(it), null) },
            hostHandler { onSettled(JsValue.undefined, JsValue(it)) },
        )
    }

/** A handler only the engine calls, never a script, so it needs no function object around it. */
private fun hostHandler(body: (Any?) -> Unit): Callable = object : Callable {
    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        body(if (args.isNotEmpty()) args[0] else Undefined.instance)
        return Undefined.instance
    }
}
