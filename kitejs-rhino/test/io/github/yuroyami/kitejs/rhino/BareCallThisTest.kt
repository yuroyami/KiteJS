/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** OrdinaryCallBindThis and callback receivers, with fixed Node 26.10 controls. */
class BareCallThisTest {
    private fun withContext(block: (Context) -> Unit) {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            block(cx)
        } finally {
            Context.exit()
        }
    }

    private fun evaluate(cx: Context, scope: Scriptable, source: String): Any? =
        cx.evaluateString(scope, source, "bare-call-this.js", 1, null)

    private fun check(expected: String, source: String) = withContext { cx ->
        assertEquals(expected, ScriptRuntime.toString(evaluate(cx, cx.initStandardObjects(), source)))
    }

    @Test
    fun strictBareFunctionsReceiveUndefinedInEveryCaller() = check("true,true,true,true", """
        function f(){'use strict';return this===undefined}
        [f(),(function(){return f()})(),(function(){'use strict';return f()})(),
         (function(){'use strict';function g(){return this===undefined}return g()})()].join();
    """)

    @Test
    fun sloppyFunctionsReceiveTheirGlobalInEveryCaller() = check("true,true,true", """
        function f(){return this===globalThis}
        [f(),(function(){'use strict';return f()})(),
         Function('"use strict"; return Function("return this")()===globalThis')()].join();
    """)

    @Test
    fun valueCallsAndPropertyCallsHaveDifferentReceivers() = check("true,true,true,true,true,true", """
        var o={m:function(){'use strict';return this}},f=o.m;
        [o.m()===o,o['m']()===o,(o.m)()===o,f()===undefined,
         (0,o.m)()===undefined,(true?o.m:f)()===undefined].join();
    """)

    @Test
    fun foldedConditionalAndLogicalResultsStayValues() = check("true,true,true,true,true,true,true", """
        var o={m:function(){'use strict';return this===undefined}};
        var a,b;with(o){a=(true?m:null)();b=(false||m)()}
        [(false?null:o.m)(),(true&&o.m)(),(false||o.m)(),
         (true?o['m']:null)(),(true?o?.m:null)(),a,b].join();
    """)

    @Test
    fun foldedEvalCallsRemainIndirect() = check("true,true,true", """
        var marker='global';function f(){var marker='local';return [
          (true?eval:null)('marker')==='global',(true&&eval)('marker')==='global',
          (false||eval)('marker')==='global'].join()}f();
    """)

    @Test
    fun localActivationLookupsDoNotBecomeReceivers() = check("true,true,true", """
        function outer(){var local=function(){'use strict';return this===undefined};return local()}
        var global=function(){'use strict';return this===undefined};
        [outer(),global(),(function(f){return f()})(global)].join();
    """)

    @Test
    fun withEnvironmentCallsKeepTheBindingObject() = check("true,true", """
        var o={m:function(){'use strict';return this===o}};
        var first,second;with(o){first=m();second=m?.()}
        [first,second].join();
    """)

    @Test
    fun optionalBareCallsKeepUndefined() = check("true,true,true", """
        var f=function(){'use strict';return this===undefined};var o={f:f};
        [f?.(),(0,o.f)?.(),o.f?.()===false].join();
    """)

    @Test
    fun arrowsKeepTheirLexicalReceiver() = check("true,true,true", """
        var o={m:function(){return ()=>this}};
        [o.m()()===o,(function(){'use strict';return ()=>this})()()===undefined,
         (function(){return ()=>this})()()===globalThis].join();
    """)

    @Test
    fun strictGeneratorsBindWhenCalled() = check("true,true,true", """
        function* f(){'use strict';yield this}var o={f:f};var it=f();
        [it.next().value===undefined,o.f().next().value===o,
         f.call(null).next().value===null].join();
    """)

    @Test
    fun callApplyAndBindKeepNullAndUndefinedForStrictFunctions() = check("true,true,true,true,true,true", """
        function f(){'use strict';return this}
        [f.call()===undefined,f.call(undefined)===undefined,f.call(null)===null,
         f.apply(null,[])===null,f.bind(null)()===null,f.bind(undefined)()===undefined].join();
    """)

    @Test
    fun callApplyAndBindUseGlobalsForSloppyFunctions() = check("true,true,true,true", """
        function f(){return this===globalThis}
        [f.call(null),f.apply(undefined,[]),f.bind(null)(),f.bind(undefined)()].join();
    """)

    @Test
    fun arrayIterationPassesMissingNullAndObjectReceivers() = check("true", """
        var methods=['every','filter','find','findIndex','findLast','findLastIndex','forEach','map','some','flatMap'],ok=true,o={};
        methods.forEach(function(m){[undefined,null,o].forEach(function(arg){
          var seen;[1][m](function(){'use strict';seen=this;return true},arg);ok=ok&&seen===arg;
        });var seen;[1][m](function(){'use strict';seen=this;return true});ok=ok&&seen===undefined});String(ok);
    """)

    @Test
    fun typedArrayIterationUsesTheSameReceiverContract() = check("true", """
        var methods=['every','filter','find','findIndex','findLast','findLastIndex','forEach','map','some'],ok=true,o={};
        methods.forEach(function(m){[undefined,null,o].forEach(function(arg){
          var seen;new Uint8Array([1])[m](function(){'use strict';seen=this;return 1},arg);ok=ok&&seen===arg;
        });var seen;new Uint8Array([1])[m](function(){'use strict';seen=this;return 1});ok=ok&&seen===undefined});String(ok);
    """)

    @Test
    fun reductionsPassUndefinedRegardlessOfTheirCaller() = check("true,true,true,true", """
        [ [1,2].reduce(function(){'use strict';return this===undefined}),
          [1,2].reduceRight(function(){'use strict';return this===undefined}),
          new Uint8Array([1,2]).reduce(function(){'use strict';return this===undefined}),
          new Uint8Array([1,2]).reduceRight(function(){'use strict';return this===undefined}) ].join();
    """)

    @Test
    fun mapAndSetCallbacksKeepMissingNullAndObjectReceivers() = check("true", """
        var ok=true,o={};[new Map([[1,2]]),new Set([1])].forEach(function(c){
          [undefined,null,o].forEach(function(arg){var seen;c.forEach(function(){'use strict';seen=this},arg);ok=ok&&seen===arg});
          var seen;c.forEach(function(){'use strict';seen=this});ok=ok&&seen===undefined;
          (function(){'use strict';c.forEach(Function('return this===globalThis ? 1 : (()=>{throw 1})()'))})();
        });String(ok);
    """)

    @Test
    fun promiseExecutorsUseTheirOwnStrictness() = check("true,true", """
        var a,b;new Promise(function(resolve){'use strict';a=this===undefined;resolve()});
        (function(){'use strict';new Promise(Function('resolve','b=this===globalThis;resolve()'))})();[a,b].join();
    """)

    @Test
    fun arrayAndTypedArrayFromPassTheSuppliedReceiver() = check("true", """
        var ok=true,o={};[Array,Uint8Array].forEach(function(C){[undefined,null,o].forEach(function(arg){
          var seen;C.from([1],function(v){'use strict';seen=this;return v},arg);ok=ok&&seen===arg;
        })});String(ok);
    """)

    @Test
    fun stringAndRegExpReplacementCallbacksReceiveUndefined() = check("true,true,true,true", """
        function f(){'use strict';return this===undefined?'yes':'no'}
        ['x'.replace('x',f)==='yes','x'.replace(/x/,f)==='yes',
         'xx'.replaceAll('x',f)==='yesyes','xx'.replaceAll(/x/g,f)==='yesyes'].join();
    """)

    @Test
    fun sortComparatorsReceiveUndefined() = check("true,true", """
        var a,b;[2,1].sort(function(x,y){'use strict';a=this===undefined;return x-y});
        new Uint8Array([2,1]).sort(function(x,y){'use strict';b=this===undefined;return x-y});[a,b].join();
    """)

    @Test
    fun directHostCallsAndForeignCallsUseTheCalleeRealm() = withContext { cx ->
        val first = cx.initStandardObjects()
        val second = cx.initStandardObjects()
        val sloppy = evaluate(cx, second, "(function(){return this})") as JSFunction
        val strict = evaluate(cx, second, "(function(){'use strict';return this})") as JSFunction
        first.put("sloppy", first, sloppy)
        first.put("strict", first, strict)
        first.put("foreignGlobal", first, second)
        assertEquals(true, evaluate(cx, first, """
            sloppy()===foreignGlobal && strict()===undefined &&
            sloppy.call(null)===foreignGlobal && sloppy.bind(null)()===foreignGlobal &&
            (function(){'use strict';return sloppy()===foreignGlobal})()
        """))
        assertSame(second, sloppy.call(cx, first, null, ScriptRuntime.emptyArgs))
        assertSame(second, sloppy.call(cx, first, Undefined.SCRIPTABLE_UNDEFINED, ScriptRuntime.emptyArgs))
        assertEquals(null, strict.call(cx, first, null, ScriptRuntime.emptyArgs))
        assertEquals(true, Undefined.isUndefined(strict.call(cx, first, Undefined.SCRIPTABLE_UNDEFINED, ScriptRuntime.emptyArgs)))
    }

    @Test
    fun foreignCallbacksUseTheCalleeGlobal() = withContext { cx ->
        val first = cx.initStandardObjects()
        val second = cx.initStandardObjects()
        first.put("foreign", first, evaluate(cx, second, "(function(){if(this!==globalThis)throw 1;return true})"))
        assertEquals(true, evaluate(cx, first, """
            var ok=[1].map(foreign)[0];new Set([1]).forEach(foreign);new Map([[1,2]]).forEach(foreign);
            ok && Array.from([1],foreign)[0] && Uint8Array.from([1],foreign)[0]===1;
        """))
    }

    @Test
    fun asyncFunctionsAndPromiseHandlersBindAfterQueueing() = withContext { cx ->
        val scope = cx.initStandardObjects()
        evaluate(cx, scope, """
            var asyncThis,handlerThis;
            async function f(){'use strict';return this===undefined}
            f().then(function(v){'use strict';asyncThis=v;handlerThis=this===undefined});
        """)
        cx.processMicrotasks()
        assertEquals(true, evaluate(cx, scope, "asyncThis && handlerThis"))
    }

    @Test
    fun stackCaptureUsesTheBuiltinRealmWithoutAReceiver() = check("string,string,string", """
        var capture=Error.captureStackTrace;Error=function(){};var a={},b={},c={};
        capture(a);capture.call(null,b);capture.call(undefined,c);[typeof a.stack,typeof b.stack,typeof c.stack].join();
    """)

    @Test
    fun explicitlySelectedLegacyModeKeepsTheOldNullReceiverRule() = withContext { cx ->
        cx.languageVersion = Context.VERSION_1_7
        val scope = cx.initStandardObjects()
        assertEquals(true, evaluate(cx, scope, "function f(){'use strict';return this===globalThis}f()&&f.call(null)"))
    }
}
