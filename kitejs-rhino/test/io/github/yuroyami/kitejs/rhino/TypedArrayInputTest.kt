/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.test.Test
import kotlin.test.assertEquals

/** ECMAScript iterable/array-like contracts, with fixed Node 26.10 controls. */
class TypedArrayInputTest {
    private fun check(expected: String, source: String) {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            assertEquals(expected, ScriptRuntime.toString(cx.evaluateString(cx.initStandardObjects(),
                source, "typed-array-input.js", 1, null)))
        } finally {
            Context.exit()
        }
    }

    @Test
    fun setsAndArrayLikeObjectsAreConstructorInputs() = check("7,9|7,9", """
        [new Uint8Array(new Set([7,9])).join(),new Uint8Array({length:2,0:7,1:9}).join()].join('|');
    """)

    @Test
    fun primitiveConstructorInputsUseToIndex() = check("1,0,2,2,0,0", """
        [new Uint8Array(true).length,new Uint8Array(false).length,new Uint8Array('2').length,new Uint8Array(2.9).length,new Uint8Array(null).length,new Uint8Array(undefined).length].join();
    """)

    @Test
    fun arraysObserveCustomIteratorsInBothPaths() = check("3:3", """
        var a=[7,9];a[Symbol.iterator]=function*(){yield 3};new Uint8Array(a).join()+':'+Uint8Array.from(a).join();
    """)

    @Test
    fun fromObservesTypedArrayIteratorsWhileConstructorsCopyInternals() = check("7,9:3:1", """
        var a=new Uint8Array([7,9]),calls=0;Object.defineProperty(a,Symbol.iterator,{get:function(){calls++;return function*(){yield 3}}});new Uint8Array(a).join()+':'+Uint8Array.from(a).join()+':'+calls;
    """)

    @Test
    fun nullAndUndefinedIteratorMethodsUseArrayLikeReads() = check("8|8|8|8", """
        var a={length:1,0:8},out=[];[null,undefined].forEach(function(v){a[Symbol.iterator]=v;out.push(new Uint8Array(a).join(),Uint8Array.from(a).join())});out.join('|');
    """)

    @Test
    fun arrayLikeReadsIncludeInheritedValuesAndHoles() = check("7,9,0:7,9,0", """
        var a=Object.create({1:9});a.length=3;a[0]=7;new Uint8Array(a).join()+':'+Uint8Array.from(a).join();
    """)

    @Test
    fun nativeArrayHolesReadTheirPrototype() = check("8:8", """
        var a=Array(1);Array.prototype[0]=8;try{new Uint8Array(a).join()+':'+Uint8Array.from(a).join()}finally{delete Array.prototype[0]};
    """)

    @Test
    fun iteratorAndNextAreLookedUpOnceAndReturnIsNeverRead() = check("6,1,1,2", """
        var reads=0,nextReads=0,calls=0,s={};Object.defineProperty(s,Symbol.iterator,{get:function(){reads++;return function(){if(this!==s)throw 1;return {get next(){nextReads++;return function(){return calls++?{done:true,get value(){throw 2}}:{value:6}}},get return(){throw 3}}}}});[new Uint8Array(s).join(),reads,nextReads,calls].join();
    """)

    @Test
    fun iterablesFinishBeforeElementConversion() = check("iter,next,next,convert", """
        var trace=[],v={valueOf:function(){trace.push('convert');return 4}},s={get length(){throw 1},[Symbol.iterator]:function(){trace.push('iter');var i=0;return {next:function(){trace.push('next');return i++?{done:true}:{value:v}},get return(){throw 2}}}};new Uint8Array(s);trace.join();
    """)

    @Test
    fun fromExhaustsBeforeConstructionMappingAndConversion() = check("next,next,ctor,map0,convert", """
        var trace=[],s={[Symbol.iterator]:function(){var i=0;return {next:function(){trace.push('next');return i++?{done:true}:{value:3}}}}};function C(n){trace.push('ctor');return new Uint8Array(n)};Uint8Array.from.call(C,s,function(v,i){trace.push('map'+i);return {valueOf:function(){trace.push('convert');return v+1}}});trace.join();
    """)

    @Test
    fun arrayLikeFromInterleavesGetMapAndConvertAfterLengthAndConstruction() = check("length,ctor,get0,map0,convert0,get1,map1,convert1", """
        var trace=[],s={get length(){trace.push('length');return 2},get 0(){trace.push('get0');return 3},get 1(){trace.push('get1');return 4}};function C(n){trace.push('ctor');return new Uint8Array(n)};Uint8Array.from.call(C,s,function(v,i){trace.push('map'+i);return {valueOf:function(){trace.push('convert'+i);return v}}});trace.join();
    """)

    @Test
    fun invalidIteratorMethodsThrowBeforeLengthAccess() = check("true", """
        var bad=[0,false,'',{},1n,Symbol()],ok=true;bad.forEach(function(v){var s={get length(){throw 1}};s[Symbol.iterator]=v;[function(){new Uint8Array(s)},function(){Uint8Array.from(s)}].forEach(function(f){try{f();ok=false}catch(e){ok=ok&&e instanceof TypeError}})});String(ok);
    """)

    @Test
    fun iteratorGetterErrorsRetainIdentity() = check("true", """
        var token={},s={get [Symbol.iterator](){throw token}},ok=true;[function(){new Uint8Array(s)},function(){Uint8Array.from(s)}].forEach(function(f){try{f();ok=false}catch(e){ok=ok&&e===token}});String(ok);
    """)

    @Test
    fun iteratorAndStepResultsMustBeObjects() = check("true", """
        var bad=[null,undefined,0,false,'x',1n,Symbol()],ok=true;bad.forEach(function(v){[function(){return v},function(){return {next:function(){return v}}}].forEach(function(iter){var s={[Symbol.iterator]:iter};[function(){new Uint8Array(s)},function(){Uint8Array.from(s)}].forEach(function(f){try{f();ok=false}catch(e){ok=ok&&e instanceof TypeError}})})});String(ok);
    """)

    @Test
    fun iteratorStepFailuresDoNotAcquireOrCallReturn() = check("true,0", """
        var token={},closed=0,ok=true;['next','done','value'].forEach(function(where){var s={[Symbol.iterator]:function(){return {next:function(){if(where==='next')throw token;return {get done(){if(where==='done')throw token;return false},get value(){throw token}}},get return(){closed++;throw 1}}}};[function(){new Uint8Array(s)},function(){Uint8Array.from(s)}].forEach(function(f){try{f();ok=false}catch(e){ok=ok&&e===token}})});ok+','+closed;
    """)

    @Test
    fun emptyTypedArrayCopiesStillCheckContentType() = check("true:0:0", """
        var ok=true;[function(){new Uint8Array(new BigInt64Array(0))},function(){new BigInt64Array(new Uint8Array(0))}].forEach(function(f){try{f();ok=false}catch(e){ok=ok&&e instanceof TypeError}});ok+':'+Uint8Array.from(new BigInt64Array(0)).length+':'+BigInt64Array.from(new Uint8Array(0)).length;
    """)

    @Test
    fun bigIntIterableValuesUseTheDestinationContentType() = check("true:2,3:2,3", """
        var ok=true;[function(){new Uint8Array(new Set([1n]))},function(){new BigInt64Array(new Set([1]))},function(){Uint8Array.from(new Set([1n]))},function(){BigInt64Array.from(new Set([1]))}].forEach(function(f){try{f();ok=false}catch(e){ok=ok&&e instanceof TypeError}});ok+':'+new BigInt64Array(new Set([2n,3n])).join()+':'+BigInt64Array.from(new Set([2n,3n])).join();
    """)

    @Test
    fun arrayLikeLengthIsReadOnceAndClampedBeforeAllocation() = check("7,9|1|0|0|true", """
        var count=0,s={get length(){count++;return 2},get 0(){return 7},get 1(){return 9}},ok=true;var out=new Uint8Array(s).join();[function(){new Uint8Array({length:Infinity})},function(){Uint8Array.from({length:Infinity})}].forEach(function(f){try{f();ok=false}catch(e){ok=ok&&e instanceof RangeError}});[out,count,new Uint8Array({length:-2}).length,Uint8Array.from({length:-2}).length,ok].join('|');
    """)

    @Test
    fun bufferInputsKeepTheirDirectConstructorBranch() = check("7,0", """
        var b=new ArrayBuffer(4);new Uint8Array(b)[1]=7;Object.defineProperty(b,Symbol.iterator,{get:function(){throw 1}});new Uint8Array(b,1,2).join();
    """)

    @Test
    fun detachedTypedArraysAreRejectedAsCopiesButFromUsesCustomIteration() = check("true:9", """
        var a=new Uint8Array(0);a.buffer.transfer();var ok=false;try{new Uint8Array(a)}catch(e){ok=e instanceof TypeError};a[Symbol.iterator]=function*(){yield 9};ok+':'+Uint8Array.from(a).join();
    """)

    @Test
    fun fromRequiresALiveTypedArrayResultOfSufficientLength() = check("true", """
        var ok=true;[function(n){return {}},function(n){return new Uint8Array(n-1)},function(n){var a=new Uint8Array(n);a.buffer.transfer();return a}].forEach(function(C){try{Uint8Array.from.call(C,[1]);ok=false}catch(e){ok=ok&&e instanceof TypeError}});try{Uint8Array.from.call(function(n){var a=new Uint8Array(0);a.buffer.transfer();return a},[]);ok=false}catch(e){ok=ok&&e instanceof TypeError};String(ok);
    """)

    @Test
    fun mapperValidationPrecedesSourceInspection() = check("true,0", """
        var seen=0,s={get [Symbol.iterator](){seen++;throw 1}},ok=false;try{Uint8Array.from(s,{})}catch(e){ok=e instanceof TypeError};ok+','+seen;
    """)

    @Test
    fun mappersReceiveIndicesAndTheExplicitObjectThisArgument() = check("true:3,5", """
        var receiver={},ok=true;var map=new Proxy(function(v,i){'use strict';ok=ok&&this===receiver;return v+i},{});var a=Uint8Array.from(new Set([3,4]),map,receiver);ok+':'+a.join();
    """)

    @Test
    fun sameTypeCopiesPreserveFloatingPointNaNPayloadBits() = check("7fc01234", """
        var a=new Float32Array(1);new DataView(a.buffer).setUint32(0,0x7fc01234,true);var b=new Float32Array(a);new DataView(b.buffer).getUint32(0,true).toString(16);
    """)

    @Test
    fun fromUsesTheStringIteratorAndConstructorUsesPrimitiveLength() = check("12:1,2:1,2", """
        new Uint8Array('12').length+':'+new Uint8Array(new String('12')).join()+':'+Uint8Array.from('12').join();
    """)

    @Test
    fun arrayIteratorsAdvanceBeforeAnElementGetterThrows() = check("true,true", """
        var token = {}, a = [0];
        Object.defineProperty(a,0,{get:function(){throw token}});
        var it = a.values(), same = false;
        try { it.next(); } catch(e) { same = e === token; }
        [same,it.next().done].join();
    """)

    @Test
    fun arrayEntriesAlsoReadInheritedElements() = check("0,8", """
        var a = Array(1);
        Array.prototype[0] = 8;
        try { a.entries().next().value.join(); } finally { delete Array.prototype[0]; }
    """)
}
