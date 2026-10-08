/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class IdInvocationTest {
    @Test
    fun nullIsACallReceiverAndConstructionKeepsItsLegacyCallbackShape() {
        val cx = Context.enter()
        try {
            val scope = cx.initStandardObjects()
            val prototype = cx.newObject(scope)
            val seen = mutableListOf<Pair<Boolean, Scriptable?>>()
            val fn = IdFunctionObject(object : IdFunctionCall {
                override fun execIdCall(f: IdFunctionObject, cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
                    seen.add(f.isConstructingCall to thisObj)
                    return if (f.isConstructingCall) NativeObject() else thisObj
                }
            }, "test", 1, "f", 0, scope)
            fn.markAsConstructor(prototype)
            assertEquals(null, fn.call(cx, scope, null, emptyArray()))
            val result = fn.construct(cx, scope, emptyArray())
            val expected: List<Pair<Boolean, Scriptable?>> = listOf(false to null, true to null)
            assertEquals(expected, seen)
            assertSame(prototype, result.prototype)
            assertSame(scope, result.parentScope)
            assertFalse(fn.isConstructingCall)
        } finally {
            Context.exit()
        }
    }

    @Test
    fun reentryAndThrowingRestoreTheEnclosingInvocationKind() {
        val cx = Context.enter()
        try {
            val scope = cx.initStandardObjects()
            val seen = mutableListOf<Boolean>()
            val fn = IdFunctionObject(object : IdFunctionCall {
                override fun execIdCall(f: IdFunctionObject, cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
                    seen.add(f.isConstructingCall)
                    if (!f.isConstructingCall) throw ScriptRuntime.typeError("nested call")
                    assertFailsWith<EcmaError> { f.call(cx, scope, null, emptyArray()) }
                    seen.add(f.isConstructingCall)
                    if (args.isNotEmpty()) throw ScriptRuntime.typeError("constructor")
                    return NativeObject()
                }
            }, "test", 1, "f", 0, scope)
            fn.markAsConstructor(cx.newObject(scope))
            fn.construct(cx, scope, emptyArray())
            assertEquals(listOf(true, false, true), seen)
            assertFalse(fn.isConstructingCall)
            assertFailsWith<EcmaError> { fn.construct(cx, scope, arrayOf(1)) }
            assertFalse(fn.isConstructingCall)
        } finally {
            Context.exit()
        }
    }
}
