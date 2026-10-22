/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals

/** FunctionDeclarationInstantiation and sequential bindings, with fixed Node 26.10 controls. */
class ParameterEnvironmentTest {
    private data class Case(val name: String, val source: String, val expected: String)

    private fun check(cases: List<Case>, async: Boolean = false) {
        val failures = mutableListOf<String>()
        for ((name, source, expected) in cases) KiteJs(Rhino).use { js ->
            val actual = try {
                if (async) {
                    js.evaluate("var log=[];")
                    js.evaluate(source)
                    js.evaluate("log.join()").asString()
                } else js.evaluate(source).asString()
            } catch (e: JsSyntaxError) { "throws SyntaxError" }
            catch (e: JsError) { "throws " + e.name }
            if (actual != expected) failures += "$name: expected $expected, was $actual"
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun defaultsAndTemporalDeadZones() = check(listOf(
        Case("function f(a=arguments.length){return a}f(undefined,2,3)", "function f(a=arguments.length){return a}f(undefined,2,3)", "3"),
        Case("var x=\"outer\";function f(a=()=>x){var x=\"inner\";return a()}f()", "var x=\"outer\";function f(a=()=>x){var x=\"inner\";return a()}f()", "outer"),
        Case("function f(a=b,b){return a}f(undefined,1)", "function f(a=b,b){return a}f(undefined,1)", "throws ReferenceError"),
        Case("function f(a=a){return a}f()", "function f(a=a){return a}f()", "throws ReferenceError"),
        Case("function f(a=1,b=a+1,c=b+1){return [a,b,c].join()}f()", "function f(a=1,b=a+1,c=b+1){return [a,b,c].join()}f()", "1,2,3"),
        Case("function f(a=1,b=a+1,c=b+1){return [a,b,c].join()}f(5)", "function f(a=1,b=a+1,c=b+1){return [a,b,c].join()}f(5)", "5,6,7"),
        Case("function f(a=1,b=a+1){return [a,b].join()}f(null)", "function f(a=1,b=a+1){return [a,b].join()}f(null)", ",1"),
        Case("var n=0;function f(a=++n,b=++n){return [a,b,n].join()}f(7,8)", "var n=0;function f(a=++n,b=++n){return [a,b,n].join()}f(7,8)", "7,8,0"),
        Case("var n=0;function f(a=++n,b=++n){return [a,b,n].join()}f(undefined,8)", "var n=0;function f(a=++n,b=++n){return [a,b,n].join()}f(undefined,8)", "1,8,1"),
        Case("var n=0;function f(a=(n=n*10+1),b=(n=n*10+2),c=(n=n*10+3)){return n}f()", "var n=0;function f(a=(n=n*10+1),b=(n=n*10+2),c=(n=n*10+3)){return n}f()", "123"),
        Case("function f(a=()=>b,b=7){return a()}f()", "function f(a=()=>b,b=7){return a()}f()", "7"),
        Case("function f(a=(b=2),b=1){return a}f()", "function f(a=(b=2),b=1){return a}f()", "throws ReferenceError"),
        Case("function f(a=(a=2)){return a}f()", "function f(a=(a=2)){return a}f()", "throws ReferenceError"),
        Case("function f(a=typeof b,b){return a}f()", "function f(a=typeof b,b){return a}f()", "throws ReferenceError"),
        Case("function f(a=typeof a){return a}f()", "function f(a=typeof a){return a}f()", "throws ReferenceError"),
        Case("function f(a=()=>a){return a()===a}f()", "function f(a=()=>a){return a()===a}f()", "true"),
        Case("function f(a=()=>arguments[0]){return typeof a()}f()", "function f(a=()=>arguments[0]){return typeof a()}f()", "undefined"),
        Case("var a=9;function f(a=()=>a){var a=1;return a}f()", "var a=9;function f(a=()=>a){var a=1;return a}f()", "1"),
        Case("function f(a=1,b=(a=3)){return [a,b].join()}f()", "function f(a=1,b=(a=3)){return [a,b].join()}f()", "3,3"),
        Case("function f(a=1){return f.length}f()", "function f(a=1){return f.length}f()", "0"),
        Case("function f(a,b=1,c){return f.length}f()", "function f(a,b=1,c){return f.length}f()", "1"),
    ))

    @Test
    fun bodyEnvironmentIsolation() = check(listOf(
        Case("var x=\"body\" is invisible to default closure", "var x=\"outer\";function f(a=()=>x){var x=\"body\";return a()}String(f())", "outer"),
        Case("var x=\"body\" is invisible to direct eval default", "var x=\"outer\";function f(a=eval(\"x\")){var x=\"body\";return a()}f()", "throws TypeError"),
        Case("let x=\"body\" is invisible to default closure", "var x=\"outer\";function f(a=()=>x){let x=\"body\";return a()}String(f())", "outer"),
        Case("let x=\"body\" is invisible to direct eval default", "var x=\"outer\";function f(a=eval(\"x\")){let x=\"body\";return a()}f()", "throws TypeError"),
        Case("const x=\"body\" is invisible to default closure", "var x=\"outer\";function f(a=()=>x){const x=\"body\";return a()}String(f())", "outer"),
        Case("const x=\"body\" is invisible to direct eval default", "var x=\"outer\";function f(a=eval(\"x\")){const x=\"body\";return a()}f()", "throws TypeError"),
        Case("function x(){return \"body\"} is invisible to default closure", "var x=\"outer\";function f(a=()=>x){function x(){return \"body\"};return a()}String(f())", "outer"),
        Case("function x(){return \"body\"} is invisible to direct eval default", "var x=\"outer\";function f(a=eval(\"x\")){function x(){return \"body\"};return a()}f()", "throws TypeError"),
        Case("class x{} is invisible to default closure", "var x=\"outer\";function f(a=()=>x){class x{};return a()}String(f())", "outer"),
        Case("class x{} is invisible to direct eval default", "var x=\"outer\";function f(a=eval(\"x\")){class x{};return a()}f()", "throws TypeError"),
        Case("function f(a=1,get=()=>a){var a=2;return [a,get()].join()}f()", "function f(a=1,get=()=>a){var a=2;return [a,get()].join()}f()", "2,1"),
        Case("function f(a=1,get=()=>a){var a;return [a,get()].join()}f()", "function f(a=1,get=()=>a){var a;return [a,get()].join()}f()", "1,1"),
        Case("function f(a=1,get=()=>a){function a(){return 2}return [a(),get()].join()}f()", "function f(a=1,get=()=>a){function a(){return 2}return [a(),get()].join()}f()", "2,1"),
        Case("function f(a=1,get=()=>a){a=2;return [a,get()].join()}f()", "function f(a=1,get=()=>a){a=2;return [a,get()].join()}f()", "2,2"),
        Case("function f(a=1,get=()=>a){var a; a=2;return [a,get()].join()}f()", "function f(a=1,get=()=>a){var a; a=2;return [a,get()].join()}f()", "2,1"),
        Case("function f(a=1,get=()=>a){var a;get=()=>a;return get()}f()", "function f(a=1,get=()=>a){var a;get=()=>a;return get()}f()", "1"),
        Case("function f(a=1,get=()=>arguments){var arguments=\"body\";return [arguments,get().length].join()}f(undefined,2)", "function f(a=1,get=()=>arguments){var arguments=\"body\";return [arguments,get().length].join()}f(undefined,2)", "throws TypeError"),
        Case("function f(a=()=>x){var x=1;return a()}f()", "function f(a=()=>x){var x=1;return a()}f()", "throws ReferenceError"),
        Case("function f(a=()=>g()){function g(){return 1}return a()}f()", "function f(a=()=>g()){function g(){return 1}return a()}f()", "throws ReferenceError"),
        Case("function f(a=()=>f){var f=1;return a()===f}f()", "function f(a=()=>f){var f=1;return a()===f}f()", "false"),
        Case("function f(a=1){var g=()=>a;var a=2;return g()}f()", "function f(a=1){var g=()=>a;var a=2;return g()}f()", "2"),
        Case("function f(a=1){function g(){return a}var a=2;return g()}f()", "function f(a=1){function g(){return a}var a=2;return g()}f()", "2"),
        Case("var n=0;function f(a=(()=>{throw Error(\"default\")})()){n++}try{f()}catch(e){}n", "var n=0;function f(a=(()=>{throw Error(\"default\")})()){n++}try{f()}catch(e){}n", "0"),
        Case("var q;function f(a=(q=()=>b),b=1){return q}f();q()", "var q;function f(a=(q=()=>b),b=1){return q}f();q()", "1"),
        Case("var q;function f(a=(q=()=>b),b=1){throw Error()}try{f()}catch(e){}q()", "var q;function f(a=(q=()=>b),b=1){throw Error()}try{f()}catch(e){}q()", "1"),
    ))

    @Test
    fun argumentsBindings() = check(listOf(
        Case("function f(a=arguments.length){return a}f()", "function f(a=arguments.length){return a}f()", "0"),
        Case("function f(a=arguments.length){return a}f(undefined,1,2)", "function f(a=arguments.length){return a}f(undefined,1,2)", "3"),
        Case("function f(a=arguments[1]){return a}f(undefined,9)", "function f(a=arguments[1]){return a}f(undefined,9)", "9"),
        Case("function f(a=(arguments[0]=9)){return [a,arguments[0]].join()}f()", "function f(a=(arguments[0]=9)){return [a,arguments[0]].join()}f()", "9,9"),
        Case("function f(a=1){arguments[0]=9;return a}f(2)", "function f(a=1){arguments[0]=9;return a}f(2)", "2"),
        Case("function f(a=1){a=9;return arguments[0]}f(2)", "function f(a=1){a=9;return arguments[0]}f(2)", "2"),
        Case("function f(a=1,b=arguments){return b===arguments}f()", "function f(a=1,b=arguments){return b===arguments}f()", "true"),
        Case("function f(a=1){return f.arguments.length}f(undefined,2,3)", "function f(a=1){return f.arguments.length}f(undefined,2,3)", "3"),
        Case("function f(a=eval(\"var arguments=7\"),b=arguments){return b}f()", "function f(a=eval(\"var arguments=7\"),b=arguments){return b}f()", "7"),
        Case("function f(a=arguments.length){function arguments(){}return a}f(undefined,2,3)", "function f(a=arguments.length){function arguments(){}return a}f(undefined,2,3)", "3"),
        Case("function f(a=1,b=()=>arguments){var arguments;return b()===arguments}f()", "function f(a=1,b=()=>arguments){var arguments;return b()===arguments}f()", "true"),
        Case("function f(arguments=3){return arguments}f()", "function f(arguments=3){return arguments}f()", "3"),
        Case("function f(a=arguments,arguments){return a}f()", "function f(a=arguments,arguments){return a}f()", "throws ReferenceError"),
        Case("function f({arguments}={arguments:7}){return arguments}f()", "function f({arguments}={arguments:7}){return arguments}f()", "7"),
        Case("function f(a=1){return typeof Object.getOwnPropertyDescriptor(arguments,\"callee\").get}f()", "function f(a=1){return typeof Object.getOwnPropertyDescriptor(arguments,\"callee\").get}f()", "function"),
        Case("(function(){return ((a=arguments.length)=>a)()})(1,2,3)", "(function(){return ((a=arguments.length)=>a)()})(1,2,3)", "3"),
        Case("(function(){return ((a=()=>arguments)=>a())()===arguments})(1)", "(function(){return ((a=()=>arguments)=>a())()===arguments})(1)", "true"),
        Case("(function(){return ((...r)=>arguments.length+\":\"+r.length)(1,2)})(7)", "(function(){return ((...r)=>arguments.length+\":\"+r.length)(1,2)})(7)", "1:2"),
        Case("function f(...r){var arguments;return r.length+\":\"+arguments.length}f(1,2)", "function f(...r){var arguments;return r.length+\":\"+arguments.length}f(1,2)", "2:2"),
    ))

    @Test
    fun evalDuringInitialization() = check(listOf(
        Case("function f(a=eval(\"var x=7\")){return x}f()", "function f(a=eval(\"var x=7\")){return x}f()", "7"),
        Case("function f(a=eval(\"var x=7\"),b=()=>x){var x;return [typeof x,b()].join()}f()", "function f(a=eval(\"var x=7\"),b=()=>x){var x;return [typeof x,b()].join()}f()", "undefined,7"),
        Case("function f(a=eval(\"var x=7\"),b=()=>x){var x=8;return [x,b()].join()}f()", "function f(a=eval(\"var x=7\"),b=()=>x){var x=8;return [x,b()].join()}f()", "8,7"),
        Case("function f(a=eval(\"var x=7\"),b=eval(\"x\")){return b}f()", "function f(a=eval(\"var x=7\"),b=eval(\"x\")){return b}f()", "7"),
        Case("function f(a=eval(\"let x=7\")){return typeof x}f()", "function f(a=eval(\"let x=7\")){return typeof x}f()", "undefined"),
        Case("function f(a=eval(\"\"use strict\";var x=7\")){return typeof x}f()", "function f(a=eval(\"\"use strict\";var x=7\")){return typeof x}f()", "throws SyntaxError"),
        Case("function f(a=eval(\"var b=7\"),b=1){return b}f()", "function f(a=eval(\"var b=7\"),b=1){return b}f()", "throws SyntaxError"),
        Case("function f(a=eval(\"a\")){return a}f()", "function f(a=eval(\"a\")){return a}f()", "throws ReferenceError"),
        Case("function f(a=eval(\"typeof b\"),b=1){return a}f()", "function f(a=eval(\"typeof b\"),b=1){return a}f()", "throws ReferenceError"),
        Case("function f(a=eval(\"var x=7\")){let x=8;return x}f()", "function f(a=eval(\"var x=7\")){let x=8;return x}f()", "8"),
        Case("function f(a=eval(\"var arguments=7\")){let arguments=8;return arguments}f()", "function f(a=eval(\"var arguments=7\")){let arguments=8;return arguments}f()", "8"),
        Case("var f=(a=eval(\"var arguments=7\"))=>{let arguments=8;return arguments};f()+\":\"+typeof globalThis.arguments", "var f=(a=eval(\"var arguments=7\"))=>{let arguments=8;return arguments};f()+\":\"+typeof globalThis.arguments", "8:undefined"),
        Case("var f=(a=eval(\"var arguments=7\"),get=()=>arguments)=>{let arguments=8;return [arguments,get()].join()};f()", "var f=(a=eval(\"var arguments=7\"),get=()=>arguments)=>{let arguments=8;return [arguments,get()].join()};f()", "8,7"),
        Case("var f=(a=eval(\"var arguments=7\"))=>()=>arguments;f()()", "var f=(a=eval(\"var arguments=7\"))=>()=>arguments;f()()", "7"),
        Case("function f(a=1){let x=1;eval(\"var x\")}f()", "function f(a=1){let x=1;eval(\"var x\")}f()", "throws SyntaxError"),
        Case("function f(...r){let x=1;eval(\"var x\")}f()", "function f(...r){let x=1;eval(\"var x\")}f()", "throws SyntaxError"),
        Case("function f({a}){let x=1;eval(\"var x\")}f({a:1})", "function f({a}){let x=1;eval(\"var x\")}f({a:1})", "throws SyntaxError"),
        Case("function f(a=eval(\"var x=1\")){eval(\"var x=2\");return x}f()", "function f(a=eval(\"var x=1\")){eval(\"var x=2\");return x}f()", "2"),
        Case("function f(a=eval(\"function g(){return 7}\"),b=()=>g()){function g(){return 8}return [g(),b()].join()}f()", "function f(a=eval(\"function g(){return 7}\"),b=()=>g()){function g(){return 8}return [g(),b()].join()}f()", "8,7"),
    ))

    @Test
    fun patternsAndRestParameters() = check(listOf(
        Case("var log=[];function f({a}=(log.push(1),{a:1}),b=(log.push(a+1),2),{c}=(log.push(b+1),{c:3})){return log.join()}f()", "var log=[];function f({a}=(log.push(1),{a:1}),b=(log.push(a+1),2),{c}=(log.push(b+1),{c:3})){return log.join()}f()", "1,2,3"),
        Case("function f({a=1},b=a+1){return [a,b].join()}f({})", "function f({a=1},b=a+1){return [a,b].join()}f({})", "1,2"),
        Case("function f(a=1,{b=a+1}={}){return [a,b].join()}f()", "function f(a=1,{b=a+1}={}){return [a,b].join()}f()", "1,2"),
        Case("function f([a=1,b=a+1]=[]){return [a,b].join()}f()", "function f([a=1,b=a+1]=[]){return [a,b].join()}f()", "1,2"),
        Case("function f({a=b,b=1}={}){return a}f()", "function f({a=b,b=1}={}){return a}f()", "throws ReferenceError"),
        Case("function f([a=b,b=1]=[]){return a}f()", "function f([a=b,b=1]=[]){return a}f()", "throws ReferenceError"),
        Case("function f({a=a}={}){return a}f()", "function f({a=a}={}){return a}f()", "throws ReferenceError"),
        Case("function f({a=1},b=()=>a){var a=2;return b()}f({})", "function f({a=1},b=()=>a){var a=2;return b()}f({})", "1"),
        Case("function f({a},b=()=>a){var a=2;return b()}f({a:1})", "function f({a},b=()=>a){var a=2;return b()}f({a:1})", "1"),
        Case("function f({a},b=()=>a){var a;return b()}f({a:1})", "function f({a},b=()=>a){var a;return b()}f({a:1})", "1"),
        Case("function f({a}){var a=2;return a}f({a:1})", "function f({a}){var a=2;return a}f({a:1})", "2"),
        Case("function f([a],b=a){return b}f([7])", "function f([a],b=a){return b}f([7])", "7"),
        Case("function f(a=rest,...rest){return a}f()", "function f(a=rest,...rest){return a}f()", "throws ReferenceError"),
        Case("function f(a=()=>rest,...rest){return a().join()}f(undefined,1,2)", "function f(a=()=>rest,...rest){return a().join()}f(undefined,1,2)", "1,2"),
        Case("function f(...[a,b]){return [a,b].join()}f(1,2)", "function f(...[a,b]){return [a,b].join()}f(1,2)", "1,2"),
        Case("function f(...{length}){return length}f(1,2)", "function f(...{length}){return length}f(1,2)", "2"),
        Case("function f(a=1,...[b=a+1]){return [a,b].join()}f()", "function f(a=1,...[b=a+1]){return [a,b].join()}f()", "1,2"),
        Case("function f({a=()=>b},b=1){return a()}f({})", "function f({a=()=>b},b=1){return a()}f({})", "1"),
        Case("function f({a=(b=2)},b=1){return a}f({})", "function f({a=(b=2)},b=1){return a}f({})", "throws ReferenceError"),
    ))

    @Test
    fun arrowsMethodsAndConstructors() = check(listOf(
        Case("((a=1,b=a+1)=>[a,b].join())()", "((a=1,b=a+1)=>[a,b].join())()", "1,2"),
        Case("((a=b,b=1)=>a)()", "((a=b,b=1)=>a)()", "throws ReferenceError"),
        Case("var x=\"outer\";((a=()=>x)=>{var x=\"body\";return a()})()", "var x=\"outer\";((a=()=>x)=>{var x=\"body\";return a()})()", "outer"),
        Case("(({a:x=1},b=x+1)=>b)({})", "(({a:x=1},b=x+1)=>b)({})", "2"),
        Case("((a=1,g=()=>a)=>{var a=2;return g()})()", "((a=1,g=()=>a)=>{var a=2;return g()})()", "1"),
        Case("var o={x:7,m(a=this.x){return a}};o.m()", "var o={x:7,m(a=this.x){return a}};o.m()", "7"),
        Case("var o={x:7,m(a=()=>this.x){this.x=8;return a()}};o.m()", "var o={x:7,m(a=()=>this.x){this.x=8;return a()}};o.m()", "8"),
        Case("var base={x:7},o={m(a=super.x){return a}};Object.setPrototypeOf(o,base);o.m()", "var base={x:7},o={m(a=super.x){return a}};Object.setPrototypeOf(o,base);o.m()", "7"),
        Case("var base={m(){return this.x}},o={x:8,m(a=super.m()){return a}};Object.setPrototypeOf(o,base);o.m()", "var base={m(){return this.x}},o={x:8,m(a=super.m()){return a}};Object.setPrototypeOf(o,base);o.m()", "8"),
        Case("function F(a=new.target){this.ok=a===F}new F().ok", "function F(a=new.target){this.ok=a===F}new F().ok", "true"),
        Case("function F(a=eval(\"new.target\")){this.ok=a===F}new F().ok", "function F(a=eval(\"new.target\")){this.ok=a===F}new F().ok", "true"),
        Case("class C{constructor(a=this){this.ok=a===this}}new C().ok", "class C{constructor(a=this){this.ok=a===this}}new C().ok", "true"),
        Case("class A{constructor(){this.x=7}}class B extends A{constructor(a=super()){this.y=a===this}}var b=new B();b.x+\":\"+b.y", "class A{constructor(){this.x=7}}class B extends A{constructor(a=super()){this.y=a===this}}var b=new B();b.x+\":\"+b.y", "7:true"),
        Case("class A{}class B extends A{constructor(a=this){super()}}new B()", "class A{}class B extends A{constructor(a=this){super()}}new B()", "throws ReferenceError"),
        Case("class C{#x=7;m(a=this.#x){return a}}new C().m()", "class C{#x=7;m(a=this.#x){return a}}new C().m()", "7"),
        Case("class C{static m(a=7){return a}}C.m()", "class C{static m(a=7){return a}}C.m()", "7"),
        Case("new Function(\"a=1\",\"b=a+1\",\"return b\")()", "new Function(\"a=1\",\"b=a+1\",\"return b\")()", "2"),
    ))

    @Test
    fun generatorInitialization() = check(listOf(
        Case("var n=0;function* f(a=++n){yield a}var g=f();n+\":\"+g.next().value", "var n=0;function* f(a=++n){yield a}var g=f();n+\":\"+g.next().value", "1:1"),
        Case("function* f(a=b,b=1){yield a}f()", "function* f(a=b,b=1){yield a}f()", "throws ReferenceError"),
        Case("var x=\"outer\";function* f(a=()=>x){var x=\"body\";yield a()}f().next().value", "var x=\"outer\";function* f(a=()=>x){var x=\"body\";yield a()}f().next().value", "outer"),
        Case("function* f(a=1,g=()=>a){var a=2;yield g()}f().next().value", "function* f(a=1,g=()=>a){var a=2;yield g()}f().next().value", "1"),
        Case("function* f(a=arguments.length){yield a}f(undefined,1,2).next().value", "function* f(a=arguments.length){yield a}f(undefined,1,2).next().value", "3"),
        Case("function* f({a=1},b=a+1){yield b}f({}).next().value", "function* f({a=1},b=a+1){yield b}f({}).next().value", "2"),
        Case("function* f([a,b]){yield a+b}f([1,2]).next().value", "function* f([a,b]){yield a+b}f([1,2]).next().value", "3"),
        Case("function* f([a,b]=[1,2]){yield a+b}f().next().value", "function* f([a,b]=[1,2]){yield a+b}f().next().value", "3"),
        Case("function* f({p:[a,b]}){yield a+b}f({p:[1,2]}).next().value", "function* f({p:[a,b]}){yield a+b}f({p:[1,2]}).next().value", "3"),
        Case("var n=0;function* f(a=(()=>{throw TypeError()})()){n++}try{f()}catch(e){n=e.name}n", "var n=0;function* f(a=(()=>{throw TypeError()})()){n++}try{f()}catch(e){n=e.name}n", "TypeError"),
        Case("function* f(a=1){function g(){return a}var a=2;yield g()}f().next().value", "function* f(a=1){function g(){return a}var a=2;yield g()}f().next().value", "2"),
        Case("function* f(a=1,g=()=>a){var a=2;yield 0;yield g()}var i=f();i.next();i.next().value", "function* f(a=1,g=()=>a){var a=2;yield 0;yield g()}var i=f();i.next();i.next().value", "1"),
    ))

    @Test
    fun asyncInitialization() = check(listOf(
        Case("async default argument scope", "var x=\"outer\";async function f(a=()=>x){var x=\"body\";return a()}f().then(v=>log.push(v),e=>log.push(e.name))", "outer"),
        Case("async arguments", "async function f(a=arguments.length){return a}f(undefined,1,2).then(v=>log.push(v))", "3"),
        Case("async temporal dead zone rejects", "async function f(a=b,b=1){return a}try{f().then(v=>log.push(v),e=>log.push(e.name));log.push(\"returned\")}catch(e){log.push(\"sync\") }", "returned,ReferenceError"),
        Case("async arrow lexical arguments", "(function(){(async(a=arguments.length)=>a)().then(v=>log.push(v))})(1,2)", "2"),
        Case("async parameter/body copy", "async function f(a=1,g=()=>a){var a=2;await 0;return g()}f().then(v=>log.push(v))", "1"),
        Case("async destructuring order", "async function f({a=1},b=a+1){return b}f({}).then(v=>log.push(v))", "2"),
        Case("async method this", "var o={x:7,async m(a=this.x){return a}};o.m().then(v=>log.push(v))", "7"),
        Case("async eval isolation", "async function f(a=eval(\"var x=7\"),g=()=>x){var x=8;return [x,g()].join()}f().then(v=>log.push(v))", "8,7"),
    ), async = true)

}
