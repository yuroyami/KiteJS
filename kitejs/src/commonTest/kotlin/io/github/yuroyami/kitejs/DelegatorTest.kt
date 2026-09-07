/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A delegator that counts the reads that reach it, so a test can see what was forwarded. */
private class CountingDelegator(obj: Scriptable?) : Delegator(obj) {

    var reads = 0

    override fun get(name: String, start: Scriptable): Any? {
        reads++
        return super.get(name, start)
    }

    override fun newInstance(): Delegator = CountingDelegator(null)
}

/**
 * `Delegator` forwards every object operation to the object behind it. Scripts should not be able
 * to tell the wrapper from the thing it wraps.
 */
class DelegatorTest {

    private fun <T> inContext(body: (Context, Scriptable) -> T): T = ContextFactory.getGlobal().call { cx ->
        cx.languageVersion = Context.VERSION_ES6
        body(cx, cx.initStandardObjects())
    }

    @Test
    fun readsAndWritesReachTheObjectBehindIt() = inContext { cx, scope ->
        val target = cx.newObject(scope)
        target.put("a", target, 1.0)
        val d = Delegator(target)

        assertEquals(1.0, d.get("a", d))
        d.put("b", d, 2.0)
        assertEquals(2.0, target.get("b", target))
        assertTrue(d.has("a", d))
        d.delete("a")
        assertTrue(!target.has("a", target))
        assertEquals(target.className, d.className)
    }

    @Test
    fun indexedAndSymbolKeysAreForwardedToo() = inContext { cx, scope ->
        val target = cx.newObject(scope)
        val d = Delegator(target)

        d.put(0, d, "zero")
        assertEquals("zero", target.get(0, target))
        assertTrue(d.has(0, d))
        d.delete(0)
        assertTrue(!d.has(0, d))

        val key = SymbolKey("mark", Symbol.Kind.REGULAR)
        d.put(key, d, 7.0)
        assertEquals(7.0, (target as SymbolScriptable).get(key, target))
        assertTrue(d.has(key, d))
        d.delete(key)
        assertTrue(!d.has(key, d))
    }

    @Test
    fun prototypeAndParentScopeAreTheTargetsOwn() = inContext { cx, scope ->
        val target = cx.newObject(scope)
        val d = Delegator(target)
        assertSame(target.prototype, d.prototype)
        assertSame(target.parentScope, d.parentScope)

        val other = cx.newObject(scope)
        d.prototype = other
        assertSame(other, target.prototype)
    }

    @Test
    fun idsComeFromTheTarget() = inContext { cx, scope ->
        val target = cx.newObject(scope)
        target.put("x", target, 1.0)
        target.put("y", target, 2.0)
        assertEquals(target.getIds().toList(), Delegator(target).getIds().toList())
    }

    /** A delegator answers itself for an object hint, and defers for a primitive one. */
    @Test
    fun defaultValueKeepsTheWrapperForObjectHints() = inContext { cx, scope ->
        val target = cx.newObject(scope)
        val d = Delegator(target)
        assertSame(d, d.getDefaultValue(null))
        assertSame(d, d.getDefaultValue(ScriptRuntime.ScriptableClass))
        assertEquals(target.getDefaultValue(ScriptRuntime.StringClass), d.getDefaultValue(ScriptRuntime.StringClass))
    }

    @Test
    fun aWrappedFunctionStillCalls() = inContext { cx, scope ->
        val f = cx.evaluateString(scope, "(function (a, b) { return a + b })", "t.js", 1) as Function
        val d = Delegator(f)
        assertEquals(3.0, d.call(cx, scope, scope, arrayOf(1.0, 2.0)))
    }

    /** With nothing behind it a delegator is a prototype: `new` on it builds a wrapper. */
    @Test
    fun anEmptyDelegatorConstructsAWrapperOverAFreshObject() = inContext { cx, scope ->
        val proto = CountingDelegator(null)
        val built = proto.construct(cx, scope, emptyArray())
        assertTrue(built is CountingDelegator)
        assertTrue(built.delegee != null)

        val over = cx.newObject(scope)
        over.put("a", over, 5.0)
        val wrapped = proto.construct(cx, scope, arrayOf(over)) as CountingDelegator
        assertSame(over, wrapped.delegee)
        assertEquals(5.0, wrapped.get("a", wrapped))
        assertEquals(1, wrapped.reads)
    }

    @Test
    fun theBaseClassSaysWhichHookASubclassOwes() {
        val bare = object : Delegator(null) {}
        assertFailsWith<IllegalStateException> {
            inContext { cx, scope -> bare.construct(cx, scope, emptyArray()) }
        }
    }

    /** The whole point: a script cannot tell the wrapper from what it wraps. */
    @Test
    fun aScriptSeesTheTargetThroughTheWrapper() = inContext { cx, scope ->
        val target = cx.newObject(scope)
        target.put("name", target, "inner")
        ScriptableObject.putProperty(scope, "wrapped", Delegator(target))

        assertEquals("inner", cx.evaluateString(scope, "wrapped.name", "t.js", 1))
        cx.evaluateString(scope, "wrapped.extra = 42", "t.js", 1)
        assertEquals(42.0, target.get("extra", target))
        assertEquals("name,extra", cx.evaluateString(scope, "Object.keys(wrapped).join()", "t.js", 1))
    }
}
