/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import io.github.yuroyami.kitejs.rhino.ScriptableObject.DescriptorInfo
import io.github.yuroyami.kitejs.rhino.ast.FunctionNode

/**
 * The `this` binding of a call to a derived class constructor (ECMAScript 2015, 8.1.1.3): empty
 * until super() fills it, once. The arrow functions made in the constructor, and eval code it
 * runs, share it, since they read `this` from the same function environment.
 */
internal class ThisBinding(val function: JSFunction, val newTarget: Scriptable) {

    var value: Scriptable? = null
        private set

    /** GetThisBinding: the value, or a ReferenceError while super() has not run. */
    fun get(): Scriptable = value ?: throw ScriptRuntime.referenceErrorById("msg.this.before.super")

    /** BindThisValue: a second super() finds the binding initialized and throws. */
    fun bind(v: Scriptable) {
        if (value != null) throw ScriptRuntime.referenceErrorById("msg.super.twice")
        value = v
    }
}

/**
 * A Private Name (ECMAScript 2022, 6.2.12), made fresh each time a class definition runs, so two
 * evaluations of one class body never see each other's private elements. A method or accessor is
 * the same function on every object that has it, so the name holds it rather than each object.
 */
internal class PrivateName(val description: String) {
    var kind: Int = FIELD
    var method: JSFunction? = null
    var getter: JSFunction? = null
    var setter: JSFunction? = null

    override fun toString(): String = description

    companion object {
        const val FIELD = 0
        const val METHOD = 1
        const val ACCESSOR = 2
    }
}

/** A ClassFieldDefinition Record: the field's key, and the function computing its value, if any. */
internal class ClassField(val key: Any, val initializer: Function?, val named: Boolean)

/** A class while its definition runs, between CLASS_BEGIN and CLASS_END. */
internal class ClassBuilder(val outerScope: Scriptable, val bindings: NativeObject, val classScope: Scriptable) {
    var protoParent: Scriptable? = null
    var constructorParent: Scriptable? = null
    lateinit var constructor: JSFunction
    lateinit var prototype: NativeObject
    var bindingName: String? = null
    val instanceFields = ArrayList<ClassField>()

    /** The private methods and accessors each instance gets, and those the class itself gets. */
    val instancePrivateMethods = ArrayList<PrivateName>()
    val staticPrivateMethods = ArrayList<PrivateName>()

    /** Static fields as [ClassField], static blocks as the function to call, in source order. */
    val staticElements = ArrayList<Any>()
}

/** The runtime half of class definitions (ECMAScript 2015, 14.5.14; ECMAScript 2022, 15.7.14). */
internal object ClassRuntime {

    /**
     * Steps 1 to 3: a scope of the class's own, which holds the class name once the class is
     * made. Its bindings object has no prototype, so no name resolves through Object.prototype.
     */
    fun begin(scope: Scriptable, bindingName: String?): ClassBuilder {
        val bindings = NativeObject()
        bindings.parentScope = scope
        bindings.prototype = null
        val builder = ClassBuilder(scope, bindings, NativeWith.create(scope, bindings))
        if (bindingName != null) {
            // Step 3.a: the class's own name is bound, uninitialized, while the heritage and the
            // computed keys run, so reading or writing it there is a ReferenceError.
            val uninitialized = { throw ScriptRuntime.referenceErrorById("msg.uninitialized.binding", bindingName) }
            bindings.defineProperty(bindingName, uninitialized, { _ -> uninitialized() }, 0)
            builder.bindingName = bindingName
        }
        return builder
    }

    /**
     * Steps 4 and 5 of ECMAScript 2022, 15.7.14: the class body's PrivateEnvironment, a scope
     * binding each private name, `#` and all, to a fresh [PrivateName]. A `#x` in the body looks
     * the name up like any identifier, and no identifier can be spelled with a `#`.
     */
    fun privateScope(scope: Scriptable, names: Array<String>): Scriptable {
        val bindings = NativeObject()
        bindings.parentScope = scope
        bindings.prototype = null
        for (name in names) bindings.defineProperty(name, PrivateName(name), ScriptableObject.READONLY or ScriptableObject.PERMANENT)
        return NativeWith.create(scope, bindings)
    }

    /** Step 6: the prototype's parent and the constructor's parent, from the heritage. */
    fun heritage(cx: Context, builder: ClassBuilder, superclass: Any?) {
        if (superclass == null) {
            builder.protoParent = null
            builder.constructorParent = ScriptableObject.getFunctionPrototype(builder.outerScope)
            return
        }
        if (!AbstractEcmaObjectOperations.isConstructor(superclass)) {
            throw ScriptRuntime.typeErrorById("msg.class.extends", describe(superclass))
        }
        val parent = superclass as Scriptable
        val protoParent = AbstractEcmaObjectOperations.get(cx, parent, "prototype", parent)
        if (protoParent != null && !ScriptRuntime.isObject(protoParent)) {
            throw ScriptRuntime.typeErrorById("msg.class.extends.proto", describe(protoParent))
        }
        builder.protoParent = protoParent as Scriptable?
        builder.constructorParent = parent
    }

    /**
     * Steps 7 to 16: the prototype object, and the constructor [f] made over it, which inherits
     * from the heritage, owns a read-only `prototype` and is the prototype's `constructor`.
     */
    fun defineConstructor(builder: ClassBuilder, f: JSFunction, hasHeritage: Boolean) {
        val top = ScriptableObject.getTopLevelScope(builder.outerScope)
        val proto = builder.prototype
        proto.prototype = if (hasHeritage) builder.protoParent else ScriptableObject.getObjectPrototype(top)
        if (hasHeritage) f.prototype = builder.constructorParent
        f.setImmunePrototypeProperty(proto)
        proto.defineProperty("constructor", f, ScriptableObject.DONTENUM)
        builder.constructor = f
    }

    /** A fresh prototype for the class, made before the constructor so methods can take it as home. */
    fun newPrototype(builder: ClassBuilder): NativeObject {
        val proto = NativeObject()
        proto.parentScope = ScriptableObject.getTopLevelScope(builder.outerScope)
        builder.prototype = proto
        return proto
    }

    /**
     * ClassElementEvaluation for one element: a method or accessor is defined on the prototype,
     * or on the constructor when static, with its name set from the key; a field is recorded with
     * its key evaluated now; a static block is recorded to run at the end.
     */
    fun defineElement(cx: Context, builder: ClassBuilder, flags: Int, key: Any?, fn: JSFunction?) {
        val isStatic = (flags and Icode.CLASS_ELEMENT_STATIC) != 0
        val home: ScriptableObject = if (isStatic) builder.constructor else builder.prototype
        if (key is PrivateName && (flags and Icode.CLASS_ELEMENT_KIND_MASK) != Icode.CLASS_ELEMENT_FIELD) {
            definePrivateMethod(builder, flags, key, fn!!, isStatic)
            return
        }
        when (flags and Icode.CLASS_ELEMENT_KIND_MASK) {
            Icode.CLASS_ELEMENT_METHOD -> {
                val propKey = ScriptRuntime.toPropertyKey(key)
                setFunctionName(fn!!, propKey, null)
                definePropertyOrThrow(cx, home, propKey, DescriptorInfo(false, true, true, fn))
            }
            Icode.CLASS_ELEMENT_GETTER -> {
                val propKey = ScriptRuntime.toPropertyKey(key)
                setFunctionName(fn!!, propKey, "get")
                definePropertyOrThrow(cx, home, propKey, DescriptorInfo(false, Scriptable.NOT_FOUND, true, fn, Scriptable.NOT_FOUND, Scriptable.NOT_FOUND))
            }
            Icode.CLASS_ELEMENT_SETTER -> {
                val propKey = ScriptRuntime.toPropertyKey(key)
                setFunctionName(fn!!, propKey, "set")
                definePropertyOrThrow(cx, home, propKey, DescriptorInfo(false, Scriptable.NOT_FOUND, true, Scriptable.NOT_FOUND, fn, Scriptable.NOT_FOUND))
            }
            Icode.CLASS_ELEMENT_FIELD -> {
                val fieldKey = if (key is PrivateName) key else ScriptRuntime.toPropertyKey(key)
                val field = ClassField(fieldKey, fn, (flags and Icode.CLASS_ELEMENT_NAMED) != 0)
                if (isStatic) builder.staticElements.add(field) else builder.instanceFields.add(field)
            }
            Icode.CLASS_ELEMENT_STATIC_BLOCK -> builder.staticElements.add(fn!!)
            else -> throw Kit.codeBug()
        }
    }

    /**
     * A private method or accessor: one function for every object that gets it, kept by its
     * Private Name, and the name recorded for the objects to be given (ECMAScript 2022, 15.4.4,
     * 15.4.5, PrivateElement records). A getter and a setter of one name make one accessor.
     */
    private fun definePrivateMethod(builder: ClassBuilder, flags: Int, name: PrivateName, fn: JSFunction, isStatic: Boolean) {
        when (flags and Icode.CLASS_ELEMENT_KIND_MASK) {
            Icode.CLASS_ELEMENT_METHOD -> {
                setFunctionName(fn, name, null)
                name.kind = PrivateName.METHOD
                name.method = fn
            }
            Icode.CLASS_ELEMENT_GETTER -> {
                setFunctionName(fn, name, "get")
                name.kind = PrivateName.ACCESSOR
                name.getter = fn
            }
            Icode.CLASS_ELEMENT_SETTER -> {
                setFunctionName(fn, name, "set")
                name.kind = PrivateName.ACCESSOR
                name.setter = fn
            }
            else -> throw Kit.codeBug()
        }
        val list = if (isStatic) builder.staticPrivateMethods else builder.instancePrivateMethods
        if (name !in list) list.add(name)
    }

    /**
     * Steps 27 to 34: the class name is bound for the class body, the instance fields and private
     * methods go to the constructor, the class gets its static private methods, and the static
     * fields and blocks run in order with the class as `this`.
     */
    fun end(cx: Context, builder: ClassBuilder): JSFunction {
        val f = builder.constructor
        builder.bindingName?.let {
            builder.bindings.delete(it)
            builder.bindings.initConstBinding(it, f)
        }
        if (builder.instanceFields.isNotEmpty()) f.classFields = builder.instanceFields.toTypedArray()
        if (builder.instancePrivateMethods.isNotEmpty()) f.classPrivateMethods = builder.instancePrivateMethods.toTypedArray()
        for (name in builder.staticPrivateMethods) privateMethodOrAccessorAdd(f, name)
        for (element in builder.staticElements) {
            if (element is ClassField) {
                defineField(cx, f, element)
            } else {
                val block = element as JSFunction
                block.call(cx, ScriptableObject.getTopLevelScope(block), f, ScriptRuntime.emptyArgs)
            }
        }
        return f
    }

    /** InitializeInstanceElements (ECMAScript 2022, 7.3.34): the fields [constructor] declares, on [o]. */
    fun initializeInstanceElements(cx: Context, o: Scriptable, constructor: JSFunction) {
        constructor.classPrivateMethods?.let { methods -> for (name in methods) privateMethodOrAccessorAdd(o, name) }
        val fields = constructor.classFields ?: return
        for (field in fields) defineField(cx, o, field)
    }

    /**
     * DefineField (ECMAScript 2022, 7.3.33): the initializer runs with [receiver] as `this`, and
     * its value becomes an own data property, defined rather than assigned, so no setter on the
     * prototype chain sees it.
     */
    private fun defineField(cx: Context, receiver: Scriptable, field: ClassField) {
        val init = field.initializer
        val value = if (init == null) {
            Undefined.instance
        } else {
            init.call(cx, ScriptableObject.getTopLevelScope(init), receiver, ScriptRuntime.emptyArgs)
        }
        if (field.named && value is BaseFunction) setFunctionName(value, field.key, null)
        if (field.key is PrivateName) {
            privateFieldAdd(receiver, field.key, value)
            return
        }
        val target = receiver as? ScriptableObject ?: throw ScriptRuntime.typeErrorById("msg.define.refused", ScriptRuntime.toString(field.key))
        definePropertyOrThrow(cx, target, field.key, DescriptorInfo(true, true, true, value))
    }

    /**
     * The constructor a derived class without one gets: Construct(F.[[GetPrototypeOf]](), args,
     * newTarget), then the class's fields on the result. The arguments are handed on as they are,
     * not spread through the array iterator (ECMAScript 2022, 15.7.14, step 14.a).
     */
    fun defaultDerivedConstruct(cx: Context, scope: Scriptable, f: JSFunction, args: Array<Any?>, newTarget: Scriptable): Scriptable {
        val result = superConstruct(cx, scope, f.prototype, args, newTarget)
        initializeInstanceElements(cx, result, f)
        return result
    }

    /** The middle of SuperCall evaluation: IsConstructor(func), then Construct(func, args, newTarget). */
    fun superConstruct(cx: Context, scope: Scriptable, func: Any?, args: Array<Any?>, newTarget: Scriptable): Scriptable {
        if (!AbstractEcmaObjectOperations.isConstructor(func)) {
            throw ScriptRuntime.typeErrorById("msg.super.not.ctor", describe(func))
        }
        return AbstractEcmaObjectOperations.construct(cx, scope, func as Constructable, args, newTarget)
    }

    /**
     * The end of [[Construct]] for a derived class constructor (ECMAScript 2015, 9.2.2, steps 13
     * to 15): an object it returns stands, anything else but undefined is a TypeError, and
     * otherwise `this`, which super() must have bound.
     */
    fun derivedConstructResult(result: Any?, binding: ThisBinding): Scriptable {
        if (result is Scriptable && result !== Undefined.SCRIPTABLE_UNDEFINED && ScriptRuntime.isObject(result)) return result
        if (!Undefined.isUndefined(result) && result !== Undefined.SCRIPTABLE_UNDEFINED) {
            throw ScriptRuntime.typeErrorById("msg.derived.ctor.return")
        }
        return binding.get()
    }

    /**
     * SetFunctionName: a symbol key names the function `[description]`, a private name by its
     * description, `#` included, and an accessor gets its prefix.
     */
    fun setFunctionName(fn: BaseFunction, key: Any, prefix: String?) {
        var name = if (key is PrivateName) {
            key.description
        } else if (key is Symbol) {
            val description = when (key) {
                is NativeSymbol -> key.key.description
                is SymbolKey -> key.description
                else -> key.name
            }
            if (Undefined.isUndefined(description)) "" else "[$description]"
        } else {
            ScriptRuntime.toString(key)
        }
        if (prefix != null) name = "$prefix $name"
        fn.setFunctionName(name)
    }

    // ---- Private elements (ECMAScript 2022, 7.3.26 to 7.3.32) ------------------------------------

    private fun privateElementsOf(o: Scriptable): HashMap<PrivateName, Any?>? = (o as? ScriptableObject)?.privateElements

    /** PrivateFieldAdd: a TypeError when the object has the field already, whatever its extensibility. */
    fun privateFieldAdd(o: Scriptable, name: PrivateName, value: Any?) {
        val target = o as? ScriptableObject ?: throw ScriptRuntime.typeErrorById("msg.private.host", name.description)
        val elements = target.privateElements ?: HashMap<PrivateName, Any?>().also { target.privateElements = it }
        if (elements.containsKey(name)) throw ScriptRuntime.typeErrorById("msg.private.twice", name.description)
        elements[name] = value
    }

    /** PrivateMethodOrAccessorAdd: the object gets the method's brand, which it may hold once. */
    fun privateMethodOrAccessorAdd(o: Scriptable, name: PrivateName) {
        val target = o as? ScriptableObject ?: throw ScriptRuntime.typeErrorById("msg.private.host", name.description)
        val elements = target.privateElements ?: HashMap<PrivateName, Any?>().also { target.privateElements = it }
        if (elements.containsKey(name)) throw ScriptRuntime.typeErrorById("msg.private.twice", name.description)
        elements[name] = null
    }

    /** PrivateElementFind, for `#x in o`. */
    fun hasPrivateElement(o: Any?, name: PrivateName): Boolean {
        if (o !is Scriptable || !ScriptRuntime.isObject(o)) {
            throw ScriptRuntime.typeErrorById("msg.private.in", name.description, ScriptRuntime.toString(o))
        }
        return privateElementsOf(o)?.containsKey(name) == true
    }

    /** PrivateGet: a field's value, a method, or what a getter returns. */
    fun privateGet(cx: Context, o: Scriptable, name: PrivateName): Any? {
        val elements = privateElementsOf(o)
        if (elements == null || !elements.containsKey(name)) throw ScriptRuntime.typeErrorById("msg.private.read", name.description)
        return when (name.kind) {
            PrivateName.FIELD -> elements[name]
            PrivateName.METHOD -> name.method
            else -> {
                val getter = name.getter ?: throw ScriptRuntime.typeErrorById("msg.private.no.getter", name.description)
                getter.call(cx, ScriptableObject.getTopLevelScope(getter), o, ScriptRuntime.emptyArgs)
            }
        }
    }

    /** PrivateSet: a field takes the value, a setter is called with it, and a method cannot be written. */
    fun privateSet(cx: Context, o: Scriptable, name: PrivateName, value: Any?) {
        val elements = privateElementsOf(o)
        if (elements == null || !elements.containsKey(name)) throw ScriptRuntime.typeErrorById("msg.private.write", name.description)
        when (name.kind) {
            PrivateName.FIELD -> elements[name] = value
            PrivateName.METHOD -> throw ScriptRuntime.typeErrorById("msg.private.method.write", name.description)
            else -> {
                val setter = name.setter ?: throw ScriptRuntime.typeErrorById("msg.private.no.setter", name.description)
                setter.call(cx, ScriptableObject.getTopLevelScope(setter), o, arrayOf(value))
            }
        }
    }

    /**
     * The private names direct eval code may use: those of every class body around the code
     * calling eval (ECMAScript 2022, 19.2.1.1, step 7, the PrivateEnvironment's names).
     */
    fun privateNamesInScope(scope: Scriptable?): Set<String>? {
        var result: HashSet<String>? = null
        var s = scope
        while (s != null) {
            if (s is NativeWith) {
                val bindings = s.prototype
                if (bindings is NativeObject && bindings.prototype == null) {
                    for (id in bindings.getIds()) {
                        if (id is String && id.startsWith('#') && bindings.get(id, bindings) is PrivateName) {
                            (result ?: HashSet<String>().also { result = it }).add(id)
                        }
                    }
                }
            }
            s = s.parentScope
        }
        return result
    }

    /**
     * Whether direct eval code runs in a class field initializer, where `arguments` is a syntax
     * error (ECMAScript 2022, 19.2.1.1, step 6). Like GetThisEnvironment, the walk looks
     * through arrow functions to the function that binds `this`.
     */
    fun inClassFieldInitializer(call: NativeCall?): Boolean {
        var c = call
        while (c != null) {
            val descriptor = c.function?.descriptor ?: return false
            if (descriptor.functionType != FunctionNode.ARROW_FUNCTION) return descriptor.isClassFieldInitializer
            var s = c.parentScope
            while (s != null && s !is NativeCall) s = s.parentScope
            c = s as NativeCall?
        }
        return false
    }

    /** Names a value for an error message without calling back into script. */
    private fun describe(value: Any?): String =
        if (value is Scriptable || ScriptRuntime.isSymbol(value)) ScriptRuntime.typeOf(value) else ScriptRuntime.toString(value)

    private fun definePropertyOrThrow(cx: Context, o: ScriptableObject, key: Any, desc: DescriptorInfo) {
        if (!o.defineOwnProperty(cx, key, desc)) {
            throw ScriptRuntime.typeErrorById("msg.define.refused", ScriptRuntime.toString(key))
        }
    }
}
