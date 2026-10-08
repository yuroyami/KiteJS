/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

/** Fixed Node/V8 controls for the realm of engine errors, including calls across two globals. */
class ErrorRealmTest {
    private data class Case(val source: String, val expected: String)

    private fun check(case: Case, async: Boolean = false) {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val caller = cx.initStandardObjects()
            val foreign = cx.initStandardObjects()
            caller.put("foreign", caller, foreign)
            foreign.put("caller", foreign, caller)
            cx.evaluateString(foreign, "var intrinsicError=Error;", "foreign.js", 1, null)
            cx.evaluateString(caller, "var intrinsicError=Error;\nfunction kind(e){return (e && e.name || typeof e)+\":\"+(e instanceof intrinsicError?\"caller\":e instanceof foreign.intrinsicError?\"foreign\":\"value\");}\nfunction capture(fn){try{fn();return \"no error\"}catch(e){return kind(e)}}", "caller.js", 1, null)
            var result = cx.evaluateString(caller, case.source, "error-realms.js", 1, null)
            if (async) {
                cx.processMicrotasks()
                result = cx.evaluateString(caller, "result", "rejection.js", 1, null)
            }
            assertEquals(case.expected, ScriptRuntime.toString(result), case.source)
        } finally {
            Context.exit()
        }
    }
    @Test
    fun scriptErrors() {
        check(Case("capture(function(){var f=foreign.eval(\"(function(){return null.x})\"); f();})", "TypeError:foreign"))
        check(Case("capture(function(){var f=foreign.eval(\"(function(){return notDeclared})\"); f();})", "ReferenceError:foreign"))
        check(Case("capture(function(){var f=foreign.eval(\"(function(){return 1n/0n})\"); f();})", "RangeError:foreign"))
        check(Case("capture(function(){var f=foreign.eval(\"(function(){return decodeURIComponent(\\\"%\\\")})\"); f();})", "URIError:foreign"))
        check(Case("capture(function(){var f=foreign.eval(\"(function(a=a){})\"); f();})", "ReferenceError:foreign"))
        check(Case("capture(function(){var f=foreign.eval(\"(function(){const x=1;x=2})\"); f();})", "TypeError:foreign"))
        check(Case("capture(function(){var f=foreign.eval(\"(function(){return String(Symbol()) + Symbol()})\"); f();})", "TypeError:foreign"))
        check(Case("capture(function(){var f=foreign.eval(\"(function(){\\\"use strict\\\";return this.x})\"); f();})", "TypeError:foreign"))
        check(Case("capture(function(){var f=foreign.eval(\"(class C {})\"); f();})", "TypeError:foreign"))
        check(Case("capture(function(){var f=foreign.eval(\"(function(){eval(\\\"(\\\")})\"); f();})", "SyntaxError:foreign"))
        check(Case("capture(function(){var f=foreign.eval(\"(function(){\\\"use strict\\\";eval(\\\"let x;var x\\\")})\"); f();})", "SyntaxError:foreign"))
        check(Case("capture(function(){var obj=foreign.eval(\"(new (class C { #x; get(){return this.#x} }))\"); obj.get.call({});})", "TypeError:foreign"))
        check(Case("capture(function(){var obj=foreign.eval(\"({[Symbol.toPrimitive](){return missingBinding}})\"); String(obj);})", "ReferenceError:foreign"))
        check(Case("capture(function(){var obj=foreign.eval(\"({get x(){return null.x}})\"); obj.x;})", "TypeError:foreign"))
        check(Case("capture(function(){var obj=foreign.eval(\"({set x(v){missingBinding}})\"); obj.x=1;})", "ReferenceError:foreign"))
    }

    @Test
    fun builtinErrors() {
        check(Case("capture(function(){foreign.JSON.parse(\"{\");})", "SyntaxError:foreign"))
        check(Case("capture(function(){new foreign.Array(-1);})", "RangeError:foreign"))
        check(Case("capture(function(){foreign.Number.prototype.toFixed.call(1,1000);})", "RangeError:foreign"))
        check(Case("capture(function(){foreign.Map.prototype.get.call({},1);})", "TypeError:foreign"))
        check(Case("capture(function(){foreign.String.prototype[Symbol.iterator].call(null);})", "TypeError:foreign"))
        check(Case("capture(function(){foreign.Object.defineProperty(null,\"x\",{});})", "TypeError:foreign"))
        check(Case("capture(function(){foreign.Object.assign(null,{});})", "TypeError:foreign"))
        check(Case("capture(function(){foreign.Reflect.get(1,\"x\");})", "TypeError:foreign"))
        check(Case("capture(function(){foreign.Symbol.keyFor(\"x\");})", "TypeError:foreign"))
        check(Case("capture(function(){foreign.Date.prototype.toISOString.call(new Date(NaN));})", "RangeError:foreign"))
        check(Case("capture(function(){new foreign.Symbol();})", "TypeError:foreign"))
        check(Case("capture(function(){new foreign.BigInt(1);})", "TypeError:foreign"))
        check(Case("capture(function(){foreign.Promise(function(){});})", "TypeError:foreign"))
        check(Case("capture(function(){foreign.RegExp(\"[\");})", "SyntaxError:foreign"))
        check(Case("capture(function(){foreign.Function(\"(\");})", "SyntaxError:foreign"))
        check(Case("capture(function(){foreign.eval(\"(\");})", "SyntaxError:foreign"))
        check(Case("capture(function(){foreign.eval?.(\"(\");})", "SyntaxError:foreign"))
        check(Case("capture(function(){foreign.Function.prototype.call.call(null);})", "TypeError:foreign"))
        check(Case("capture(function(){foreign.Function.prototype.apply.call(function(){},null,1);})", "TypeError:foreign"))
        check(Case("capture(function(){foreign.Function.prototype.apply.call(null,null,[]);})", "TypeError:foreign"))
        check(Case("capture(function(){new foreign.Proxy(1,{});})", "TypeError:foreign"))
        check(Case("capture(function(){new foreign.Proxy({},null);})", "TypeError:foreign"))
        check(Case("capture(function(){new foreign.Proxy();})", "TypeError:foreign"))
    }

    @Test
    fun callSiteChecks() {
        check(Case("capture(function(){new foreign.parseInt();})", "TypeError:caller"))
        check(Case("capture(function(){new foreign.JSON.parse();})", "TypeError:caller"))
        check(Case("capture(function(){new foreign.String.prototype.trim();})", "TypeError:caller"))
        check(Case("capture(function(){new (foreign.eval(\"()=>1\"))();})", "TypeError:caller"))
        check(Case("capture(function(){foreign.eval(\"1\")();})", "TypeError:caller"))
        check(Case("capture(function(){new (foreign.eval(\"1\"))();})", "TypeError:caller"))
        check(Case("capture(function(){var f=foreign.eval(\"(function(){\\\"use strict\\\";})\"); f.caller;})", "TypeError:foreign"))
        check(Case("capture(function(){var get=foreign.Object.getOwnPropertyDescriptor(foreign.Map.prototype,\"size\").get;get.call({});})", "TypeError:foreign"))
    }

    @Test
    fun nestedCallsAndRestoration() {
        check(Case("var f=foreign.eval(\"(function(cb){return cb()})\");capture(function(){f(function(){return null.x})})", "TypeError:caller"))
        check(Case("capture(function(){foreign.Array.prototype.map.call([1],function(){missingBinding})})", "ReferenceError:caller"))
        check(Case("var f=foreign.eval(\"(function(){try{caller.fail()}catch(e){return e}})\");var fail=function(){return null.x};kind(f())", "TypeError:caller"))
        check(Case("var f=foreign.eval(\"(function(){try{null.x}catch(e){return 1}})\");f();capture(function(){null.x;})", "TypeError:caller"))
        check(Case("var f=foreign.eval(\"(function(){return null.x})\");capture(f)+\",\"+capture(function(){null.x})", "TypeError:foreign,TypeError:caller"))
        check(Case("var local=function(){return null.x};var f=foreign.eval(\"(function(cb){return cb()})\");capture(function(){return f(local)})", "TypeError:caller"))
        check(Case("var f=foreign.eval(\"(function(){try{return null.x}finally{caller.eval(\\\"1\\\")}})\");capture(f)", "TypeError:foreign"))
        check(Case("var f=foreign.eval(\"(function(){try{return null.x}finally{TypeError=function(){throw 7}}})\");capture(f)", "TypeError:foreign"))
        check(Case("var err=foreign.eval(\"new TypeError(\\\"explicit\\\")\");var f=foreign.eval(\"(function(e){throw e})\");try{f(err)}catch(e){kind(e)+\",\"+(e===err)}", "TypeError:foreign,true"))
    }

    @Test
    fun boundFunctionsAndProxies() {
        check(Case("capture(function(){var r=foreign.Proxy.revocable({},{});r.revoke();r.proxy.x;})", "TypeError:caller"))
        check(Case("capture(function(){var r=foreign.Proxy.revocable(function(){},{});r.revoke();r.proxy();})", "TypeError:caller"))
        check(Case("capture(function(){var p=foreign.eval(\"new Proxy(Object.defineProperty({},\\\"x\\\",{value:1}),{get(){return 2}})\");p.x;})", "TypeError:caller"))
        check(Case("capture(function(){var r=foreign.Proxy.revocable({},{});r.revoke();foreign.Reflect.get(r.proxy,\"x\");})", "TypeError:foreign"))
        check(Case("capture(function(){var p=foreign.eval(\"new Proxy({},{get(){return null.x}})\");p.x;})", "TypeError:foreign"))
        check(Case("capture(function(){var p=foreign.eval(\"new Proxy(function(){return null.x},{})\");p();})", "TypeError:foreign"))
        check(Case("capture(function(){var p=new Proxy(foreign.eval(\"(function(){return null.x})\"),{});p();})", "TypeError:foreign"))
        check(Case("capture(function(){var f=foreign.eval(\"(function(){return null.x})\");var b=f.bind(null);b();})", "TypeError:foreign"))
        check(Case("capture(function(){var r=foreign.Proxy.revocable(function(){},{});r.revoke();foreign.Reflect.apply(r.proxy,null,[]);})", "TypeError:foreign"))
        check(Case("capture(function(){var p=foreign.eval(\"new Proxy({},{get:1})\");p.x;})", "TypeError:caller"))
    }

    @Test
    fun generatorsAndConstructors() {
        check(Case("capture(function(){var g=foreign.eval(\"(function*(){yield 1;null.x})\")();g.next();g.next();})", "TypeError:foreign"))
        check(Case("capture(function(){var g=foreign.eval(\"(function*(){yield 1;missingBinding})\")();g.next();g.next();})", "ReferenceError:foreign"))
        check(Case("capture(function(){var g=foreign.eval(\"(function*(){try{yield 1}finally{null.x}})\")();g.next();g.return();})", "TypeError:foreign"))
        check(Case("capture(function(){var f=foreign.eval(\"(function*(a=a){})\");f();})", "ReferenceError:foreign"))
        check(Case("capture(function(){var g=foreign.eval(\"(function*(){yield 1})\");g.prototype.next.call({});})", "TypeError:foreign"))
        check(Case("capture(function(){var g=foreign.eval(\"(function*(cb){yield 1;cb()})\")(function(){null.x});g.next();g.next();})", "TypeError:caller"))
        check(Case("capture(function(){var C=foreign.eval(\"(class C {constructor(){return null.x}})\");new C();})", "TypeError:foreign"))
        check(Case("capture(function(){var C=foreign.eval(\"(class C extends Object {constructor(){return 1}})\");new C();})", "TypeError:caller"))
        check(Case("capture(function(){var C=foreign.eval(\"(class C extends Object {constructor(){this.x=1}})\");new C();})", "ReferenceError:foreign"))
        check(Case("capture(function(){var C=foreign.eval(\"(class C {x=null.x})\");new C();})", "TypeError:foreign"))
        check(Case("capture(function(){var C=foreign.eval(\"(class C extends Object {constructor(){super();return 1}})\");new C();})", "TypeError:caller"))
    }

    @Test
    fun asyncAndPromises() {
        check(Case("var result=\"pending\";var f=foreign.eval(\"(async function(cb){null.x})\");f(function(){null.x}).catch(function(e){result=kind(e)});", "TypeError:foreign"), async = true)
        check(Case("var result=\"pending\";var f=foreign.eval(\"(async function(cb){await 1;null.x})\");f(function(){null.x}).catch(function(e){result=kind(e)});", "TypeError:foreign"), async = true)
        check(Case("var result=\"pending\";var f=foreign.eval(\"(async function(cb){await 1;missingBinding})\");f(function(){null.x}).catch(function(e){result=kind(e)});", "ReferenceError:foreign"), async = true)
        check(Case("var result=\"pending\";var f=foreign.eval(\"(async function(cb){await 1;1n/0n})\");f(function(){null.x}).catch(function(e){result=kind(e)});", "RangeError:foreign"), async = true)
        check(Case("var result=\"pending\";var f=foreign.eval(\"(async function(cb){await 1;decodeURIComponent(\\\"%\\\")})\");f(function(){null.x}).catch(function(e){result=kind(e)});", "URIError:foreign"), async = true)
        check(Case("var result=\"pending\";var f=foreign.eval(\"(async function(cb){await 1;cb()})\");f(function(){null.x}).catch(function(e){result=kind(e)});", "TypeError:caller"), async = true)
        check(Case("var result=\"pending\";var f=foreign.eval(\"(async function(a=a){})\");f().catch(function(e){result=kind(e)});", "ReferenceError:foreign"), async = true)
        check(Case("var result=\"pending\";var f=foreign.eval(\"(function(){null.x})\");Promise.resolve().then(f).catch(function(e){result=kind(e)});", "TypeError:foreign"), async = true)
        check(Case("var result=\"pending\";var f=foreign.eval(\"(function(resolve){null.x})\");new Promise(f).catch(function(e){result=kind(e)});", "TypeError:foreign"), async = true)
        check(Case("var result=\"pending\";new foreign.Promise(function(resolve){null.x}).catch(function(e){result=kind(e)});", "TypeError:caller"), async = true)
        check(Case("var result=\"pending\";var obj=foreign.eval(\"({then(){null.x}})\");Promise.resolve(obj).catch(function(e){result=kind(e)});", "TypeError:foreign"), async = true)
        check(Case("var result=\"pending\";var obj=foreign.eval(\"({get then(){null.x}})\");Promise.resolve(obj).catch(function(e){result=kind(e)});", "TypeError:foreign"), async = true)
        check(Case("var result=\"pending\";var f=foreign.eval(\"(async function(){null.x})\");foreign.TypeError=function(){throw 7};f().catch(function(e){result=kind(e)});", "TypeError:foreign"), async = true)
        check(Case("var result=\"pending\";var f=foreign.eval(\"(function(){null.x})\");Promise.resolve().then(f).catch(function(e){result=String(e.message.indexOf(\"TypeError:\")===-1)});", "true"), async = true)
        check(Case("var result=\"pending\";var p=new Proxy(foreign.eval(\"(function(){})\"),{apply:1});Promise.resolve().then(p).catch(function(e){result=kind(e)});", "TypeError:foreign"), async = true)
        check(Case("var result=\"pending\";var r=Proxy.revocable(foreign.eval(\"(function(){})\"),{});Promise.resolve().then(r.proxy).catch(function(e){result=kind(e)});r.revoke();", "TypeError:foreign"), async = true)
        check(Case("var result=\"pending\";var resolve;var pending=new Promise(function(r){resolve=r});var r=Proxy.revocable(foreign.eval(\"(function(){})\"),{});pending.then(r.proxy).catch(function(e){result=kind(e)});r.revoke();resolve();", "TypeError:caller"), async = true)
        check(Case("var result=\"pending\";var p=new Proxy(foreign.eval(\"(function(){})\"),{apply:1});Promise.resolve({then:p}).catch(function(e){result=kind(e)});", "TypeError:foreign"), async = true)
        check(Case("var result=\"pending\";var r=Proxy.revocable(foreign.eval(\"(function(){})\"),{});Promise.resolve({then:r.proxy}).catch(function(e){result=kind(e)});r.revoke();", "TypeError:foreign"), async = true)
        check(Case("var result=\"pending\";var r=Proxy.revocable(foreign.eval(\"(function(){})\"),{});r.revoke();Promise.resolve({then:r.proxy}).catch(function(e){result=kind(e)});", "TypeError:caller"), async = true)
        check(Case("var result=\"pending\";var p=new Proxy(foreign.eval(\"(function(){})\"),{apply:1}).bind(null);Promise.resolve().then(p).catch(function(e){result=kind(e)});", "TypeError:foreign"), async = true)
        check(Case("var result=\"pending\";var then=foreign.eval(\"(function(resolve){caller.result=String(Object.getPrototypeOf(resolve)===Function.prototype);resolve()})\");Promise.resolve({then:then});", "true"), async = true)
        check(Case("var result=\"pending\";var p=new Proxy(foreign.eval(\"(function(){})\"),{apply:function(target,receiver,args){result=String(Object.getPrototypeOf(args)===foreign.Array.prototype);args[0]()}});Promise.resolve({then:p});", "true"), async = true)
    }

}
