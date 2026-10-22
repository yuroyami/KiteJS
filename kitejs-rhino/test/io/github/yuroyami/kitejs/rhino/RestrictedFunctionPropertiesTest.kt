/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import io.github.yuroyami.kitejs.api.KiteJs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RestrictedFunctionPropertiesTest {
    private fun withContext(version: Int = Context.VERSION_ES6, block: (Context) -> Unit) {
        val cx = Context.enter()
        try {
            cx.languageVersion = version
            block(cx)
        } finally {
            Context.exit()
        }
    }

    private fun evaluate(cx: Context, scope: Scriptable, source: String): Any? =
        cx.evaluateString(scope, source, "restricted-functions.js", 1, null)

    private fun check(expected: String, source: String) = withContext { cx ->
        assertEquals(expected, ScriptRuntime.toString(evaluate(cx, cx.initStandardObjects(), source)))
    }

    @Test
    fun prototypeAccessorsShareTheRealmThrower() = check("true,true,true,false,true,false", """
        var a = Object.getOwnPropertyDescriptor(Function.prototype, 'arguments');
        var c = Object.getOwnPropertyDescriptor(Function.prototype, 'caller');
        [a.get === a.set, a.get === c.get, c.get === c.set, a.enumerable,
         a.configurable, Object.prototype.hasOwnProperty.call(Function.prototype, 'arity')].join(',');
    """)

    @Test
    fun throwerHasTheRequiredShape() = check("0,,false,true,false,false,false,false,length,name", """
        var t = Object.getOwnPropertyDescriptor(Function.prototype, 'caller').get;
        var l = Object.getOwnPropertyDescriptor(t, 'length');
        var n = Object.getOwnPropertyDescriptor(t, 'name');
        [t.length, t.name, Object.isExtensible(t), Object.getPrototypeOf(t) === Function.prototype,
         l.writable, l.configurable, n.writable, n.configurable, Object.getOwnPropertyNames(t).join(',')].join(',');
    """)

    @Test
    fun modernFunctionsInheritBothRestrictions() = check("true", """
        var kinds = [function(){'use strict'}, ()=>1, {m(){}}.m, function*(){}, async function(){},
                     class C {}, Math.sin, (function(){}).bind(null), Function.prototype];
        kinds.every(function(f) {
            return ['caller','arguments'].every(function(p) {
                var read = false, write = false;
                try { f[p]; } catch (e) { read = e instanceof TypeError; }
                try { f[p] = 1; } catch (e) { write = e instanceof TypeError; }
                return read && write;
            });
        });
    """)

    @Test
    fun modernFunctionsHaveNoOwnLegacyProperties() = check("true", """
        var kinds = [function(){'use strict'}, ()=>1, {m(){}}.m, function*(){}, async function(){},
                     class C {}, Math.sin, (function(){}).bind(null)];
        kinds.every(function(f) {
            return ['caller','arguments','arity'].every(function(p) {
                return !Object.prototype.hasOwnProperty.call(f, p);
            });
        });
    """)

    @Test
    fun sloppyArrowsAndMethodsOnlyHaveStandardOwnNames() = check("length,name|length,name", """
        Object.getOwnPropertyNames(()=>1).join(',') + '|' + Object.getOwnPropertyNames({m(){}}.m).join(',');
    """)

    @Test
    fun sloppyOrdinaryFunctionsRetainLegacyArgumentsAndNullCaller() = check("true,true,2,42", """
        function f(a,b) { return f.arguments[0]; }
        [f.caller === null, f.arguments === null, f.arity, f(42)].join(',');
    """)

    @Test
    fun functionCodeRatherThanTheCreatingCallerDeterminesItsProperties() = check("true,false", """
        'use strict';
        var sloppy = Function('return 1');
        var strict = Function('"use strict"; return 1');
        [Object.prototype.hasOwnProperty.call(sloppy, 'arguments'),
         Object.prototype.hasOwnProperty.call(strict, 'arguments')].join(',');
    """)

    @Test
    fun strictArgumentsCalleeUsesThePrototypeThrower() = check("true,true,false,false", """
        var args = (function(){'use strict';return arguments})();
        var d = Object.getOwnPropertyDescriptor(args, 'callee');
        var t = Object.getOwnPropertyDescriptor(Function.prototype, 'caller').get;
        [d.get === t, d.set === t, d.configurable, d.enumerable].join(',');
    """)

    @Test
    fun nonSimpleParametersUseTheSameUnmappedArgumentsThrower() = check("true", """
        var t = Object.getOwnPropertyDescriptor(Function.prototype, 'caller').get;
        [function(a=0){return arguments}, function(...a){return arguments},
         function({a}){return arguments}].every(function(f) {
            var d = Object.getOwnPropertyDescriptor(f({a:1}), 'callee');
            return d.get === t && d.set === t && !d.configurable && !d.enumerable;
        });
    """)

    @Test
    fun nonSimpleParametersDoNotAliasArgumentElements() = check("1,1,1", """
        [(function(a,b=0){a=4;return arguments[0]})(1),
         (function(a,...b){a=4;return arguments[0]})(1),
         (function(a,{b}){a=4;return arguments[0]})(1,{b:2})].join(',');
    """)

    @Test
    fun realmsInOneContextHaveDistinctThrowers() = withContext { cx ->
        val first = cx.initStandardObjects()
        val second = cx.initStandardObjects()
        val one = ScriptRuntime.typeErrorThrower(first)
        val two = ScriptRuntime.typeErrorThrower(second)
        assertNotSame(one, two)
        assertSame(one, ScriptRuntime.typeErrorThrower(first))
        assertSame(ScriptableObject.getFunctionPrototype(first), one.prototype)
        assertSame(ScriptableObject.getFunctionPrototype(second), two.prototype)
    }

    @Test
    fun foreignCallsUseTheFunctionsRealmForStrictArguments() = withContext { cx ->
        val first = cx.initStandardObjects()
        val second = cx.initStandardObjects()
        val foreign = evaluate(cx, second, "(function(){'use strict';return arguments})")
        first.put("foreign", first, foreign)
        first.put("foreignThrower", first, ScriptRuntime.typeErrorThrower(second))
        assertEquals(true, evaluate(cx, first,
            "Object.getOwnPropertyDescriptor(foreign(), 'callee').get === foreignThrower"))
    }

    @Test
    fun bindingAForeignFunctionKeepsItsPrototypeAndRestrictions() = withContext { cx ->
        val first = cx.initStandardObjects()
        val second = cx.initStandardObjects()
        first.put("foreign", first, evaluate(cx, second, "(function(){'use strict'})"))
        first.put("foreignPrototype", first, ScriptableObject.getFunctionPrototype(second))
        assertEquals(true, evaluate(cx, first, """
            var bound = Function.prototype.bind.call(foreign, null);
            Object.getPrototypeOf(bound) === foreignPrototype && !bound.hasOwnProperty('caller');
        """))
    }

    @Test
    fun publicPropertyMutationsDoNotReplaceTheIntrinsic() = check("true", """
        var t = Object.getOwnPropertyDescriptor(Function.prototype, 'caller').get;
        Object.defineProperty(Function.prototype, 'caller', {get: function(){return 1}});
        delete Function.prototype.arguments;
        Function = function replacement(){};
        var args = (function(){'use strict';return arguments})();
        Object.getOwnPropertyDescriptor(args, 'callee').get === t;
    """)

    @Test
    fun aRealmKeepsItsThrowerAcrossContexts() {
        var realm: ScriptableObject? = null
        var thrower: BaseFunction? = null
        withContext { cx ->
            realm = cx.initStandardObjects()
            thrower = ScriptRuntime.typeErrorThrower(realm!!)
        }
        withContext { cx ->
            val actual = evaluate(cx, realm!!,
                "Object.getOwnPropertyDescriptor((function(){'use strict';return arguments})(),'callee').get")
            assertSame(thrower, actual)
        }
    }

    @Test
    fun explicitLegacyModeRetainsItsPrototypeLayout() = withContext(Context.VERSION_1_8) { cx ->
        assertEquals("true,true,undefined", ScriptRuntime.toString(evaluate(cx, cx.initStandardObjects(), """
            [Function.prototype.hasOwnProperty('arity'), Function.prototype.hasOwnProperty('arguments'),
             typeof Function.prototype.caller].join(',');
        """)))
    }

    @Test
    fun asmExportsKeepTheirSourceFunctionsStrictness() {
        for (enabled in listOf(false, true)) for (strict in listOf(false, true)) {
            KiteJs(Rhino) { asmJs = enabled }.use { js ->
                js.evaluate((if (strict) "'use strict';" else "") + """
                    var f = (function() {
                        'use asm';
                        function exported(x) { x=x|0; return x|0; }
                        return exported;
                    })();
                """)
                if (enabled) assertTrue(js.asmReports.single().linked)
                val actual = js.evaluate("""
                    ['arity','arguments','caller'].map(function(p) {
                        try { return String(f[p]); } catch(e) { return e.name; }
                    }).join(',');
                """).asString()
                assertEquals(if (strict) "undefined,TypeError,TypeError" else "1,null,null", actual,
                    "asmJs=$enabled strict=$strict")
            }
        }
    }
}
