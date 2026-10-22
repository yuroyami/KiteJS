/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals

/** Eval environment isolation and declaration checks, with fixed Node 26.10 controls. */
class EvalEnvironmentTest {
    private data class Case(val name: String, val source: String, val expected: String)

    private fun check(cases: List<Case>) {
        val failures = mutableListOf<String>()
        for ((name, source, expected) in cases) KiteJs(Rhino).use { js ->
            val actual = try { js.evaluate(source).asString() }
            catch (e: JsSyntaxError) { "throws SyntaxError" }
            catch (e: JsError) { "throws " + e.name }
            if (actual != expected) failures += "$name: expected $expected, was $actual"
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun lexicalIsolation() = check(listOf(
        Case("let shadows outer binding", "(function(){let x=1;eval(\"let x=2\");return x})()", "1"),
        Case("let stays in eval", "(function(){eval(\"let x=2\");return typeof x})()", "undefined"),
        Case("let indirect stays in eval", "(0,eval)(\"let x=2\");typeof x", "undefined"),
        Case("let is visible inside eval", "eval(\"let x=2;x\")", "2"),
        Case("let repeated eval has fresh binding", "eval(\"let x=2\");eval(\"let x=2\");typeof x", "undefined"),
        Case("let shadows global NaN", "eval(\"let NaN=2;typeof NaN\")+\"|\"+isNaN(NaN)", "number|true"),
        Case("const shadows outer binding", "(function(){let x=1;eval(\"const x=2\");return x})()", "1"),
        Case("const stays in eval", "(function(){eval(\"const x=2\");return typeof x})()", "undefined"),
        Case("const indirect stays in eval", "(0,eval)(\"const x=2\");typeof x", "undefined"),
        Case("const is visible inside eval", "eval(\"const x=2;x\")", "2"),
        Case("const repeated eval has fresh binding", "eval(\"const x=2\");eval(\"const x=2\");typeof x", "undefined"),
        Case("const shadows global NaN", "eval(\"const NaN=2;typeof NaN\")+\"|\"+isNaN(NaN)", "number|true"),
        Case("class shadows outer binding", "(function(){let x=1;eval(\"class x{}\");return x})()", "1"),
        Case("class stays in eval", "(function(){eval(\"class x{}\");return typeof x})()", "undefined"),
        Case("class indirect stays in eval", "(0,eval)(\"class x{}\");typeof x", "undefined"),
        Case("class is visible inside eval", "eval(\"class x{};typeof x\")", "function"),
        Case("class repeated eval has fresh binding", "eval(\"class x{}\");eval(\"class x{}\");typeof x", "undefined"),
        Case("class shadows global NaN", "eval(\"class NaN{};typeof NaN\")+\"|\"+isNaN(NaN)", "function|true"),
    ))

    @Test
    fun variableEnvironments() = check(listOf(
        Case("direct=True, directive=False: var x=2", "(function(){var x=1;eval(\"var x=2\");return typeof x+\":\"+(typeof x===\"function\"?x():x)})()+\"|\"+typeof globalThis.x", "number:2|undefined"),
        Case("direct=True, directive=False: function x(){return 2}", "(function(){var x=1;eval(\"function x(){return 2}\");return typeof x+\":\"+(typeof x===\"function\"?x():x)})()+\"|\"+typeof globalThis.x", "function:2|undefined"),
        Case("direct=True, directive=True: var x=2", "(function(){var x=1;eval(\"'use strict';var x=2\");return typeof x+\":\"+(typeof x===\"function\"?x():x)})()+\"|\"+typeof globalThis.x", "number:1|undefined"),
        Case("direct=True, directive=True: function x(){return 2}", "(function(){var x=1;eval(\"'use strict';function x(){return 2}\");return typeof x+\":\"+(typeof x===\"function\"?x():x)})()+\"|\"+typeof globalThis.x", "number:1|undefined"),
        Case("strict caller, direct=True", "(function(){\"use strict\";eval(\"var x=2;function f(){}\");return typeof x+\",\"+typeof f})()+\"|\"+typeof globalThis.x+\",\"+typeof globalThis.f", "undefined,undefined|undefined,undefined"),
        Case("direct=False, directive=False: var x=2", "(function(){var x=1;(0,eval)(\"var x=2\");return typeof x+\":\"+(typeof x===\"function\"?x():x)})()+\"|\"+typeof globalThis.x", "number:1|number"),
        Case("direct=False, directive=False: function x(){return 2}", "(function(){var x=1;(0,eval)(\"function x(){return 2}\");return typeof x+\":\"+(typeof x===\"function\"?x():x)})()+\"|\"+typeof globalThis.x", "number:1|function"),
        Case("direct=False, directive=True: var x=2", "(function(){var x=1;(0,eval)(\"'use strict';var x=2\");return typeof x+\":\"+(typeof x===\"function\"?x():x)})()+\"|\"+typeof globalThis.x", "number:1|undefined"),
        Case("direct=False, directive=True: function x(){return 2}", "(function(){var x=1;(0,eval)(\"'use strict';function x(){return 2}\");return typeof x+\":\"+(typeof x===\"function\"?x():x)})()+\"|\"+typeof globalThis.x", "number:1|undefined"),
        Case("strict caller, direct=False", "(function(){\"use strict\";(0,eval)(\"var x=2;function f(){}\");return typeof x+\",\"+typeof f})()+\"|\"+typeof globalThis.x+\",\"+typeof globalThis.f", "number,function|number,function"),
        Case("global directive=False: var x=2", "eval(\"var x=2\");typeof x", "number"),
        Case("global directive=True: var x=2", "eval(\"'use strict';var x=2\");typeof x", "undefined"),
        Case("global directive=False: function x(){return 2}", "eval(\"function x(){return 2}\");typeof x", "function"),
        Case("global directive=True: function x(){return 2}", "eval(\"'use strict';function x(){return 2}\");typeof x", "undefined"),
        Case("sloppy eval reuses an existing binding without initializing it", "(function(){var x=7;eval(\"var x\");return x})()", "7"),
        Case("sloppy eval can add local variables", "(function(){eval(\"var x=7;function f(){return x}\");return x+\":\"+f()})()+\"|\"+typeof x+\",\"+typeof f", "7:7|undefined,undefined"),
        Case("eval var shadows an outer function variable", "(function(){var x=7;return (function(){eval(\"var x; x=9\");return x})()+\":\"+x})()", "9:7"),
        Case("strict eval var remains in returned closure", "eval(\"\\\"use strict\\\";var x=1;()=>x\")()+\"|\"+typeof x", "1|undefined"),
        Case("sloppy eval var is deletable", "(function(){eval(\"var x=1\");return delete x})()", "true"),
    ))

    @Test
    fun closuresAndNestedEval() = check(listOf(
        Case("let closure directive=False", "(function(){var x=1,f=eval(\"let x=2; (function(){return x})\");return f()+\":\"+x})()", "2:1"),
        Case("const closure directive=False", "(function(){var x=1,f=eval(\"const x=2; (function(){return x})\");return f()+\":\"+x})()", "2:1"),
        Case("function closure directive=False", "(function(){var x=1,f=eval(\"var x=2; function f(){return x};f\");return f()+\":\"+x})()", "2:2"),
        Case("let closure directive=True", "(function(){var x=1,f=eval(\"'use strict';let x=2; (function(){return x})\");return f()+\":\"+x})()", "2:1"),
        Case("const closure directive=True", "(function(){var x=1,f=eval(\"'use strict';const x=2; (function(){return x})\");return f()+\":\"+x})()", "2:1"),
        Case("function closure directive=True", "(function(){var x=1,f=eval(\"'use strict';var x=2; function f(){return x};f\");return f()+\":\"+x})()", "2:1"),
        Case("mutable eval lexical closure retains state", "var f=eval(\"let x=0;()=>++x\");f()+\":\"+f()+\"|\"+typeof x", "1:2|undefined"),
        Case("eval function captures eval lexical binding", "(function(){var x=1;eval(\"let y=2;function f(){return y}\");return f()+\":\"+x+\":\"+typeof y})()", "2:1:undefined"),
        Case("strict nested eval vars remain separate", "eval(\"\\\"use strict\\\";var x=1;eval(\\\"var x=2\\\");x\")", "1"),
        Case("sloppy nested eval shares variable environment", "(function(){eval(\"var x=1;eval(\\\"var x=2\\\")\");return x})()", "2"),
        Case("nested eval lexicals shadow each other", "eval(\"let x=1;eval(\\\"let x=2; x\\\")+\\\":\\\"+x\")", "2:1"),
        Case("nested eval var cannot cross eval let", "eval(\"let x=1;try{eval(\\\"var x\\\")}catch(e){e.name}\")", "SyntaxError"),
        Case("nested eval strict var may shadow eval let", "eval(\"let x=1;eval(\\\"\\\\\\\"use strict\\\\\\\";var x=2; x\\\")+\\\":\\\"+x\")", "2:1"),
        Case("returned eval closure can run direct eval", "var f=eval(\"let x=7;()=>eval(\\\"x\\\")\");f()", "7"),
        Case("two returned lexical environments are independent", "var a=eval(\"let x=0;()=>++x\"),b=eval(\"let x=10;()=>++x\");a()+\":\"+b()+\":\"+a()", "1:11:2"),
    ))

    @Test
    fun declarationConflicts() = check(listOf(
        Case("let function body: var x", "(function(){let x=1;eval(\"var x\")})()", "throws SyntaxError"),
        Case("let nested block: var x", "(function(){ {let x=1;{eval(\"var x\")}} })()", "throws SyntaxError"),
        Case("let global: var x", "let x=1;eval(\"var x\")", "throws SyntaxError"),
        Case("let function body: function x(){}", "(function(){let x=1;eval(\"function x(){}\")})()", "throws SyntaxError"),
        Case("let nested block: function x(){}", "(function(){ {let x=1;{eval(\"function x(){}\")}} })()", "throws SyntaxError"),
        Case("let global: function x(){}", "let x=1;eval(\"function x(){}\")", "throws SyntaxError"),
        Case("let can be shadowed by strict eval var", "(function(){let x=1;return eval(\"\\\"use strict\\\";var x=2;x\")})()", "2"),
        Case("const function body: var x", "(function(){const x=1;eval(\"var x\")})()", "throws SyntaxError"),
        Case("const nested block: var x", "(function(){ {const x=1;{eval(\"var x\")}} })()", "throws SyntaxError"),
        Case("const global: var x", "const x=1;eval(\"var x\")", "throws SyntaxError"),
        Case("const function body: function x(){}", "(function(){const x=1;eval(\"function x(){}\")})()", "throws SyntaxError"),
        Case("const nested block: function x(){}", "(function(){ {const x=1;{eval(\"function x(){}\")}} })()", "throws SyntaxError"),
        Case("const global: function x(){}", "const x=1;eval(\"function x(){}\")", "throws SyntaxError"),
        Case("const can be shadowed by strict eval var", "(function(){const x=1;return eval(\"\\\"use strict\\\";var x=2;x\")})()", "2"),
        Case("class function body: var x", "(function(){class x{};eval(\"var x\")})()", "throws SyntaxError"),
        Case("class nested block: var x", "(function(){ {class x{};{eval(\"var x\")}} })()", "throws SyntaxError"),
        Case("class global: var x", "class x{};eval(\"var x\")", "throws SyntaxError"),
        Case("class function body: function x(){}", "(function(){class x{};eval(\"function x(){}\")})()", "throws SyntaxError"),
        Case("class nested block: function x(){}", "(function(){ {class x{};{eval(\"function x(){}\")}} })()", "throws SyntaxError"),
        Case("class global: function x(){}", "class x{};eval(\"function x(){}\")", "throws SyntaxError"),
        Case("class can be shadowed by strict eval var", "(function(){class x{};return eval(\"\\\"use strict\\\";var x=2;x\")})()", "2"),
        Case("all declarations are checked before creating variables", "let x=1;try{eval(\"var y=1;var x\")}catch(e){};typeof y", "undefined"),
        Case("var conflicts with later lexical declaration", "(function(){eval(\"var x\");let x=1})()", "throws SyntaxError"),
        Case("var may reuse a parameter", "(function(x){eval(\"var x=2\");return x})(1)", "2"),
        Case("global lexical function conflict", "let f=1;eval(\"function f(){}\")", "throws SyntaxError"),
        Case("with does not create declaration conflict", "(function(){var o={x:1};with(o){eval(\"var x=2\")}return x+\":\"+o.x})()", "undefined:2"),
        Case("with between eval and conflicting block", "(function(){let x=1;with({x:2}){eval(\"var x\")}})()", "throws SyntaxError"),
    ))

    @Test
    fun directAndIndirect() = check(listOf(
        Case("optional eval reads global scope", "(function(){var x=1;return eval?.(\"typeof x\")})()", "undefined"),
        Case("optional eval ignores strict caller for var declarations", "(function(){\"use strict\";eval?.(\"var x=1\");return typeof x})()+\"|\"+x", "number|1"),
        Case("ordinary direct eval reads local scope", "(function(){var x=1;return eval(\"x\")})()", "1"),
        Case("indirect: (0,eval)", "(function(){var x=1;return (0,eval)(\"typeof x\")})()", "undefined"),
        Case("indirect: eval.call.bind(eval,null)", "(function(){var x=1;return eval.call.bind(eval,null)(\"typeof x\")})()", "undefined"),
        Case("indirect: eval.bind(null)", "(function(){var x=1;return eval.bind(null)(\"typeof x\")})()", "undefined"),
        Case("indirect: (true?eval:eval)", "(function(){var x=1;return (true?eval:eval)(\"typeof x\")})()", "undefined"),
        Case("indirect: (eval||eval)", "(function(){var x=1;return (eval||eval)(\"typeof x\")})()", "undefined"),
        Case("eval from a with environment is direct", "(function(){var x=7;with({eval:eval}){return eval(\"x\")}})()", "7"),
        Case("optional eval from with is indirect", "(function(){var x=7;with({eval:eval}){return eval?.(\"typeof x\")}})()", "undefined"),
        Case("reassigned non-intrinsic eval is called normally", "(function(eval){return eval(\"x\")})(function(x){return \"called:\"+x})", "called:x"),
        Case("missing optional eval is undefined", "(function(eval){return eval?.(\"x\")})(null)", "undefined"),
        Case("with unscopables assignment crosses eval lexical scope", "(function(){var x=1,o={x:2,[Symbol.unscopables]:{x:true}};with(o){eval(\"x=3\")}return x+\":\"+o.x})()", "3:2"),
        Case("with setter receives initializer without conflict", "(function(){var n=0,o={set x(v){n=v}};with(o){eval(\"var x=3\")}return typeof x+\":\"+n})()", "undefined:3"),
    ))

    @Test
    fun callerContext() = check(listOf(
        Case("strict null this is preserved", "(function(){\"use strict\";return eval(\"this===null\")}).call(null)", "true"),
        Case("strict undefined this is preserved", "(function(){\"use strict\";return eval(\"this===undefined\")})()", "true"),
        Case("strict caller arguments are preserved", "(function(x){\"use strict\";return eval(\"arguments[0]\")})(7)", "7"),
        Case("sloppy caller arguments are preserved", "(function(x){return eval(\"arguments[0]\")})(8)", "8"),
        Case("sloppy eval lexical arguments shadows caller", "(function(){return eval(\"let arguments=9;arguments\")})(1)", "9"),
        Case("new.target is preserved", "function F(){return eval(\"new.target===F\")?{ok:true}:{ok:false}}new F().ok", "true"),
        Case("new.target is preserved through nested eval", "function F(){return eval(\"eval(\\\"new.target===F\\\")\")?{ok:true}:{ok:false}}new F().ok", "true"),
        Case("new.target is preserved through arrow", "function F(){return (()=>eval(\"new.target===F\"))()?{ok:true}:{ok:false}}new F().ok", "true"),
        Case("super is preserved in method", "var base={x:7},o={m(){return eval(\"super.x\")}};Object.setPrototypeOf(o,base);o.m()", "7"),
        Case("super method receiver is preserved", "var base={m(){return this.x}},o={x:8,m(){return eval(\"super.m()\")}};Object.setPrototypeOf(o,base);o.m()", "8"),
        Case("private names remain available", "class C{#x=7;m(){return eval(\"this.#x\")}}new C().m()", "7"),
        Case("private names remain available through nested eval", "class C{#x=7;m(){return eval(\"eval(\\\"this.#x\\\")\")}}new C().m()", "7"),
        Case("super call binds derived this", "class A{constructor(){this.x=7}}class B extends A{constructor(){eval(\"super()\");this.x++}}new B().x", "8"),
        Case("non-string eval returns input", "var o={};eval(o)===o", "true"),
        Case("empty eval returns undefined", "eval()", "undefined"),
    ))

    @Test
    fun globalDeclarations() = check(listOf(
        Case("sloppy var may reuse NaN", "eval(\"var NaN\");typeof NaN", "number"),
        Case("sloppy function cannot replace NaN", "eval(\"function NaN(){}\")", "throws TypeError"),
        Case("strict function may shadow NaN", "eval(\"\\\"use strict\\\";function NaN(){return 7};NaN()\")", "7"),
        Case("sloppy var may reuse Infinity", "eval(\"var Infinity\");typeof Infinity", "number"),
        Case("sloppy function cannot replace Infinity", "eval(\"function Infinity(){}\")", "throws TypeError"),
        Case("strict function may shadow Infinity", "eval(\"\\\"use strict\\\";function Infinity(){return 7};Infinity()\")", "7"),
        Case("sloppy var may reuse undefined", "eval(\"var undefined\");typeof undefined", "undefined"),
        Case("sloppy function cannot replace undefined", "eval(\"function undefined(){}\")", "throws TypeError"),
        Case("strict function may shadow undefined", "eval(\"\\\"use strict\\\";function undefined(){return 7};undefined()\")", "7"),
        Case("var declaration does not read accessor, configurable=False", "var n=0;Object.defineProperty(globalThis,\"x\",{get:function(){n++;return 7},configurable:false});eval(\"var x\");n", "0"),
        Case("function declaration replaces only configurable accessor False", "var n=0;Object.defineProperty(globalThis,\"x\",{get:function(){n++;return 7},configurable:false});try{eval(\"function x(){return 8}\");returnX=x()}catch(e){returnX=e.name};n+\":\"+returnX", "0:TypeError"),
        Case("var declaration does not read accessor, configurable=True", "var n=0;Object.defineProperty(globalThis,\"x\",{get:function(){n++;return 7},configurable:true});eval(\"var x\");n", "0"),
        Case("function declaration replaces only configurable accessor True", "var n=0;Object.defineProperty(globalThis,\"x\",{get:function(){n++;return 7},configurable:true});try{eval(\"function x(){return 8}\");returnX=x()}catch(e){returnX=e.name};n+\":\"+returnX", "0:8"),
        Case("non-configurable global function w=False e=False", "Object.defineProperty(globalThis,\"x\",{value:7,writable:false,enumerable:false,configurable:false});try{eval(\"function x(){return 8}\");out=x()}catch(e){out=e.name};String(out)+\"|\"+Object.getOwnPropertyDescriptor(globalThis,\"x\").configurable", "TypeError|false"),
        Case("non-configurable global function w=False e=True", "Object.defineProperty(globalThis,\"x\",{value:7,writable:false,enumerable:true,configurable:false});try{eval(\"function x(){return 8}\");out=x()}catch(e){out=e.name};String(out)+\"|\"+Object.getOwnPropertyDescriptor(globalThis,\"x\").configurable", "TypeError|false"),
        Case("non-configurable global function w=True e=False", "Object.defineProperty(globalThis,\"x\",{value:7,writable:true,enumerable:false,configurable:false});try{eval(\"function x(){return 8}\");out=x()}catch(e){out=e.name};String(out)+\"|\"+Object.getOwnPropertyDescriptor(globalThis,\"x\").configurable", "TypeError|false"),
        Case("non-configurable global function w=True e=True", "Object.defineProperty(globalThis,\"x\",{value:7,writable:true,enumerable:true,configurable:false});try{eval(\"function x(){return 8}\");out=x()}catch(e){out=e.name};String(out)+\"|\"+Object.getOwnPropertyDescriptor(globalThis,\"x\").configurable", "8|false"),
    ))

}
