/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Entering and leaving a context, and the settings it carries. */
class ContextTest {

    @Test
    fun enterAndExitAreCounted() {
        assertNull(Context.getCurrentContext())
        val cx = Context.enter()
        assertSame(cx, Context.getCurrentContext())
        assertSame(cx, Context.enter(), "a second enter gives the same context")
        Context.exit()
        assertSame(cx, Context.getCurrentContext(), "one exit for two enters leaves it in place")
        Context.exit()
        assertNull(Context.getCurrentContext())
        assertFailsWith<IllegalStateException> { Context.exit() }
    }

    @Test
    fun callEntersAndLeavesAround() {
        val result = ContextFactory.getGlobal().call { cx ->
            assertSame(cx, Context.getCurrentContext())
            cx.languageVersion
        }
        assertEquals(Context.VERSION_ES6, result)
        assertNull(Context.getCurrentContext())
    }

    @Test
    fun featuresFollowTheLanguageVersion() {
        Context.enter().use { cx ->
            cx.languageVersion = Context.VERSION_ES6
            assertTrue(cx.hasFeature(Context.FEATURE_ENUMERATE_IDS_FIRST))
            assertFalse(cx.hasFeature(Context.FEATURE_OLD_UNDEF_NULL_THIS))
            cx.languageVersion = Context.VERSION_1_7
            assertFalse(cx.hasFeature(Context.FEATURE_ENUMERATE_IDS_FIRST))
            assertTrue(cx.hasFeature(Context.FEATURE_OLD_UNDEF_NULL_THIS))
            assertFalse(cx.hasFeature(Context.FEATURE_THREAD_SAFE_OBJECTS), "always off (D-3)")
            assertFailsWith<IllegalArgumentException> { cx.hasFeature(99) }
        }
    }

    @Test
    fun aSealedContextRefusesChanges() {
        Context.enter().use { cx ->
            cx.seal("key")
            assertFailsWith<IllegalStateException> { cx.languageVersion = Context.VERSION_1_8 }
            cx.unseal("key")
            cx.languageVersion = Context.VERSION_1_8
            assertEquals(Context.VERSION_1_8, cx.languageVersion)
        }
    }

    @Test
    fun theFactoryHearsAboutContexts() {
        val events = mutableListOf<String>()
        val factory = object : ContextFactory() {
            override fun hasFeature(cx: Context, featureIndex: Int): Boolean =
                if (featureIndex == Context.FEATURE_STRICT_MODE) true else super.hasFeature(cx, featureIndex)
        }
        factory.addListener(object : ContextFactory.Listener {
            override fun contextCreated(cx: Context) { events.add("created") }
            override fun contextReleased(cx: Context) { events.add("released") }
        })
        factory.call { cx ->
            assertTrue(cx.hasFeature(Context.FEATURE_STRICT_MODE))
            assertSame(factory, cx.factory)
        }
        assertEquals(listOf("created", "released"), events)
    }

    @Test
    fun microtasksRunInOrderAndCanQueueMore() {
        Context.enter().use { cx ->
            val ran = mutableListOf<Int>()
            cx.enqueueMicrotask { ran.add(1); cx.enqueueMicrotask { ran.add(3) } }
            cx.enqueueMicrotask { ran.add(2) }
            cx.processMicrotasks()
            assertEquals(listOf(1, 2, 3), ran)
        }
    }

    @Test
    fun errorsRouteThroughTheReporter() {
        Context.enter().use { cx ->
            val seen = mutableListOf<String>()
            cx.errorReporter = object : ErrorReporter {
                override fun warning(message: String, sourceName: String?, line: Int, lineSource: String?, lineOffset: Int) { seen.add("warn:$message") }
                override fun error(message: String, sourceName: String?, line: Int, lineSource: String?, lineOffset: Int) { seen.add("error:$message") }
                override fun runtimeError(message: String, sourceName: String?, line: Int, lineSource: String?, lineOffset: Int): EvaluatorException {
                    seen.add("runtime:$message")
                    return EvaluatorException(message, sourceName, line, lineSource, lineOffset)
                }
            }
            Context.reportWarning("w")
            Context.reportError("e")
            val ex = Context.reportRuntimeError("r")
            assertEquals("r", ex.message)
            assertEquals(listOf("warn:w", "error:e", "runtime:r"), seen)
        }
        // Without a context, an error throws right away.
        assertFailsWith<EvaluatorException> { Context.reportError("no context") }
    }
}
