/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * Running one test262 file through this engine and saying what happened, in a form two runs can be
 * compared on.
 *
 * It lives in common code because both the JVM parity run and the other targets have to reach the
 * same verdict for the same reasons. If this differed between them, the cross-target check would
 * be comparing two different questions.
 */
internal object Test262Execution {

    const val PASS: String = "passed"

    /**
     * Runs [source] with its [harness] files already read, in strict mode or not, and answers what
     * happened. A crash is reported by type alone, never by message or line: two engines or two
     * platforms would never agree on those.
     */
    fun run(
        path: String,
        source: String,
        meta: Test262FrontMatter,
        strict: Boolean,
        harness: List<String>,
        onCrash: (Throwable) -> Unit = {},
    ): String {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initSafeStandardObjects(TopLevel(), false)
            for (text in harness) {
                cx.evaluateString(scope, text, "harness", 1)
            }
            installHost(cx, scope)

            var failedEarly = true
            return try {
                // The "use strict" line goes on the front, so line numbers are kept by starting at 0.
                val text = if (strict) "\"use strict\";\n$source" else source
                val script = cx.compileString(text, path, if (strict) 0 else 1)
                failedEarly = false
                script.exec(cx, scope, scope)
                if (meta.isNegative) {
                    "expected ${meta.negativeType} at ${meta.negativePhase} but nothing was thrown"
                } else {
                    PASS
                }
            } catch (e: RhinoException) {
                judge(meta, errorName(e), failedEarly)
            } catch (e: Throwable) {
                onCrash(e)
                "crashed with ${crashName(e)}"
            }
        } catch (e: Throwable) {
            onCrash(e)
            return "crashed with ${crashName(e)}"
        } finally {
            Context.exit()
        }
    }

    private fun judge(meta: Test262FrontMatter, errorName: String, failedEarly: Boolean): String {
        if (!meta.isNegative) return "threw $errorName"
        if (meta.hasEarlyError && !failedEarly) {
            return "expected an early ${meta.negativeType}, got $errorName at runtime"
        }
        if (errorName != meta.negativeType) return "expected ${meta.negativeType}, got $errorName"
        return PASS
    }

    private fun errorName(e: RhinoException): String {
        if (e is EvaluatorException) return "SyntaxError"
        return e.details().substringBefore(":")
    }

    /**
     * The exception's own name. `simpleName` is not the same string on every target, so the few
     * that matter are spelled out and anything else falls back to its message-free description.
     */
    private fun crashName(e: Throwable): String = when (e) {
        is NullPointerException -> "NullPointerException"
        is ClassCastException -> "ClassCastException"
        is ArithmeticException -> "ArithmeticException"
        is IllegalStateException -> "IllegalStateException"
        is IllegalArgumentException -> "IllegalArgumentException"
        is IndexOutOfBoundsException -> "IndexOutOfBoundsException"
        is UnsupportedOperationException -> "UnsupportedOperationException"
        else -> e::class.simpleName ?: "Throwable"
    }

    /** The `$262` object the suite expects a host to provide. */
    private fun installHost(cx: Context, scope: ScriptableObject) {
        val dollar = cx.newObject(scope) as ScriptableObject
        fun fn(name: String, arity: Int, body: (Array<Any?>) -> Any?) {
            val f = LambdaFunction(scope, name, arity, SerializableCallable { _, _, _, args -> body(args) })
            dollar.defineProperty(name, f, ScriptableObject.DONTENUM)
        }
        // No gc() worth the name off the JVM, and nothing in the suite depends on it collecting.
        fn("gc", 0) { Undefined.instance }
        fn("evalScript", 1) { args ->
            cx.evaluateString(scope, ScriptRuntime.toString(args.firstOrNull()), "<evalScript>", 1)
        }
        fn("detachArrayBuffer", 1) { args ->
            (args.firstOrNull() as? io.github.yuroyami.kitejs.typedarrays.NativeArrayBuffer)?.detach()
            Undefined.instance
        }
        fn("createRealm", 0) {
            val realm = cx.initSafeStandardObjects(TopLevel(), false)
            installHost(cx, realm)
            realm.get("\$262", realm)
        }
        dollar.defineProperty("global", scope, ScriptableObject.DONTENUM)
        scope.defineProperty("\$262", dollar, ScriptableObject.DONTENUM)
    }
}
