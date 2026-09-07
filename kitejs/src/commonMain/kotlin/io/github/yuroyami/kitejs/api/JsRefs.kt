/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.api

import io.github.yuroyami.kitejs.Constructable
import io.github.yuroyami.kitejs.Context
import io.github.yuroyami.kitejs.Function
import io.github.yuroyami.kitejs.NativeArray
import io.github.yuroyami.kitejs.ScriptRuntime
import io.github.yuroyami.kitejs.Scriptable
import io.github.yuroyami.kitejs.ScriptableObject
import io.github.yuroyami.kitejs.SerializableCallable

/** The context the open engine entered. Every wrapper runs inside it. */
internal fun liveContext(): Context =
    Context.getCurrentContext() ?: throw JsEngineError("no engine is open on this thread")

/**
 * Runs [body] as an outermost call when nothing else is running, so a call the host starts sets up
 * the same top scope a script would. Inside a host callback there already is one, and starting a
 * second is not allowed, so this steps aside.
 */
internal inline fun <T> topCall(cx: Context, scope: Scriptable, crossinline body: () -> T): T {
    if (cx.topCallScope != null) return body()
    var out: Any? = null
    ScriptRuntime.doTopCall(
        SerializableCallable { _, _, _, _ -> out = body(); null },
        cx, scope, null, ScriptRuntime.emptyArgs,
    )
    @Suppress("UNCHECKED_CAST")
    return out as T
}

/**
 * A JavaScript object seen from Kotlin. Reading and writing goes straight through to the engine,
 * so a change here is a change the script sees.
 */
public open class JsObject internal constructor(internal val target: Scriptable) {

    /** The value this wrapper holds, for handing back to the engine. */
    public val value: JsValue get() = JsValue(target)

    public operator fun get(name: String): JsValue = JsValue(unwrapNotFound(ScriptableObject.getProperty(target, name)))

    public operator fun get(index: Int): JsValue = JsValue(unwrapNotFound(ScriptableObject.getProperty(target, index)))

    public operator fun set(name: String, value: Any?) {
        ScriptableObject.putProperty(target, name, Converters.toEngine(value, liveContext(), target))
    }

    public operator fun set(index: Int, value: Any?) {
        ScriptableObject.putProperty(target, index, Converters.toEngine(value, liveContext(), target))
    }

    public fun has(name: String): Boolean = ScriptableObject.hasProperty(target, name)

    public fun delete(name: String) {
        target.delete(name)
    }

    /** The object's own enumerable string keys, in the order a script would see them. */
    public val keys: List<String> get() = target.getIds().filterIsInstance<String>()

    /** Calls a method on this object, the way `obj.name(...)` does in a script. */
    public fun call(name: String, vararg args: Any?): JsValue {
        val cx = liveContext()
        val f = ScriptableObject.getProperty(target, name) as? Function
            ?: throw jsTypeError("$name is not a function")
        val scope = scopeOf(target)
        val engineArgs = Converters.toEngineAll(args, cx, target)
        return JsValue(topCall(cx, scope) { f.call(cx, scope, target, engineArgs) })
    }

    /** A plain Kotlin map, all the way down. Cycles come back as the [JsObject] that closed them. */
    public fun toMap(): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return Converters.toKotlin(target, HashSet()) as Map<String, Any?>
    }

    override fun toString(): String = ScriptRuntime.toString(target)

    override fun equals(other: Any?): Boolean = other is JsObject && other.target === target

    override fun hashCode(): Int = target.hashCode()
}

/** A JavaScript array seen from Kotlin. */
public class JsArray internal constructor(private val array: NativeArray) : JsObject(array) {

    public val size: Int get() = array.length.toInt()

    public fun add(value: Any?) {
        this[size] = value
    }

    public fun toList(): List<Any?> {
        @Suppress("UNCHECKED_CAST")
        return Converters.toKotlin(array, HashSet()) as List<Any?>
    }

    /** Every element as a [JsValue], without converting anything. */
    public fun values(): List<JsValue> = (0 until size).map { this[it] }
}

/** A JavaScript function seen from Kotlin. */
public class JsFunction internal constructor(private val function: Function) : JsObject(function) {

    /** The declared argument count, which is what `f.length` answers. */
    public val arity: Int get() = ScriptRuntime.toInt32(ScriptableObject.getProperty(function, "length"))

    /** Calls the function with `this` set to the global scope. */
    public operator fun invoke(vararg args: Any?): JsValue = callOn(null, *args)

    /** Calls the function with an explicit `this`. */
    public fun callOn(thisArg: Any?, vararg args: Any?): JsValue {
        val cx = liveContext()
        val scope = scopeOf(function)
        val self = when (thisArg) {
            null -> scope
            is JsObject -> thisArg.target
            else -> Converters.toEngine(thisArg, cx, scope) as? Scriptable ?: scope
        }
        val engineArgs = Converters.toEngineAll(args, cx, scope)
        return JsValue(topCall(cx, scope) { function.call(cx, scope, self, engineArgs) })
    }

    /** Calls it with `new`. */
    public fun construct(vararg args: Any?): JsObject {
        val cx = liveContext()
        val scope = scopeOf(function)
        val ctor = function as? Constructable ?: throw jsTypeError("this function is not a constructor")
        val engineArgs = Converters.toEngineAll(args, cx, scope)
        return JsObject(topCall(cx, scope) { ctor.construct(cx, scope, engineArgs) })
    }

    /** A new function with `this` and the leading arguments fixed, as `Function.prototype.bind` does. */
    public fun bind(thisArg: Any?, vararg args: Any?): JsFunction {
        val cx = liveContext()
        val scope = scopeOf(function)
        val self = if (thisArg is JsObject) thisArg.target else Converters.toEngine(thisArg, cx, scope) as? Scriptable
        val engineArgs = Converters.toEngineAll(args, cx, scope)
        return JsFunction(
            topCall(cx, scope) {
                io.github.yuroyami.kitejs.BoundFunction(cx, scope, function, self, engineArgs)
            },
        )
    }
}

/** A property read that found nothing reads back as `undefined`, not as the engine's sentinel. */
internal fun unwrapNotFound(value: Any?): Any? =
    if (value === Scriptable.NOT_FOUND) io.github.yuroyami.kitejs.Undefined.instance else value

internal fun scopeOf(obj: Scriptable): Scriptable = ScriptableObject.getTopLevelScope(obj)
