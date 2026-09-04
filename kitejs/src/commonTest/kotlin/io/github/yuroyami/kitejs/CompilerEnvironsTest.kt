/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import io.github.yuroyami.kitejs.ast.ErrorCollector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Covers the compiler configuration the lexer and parser read. */
class CompilerEnvironsTest {

    @Test
    fun defaultsMatchUpstream() {
        val env = CompilerEnvirons()
        assertEquals(Context.VERSION_ES6, env.languageVersion)
        assertTrue(env.generateDebugInfo)
        assertTrue(env.reservedKeywordAsIdentifier)
        assertFalse(env.allowMemberExprAsFunctionName)
        assertTrue(env.xmlAvailable)
        assertFalse(env.interpretedMode)
        assertTrue(env.generatingSource)
        assertFalse(env.strictMode)
        assertFalse(env.warningAsError)
        assertFalse(env.generateObserverCount)
        assertFalse(env.allowSharpComments)
        assertFalse(env.ideMode)
        assertFalse(env.recoverFromErrors)
        assertNull(env.activationNames)
        assertSame(DefaultErrorReporter.instance, env.errorReporter)
    }

    @Test
    fun idePresetTurnsOnRecoveryAndCollectsErrors() {
        val env = CompilerEnvirons.ideEnvirons()
        assertTrue(env.recoverFromErrors)
        assertTrue(env.recordingComments)
        assertTrue(env.strictMode)
        assertTrue(env.warnTrailingComma)
        assertTrue(env.reservedKeywordAsIdentifier)
        assertTrue(env.ideMode)
        assertEquals(Context.VERSION_1_7, env.languageVersion)
        assertTrue(env.errorReporter is ErrorCollector)
    }

    @Test
    fun languageVersionIsValidated() {
        val env = CompilerEnvirons()
        env.languageVersion = Context.VERSION_1_8
        assertEquals(Context.VERSION_1_8, env.languageVersion)
        assertFailsWith<IllegalArgumentException> { env.languageVersion = 999 }
    }

    @Test
    fun activationNamesRoundTrip() {
        val env = CompilerEnvirons()
        env.activationNames = setOf("eval", "with")
        assertEquals(setOf("eval", "with"), env.activationNames)
    }

    @Test
    fun warningAsErrorDrivesReportWarningAsError() {
        val env = CompilerEnvirons()
        assertFalse(env.reportWarningAsError())
        env.warningAsError = true
        assertTrue(env.reportWarningAsError())
    }
}
