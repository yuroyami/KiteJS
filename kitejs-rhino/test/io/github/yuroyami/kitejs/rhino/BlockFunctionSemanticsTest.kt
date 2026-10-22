/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

/** Fixed Node/V8 controls for lexical block functions and Annex B compatibility. */
class BlockFunctionSemanticsTest {
    private fun check(source: String, expected: String) = check(listOf(source), expected)

    private fun check(sources: List<String>, expected: String) {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            var value: Any? = Undefined.instance
            for (source in sources) value = cx.evaluateString(scope, source, "block-functions.js", 1, null)
            assertEquals(expected, ScriptRuntime.toString(value), sources.joinToString("\n"))
        } finally {
            Context.exit()
        }
    }
    @Test
    fun blockInitialization() {
        check("var r=[];{r.push(typeof f);label:function f(){return 2};r.push(f())}r.push(f());r.join()", "function,2,2")
        check("var r=[];{let f=1;{label:function f(){}}r.push(typeof f)}r.push(typeof f);r.join()", "number,undefined")
        check("var r=[];{r.push(f());label:function f(){return 2}}r.join()", "2")
        check("var r=[];{r.push(typeof f);function f(){return 7}r.push(f())}r.join()", "function,7")
        check("'use strict';var r=[];{r.push(typeof f);function f(){return 7}r.push(f())}r.push(typeof f);r.join()", "function,7,undefined")
        check("var f=1;var r=[];{r.push(typeof f);function f(){}r.push(typeof f)}r.push(typeof f);r.join()", "function,function,function")
        check("var r=[];{function f(){return 1}r.push(f());function f(){return 2}r.push(f())}r.push(f());r.join()", "2,2,2")
        check("var r=[];{r.push(f());function f(){return 1};{r.push(f());function f(){return 2}}r.push(f())}r.push(f());r.join()", "1,2,1,2")
        check("var saved;{function f(){return f}saved=f}String(saved()===saved)", "true")
        check("var saved;{function f(){return f}saved=f;f=3}String(saved())", "3")
        check("var r=[];{function f(){}r.push(delete f,typeof f)}r.push(typeof f);r.join()", "false,function,function")
        check("var r=[];{function* g(){yield 2}r.push(typeof g,g().next().value)}r.push(typeof g);r.join()", "function,2,undefined")
        check("var r=[];{async function a(){return 2}r.push(typeof a,a() instanceof Promise)}r.push(typeof a);r.join()", "function,true,undefined")
        check("String((function(){'use strict';{function f(){}}return typeof f})())", "undefined")
        check("String(({m(){ {function f(){}}return typeof f}}).m())", "function")
    }

    @Test
    fun annexTiming() {
        check("var r=[];r.push(typeof f);if(false){function f(){}}r.push(typeof f,'f' in globalThis);r.join()", "undefined,undefined,true")
        check("var f=1;var r=[];r.push(f);{r.push(typeof f);f=3;function f(){return 2};r.push(f)}r.push(f);r.join()", "1,function,3,3")
        check("var f=1;var r=[];{r.push(typeof f);function f(){return 2}}r.push(f());r.join()", "function,2")
        check("var f=1;label:{function f(){return 2}break label}String(f())", "2")
        check("var f=1;label:{break label;function f(){return 2}}String(f)", "1")
        check("var f=1;try{throw 2;{function f(){return 3}}}catch(e){}String(f)", "1")
        check("var f=1;try{{throw 2;function f(){return 3}}}catch(e){}String(f)", "1")
        check("var f=1;if(false)function f(){return 2};String(f)", "1")
        check("var f=1;if(true)function f(){return 2};String(f())", "2")
        check("var f=1;if(false)function f(){return 2}else function f(){return 3};String(f())", "3")
        check("var r=[];{let f=1;if(true)function f(){}r.push(f)}r.push(typeof f);r.join()", "1,undefined")
        check("var f=1;{function f(){'use strict';return 2}}String(f())", "2")
        check("var f=1;{function f(){return 2}f=3}String(f())", "2")
        check("{function fresh(){}}JSON.stringify(Object.getOwnPropertyDescriptor(globalThis,'fresh'),function(k,v){return typeof v==='function'?'function':v})", "{\"value\":\"function\",\"writable\":true,\"enumerable\":true,\"configurable\":false}")
    }

    @Test
    fun lexicalEligibility() {
        check("var r=[];{let f=1;{function f(){}r.push(typeof f)}r.push(typeof f)}r.push(typeof f);r.join()", "function,number,undefined")
        check("var r=[];{const f=1;{function f(){}r.push(typeof f)}r.push(f)}r.push(typeof f);r.join()", "function,1,undefined")
        check("var r=[];{class f{};{function f(){}r.push(typeof f)}r.push(typeof f)}r.push(typeof f);r.join()", "function,function,undefined")
        check("var r=[];{ {function f(){}r.push(typeof f)}let f=1;r.push(f)}r.push(typeof f);r.join()", "function,1,undefined")
        check("{function f(){}}let f=1;String(f)", "1")
        check("(function(){{function f(){}}let f=1;return String(f)})()", "1")
        check("var f=1;{let f=2;{function f(){return 3}}}String(f)", "1")
        check("var f=1;{function f(){return 2}{function f(){return 3}}}String(f())", "3")
        check("var f=1;{function f(){return 2}{let f=3;{function f(){return 4}}}}String(f())", "2")
        check("(function(){var f=1;try{throw 2}catch(f){{function f(){return 3}}}return typeof f})()", "function")
        check("(function(){var f=1;try{throw 2}catch(f){{function f(){return 3}}return f}return 'unreachable'})()", "2")
        check("(function(){var f=1;{const f=2;if(true)function f(){return 3}}return String(f)})()", "1")
    }

    @Test
    fun functionParameters() {
        check("String((function(f){{function f(){return 2}}return f})(1))", "1")
        check("String((function(f=1){{function f(){return 2}}return f})())", "1")
        check("String((function({f}){{function f(){return 2}}return f})({f:1}))", "1")
        check("String((function(...f){{function f(){return 2}}return f.join()})(1,2))", "1,2")
        check("String((function(f){var f;{function f(){return 2}}return f})(1))", "1")
        check("String((function(f){{function f(){return 2}}return arguments[0]})(1))", "1")
        check("String((function(){{function arguments(){}}return typeof arguments})())", "function")
        check("String((function f(){if(false){function f(){}}return typeof f})())", "undefined")
        check("String((function f(){{function f(){return 2}}return f()})())", "2")
        check("String((function(){if(false){function local(){}}return typeof local})())", "undefined")
        check("String((function(){'use strict';{function local(){}}return typeof local})())", "undefined")
        check("String((function(x=()=>typeof f){var before=x();{function f(){}}return before+':'+typeof f})())", "undefined:function")
    }

    @Test
    fun loops() {
        check("var r=[];for(let i=0;i<3;i++){function f(){return i}r.push(f)}r.map(function(f){return f()}).join()", "0,1,2")
        check("'use strict';var r=[];for(let i=0;i<3;i++){function f(){return i}r.push(f)}r.map(function(f){return f()}).join()+':'+typeof f", "0,1,2:undefined")
        check("var r=[];for(var i=0;i<3;i++){function f(){return i}r.push(f)}r.map(function(f){return f()}).join()", "3,3,3")
        check("var r=[];for(let i=0;i<3;i++){function f(){return f}r.push(f)}String(r[0]!==r[1])+':'+r.map(function(f){return f()===f}).join()", "true:true,true,true")
        check("var r=[];for(let i=0;i<3;i++){function f(){return i}r.push(f);if(i===1)continue}r.map(function(f){return f()}).join()", "0,1,2")
        check("var r=[];for(let i=0;i<3;i++){function f(){return i}r.push(f);if(i===1)break}r.map(function(f){return f()}).join()", "0,1")
        check("var r=[];for(let i=0;i<2;i++){function f(){return i}r.push(f);i++}r.map(function(f){return f()}).join()", "1")
        check("var r=[];for(let i=0,j=4;i<2;i++,j++){function f(){return i+':'+j}r.push(f)}r.map(function(f){return f()}).join()", "0:4,1:5")
        check("var r=[];for(let i of [1,2]){function f(){return i}r.push(f)}r.map(function(f){return f()}).join()", "1,2")
        check("var r=[];for(const i of [1,2]){function f(){return i}r.push(f)}r.map(function(f){return f()}).join()", "1,2")
        check("var r=[];for(let i in {a:1,b:2}){function f(){return i}r.push(f)}r.map(function(f){return f()}).join()", "a,b")
        check("var r=[];for(let i=0;i<2;i++){function* f(){yield i}r.push(f)}r.map(function(f){return f().next().value}).join()+':'+typeof f", "0,1:undefined")
        check("var r=[];for(let i=0;i<2;i++){try{function f(){return i}r.push(f);continue}finally{r.push(function(){return i})}}r.map(function(f){return f()}).join()", "0,0,1,1")
        check("var r=[];outer:for(let i=0;i<2;i++){for(let j=0;j<2;j++){function f(){return i+':'+j}r.push(f);continue outer}}r.map(function(f){return f()}).join()", "0:0,1:0")
        check("var r=[];for(let i=0;i<2;i++,r.push(function(){return i})){function f(){return i}r.push(f)}r.map(function(f){return f()}).join()", "0,1,1,2")
        check("var r=[];for(let i=0;(r.push(function(){return i}),i<2);i++){function f(){return i}r.push(f)}r.map(function(f){return f()}).join()", "0,0,1,1,2")
    }

    @Test
    fun switchScopes() {
        check("const f=1;switch(f){case 1:function f(){return 2}}String(f)", "1")
        check("let f=1;switch(f){case 1:const f=2;String(f)}", "2")
        check("const f=1;switch(f){case 1:const f=2;String(f)}", "2")
        check("var r=[];switch(1){case 1:const f=2;{function f(){return 3};r.push(f())};r.push(f)}r.push(typeof f);r.join()", "3,2,undefined")
        check("var f=1;switch(typeof f){case 'number':function f(){return 2}}String(f())", "2")
        check("var r=[];switch(1){case f():r.push('matched');break;case 2:function f(){return 1}}r.push(typeof f);r.join()", "matched,undefined")
        check("var f=1;switch(1){case 1:break;case 2:function f(){return 2}}String(f)", "1")
        check("var r=[];switch(1){case 1:r.push(f());break;case 2:function f(){return 2}}r.join()+':'+typeof f", "2:undefined")
        check("var r=[];switch(1){case 1:function f(){return 1};r.push(f());break;case 2:function f(){return 2}}r.push(f());r.join()", "2,2")
        check("'use strict';var r=[];switch(1){case 1:r.push(f());function f(){return 2}}r.push(typeof f);r.join()", "2,undefined")
        check("var saved;switch(1){case 1:function f(){return f};saved=f}String(saved()===saved)", "true")
        check("var f=1;switch(f){default:function f(){return 2};f=3}String(f())", "2")
        check("var f=1;switch(0){case 1:function f(){return 2}}String(f)", "1")
        check("let f=1;switch(f){case 1:function f(){return 2}}String(f)", "1")
    }

    @Test
    fun evalScopes() {
        check("var f=1;eval('{function f(){return 2}}');String(f())", "2")
        check("var f=1;eval('if(false){function f(){return 2}}');String(f)", "1")
        check("let f=1;eval('{function f(){return 2}}');String(f)", "1")
        check("(function(){let f=1;eval('{function f(){return 2}}');return String(f)})()", "1")
        check("(function(f){eval('{function f(){return 2}}');return String(f())})(1)", "2")
        check("var f=1;eval(\"'use strict';{function f(){return 2}}\");String(f)", "1")
        check("var f=1;{let f=2;eval('{function f(){return 3}}')}String(f)", "1")
        check("var saved;eval('{function f(){return f};saved=f}');String(saved()===saved)", "true")
        check("var result=eval('{function f(){};typeof f}');[result,typeof f,delete globalThis.f].join()", "function,function,true")
        check("Object.defineProperty(globalThis,'f',{value:1,writable:false});eval('{function f(){return 2}}');String(f)", "1")
        check("(function(){var f=1;with({f:2}){eval('{function f(){return 3}}')}return String(f())})()", "3")
    }

    @Test
    fun functionObjects() {
        check("{function f(a,b){return a+b}}[f.name,f.length,f(2,3),new f() instanceof f].join()", "f,2,5,true")
        check("'use strict';var saved;{function f(){return this};saved=f}String(saved()===undefined)", "true")
        check("var saved;{function f(){return this};saved=f}String(saved()===globalThis)", "true")
        check("var saved;{function f(){'use strict';return this};saved=f}String(saved()===undefined)", "true")
        check("var saved;{function f(){return typeof f};saved=f}String(saved())", "function")
        check("var saved;{function f(){f=4;return f};saved=f}String(saved())", "4")
        check("var saved;{function f(){var f=5;return f};saved=f}String(saved())", "5")
    }

    @Test
    fun declarationErrors() {
        check("try{eval(\"switch(0){case 1:const f=0;default:function f(){}}\")}catch(e){e.name}", "SyntaxError")
        check("try{eval(\"switch(0){case 1:const f=0;default:function* f(){}}\")}catch(e){e.name}", "SyntaxError")
        check("try{eval(\"switch(0){case 1:const f=0;default:async function f(){}}\")}catch(e){e.name}", "SyntaxError")
        check("try{eval(\"'use strict';{label:function f(){}}\")}catch(e){e.name}", "SyntaxError")
        check("try{eval(\"'use strict';{function f(){}function f(){}}\")}catch(e){e.name}", "SyntaxError")
        check("try{eval(\"{let f;function f(){}}\")}catch(e){e.name}", "SyntaxError")
        check("try{eval(\"{function f(){}let f}\")}catch(e){e.name}", "SyntaxError")
        check("try{eval(\"{const f=1;function f(){}}\")}catch(e){e.name}", "SyntaxError")
        check("try{eval(\"{function f(){}var f}\")}catch(e){e.name}", "SyntaxError")
        check("try{eval(\"{function f(){}{var f}}\")}catch(e){e.name}", "SyntaxError")
        check("try{eval(\"{function* f(){}function f(){}}\")}catch(e){e.name}", "SyntaxError")
        check("try{eval(\"{async function f(){}function f(){}}\")}catch(e){e.name}", "SyntaxError")
        check("try{eval(\"'use strict';if(true)function f(){}\")}catch(e){e.name}", "SyntaxError")
        check("try{eval(\"if(true)function* f(){}\")}catch(e){e.name}", "SyntaxError")
        check("try{eval(\"while(false)function f(){}\")}catch(e){e.name}", "SyntaxError")
        check("try{eval(\"{function f(){}class f{}}\")}catch(e){e.name}", "SyntaxError")
    }

    @Test
    fun globalSequences() {
        check(listOf("Object.defineProperty(globalThis,'f',{value:1,writable:false})","{function f(){return 2}}String(f)"), "1")
    }

}
