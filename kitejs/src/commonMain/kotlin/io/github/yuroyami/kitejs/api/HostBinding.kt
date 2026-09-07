/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.LambdaConstructor
import io.github.yuroyami.kitejs.LambdaFunction
import io.github.yuroyami.kitejs.ScriptableObject
import io.github.yuroyami.kitejs.SerializableCallable
import io.github.yuroyami.kitejs.SerializableConstructable
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1

/** How a property behaves. The defaults match what `obj.x = 1` gives you in a script. */
public class PropertyFlags(public val writable: Boolean = true, public val enumerable: Boolean = true, public val configurable: Boolean = true)

private fun PropertyFlags.attributes(): Int {
    var a = 0
    if (!writable) a = a or ScriptableObject.READONLY
    if (!enumerable) a = a or ScriptableObject.DONTENUM
    if (!configurable) a = a or ScriptableObject.PERMANENT
    return a
}

private fun JsObject.asScriptableObject(): ScriptableObject =
    target as? ScriptableObject ?: throw JsEngineError("this object cannot take host bindings")

// ---- Values ---------------------------------------------------------------------------------

/** Puts a value on the object. */
public fun JsObject.property(name: String, value: Any?, flags: PropertyFlags = PropertyFlags()) {
    asScriptableObject().defineProperty(name, Converters.toEngine(value, liveContext(), target), flags.attributes())
}

/** Puts a value that a script can read but not change. */
public fun JsObject.constant(name: String, value: Any?) {
    property(name, value, PropertyFlags(writable = false, configurable = false))
}

// ---- Accessors ------------------------------------------------------------------------------

/** A property computed on every read. */
public fun JsObject.getter(name: String, flags: PropertyFlags = PropertyFlags(), read: () -> Any?) {
    val scope = target
    asScriptableObject().defineProperty(
        name,
        { Converters.toEngine(read(), liveContext(), scope) },
        null,
        flags.attributes(),
    )
}

/** A property that only accepts writes. */
public fun JsObject.setter(name: String, flags: PropertyFlags = PropertyFlags(), write: (JsValue) -> Unit) {
    asScriptableObject().defineProperty(name, null, { v -> write(JsValue(v)) }, flags.attributes())
}

/** A property with both halves. */
public fun JsObject.accessor(
    name: String,
    flags: PropertyFlags = PropertyFlags(),
    read: () -> Any?,
    write: (JsValue) -> Unit,
) {
    val scope = target
    asScriptableObject().defineProperty(
        name,
        { Converters.toEngine(read(), liveContext(), scope) },
        { v -> write(JsValue(v)) },
        flags.attributes(),
    )
}

// ---- Functions ------------------------------------------------------------------------------

/** A host function that takes the arguments as they came. */
public fun JsObject.function(
    name: String,
    arity: Int = 0,
    flags: PropertyFlags = PropertyFlags(enumerable = false),
    body: (List<JsValue>) -> Any?,
): JsFunction {
    val scope = scopeOf(target)
    val fn = LambdaFunction(
        scope,
        name,
        arity,
        SerializableCallable { cx, s, _, args -> Converters.toEngine(body(args.map { JsValue(it) }), cx, s) },
    )
    asScriptableObject().defineProperty(name, fn, flags.attributes())
    return JsFunction(fn)
}

/** A host function that also wants the `this` it was called on. */
public fun JsObject.method(
    name: String,
    arity: Int = 0,
    flags: PropertyFlags = PropertyFlags(enumerable = false),
    body: (self: JsValue, args: List<JsValue>) -> Any?,
): JsFunction {
    val scope = scopeOf(target)
    val fn = LambdaFunction(
        scope,
        name,
        arity,
        SerializableCallable { cx, s, thisObj, args ->
            Converters.toEngine(body(JsValue(thisObj), args.map { JsValue(it) }), cx, s)
        },
    )
    asScriptableObject().defineProperty(name, fn, flags.attributes())
    return JsFunction(fn)
}

/** A host constructor, callable with `new`. [build] fills in the object it is given. */
public fun JsObject.constructor(
    name: String,
    arity: Int = 0,
    flags: PropertyFlags = PropertyFlags(enumerable = false),
    build: (JsObject, List<JsValue>) -> Unit,
): JsFunction {
    val scope = scopeOf(target)
    val ctor = LambdaConstructor(
        scope,
        name,
        arity,
        SerializableConstructable { cx, s, args ->
            val obj = cx.newObject(s)
            build(JsObject(obj), args.map { JsValue(it) })
            obj
        },
    )
    asScriptableObject().defineProperty(name, ctor, flags.attributes())
    return JsFunction(ctor)
}

// ---- Nesting --------------------------------------------------------------------------------

/** A nested object, built by [build]. Returns it so you can keep a handle. */
public fun JsObject.obj(name: String, build: JsObject.() -> Unit = {}): JsObject {
    val child = JsObject(liveContext().newObject(scopeOf(target)))
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
        JsObject::class -> asObject()
        JsArray::class -> asArray()
        JsFunction::class -> asFunction()
        List::class -> asArray().toList()
        Map::class -> asObject().toMap()
        else -> toKotlin()
    }
    return out as T
}

private fun List<JsValue>.at(i: Int): JsValue = getOrElse(i) { JsValue.undefined }

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
