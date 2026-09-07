/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * A function that can be used as a constructor, with the construction handed off to a lambda.
 * [LambdaFunction] adds the prototype methods the same way.
 *
 * This is how the built-in classes are written. Upstream measured it at roughly 15% faster than
 * `IdScriptableObject` and 25% faster than the reflection-based `defineClass`, and the resulting
 * code reads much closer to the JavaScript it implements.
 */
open class LambdaConstructor : LambdaFunction {

    protected val targetConstructor: SerializableConstructable?
    private val flags: Int

    /** Callable both with and without `new`, and either way it returns a wired-up new object. */
    constructor(scope: Scriptable, name: String, length: Int, target: SerializableConstructable) :
        super(scope, name, length, null as SerializableCallable?) {
        this.targetConstructor = target
        this.flags = CONSTRUCTOR_DEFAULT
    }

    /**
     * [flags] says which of `new` and a direct call are allowed. Whichever is allowed behaves the
     * same way; the other throws a TypeError.
     */
    constructor(
        scope: Scriptable,
        name: String,
        length: Int,
        flags: Int,
        target: SerializableConstructable,
    ) : super(scope, name, length, null as SerializableCallable?) {
        this.targetConstructor = target
        this.flags = flags
    }

    /**
     * Behaves differently with and without `new`: `new` returns a wired-up object, a direct call
     * does whatever [target] says. `Date` is the standard example.
     */
    constructor(
        scope: Scriptable,
        name: String,
        length: Int,
        target: SerializableCallable?,
        targetConstructor: SerializableConstructable?,
    ) : super(scope, name, length, target, true) {
        this.targetConstructor = targetConstructor
        this.flags =
            (if (target != null) CONSTRUCTOR_FUNCTION else 0) or
                (if (targetConstructor != null) CONSTRUCTOR_NEW else 0)
    }

    constructor(
        scope: Scriptable,
        name: String,
        length: Int,
        prototype: Any?,
        target: SerializableCallable?,
        targetConstructor: SerializableConstructable?,
    ) : super(scope, name, length, target, false) {
        setPrototypeProperty(prototype)
        this.targetConstructor = targetConstructor
        this.flags =
            (if (target != null) CONSTRUCTOR_FUNCTION else 0) or
                (if (targetConstructor != null) CONSTRUCTOR_NEW else 0)
    }

    protected fun getTargetConstructor(): Constructable? = targetConstructor

    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        if ((flags and CONSTRUCTOR_FUNCTION) == 0) {
            throw ScriptRuntime.typeErrorById("msg.constructor.no.function", functionName)
        }
        val declScope = declarationScope!!
        val t = getTarget()
        return t?.call(cx, declScope, thisObj, args) ?: fireConstructor(cx, declScope, args)
    }

    override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
        if ((flags and CONSTRUCTOR_NEW) == 0) {
            throw ScriptRuntime.typeErrorById("msg.no.new", functionName)
        }
        return fireConstructor(cx, declarationScope!!, args)
    }

    private fun fireConstructor(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable {
        val obj = targetConstructor!!.construct(cx, scope, args)
        obj.prototype = getClassPrototype()
        obj.parentScope = scope
        return obj
    }

    // ---- Prototype methods ---------------------------------------------------------------------

    fun definePrototypeMethod(
        scope: Scriptable,
        name: String,
        length: Int,
        target: SerializableCallable,
        attributes: Int = DONTENUM,
        propertyAttributes: Int = DONTENUM or READONLY,
    ) {
        val f = LambdaFunction(scope, name, length, target, false)
        f.setStandardPropertyAttributes(propertyAttributes)
        getPrototypeScriptable().defineProperty(name, f, attributes)
    }

    fun definePrototypeMethod(
        scope: Scriptable,
        name: SymbolKey,
        length: Int,
        target: SerializableCallable,
        attributes: Int = DONTENUM,
        propertyAttributes: Int = DONTENUM or READONLY,
    ) {
        val f = LambdaFunction(scope, "[" + name.name + "]", length, target, false)
        f.setStandardPropertyAttributes(propertyAttributes)
        getPrototypeScriptable().defineProperty(name, f, attributes)
    }

    fun definePrototypeMethod(
        scope: Scriptable,
        name: String,
        length: Int,
        prototype: Any?,
        target: SerializableCallable,
        attributes: Int,
        propertyAttributes: Int,
    ) {
        val f = LambdaFunction(scope, name, length, prototype, target)
        f.setStandardPropertyAttributes(propertyAttributes)
        getPrototypeScriptable().defineProperty(name, f, attributes)
    }

    fun definePrototypeMethod(
        scope: Scriptable,
        name: SymbolKey,
        length: Int,
        prototype: Any?,
        target: SerializableCallable,
        attributes: Int,
        propertyAttributes: Int,
    ) {
        val f = LambdaFunction(scope, "[" + name.name + "]", length, prototype, target)
        f.setStandardPropertyAttributes(propertyAttributes)
        getPrototypeScriptable().defineProperty(name, f, attributes)
    }

    /** Same as [definePrototypeMethod], but the engine can recognise the result by its tag. */
    fun defineKnownBuiltInPrototypeMethod(
        tag: Any,
        scope: Scriptable,
        name: String,
        length: Int,
        prototype: Any?,
        target: SerializableCallable,
        attributes: Int,
        propertyAttributes: Int,
    ) {
        val f = KnownBuiltInFunction(tag, scope, name, length, prototype, target)
        f.setStandardPropertyAttributes(propertyAttributes)
        getPrototypeScriptable().defineProperty(name, f, attributes)
    }

    // ---- Prototype properties -------------------------------------------------------------------

    fun definePrototypeProperty(name: String, value: Any?, attributes: Int) {
        getPrototypeScriptable().defineProperty(name, value, attributes)
    }

    fun definePrototypeProperty(key: Symbol, value: Any?, attributes: Int) {
        getPrototypeScriptable().defineProperty(key, value, attributes)
    }

    fun definePrototypeProperty(cx: Context, name: String, descriptor: ScriptableObject) {
        getPrototypeScriptable().defineOwnProperty(cx, name, descriptor)
    }

    fun definePrototypeProperty(cx: Context, key: Symbol, descriptor: ScriptableObject) {
        getPrototypeScriptable().defineOwnProperty(cx, key, descriptor)
    }

    /**
     * Defines a prototype property through a getter. The result looks exactly like a property
     * defined with `Object.defineProperty` and an accessor descriptor.
     */
    fun definePrototypeProperty(
        cx: Context,
        name: String,
        getter: LambdaGetterFunction,
        attributes: Int = DONTENUM or READONLY,
    ) {
        getPrototypeScriptable().defineProperty(cx, name, getter, null, attributes)
    }

    fun definePrototypeProperty(
        cx: Context,
        key: Symbol,
        getter: LambdaGetterFunction,
        attributes: Int,
    ) {
        getPrototypeScriptable().defineProperty(cx, key, getter, null, attributes)
    }

    /** The getter and setter pair version of [definePrototypeProperty]. */
    fun definePrototypeProperty(
        cx: Context,
        name: String,
        getter: LambdaGetterFunction?,
        setter: LambdaSetterFunction?,
        attributes: Int = DONTENUM,
    ) {
        getPrototypeScriptable().defineProperty(cx, name, getter, setter, attributes)
    }

    fun definePrototypeProperty(
        cx: Context,
        key: Symbol,
        getter: LambdaGetterFunction?,
        setter: LambdaSetterFunction?,
        attributes: Int = DONTENUM,
    ) {
        getPrototypeScriptable().defineProperty(cx, key, getter, setter, attributes)
    }

    /** Gives [alias] the same value as the property already named [name]. */
    fun definePrototypeAlias(name: String, alias: SymbolKey, attributes: Int) {
        val proto = getPrototypeScriptable()
        proto.defineProperty(alias, proto.get(name, proto), attributes)
    }

    fun definePrototypeAlias(name: String, alias: String, attributes: Int) {
        val proto = getPrototypeScriptable()
        proto.defineProperty(alias, proto.get(name, proto), attributes)
    }

    // ---- Methods on the constructor itself ------------------------------------------------------

    fun defineConstructorMethod(
        scope: Scriptable,
        name: String,
        length: Int,
        target: SerializableCallable,
        attributes: Int = DONTENUM,
        propertyAttributes: Int = DONTENUM or READONLY,
    ) {
        val f = LambdaFunction(scope, name, length, target, false)
        f.setStandardPropertyAttributes(propertyAttributes)
        defineProperty(name, f, attributes)
    }

    /** Upstream ignores [key] here and defines the method under [name], which this copies. */
    fun defineConstructorMethod(
        scope: Scriptable,
        key: Symbol,
        name: String,
        length: Int,
        target: SerializableCallable,
    ) {
        defineConstructorMethod(scope, name, length, target)
    }

    fun defineConstructorMethod(
        scope: Scriptable,
        name: String,
        length: Int,
        prototype: Any?,
        target: SerializableCallable,
        attributes: Int,
        propertyAttributes: Int,
    ) {
        val f = LambdaFunction(scope, name, length, prototype, target)
        f.setStandardPropertyAttributes(propertyAttributes)
        defineProperty(name, f, attributes)
    }

    /**
     * Swaps the plain `Object` prototype for one of a particular kind. Only a few built-ins need
     * this, `Boolean` among them, because their prototype has to carry an internal slot.
     */
    fun setPrototypeScriptable(proto: ScriptableObject) {
        proto.parentScope = declarationScope
        setPrototypeProperty(proto)
        val objectProto = getObjectPrototype(this)
        // The object just made has to stay grounded.
        if (proto !== objectProto) proto.prototype = objectProto
        proto.defineProperty("constructor", this, DONTENUM)
    }

    private fun getPrototypeScriptable(): ScriptableObject =
        prototypeProperty as? ScriptableObject
            ?: throw ScriptRuntime.typeError("Not properly a lambda constructor")

    companion object {
        /** The constructor may be called as an ordinary function. */
        const val CONSTRUCTOR_FUNCTION = 1

        /** The constructor may be called with `new`. */
        const val CONSTRUCTOR_NEW = 1 shl 1

        /** Both, which is the default. */
        const val CONSTRUCTOR_DEFAULT = CONSTRUCTOR_FUNCTION or CONSTRUCTOR_NEW

        /**
         * Casts `this` to [T], with a TypeError when it does not fit. Lambda implementations need
         * this because JavaScript's `this` is not guaranteed to be an instance of anything.
         */
        inline fun <reified T : Any> convertThisObject(thisObj: Scriptable?): T =
            thisObj as? T
                ?: throw ScriptRuntime.typeErrorById("msg.this.not.instance", T::class.simpleName)
    }
}
