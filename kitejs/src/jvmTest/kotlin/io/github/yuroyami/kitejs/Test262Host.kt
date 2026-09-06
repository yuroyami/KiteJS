/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * The `$262` object test262 expects a host to provide, built twice: once against upstream's classes
 * and once against the port's. Both give the same members so that a test using it is comparing the
 * engines, not two different hosts.
 *
 * `agent` is missing on purpose, the same as upstream: the tests that want it need real threads,
 * and they are filtered out with the `Atomics` and `SharedArrayBuffer` features.
 */
internal object Test262Host {

    fun installUpstream(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.ScriptableObject) {
        val dollar = cx.newObject(scope) as org.mozilla.javascript.ScriptableObject
        fun fn(name: String, arity: Int, body: (Array<Any?>) -> Any?) {
            val f = org.mozilla.javascript.LambdaFunction(
                scope,
                name,
                arity,
                org.mozilla.javascript.SerializableCallable { _, _, _, args -> body(args) },
            )
            dollar.defineProperty(name, f, org.mozilla.javascript.ScriptableObject.DONTENUM)
        }

        fn("gc", 0) { System.gc(); org.mozilla.javascript.Undefined.instance }
        fn("evalScript", 1) { args ->
            val source = org.mozilla.javascript.Context.toString(args.firstOrNull())
            cx.evaluateString(scope, source, "<evalScript>", 1, null)
        }
        fn("detachArrayBuffer", 1) { args ->
            (args.firstOrNull() as? org.mozilla.javascript.typedarrays.NativeArrayBuffer)?.detach()
            org.mozilla.javascript.Undefined.instance
        }
        fn("createRealm", 0) {
            val realm = cx.initSafeStandardObjects(org.mozilla.javascript.TopLevel(), false)
            installUpstream(cx, realm)
            realm.get("\$262", realm)
        }
        dollar.defineProperty("global", scope, org.mozilla.javascript.ScriptableObject.DONTENUM)
        scope.defineProperty("\$262", dollar, org.mozilla.javascript.ScriptableObject.DONTENUM)
    }

    fun installPorted(cx: Context, scope: ScriptableObject) {
        val dollar = cx.newObject(scope) as ScriptableObject
        fun fn(name: String, arity: Int, body: (Array<Any?>) -> Any?) {
            val f = LambdaFunction(scope, name, arity, SerializableCallable { _, _, _, args -> body(args) })
            dollar.defineProperty(name, f, ScriptableObject.DONTENUM)
        }

        fn("gc", 0) { System.gc(); Undefined.instance }
        fn("evalScript", 1) { args ->
            val source = ScriptRuntime.toString(args.firstOrNull())
            cx.evaluateString(scope, source, "<evalScript>", 1, null)
        }
        fn("detachArrayBuffer", 1) { args ->
            (args.firstOrNull() as? io.github.yuroyami.kitejs.typedarrays.NativeArrayBuffer)?.detach()
            Undefined.instance
        }
        fn("createRealm", 0) {
            val realm = cx.initSafeStandardObjects(TopLevel(), false)
            installPorted(cx, realm)
            realm.get("\$262", realm)
        }
        dollar.defineProperty("global", scope, ScriptableObject.DONTENUM)
        scope.defineProperty("\$262", dollar, ScriptableObject.DONTENUM)
    }
}
