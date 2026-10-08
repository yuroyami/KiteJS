/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.rhino.Rhino
import kotlin.test.Test
import kotlin.test.assertEquals

/** Fixed Node/V8 controls for Date's separate call and construction behavior. */
class DateInvocationTest {
    private fun check(source: String, expected: String) = KiteJs(Rhino).use { js ->
        assertEquals(expected, js.evaluate(source).asString(), source)
    }

    @Test
    fun ordinaryCallsIgnoreTheirReceiver() {
        check("typeof Date.call(null,0)", "string")
        check("typeof Date.apply(null,[0])", "string")
        check("typeof Date.bind(null)(0)", "string")
        check("typeof Reflect.apply(Date,null,[0])", "string")
        check("typeof Date.call(undefined,0)", "string")
        check("typeof Date.apply(undefined,[0])", "string")
        check("typeof Date.bind(undefined)(0)", "string")
        check("typeof Reflect.apply(Date,undefined,[0])", "string")
        check("typeof Date.call({},0)", "string")
        check("typeof Date.apply({},[0])", "string")
        check("typeof Date.bind({})(0)", "string")
        check("typeof Reflect.apply(Date,{},[0])", "string")
        check("typeof Date.call(new Date(0),0)", "string")
        check("typeof Date.apply(new Date(0),[0])", "string")
        check("typeof Date.bind(new Date(0))(0)", "string")
        check("typeof Reflect.apply(Date,new Date(0),[0])", "string")
        check("typeof Date.call(Object.create(null),0)", "string")
        check("typeof Date.apply(Object.create(null),[0])", "string")
        check("typeof Date.bind(Object.create(null))(0)", "string")
        check("typeof Reflect.apply(Date,Object.create(null),[0])", "string")
        check("typeof Date.call(1,0)", "string")
        check("typeof Date.apply(1,[0])", "string")
        check("typeof Date.bind(1)(0)", "string")
        check("typeof Reflect.apply(Date,1,[0])", "string")
        check("typeof Date.call(1n,0)", "string")
        check("typeof Date.apply(1n,[0])", "string")
        check("typeof Date.bind(1n)(0)", "string")
        check("typeof Reflect.apply(Date,1n,[0])", "string")
        check("typeof Date.call(true,0)", "string")
        check("typeof Date.apply(true,[0])", "string")
        check("typeof Date.bind(true)(0)", "string")
        check("typeof Reflect.apply(Date,true,[0])", "string")
        check("typeof Date.call(\"receiver\",0)", "string")
        check("typeof Date.apply(\"receiver\",[0])", "string")
        check("typeof Date.bind(\"receiver\")(0)", "string")
        check("typeof Reflect.apply(Date,\"receiver\",[0])", "string")
        check("typeof Date.call(Symbol(\"receiver\"),0)", "string")
        check("typeof Date.apply(Symbol(\"receiver\"),[0])", "string")
        check("typeof Date.bind(Symbol(\"receiver\"))(0)", "string")
        check("typeof Reflect.apply(Date,Symbol(\"receiver\"),[0])", "string")
        check("typeof Date(0)", "string")
        check("typeof (0,Date)(0)", "string")
        check("typeof Date?.(0)", "string")
        check("typeof Date.call()", "string")
        check("typeof Date.apply()", "string")
        check("typeof Reflect.apply(Date,null,[])", "string")
    }

    @Test
    fun constructionIsDistinct() {
        check("var Bound=Date.bind(null);var d=new Bound(0);[d instanceof Date,d.getTime()].join()", "true,0")
        check("var Bound=Date.bind(undefined);var d=new Bound(0);[d instanceof Date,d.getTime()].join()", "true,0")
        check("var Bound=Date.bind({});var d=new Bound(0);[d instanceof Date,d.getTime()].join()", "true,0")
        check("var Bound=Date.bind(new Date(0));var d=new Bound(0);[d instanceof Date,d.getTime()].join()", "true,0")
        check("var Bound=Date.bind(Object.create(null));var d=new Bound(0);[d instanceof Date,d.getTime()].join()", "true,0")
        check("var Bound=Date.bind(1);var d=new Bound(0);[d instanceof Date,d.getTime()].join()", "true,0")
        check("var Bound=Date.bind(1n);var d=new Bound(0);[d instanceof Date,d.getTime()].join()", "true,0")
        check("var Bound=Date.bind(true);var d=new Bound(0);[d instanceof Date,d.getTime()].join()", "true,0")
        check("var Bound=Date.bind(\"receiver\");var d=new Bound(0);[d instanceof Date,d.getTime()].join()", "true,0")
        check("var Bound=Date.bind(Symbol(\"receiver\"));var d=new Bound(0);[d instanceof Date,d.getTime()].join()", "true,0")
    }

    @Test
    fun argumentCoercionAndReentry() {
        check("var log=[];var value={[Symbol.toPrimitive](){log.push(1);throw Error(\"coerced\")}};[typeof Date.call(null,value),log.length].join()", "string,0")
        check("var log=[];var value={[Symbol.toPrimitive](){log.push(1);throw Error(\"coerced\")}};[typeof Date.apply(null,[value]),log.length].join()", "string,0")
        check("var log=[];var value={[Symbol.toPrimitive](){log.push(1);throw Error(\"coerced\")}};[typeof Date.bind(null)(value),log.length].join()", "string,0")
        check("var log=[];var value={[Symbol.toPrimitive](){log.push(1);throw Error(\"coerced\")}};[typeof Reflect.apply(Date,null,[value]),log.length].join()", "string,0")
        check("var log=[];var value={[Symbol.toPrimitive](){log.push(1);throw Error(\"coerced\")}};[typeof (new Proxy(Date,{})).call(null,value),log.length].join()", "string,0")
        check("var log=[];var d=new Date({valueOf(){log.push(typeof Date.call(null,0));return 0}});[d.getTime(),log.join()].join() ", "0,string")
        check("var log=[];var d=new Date({valueOf(){log.push(typeof Date.bind(null)(0));return 0}});[d.getTime(),log.join()].join() ", "0,string")
        check("var log=[];var d=new Date({valueOf(){log.push(new Date(1).getTime());return 0}});[d.getTime(),log.join()].join() ", "0,1")
        check("var obj={valueOf(){Date.call(null);throw Error(\"coercion\")}};try{new Date(obj)}catch(e){}typeof Date.call(null,0)", "string")
        check("var log=[];var d=new (Date.bind(null))({valueOf(){log.push(typeof Date.call(null));return 0}});[d.getTime(),log.join()].join() ", "0,string")
        check("var P=new Proxy(Date,{});[typeof P.call(null,0),new P(0).getTime()].join()", "string,0")
        check("var B=new Proxy(Date,{}).bind(null);[typeof B(0),new B(0).getTime()].join()", "string,0")
        check("var n=0;var receiver=new Proxy({}, {get(){n++;throw Error(\"receiver read\")}});[typeof Date.call(receiver,0),n].join()", "string,0")
        check("function F(){};var d=Reflect.construct(Date,[0],F);[Object.getPrototypeOf(d)===F.prototype,Date.prototype.getTime.call(d)].join()", "true,0")
    }

}
