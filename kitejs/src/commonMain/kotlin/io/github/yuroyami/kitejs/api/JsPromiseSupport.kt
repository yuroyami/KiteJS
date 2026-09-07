/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

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

/** True when this value is thenable, which is what `await` looks for. */
public val JsValue.isThenable: Boolean
    get() = type == JsType.OBJECT || type == JsType.FUNCTION

/**
 * Registers [onSettled] on a promise. It is called with the value and a null error on success, or
 * with undefined and the reason on failure. Returns false if the value was not thenable at all.
 */
public fun KiteJs.onSettled(value: JsValue, onSettled: (JsValue, JsValue?) -> Unit): Boolean {
    val obj = value.asObjectOrNull() ?: return false
    val then = obj["then"].asFunctionOrNull() ?: return false
    val holder = newObject()
    holder.function("ok", 1) { args -> onSettled(args.getOrElse(0) { JsValue.undefined }, null) }
    holder.function("fail", 1) { args -> onSettled(JsValue.undefined, args.getOrElse(0) { JsValue.undefined }) }
    then.callOn(obj, holder["ok"], holder["fail"])
    return true
}
