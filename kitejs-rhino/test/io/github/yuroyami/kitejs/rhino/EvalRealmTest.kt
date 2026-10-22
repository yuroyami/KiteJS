/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

class EvalRealmTest {
    private fun check(expected: String, source: String) {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val caller = cx.initStandardObjects()
            val foreign = cx.initStandardObjects()
            cx.evaluateString(caller, "var marker='caller';", "caller.js", 1, null)
            cx.evaluateString(foreign, "var marker='foreign';", "foreign.js", 1, null)
            caller.put("foreignEval", caller, foreign.get("eval", foreign))
            caller.put("foreignGlobal", caller, foreign)
            assertEquals(expected, ScriptRuntime.toString(cx.evaluateString(caller, source, "eval-realms.js", 1, null)))
        } finally {
            Context.exit()
        }
    }

    @Test
    fun aForeignIntrinsicBoundToEvalIsIndirect() = check("foreign", """
        (function(eval) { var marker='local'; return eval('marker') })(foreignEval);
    """)

    @Test
    fun theCallerIntrinsicBoundToEvalIsDirect() = check("local", """
        (function(eval) { var marker='local'; return eval('marker') })(eval);
    """)

    @Test
    fun foreignEvalDeclaresIntoItsOwnGlobal() = check("undefined,number,7", """
        (function() { 'use strict'; foreignEval('var added=7'); })();
        [typeof added, typeof foreignGlobal.added, foreignGlobal.added].join();
    """)

    @Test
    fun strictForeignEvalKeepsItsDeclarationsPrivate() = check("undefined,undefined,undefined", """
        (function(eval) { eval("'use strict'; var added=7; let lexical=8;"); })(foreignEval);
        [typeof added, typeof foreignGlobal.added, typeof foreignGlobal.lexical].join();
    """)

    @Test
    fun optionalEvalUsesTheIntrinsicRealm() = check("caller,foreign", """
        (function() { var marker='local'; return [eval?.('marker'), foreignEval?.('marker')].join() })();
    """)

    @Test
    fun aWithBindingDoesNotMakeForeignEvalDirect() = check("local,foreign", """
        (function() {
            var marker='local', one, two;
            with ({eval:eval}) { one=eval('marker') }
            with ({eval:foreignEval}) { two=eval('marker') }
            return [one,two].join();
        })();
    """)

    @Test
    fun globalDeclarationChecksRunBeforeCreatingAnyBindings() = check("false,false,false,false", """
        // EvalDeclarationInstantiation validates all var/function names before creating bindings.
        // V8 currently leaves partial global declarations in these two cases.
        try { (0,eval)('var beforeVar; function NaN(){} var afterVar;') } catch(e) {}
        try { (0,eval)('function beforeFn(){} function NaN(){} function afterFn(){}') } catch(e) {}
        ['beforeVar','afterVar','beforeFn','afterFn'].map(k => Object.prototype.hasOwnProperty.call(globalThis,k)).join();
    """)
}
