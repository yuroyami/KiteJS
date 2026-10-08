/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals

class ReflectHostObjectTest {
    /** A host written against the original interface, without ScriptableObject storage. */
    private open class PlainHost(scope: Scriptable) : Scriptable {
        val values = linkedMapOf<Any, Any?>()
        override val className: String get() = "Host"
        override var parentScope: Scriptable? = scope
        override var prototype: Scriptable? = ScriptableObject.getObjectPrototype(scope)
        override fun get(name: String, start: Scriptable): Any? = values[name] ?: if (values.containsKey(name)) null else Scriptable.NOT_FOUND
        override fun get(index: Int, start: Scriptable): Any? = values[index] ?: if (values.containsKey(index)) null else Scriptable.NOT_FOUND
        override fun has(name: String, start: Scriptable): Boolean = values.containsKey(name)
        override fun has(index: Int, start: Scriptable): Boolean = values.containsKey(index)
        override fun put(name: String, start: Scriptable, value: Any?) { values[name] = value }
        override fun put(index: Int, start: Scriptable, value: Any?) { values[index] = value }
        override fun delete(name: String) { values.remove(name) }
        override fun delete(index: Int) { values.remove(index) }
        override fun getIds(): Array<Any?> = values.keys.toTypedArray()
        override fun getDefaultValue(hint: KClass<*>?): Any = "[object Host]"
        override fun hasInstance(instance: Scriptable): Boolean = false
    }

    /** A descriptor-aware host uses the protocol without inheriting the storage implementation. */
    private class RichHost(private val backing: ScriptableObject) : Scriptable by backing, SymbolScriptable by backing {
        private fun receiver(start: Scriptable): Scriptable = if (start === this) backing else start
        override fun put(name: String, start: Scriptable, value: Any?) = backing.put(name, receiver(start), value)
        override fun put(index: Int, start: Scriptable, value: Any?) = backing.put(index, receiver(start), value)
        override fun put(key: Symbol, start: Scriptable, value: Any?) = backing.put(key, receiver(start), value)
    }

    private class HostCallable(scope: Scriptable) : PlainHost(scope), Callable {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any =
            args.joinToString(":") { if (it is Scriptable) "[object ${it.className}]" else ScriptRuntime.toString(it) }
    }

    private class HostConstructor(scope: Scriptable) : PlainHost(scope), Constructable {
        override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable = cx.newObject(scope).also {
            it.put("answer", it, args.joinToString(":") { value -> ScriptRuntime.toString(value) })
        }
    }

    private fun check(source: String, expected: String, setup: (Context, ScriptableObject) -> Unit = { _, _ -> }) {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            val host = PlainHost(scope)
            host.values["x"] = 7.0
            host.values[0] = "zero"
            scope.put("host", scope, host)
            setup(cx, scope)
            assertEquals(expected, ScriptRuntime.toString(cx.evaluateString(scope, source, "reflect-host.js", 1, null)), source)
        } finally {
            Context.exit()
        }
    }

    @Test
    fun plainHostValuesAndReceivers() {
        check("[Reflect.get(host,'x'),Reflect.has(host,'x'),Reflect.get(host,0),Reflect.ownKeys(host).join('|'),Reflect.getPrototypeOf(host)===Object.prototype].join()",
            "7,true,zero,x|0,true")
        check("var r={};[Reflect.set(host,'x',9,r),host.x,r.x,Reflect.set(host,'y',2),host.y,Reflect.deleteProperty(host,'x'),Reflect.has(host,'x')].join()",
            "true,7,9,true,2,true,false")
        check("[Reflect.get(host,'x',null),Reflect.get(host,'x',undefined),Reflect.set(host,'x',1,null),Reflect.set(host,'x',1,undefined),host.x].join()",
            "7,7,false,false,7")
        check("JSON.stringify(Reflect.getOwnPropertyDescriptor(host,'x'))",
            "{\"value\":7,\"writable\":true,\"enumerable\":true,\"configurable\":true}")
    }

    @Test
    fun hostArgumentListsAndDescriptors() {
        val setup = { _: Context, scope: ScriptableObject ->
            val list = PlainHost(scope)
            list.values["length"] = 2.0
            list.values[0] = "first"
            list.values[1] = 4.0
            scope.put("list", scope, list)
            val desc = PlainHost(scope)
            desc.values["value"] = 12.0
            scope.put("desc", scope, desc)
            scope.put("empty", scope, PlainHost(scope))
        }
        check("Reflect.apply(function(a,b){return a+':'+b},{},list)", "first:4", setup)
        check("Reflect.construct(function(a,b){this.answer=a+':'+b},list).answer", "first:4", setup)
        check("Reflect.apply(function(){return String(arguments.length)},{},empty)", "0", setup)
        check("Reflect.construct(function(){this.count=arguments.length},empty).count", "0", setup)
        check("var o={};Reflect.defineProperty(o,'x',desc)+':'+o.x", "true:12", setup)
    }

    @Test
    fun legacyWithIsAcceptedAsAnObject() {
        check("[Reflect.getPrototypeOf(With.prototype)===Object.prototype,typeof Reflect.get(With.prototype,'toString'),Reflect.has(With.prototype,'toString'),Reflect.ownKeys(With.prototype).indexOf('toString')>=0].join()",
            "true,function,true,true")
    }

    @Test
    fun legacyDefaultsRefuseCapabilitiesTheHostCannotStore() {
        check("[Reflect.defineProperty(host,'x',{value:8}),host.x,Reflect.defineProperty(host,'x',{}),Reflect.defineProperty(host,'x',{writable:false}),Reflect.defineProperty(host,'x',{enumerable:false}),Reflect.defineProperty(host,'x',{configurable:false}),Reflect.defineProperty(host,'g',{get:function(){return 1}}),Reflect.defineProperty(host,'y',{value:3}),Reflect.has(host,'y')].join()",
            "true,8,true,false,false,false,false,false,false")
        check("[Reflect.defineProperty(host,'y',{value:null,writable:true,enumerable:true,configurable:true}),Reflect.defineProperty(host,'y',{enumerable:true}),Reflect.get(host,'y')===null].join()",
            "true,true,true")
        check("[Reflect.isExtensible(host),Reflect.preventExtensions(host),Reflect.isExtensible(host),Object.isExtensible(host),Object.isSealed(host),Object.isFrozen(host),Reflect.set(host,'new',1),host.new].join()",
            "true,false,true,true,false,false,true,1")
        check("['preventExtensions','seal','freeze'].map(function(k){try{Object[k](host);return 'accepted'}catch(e){return e.name}}).join()",
            "TypeError,TypeError,TypeError")
        check("[Reflect.get(host,Symbol())===undefined,Reflect.has(host,Symbol()),Reflect.set(host,Symbol(),1),Reflect.deleteProperty(host,Symbol())].join()",
            "true,false,false,true")
        check("var p={};[Reflect.setPrototypeOf(host,p),Reflect.getPrototypeOf(host)===p,Reflect.setPrototypeOf(host,host),Reflect.getPrototypeOf(host)===p,Reflect.setPrototypeOf(host,null),Reflect.getPrototypeOf(host)===null].join()",
            "true,true,false,true,true,true")
        check("var p={};[Object.setPrototypeOf(host,p)===host,Object.getPrototypeOf(host)===p].join()", "true,true")
    }

    @Test
    fun ordinaryObjectsUseTheHostsReceiverAndInheritedProperties() {
        check("var t={x:1};[Reflect.set(t,'x',5,host),t.x,host.x,Reflect.set(t,'z',6,host),host.z].join()", "true,1,5,true,6")
        check("Object.setPrototypeOf(host,{inherited:4});[Reflect.get(host,'inherited'),Reflect.has(host,'inherited'),Reflect.getOwnPropertyDescriptor(host,'inherited')===undefined,Reflect.deleteProperty(host,'inherited'),host.inherited].join()",
            "4,true,true,true,4")
        check("var p={};Object.defineProperty(p,'x',{value:1,writable:false});Object.setPrototypeOf(host,p);[Reflect.set(host,'x',8),host.x,Reflect.set(host,'y',9),host.y].join()",
            "true,8,true,9")
        check("var p={};Object.defineProperty(p,'missing',{value:1,writable:false});Object.setPrototypeOf(host,p);[Reflect.set(host,'missing',8),Reflect.getOwnPropertyDescriptor(host,'missing')===undefined,host.missing].join()",
            "false,true,1")
        check("var p={get inherited(){return this.x},set inherited(v){this.x=v}};Object.setPrototypeOf(host,p);[Reflect.get(host,'inherited'),Reflect.set(host,'inherited',10),host.x].join()",
            "7,true,10")
        check("Object.defineProperty(host,'x',{value:11});[host.x,Object.keys(host).join('|'),Object.getOwnPropertyNames(host).join('|'),Object.getOwnPropertySymbols(host).length,Object.getOwnPropertyDescriptor(host,'x').value,Object.getOwnPropertyDescriptors(host).x.value].join()",
            "11,x|0,x|0,0,11,11")
    }

    private val richSetup: (Context, ScriptableObject) -> Unit = { cx, scope ->
        val backing = cx.evaluateString(scope, "({x:7,tag:'host',get answer(){return this.tag},set answer(v){this.written=v}})", "rich-host.js", 1, null) as ScriptableObject
        scope.put("rich", scope, RichHost(backing))
        scope.put("delegator", scope, Delegator(backing))
        scope.put("withHost", scope, NativeWith.create(scope, backing))
    }

    @Test
    fun richDescriptorsAccessorsAndIntegrityAreDispatchedToTheHost() {
        check("var receiver={tag:'receiver'};[Reflect.get(rich,'answer',receiver),Reflect.set(rich,'answer',5,receiver),receiver.written,rich.written===undefined].join()",
            "receiver,true,5,true", richSetup)
        check("Reflect.defineProperty(rich,'secret',{value:3});[Reflect.get(rich,'secret'),Reflect.getOwnPropertyDescriptor(rich,'secret').writable,Object.keys(rich).indexOf('secret'),Reflect.ownKeys(rich).indexOf('secret')>=0,Reflect.set(rich,'secret',4),Reflect.deleteProperty(rich,'secret')].join()",
            "3,false,-1,true,false,false", richSetup)
        check("var s=Symbol();Reflect.defineProperty(rich,s,{value:null,writable:true,enumerable:true,configurable:true});[Reflect.get(rich,s)===null,Reflect.has(rich,s),Reflect.set(rich,s,8),Reflect.get(rich,s),Reflect.ownKeys(rich).indexOf(s)>=0,Object.getOwnPropertySymbols(rich)[0]===s,Reflect.deleteProperty(rich,s)].join()",
            "true,true,true,8,true,true,true", richSetup)
        check("[Object.freeze(rich)===rich,Reflect.isExtensible(rich),Object.isFrozen(rich),Reflect.set(rich,'x',8),Reflect.defineProperty(rich,'y',{value:1}),Reflect.deleteProperty(rich,'x'),Reflect.setPrototypeOf(rich,{})].join()",
            "true,false,true,false,false,false,false", richSetup)
        check("[Object.seal(rich)===rich,Object.isSealed(rich),Object.isFrozen(rich),Reflect.set(rich,'x',8),rich.x].join()",
            "true,true,false,true,8", richSetup)
    }

    @Test
    fun wrappersForwardMetadataAndMutations() {
        for (name in listOf("delegator", "withHost")) {
            check("var o=$name;Reflect.defineProperty(o,'secret',{value:2});[Reflect.getOwnPropertyDescriptor(o,'secret').configurable,Reflect.ownKeys(o).indexOf('secret')>=0,Reflect.set(o,'secret',9),Reflect.deleteProperty(o,'secret'),Reflect.preventExtensions(o),Reflect.isExtensible(o),Object.isExtensible(o)].join()",
                "false,true,false,false,true,false,false", richSetup)
        }
    }

    @Test
    fun proxyForwardingUsesHostDescriptorsAndCapabilities() {
        check("var p=new Proxy(host,{});[Reflect.get(p,'x'),Reflect.set(p,'x',8),host.x,Reflect.set(p,'y',9),host.y,Reflect.ownKeys(p).join('|'),Reflect.preventExtensions(p),Reflect.isExtensible(p)].join()",
            "7,true,8,true,9,x|0|y,false,true")
        check("Object.defineProperty(rich,'locked',{value:1});var p=new Proxy(rich,{get:function(){return 2}});try{Reflect.get(p,'locked')}catch(e){e.name}",
            "TypeError", richSetup)
        check("Object.freeze(rich);var p=new Proxy(rich,{isExtensible:function(){return true}});try{Reflect.isExtensible(p)}catch(e){e.name}",
            "TypeError", richSetup)
        check("var p=new Proxy(host,{getOwnPropertyDescriptor:function(){return desc}});Reflect.getOwnPropertyDescriptor(p,'x').value", "7") { _, scope ->
            val desc = PlainHost(scope)
            desc.values.putAll(mapOf("value" to 7.0, "writable" to true, "enumerable" to true, "configurable" to true))
            scope.put("desc", scope, desc)
        }
    }

    @Test
    fun callableAndConstructableHostsRemainObjects() {
        val setup = { _: Context, scope: ScriptableObject ->
            scope.put("fn", scope, HostCallable(scope))
            scope.put("ctor", scope, HostConstructor(scope))
        }
        check("[Reflect.isExtensible(fn),Reflect.apply(fn,{},['a',2]),Reflect.getPrototypeOf(fn)===Object.prototype,Reflect.ownKeys(fn).length].join()",
            "true,a:2,true,0", setup)
        check("var p=new Proxy(fn,{});[typeof p,Reflect.apply(p,{},['a',2])].join()", "function,a:2", setup)
        check("[Reflect.construct(ctor,['a',2]).answer,Reflect.construct(new Proxy(ctor,{}),['a',2]).answer].join()", "a:2,a:2", setup)
        check("var handler=Object.create(null);handler.get=fn;Reflect.get(new Proxy(host,handler),'x')", "[object Host]:x:[object Host]", setup)
    }

    @Test
    fun refusedHostWritesAreReportedAndExceptionsPropagate() {
        val setup = { _: Context, scope: ScriptableObject ->
            val refused = object : PlainHost(scope) {
                override fun defineOwnPropertyOrFalse(cx: Context, id: Any?, desc: ScriptableObject.DescriptorInfo): Boolean = false
            }
            refused.values["x"] = 1.0
            scope.put("refused", scope, refused)
            val throwing = object : PlainHost(scope) {
                override fun defineOwnPropertyOrFalse(cx: Context, id: Any?, desc: ScriptableObject.DescriptorInfo): Boolean = throw ScriptRuntime.typeError("host failure")
            }
            scope.put("throwing", scope, throwing)
        }
        check("[Reflect.set(refused,'x',2),refused.x,Reflect.set(refused,'y',3),Reflect.defineProperty(refused,'x',{value:2})].join()", "false,1,false,false", setup)
        check("try{Object.defineProperty(refused,'x',{value:2})}catch(e){e.name}", "TypeError", setup)
        check("try{Reflect.defineProperty(throwing,'x',{value:2})}catch(e){e.message}", "host failure", setup)
    }

    @Test
    fun argumentListReadsFollowGetWithoutHasOrIteration() {
        val setup = { _: Context, scope: ScriptableObject ->
            val events = mutableListOf<String>()
            val list = object : PlainHost(scope) {
                override fun get(name: String, start: Scriptable): Any? { events.add("get:$name"); return super.get(name, start) }
                override fun get(index: Int, start: Scriptable): Any? { events.add("get:$index"); return super.get(index, start) }
                override fun has(name: String, start: Scriptable): Boolean { events.add("has:$name"); return super.has(name, start) }
            }
            list.values["length"] = 2.0
            list.values[0] = "a"
            list.values[1] = "b"
            scope.put("list", scope, list)
            scope.put("events", scope, LambdaFunction(scope, 0, SerializableCallable { _, _, _, _ -> events.joinToString("|") }))
        }
        check("Reflect.apply(function(a,b){return a+b},{},list)+':'+events()", "ab:get:length|get:0|get:1", setup)
        check("Reflect.construct(function(a,b){this.answer=a+b},list).answer+':'+events()", "ab:get:length|get:0|get:1", setup)
        check("try{Reflect.apply({},null,list)}catch(e){e.name+':'+events()}", "TypeError:", setup)
    }
}
