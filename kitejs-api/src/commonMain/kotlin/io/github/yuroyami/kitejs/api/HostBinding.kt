/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1

/** How a property behaves. The defaults match what `obj.x = 1` gives you in a script. */
public class PropertyFlags(public val writable: Boolean = true, public val enumerable: Boolean = true, public val configurable: Boolean = true)

// ---- Values ---------------------------------------------------------------------------------

/** Puts a value on the object. */
public fun JsObject.property(name: String, value: Any?, flags: PropertyFlags = PropertyFlags()) {
    defineValue(name, value, flags)
}

/** Puts a value that a script can read but not change. */
public fun JsObject.constant(name: String, value: Any?) {
    property(name, value, PropertyFlags(writable = false, configurable = false))
}

// ---- Accessors ------------------------------------------------------------------------------

/** A property computed on every read. */
public fun JsObject.getter(name: String, flags: PropertyFlags = PropertyFlags(), read: () -> Any?) {
    defineAccessor(name, read, null, flags)
}

/** A property that only accepts writes. */
public fun JsObject.setter(name: String, flags: PropertyFlags = PropertyFlags(), write: (JsValue) -> Unit) {
    defineAccessor(name, null, write, flags)
}

/** A property with both halves. */
public fun JsObject.accessor(
    name: String,
    flags: PropertyFlags = PropertyFlags(),
    read: () -> Any?,
    write: (JsValue) -> Unit,
) {
    defineAccessor(name, read, write, flags)
}

// ---- Functions ------------------------------------------------------------------------------

/** A host function that takes the arguments as they came. */
public fun JsObject.function(
    name: String,
    arity: Int = 0,
    flags: PropertyFlags = PropertyFlags(enumerable = false),
    body: (List<JsValue>) -> Any?,
): JsFunction {
    val fn = engine.newFunction(name, arity) { _, args -> body(args) }
    defineValue(name, fn, flags)
    return fn
}

/** A host function that also wants the `this` it was called on. */
public fun JsObject.method(
    name: String,
    arity: Int = 0,
    flags: PropertyFlags = PropertyFlags(enumerable = false),
    body: (self: JsValue, args: List<JsValue>) -> Any?,
): JsFunction {
    val fn = engine.newFunction(name, arity, body)
    defineValue(name, fn, flags)
    return fn
}

/** A host constructor, callable with `new`. [build] fills in the object it is given. */
public fun JsObject.constructor(
    name: String,
    arity: Int = 0,
    flags: PropertyFlags = PropertyFlags(enumerable = false),
    build: (JsObject, List<JsValue>) -> Unit,
): JsFunction {
    val ctor = engine.newConstructor(name, arity, build)
    defineValue(name, ctor, flags)
    return ctor
}

// ---- Nesting --------------------------------------------------------------------------------

/** A nested object, built by [build]. Returns it so you can keep a handle. */
public fun JsObject.obj(name: String, build: JsObject.() -> Unit = {}): JsObject {
    val child = engine.newObject()
    child.build()
    property(name, child)
    return child
}

// ---- Binding a Kotlin object ----------------------------------------------------------------

/** What [bind] gives you: a place to name the members a script should see. */
public class BindScope<T : Any> internal constructor(private val obj: JsObject, private val instance: T) {

    /** Exposes a read-only Kotlin property under its own name. */
    public fun property(name: String, prop: KProperty1<T, Any?>) {
        obj.getter(name) { prop.get(instance) }
    }

    /** Exposes a Kotlin property a script can also write. */
    public fun property(name: String, prop: KMutableProperty1<T, Any?>) {
        obj.accessor(name, read = { prop.get(instance) }, write = { v -> prop.set(instance, v.toKotlin()) })
    }

    /** Exposes a Kotlin function under [name]. */
    public fun method(name: String, arity: Int = 0, body: T.(List<JsValue>) -> Any?) {
        obj.function(name, arity) { args -> instance.body(args) }
    }
}

/**
 * Binds a Kotlin object into a fresh JavaScript object.
 *
 * ```
 * js.global.bind("clock", myClock) {
 *     property("now", Clock::now)
 *     method("tick") { advance(); Unit }
 * }
 * ```
 */
public fun <T : Any> JsObject.bind(name: String, instance: T, build: BindScope<T>.() -> Unit): JsObject {
    val child = obj(name)
    BindScope(child, instance).build()
    return child
}

// ---- Typed function shapes -------------------------------------------------------------------

/** Reads a [JsValue] as [T], coercing the way JavaScript would where that makes sense. */
public inline fun <reified T> JsValue.convertTo(): T {
    val out: Any? = when (T::class) {
        JsValue::class -> this
        Boolean::class -> asBoolean()
        Double::class -> asDouble()
        Float::class -> asDouble().toFloat()
        Int::class -> asInt()
        Long::class -> asLong()
        String::class -> asString()
        KBigInt::class -> asBigInt()
        JsObject::class -> asObject()
        JsArray::class -> asArray()
        JsFunction::class -> asFunction()
        JsSymbol::class -> asSymbol()
        List::class -> asArray().toList()
        Map::class -> asObject().toMap()
        else -> toKotlin()
    }
    return out as T
}

/** A one-argument host function, with the argument and the answer converted for you. */
public inline fun <reified A, reified R> JsObject.function(
    name: String,
    crossinline body: (A) -> R,
): JsFunction = function(name, 1) { args -> body(args.getOrElse(0) { JsValue.undefined }.convertTo<A>()) }

/** A two-argument host function. */
public inline fun <reified A, reified B, reified R> JsObject.function(
    name: String,
    crossinline body: (A, B) -> R,
): JsFunction = function(name, 2) { args ->
    body(
        args.getOrElse(0) { JsValue.undefined }.convertTo<A>(),
        args.getOrElse(1) { JsValue.undefined }.convertTo<B>(),
    )
}

/** A three-argument host function. */
public inline fun <reified A, reified B, reified C, reified R> JsObject.function(
    name: String,
    crossinline body: (A, B, C) -> R,
): JsFunction = function(name, 3) { args ->
    body(
        args.getOrElse(0) { JsValue.undefined }.convertTo<A>(),
        args.getOrElse(1) { JsValue.undefined }.convertTo<B>(),
        args.getOrElse(2) { JsValue.undefined }.convertTo<C>(),
    )
}
