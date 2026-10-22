/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class ErrorRealmHostTest {
    private inline fun realms(body: (Context, ScriptableObject, ScriptableObject) -> Unit) {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            body(cx, cx.initStandardObjects(), cx.initStandardObjects())
        } finally {
            Context.exit()
        }
    }

    @Test
    fun hostConversionKeepsTheThrowingRealmAndObjectIdentity() = realms { cx, caller, foreign ->
        caller.put("foreign", caller, foreign)
        val error = assertFailsWith<EcmaError> {
            cx.evaluateString(caller, "foreign.eval('(function(){null.x})')()", "caller.js", 1, null)
        }
        assertSame(foreign, error.errorRealm)
        assertNull(cx.currentRealm)
        val first = ScriptRuntime.wrapException(error, caller, cx)
        val second = ScriptRuntime.wrapException(error, foreign, cx)
        assertSame(first, second)
        val typeError = foreign.get("TypeError", foreign) as Scriptable
        assertSame(typeError.get("prototype", typeError), first.prototype)
        assertEquals(error.errorMessage, first.get("message", first))
        assertNull(cx.currentRealm)
    }

    @Test
    fun nativeAccessorsUseTheirFunctionsRealmOnDirectReadsAndWrites() = realms { cx, caller, foreign ->
        val prototype = cx.newObject(foreign) as ScriptableObject
        prototype.defineProperty(cx, "x",
            ScriptableObject.LambdaGetterFunction { throw ScriptRuntime.typeError("native getter") },
            ScriptableObject.LambdaSetterFunction { _, _ -> throw ScriptRuntime.referenceError("native setter") },
            0)
        val receiver = cx.newObject(caller)
        receiver.prototype = prototype
        caller.put("receiver", caller, receiver)
        caller.put("foreign", caller, foreign)
        assertEquals("true,true", ScriptRuntime.toString(cx.evaluateString(caller, """
            var read, write;
            try { receiver.x } catch(e) { read=e instanceof foreign.TypeError }
            try { receiver.x=1 } catch(e) { write=e instanceof foreign.ReferenceError }
            [read,write].join();
        """, "accessors.js", 1, null)))
        assertNull(cx.currentRealm)
    }

    @Test
    fun hostReentryRestoresTheBuiltinRealmBeforeItsNextOperation() = realms { cx, caller, foreign ->
        val fn = LambdaFunction(foreign, "reentry", 0, SerializableCallable { callCx, _, _, _ ->
            callCx.evaluateString(caller, "try { null.x } catch(e) {}", "reentry.js", 1, null)
            throw ScriptRuntime.typeError("after reentry")
        }, false)
        caller.put("fn", caller, fn)
        caller.put("foreign", caller, foreign)
        assertEquals("true", ScriptRuntime.toString(cx.evaluateString(caller,
            "try { fn() } catch(e) { String(e instanceof foreign.TypeError) }", "host-call.js", 1, null)))
        assertNull(cx.currentRealm)
        assertEquals("true", ScriptRuntime.toString(cx.evaluateString(caller,
            "try { null.x } catch(e) { String(e instanceof TypeError) }", "after-call.js", 1, null)))
    }
}
