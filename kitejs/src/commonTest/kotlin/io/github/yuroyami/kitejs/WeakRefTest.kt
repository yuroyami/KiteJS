/*
 * Copyright 2026 yuroyami
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Taken from KiteCore (https://github.com/yuroyami/KiteCore), which is where
 * this code was written. A copy of the license is in LICENSE-APACHE-2.0.
 */

package io.github.yuroyami.kitejs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What [WeakRef] promises on every target. Collection itself cannot be forced from common code,
 * so `WeakRefGcTest` on the JVM is where that half is checked.
 */
class WeakRefTest {

    @Test
    fun getReturnsTheReferentWhileSomethingElseHoldsIt() {
        val target = StringBuilder("kite")
        val weak = WeakRef(target)
        assertSame(target, weak.get())
    }

    @Test
    fun clearNullsTheReference() {
        val target = Any()
        val weak = WeakRef(target)
        assertTrue(weak.get() != null)
        weak.clear()
        assertNull(weak.get())
        // Clearing twice is allowed.
        weak.clear()
        assertNull(weak.get())
    }

    @Test
    fun aPrimitiveReferentIsSafe() {
        // A JS string is not a valid WeakRef target, so the JS actual has to fall back to holding
        // it strongly rather than throwing. Everywhere else this is an ordinary reference.
        val weak = WeakRef("kite")
        assertEquals("kite", weak.get())
        weak.clear()
        assertNull(weak.get())
    }

    @Test
    fun aRuntimeWithoutWeakSupportStillAnswers() {
        // The contract when isWeakSupported is false: the referent is held strongly, so get()
        // keeps answering until clear(). That has to hold whichever way the flag comes out.
        val weak = WeakRef(StringBuilder("held"))
        if (!WeakRef.isWeakSupported) assertTrue(weak.get() != null)
        weak.clear()
        assertNull(weak.get())
    }
}
