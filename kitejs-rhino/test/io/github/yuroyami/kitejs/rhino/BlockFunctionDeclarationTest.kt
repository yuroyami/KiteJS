/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

class BlockFunctionDeclarationTest {
    private fun check(source: String, expected: String, version: Int = Context.VERSION_ES6) {
        val cx = Context.enter()
        try {
            cx.languageVersion = version
            val scope = cx.initStandardObjects()
            assertEquals(expected, ScriptRuntime.toString(cx.evaluateString(scope, source, "block-functions.js", 1, null)), source)
        } finally {
            Context.exit()
        }
    }

    @Test
    fun innerFunctionShadowsAnOuterLexicalBinding() {
        check("var r=[];{let f=1;{function f(){}r.push(typeof f)}r.push(typeof f)}r.push(typeof f);r.join()", "function,number,undefined")
    }

    @Test
    fun functionsCaptureEachLoopIteration() {
        check("var r=[];for(let i=0;i<2;i++){function k(){return i}r.push(k)}r.map(function(f){return f()}).join()", "0,1")
    }

    @Test
    fun strictBlocksDoNotCreateAnOuterBinding() {
        check("'use strict';{function m(){}}typeof m", "undefined")
    }

    @Test
    fun ifClauseHasItsOwnLexicalBlock() {
        check("{let f;if(true)function f(){}}'ok'", "ok")
    }

    @Test
    fun explicitLegacyVersionRetainsFunctionWideDeclarations() {
        check("'use strict';{function m(){}}typeof m", "function", Context.VERSION_1_8)
    }

    @Test
    fun nonExtensibleGlobalsSuppressAnnexBBindings() {
        // GlobalDeclarationInstantiation and EvalDeclarationInstantiation's Annex B steps
        // skip an optional binding when CanDeclareGlobalVar is false. Node 26 throws here.
        check("Object.preventExtensions(globalThis);eval('{function fresh(){}}');typeof fresh", "undefined")
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            cx.evaluateString(scope, "Object.preventExtensions(globalThis)", "block-functions.js", 1, null)
            assertEquals("undefined", ScriptRuntime.toString(cx.evaluateString(scope, "{function fresh(){}}typeof fresh", "block-functions.js", 1, null)))
        } finally {
            Context.exit()
        }
    }

    @Test
    fun earlierGlobalLexicalBindingsSuppressAnnexBCopies() {
        // GlobalDeclarationInstantiation checks HasLexicalDeclaration before creating an
        // Annex B var binding. V8's multi-script control instead rejects the declaration.
        for (declaration in listOf("let f=1", "const f=1")) {
            val cx = Context.enter()
            try {
                cx.languageVersion = Context.VERSION_ES6
                val scope = cx.initStandardObjects()
                cx.evaluateString(scope, declaration, "first-script.js", 1, null)
                assertEquals("1", ScriptRuntime.toString(cx.evaluateString(scope, "{function f(){return 2}}String(f)", "second-script.js", 1, null)))
                assertEquals("1", ScriptRuntime.toString(cx.evaluateString(scope, "eval('{function f(){return 3}}');String(f)", "third-script.js", 1, null)))
            } finally {
                Context.exit()
            }
        }
    }
}
