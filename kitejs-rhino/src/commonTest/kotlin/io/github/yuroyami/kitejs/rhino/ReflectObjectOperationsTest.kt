/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

/** Fixed Node/V8 controls for object operations shared by Reflect and host objects. */
class ReflectObjectOperationsTest {
    private fun check(source: String, expected: String) {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val caller = cx.initStandardObjects()
            val foreign = cx.initStandardObjects()
            caller.put("foreign", caller, foreign)
            foreign.put("caller", foreign, caller)
            assertEquals(expected, ScriptRuntime.toString(cx.evaluateString(caller, source, "reflect-objects.js", 1, null)), source)
        } finally {
            Context.exit()
        }
    }
    @Test
    fun argumentLists() {
        check("var log=[];var a=new Proxy({length:1,0:4},{has(){throw Error(\"has\")},get(t,k){log.push(String(k));return t[k]}});[Reflect.apply(function(x){return x},null,a),log.join(\"|\")].join(\":\")", "4:length|0")
        check("var log=[];var a=new Proxy({length:1,0:4},{has(){throw Error(\"has\")},get(t,k){log.push(String(k));return t[k]}});[Reflect.construct(function(x){this.x=x},a).x,log.join(\"|\")].join(\":\")", "4:length|0")
        check("var log=[];var a={get length(){log.push(\"length\");return {valueOf(){log.push(\"coerce\");return 2.9}}},get 0(){log.push(\"zero\");return 1},get 1(){log.push(\"one\");return 2},get [Symbol.iterator](){throw Error(\"iterate\")}};[Reflect.apply(function(){return [].join.call(arguments,\"|\")},{},a),log.join(\"|\")].join(\":\")", "1|2:length|coerce|zero|one")
        check("var log=[];var a={get length(){log.push(\"length\");return 0},get 0(){throw Error(\"index\")}};[Reflect.apply(function(){return arguments.length},null,a),log.join()].join(\":\")", "0:length")
        check("var a=Object.create({length:1,0:5});String(Reflect.apply(function(x){return x},null,a))", "5")
        check("String(Reflect.apply(function(){return arguments.length},null,{}))", "0")
        check("String(Reflect.construct(function(){this.n=arguments.length},{}).n)", "0")
        check("var log=[];var a={get length(){log.push(\"length\");return 1}};try{Reflect.apply({},null,a)}catch(e){e.name+\":\"+log.join()}", "TypeError:")
        check("var log=[];var a={get length(){log.push(\"length\");return 1}};try{Reflect.construct(()=>{},a)}catch(e){e.name+\":\"+log.join()}", "TypeError:")
        check("var log=[];var a={get length(){log.push(\"length\");return 1}};try{Reflect.construct(function(){},a,()=>{})}catch(e){e.name+\":\"+log.join()}", "TypeError:")
        check("var log=[];var f={[Symbol.toPrimitive](){log.push(\"convert\");return function(){}}};try{Reflect.apply(f,null,[])}catch(e){e.name+\":\"+log.join()}", "TypeError:")
        check("String(Reflect.apply(function(){\"use strict\";return this===null},null,[]))", "true")
        check("String(Reflect.apply(function(){\"use strict\";return this===undefined},undefined,[]))", "true")
        check("var o={};String(Reflect.apply(function(){\"use strict\";return this===o},o,[]))", "true")
    }

    @Test
    fun ownKeysAndDescriptors() {
        check("var s=Symbol(\"s\");var o={};o[s]=1;o.x=2;Reflect.ownKeys(o).map(String).join()", "x,Symbol(s)")
        check("var s=Symbol(\"s\");var log=[];var o={};Object.defineProperty(o,s,{enumerable:true,get(){log.push(\"s\");return 1}});Object.defineProperty(o,\"x\",{enumerable:true,get(){log.push(\"x\");return 2}});Object.assign({},o);log.join()", "x,s")
        check("var s=Symbol(\"s\");var log=[];var o={};Object.defineProperty(o,s,{enumerable:true,get(){log.push(\"s\");return 1}});Object.defineProperty(o,\"x\",{enumerable:true,get(){log.push(\"x\");return 2}});({...new Proxy(o,{})});log.join()", "x,s")
        check("var s=Symbol(\"s\");var o={};o[s]=1;o.x=2;Object.keys(o).join()+\":\"+Object.getOwnPropertySymbols(o).map(String).join()", "x:Symbol(s)")
        check("var o=Object.create(null);o.__proto__=3;var d=Object.getOwnPropertyDescriptors(o);[Object.getPrototypeOf(d)===Object.prototype,Object.prototype.hasOwnProperty.call(d,\"__proto__\"),d.__proto__.value].join()", "true,true,3")
        check("var s=Symbol(\"s\");var p=new Proxy({x:1,[s]:2},{ownKeys(){return [s,\"x\"]}});Reflect.ownKeys(p).map(String).join()", "Symbol(s),x")
        check("var o={};Object.defineProperty(o,\"hidden\",{value:3});[Object.keys(o).join(),Reflect.ownKeys(o).join(),Reflect.getOwnPropertyDescriptor(o,\"hidden\").value].join(\":\")", ":hidden:3")
        check("var s=Symbol();var o={[s]:7};var key={[Symbol.toPrimitive](){return s}};String(Object.getOwnPropertyDescriptor(o,key).value)", "7")
    }

    @Test
    fun descriptorConversion() {
        check("var log=[];var d=new Proxy({enumerable:true,configurable:true,value:3,writable:true},{has(t,k){log.push(\"h:\"+k);return k in t},get(t,k){log.push(\"g:\"+k);return t[k]}});var key={toString(){log.push(\"key\");return \"x\"}};Reflect.defineProperty({},key,d);log.join(\"|\")", "key|h:enumerable|g:enumerable|h:configurable|g:configurable|h:value|g:value|h:writable|g:writable|h:get|h:set")
        check("var log=[];try{Reflect.defineProperty({},\"x\",{get get(){log.push(\"get\");return 1},get set(){log.push(\"set\");return undefined}})}catch(e){e.name+\":\"+log.join()}", "TypeError:get")
        check("try{Reflect.defineProperty({},\"x\",{get get(){return 1},get set(){throw Error(\"setter read\")}})}catch(e){e.name}", "TypeError")
        check("var log=[];try{Object.defineProperty({},\"x\",{get get(){log.push(\"get\");return 1},get set(){log.push(\"set\");return undefined}})}catch(e){e.name+\":\"+log.join()}", "TypeError:get")
        check("var d=Object.create({value:4,writable:true,enumerable:true,configurable:true});var o={};[Reflect.defineProperty(o,\"x\",d),o.x,Object.getOwnPropertyDescriptor(o,\"x\").writable].join()", "true,4,true")
        check("var log=[];var t={};var d={get enumerable(){log.push(\"enum\");return true},get configurable(){log.push(\"config\");return true},get value(){log.push(\"value\");return 4},get writable(){log.push(\"write\");return true},get get(){log.push(\"get\");return undefined},get set(){log.push(\"set\");return undefined}};try{Reflect.defineProperty(t,\"x\",d)}catch(e){e.name+\":\"+log.join()+\":\"+(\"x\" in t)}", "TypeError:enum,config,value,write,get,set:false")
        check("var o={};String(Reflect.defineProperty(o,\"x\",{get:undefined,set:undefined}))+\":\"+String(o.x)", "true:undefined")
        check("var o={};var s=function(v){};Object.defineProperty(o,\"x\",{set:s});Object.defineProperty(o,\"x\",{get:undefined});var d=Object.getOwnPropertyDescriptor(o,\"x\");[Object.prototype.hasOwnProperty.call(d,\"get\"),d.get===undefined,d.set===s,d.configurable].join()", "true,true,true,false")
        check("var o={};var s=function(v){};Object.defineProperty(o,\"x\",{set:s});var ok=Reflect.defineProperty(o,\"x\",{get:undefined});var d=Reflect.getOwnPropertyDescriptor(o,\"x\");[ok,Object.prototype.hasOwnProperty.call(d,\"get\"),d.get===undefined,d.set===s].join()", "true,true,true,true")
    }

    @Test
    fun bulkDefinitions() {
        check("var p={get a(){Object.defineProperty(p,\"b\",{enumerable:false});return {value:1}},b:{value:2}};var o={};Object.defineProperties(o,p);Reflect.ownKeys(o).join()", "a")
        check("var p={get a(){Object.defineProperty(p,\"b\",{enumerable:true});return {value:1}}};Object.defineProperty(p,\"b\",{value:{value:2},configurable:true});var o={};Object.defineProperties(o,p);Reflect.ownKeys(o).join()", "a,b")
        check("var p={get a(){delete p.b;return {value:1}},b:{value:2}};var o={};Object.defineProperties(o,p);Reflect.ownKeys(o).join()", "a")
        check("var p={get a(){p.c={value:3};return {value:1}}};var o={};Object.defineProperties(o,p);Reflect.ownKeys(o).join()", "a")
        check("var o={};try{Object.defineProperties(o,{a:{value:1},b:{get:1}})}catch(e){e.name+\":\"+Reflect.ownKeys(o).length}", "TypeError:0")
        check("var log=[];var p=new Proxy({x:{value:1}},{ownKeys(){log.push(\"keys\");return [\"x\"]},getOwnPropertyDescriptor(){log.push(\"descriptor\");return {enumerable:false,configurable:true}},get(){log.push(\"get\");return {value:1}}});Object.defineProperties({},p);log.join()", "keys,descriptor")
    }

    @Test
    fun primitiveObjectChecks() {
        check("var s=Symbol();[Object.isExtensible(s),Object.isSealed(s),Object.isFrozen(s),Object.preventExtensions(s)===s,Object.seal(s)===s,Object.freeze(s)===s].join()", "false,true,true,true,true,true")
        check("var s=Object(Symbol());[Object.isExtensible(s),Reflect.isExtensible(s),Reflect.preventExtensions(s),Object.isExtensible(s),Object.isSealed(s)].join()", "true,true,true,false,true")
        check("[null,undefined,1,\"a\",true,1n,Symbol()].map(function(x){try{Reflect.ownKeys(x);return \"accepted\"}catch(e){return e.name}}).join()", "TypeError,TypeError,TypeError,TypeError,TypeError,TypeError,TypeError")
        check("var o=Object.create(null);[Reflect.setPrototypeOf(o,o),Reflect.getPrototypeOf(o)===null,Reflect.isExtensible(o),Reflect.preventExtensions(o),Reflect.setPrototypeOf(o,{})].join()", "false,true,true,true,false")
    }

    @Test
    fun descriptorRealms() {
        check("var result;var p=new Proxy({}, {defineProperty(t,k,d){result=Object.getPrototypeOf(d)===foreign.Object.prototype;return true}});foreign.Reflect.defineProperty(p,\"x\",{value:1,configurable:true});String(result)", "true")
        check("var result;var target={};var p=foreign.eval(\"new Proxy(caller.target,{defineProperty(t,k,d){caller.result=Object.getPrototypeOf(d)===caller.Object.prototype;return true}})\");Reflect.defineProperty(p,\"x\",{value:1,configurable:true});String(result)", "true")
    }

}
