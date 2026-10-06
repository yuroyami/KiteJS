/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino.facade

import io.github.yuroyami.kitejs.api.JsArray
import io.github.yuroyami.kitejs.api.JsEngineError
import io.github.yuroyami.kitejs.api.JsFunction
import io.github.yuroyami.kitejs.api.JsObject
import io.github.yuroyami.kitejs.api.JsScript
import io.github.yuroyami.kitejs.api.JsSymbol
import io.github.yuroyami.kitejs.api.JsValue
import io.github.yuroyami.kitejs.api.PrimitiveHint
import io.github.yuroyami.kitejs.api.PropertyFlags
import io.github.yuroyami.kitejs.rhino.BoundFunction
import io.github.yuroyami.kitejs.rhino.Callable
import io.github.yuroyami.kitejs.rhino.NativeArray
import io.github.yuroyami.kitejs.rhino.RhinoException
import io.github.yuroyami.kitejs.rhino.Script
import io.github.yuroyami.kitejs.rhino.ScriptRuntime
import io.github.yuroyami.kitejs.rhino.Scriptable
import io.github.yuroyami.kitejs.rhino.ScriptableObject
import io.github.yuroyami.kitejs.rhino.SymbolKey
import io.github.yuroyami.kitejs.rhino.typedarrays.NativeArrayBuffer
import io.github.yuroyami.kitejs.rhino.typedarrays.NativeDataView
import io.github.yuroyami.kitejs.rhino.typedarrays.NativeTypedArrayView

/*
 * The handles a Rhino engine gives out. JsObject, JsArray and JsFunction are classes, so the three
 * cannot share a base here; each forwards to the functions on RhinoHandle instead. Reading and
 * writing a property goes through the same runtime functions `obj[key]` does in a script, so an
 * index-like name such as "0" reaches the element it names.
 */

/** What every Rhino handle holds: its engine and the engine object. */
internal interface RhinoHandle {
    val engine: RhinoKiteJs
    val target: Scriptable

    fun getAt(name: String): JsValue = engine.call(drain = false) {
        engine.toJs(ScriptRuntime.getObjectElem(target, name, engine.cx))
    }

    fun getAt(index: Int): JsValue = engine.call(drain = false) {
        engine.toJs(ScriptRuntime.getObjectElem(target, index, engine.cx))
    }

    fun setAt(name: String, value: Any?) {
        engine.call(drain = false) { ScriptRuntime.setObjectElem(target, name, engine.toRhino(value), engine.cx) }
    }

    fun setAt(index: Int, value: Any?) {
        engine.call(drain = false) { ScriptRuntime.setObjectElem(target, index, engine.toRhino(value), engine.cx) }
    }

    fun hasKey(name: String): Boolean = engine.call(drain = false) { ScriptRuntime.hasObjectElem(target, name, engine.cx) }

    fun deleteKey(name: String) {
        engine.call(drain = false) { ScriptRuntime.deleteObjectElem(target, name, engine.cx) }
    }

    /** What `Object.keys` answers: the own enumerable ids, each as a string. */
    fun ownKeys(): List<String> = engine.call(drain = false) { target.getIds().map { ScriptRuntime.toString(it) } }

    fun callMethod(name: String, args: Array<out Any?>): JsValue = engine.call {
        val cx = engine.cx
        val scope = engine.scope
        val method = ScriptRuntime.getPropAndThis(target, name, cx, scope)!!
        engine.toJs(method.call(cx, scope, engine.toRhinoArgs(args)))
    }

    fun primitive(hint: PrimitiveHint): JsValue = engine.call(drain = false) {
        val type = if (hint == PrimitiveHint.STRING) ScriptRuntime.StringClass else ScriptRuntime.NumberClass
        engine.toJs(ScriptRuntime.toPrimitive(target, type))
    }

    fun usable(): Boolean = engine.isUsableHere

    fun inert(): String = inertText(target)

    /** A copy of the bytes the target views when it is an ArrayBuffer, a typed array or a DataView. */
    fun viewedBytes(): ByteArray? = engine.call(drain = false) {
        when (val t = target) {
            is NativeArrayBuffer -> t.buffer?.copyOf() ?: ByteArray(0)
            is NativeTypedArrayView -> if (t.isTypedArrayOutOfBounds) ByteArray(0) else t.buffer.buffer!!.copyOfRange(t.offset, t.offset + t.byteLength)
            is NativeDataView -> if (t.isDataViewOutOfBounds) ByteArray(0) else t.buffer.buffer!!.copyOfRange(t.offset, t.offset + t.byteLength)
            else -> null
        }
    }

    fun defineData(name: String, value: Any?, flags: PropertyFlags) {
        engine.call(top = false, drain = false) { holder().defineProperty(name, engine.toRhino(value), flags.attributes()) }
    }

    fun defineAccessorProperty(name: String, read: (() -> Any?)?, write: ((JsValue) -> Unit)?, flags: PropertyFlags) {
        // A real accessor, so a missing half reads as undefined and the descriptor shows get and
        // set, as one a script defines would. An accessor has no writable flag to carry.
        engine.call(top = false, drain = false) {
            holder().defineProperty(
                engine.cx,
                name,
                read?.let { r -> ScriptableObject.LambdaGetterFunction { engine.toRhino(r()) } },
                write?.let { w -> ScriptableObject.LambdaSetterFunction { _, v -> w(engine.toJs(v)) } },
                flags.attributes() and ScriptableObject.READONLY.inv(),
            )
        }
    }

    private fun holder(): ScriptableObject =
        target as? ScriptableObject ?: throw JsEngineError("this object cannot take host bindings")

    companion object {
        /**
         * What a handle prints as where it cannot be touched: its class, without running any
         * script. A revoked proxy has no class to give, and prints as a plain object.
         */
        fun inertText(obj: Scriptable): String {
            val className = try {
                obj.className
            } catch (e: RhinoException) {
                "Object"
            }
            return "[object $className]"
        }

        private fun PropertyFlags.attributes(): Int {
            var a = 0
            if (!writable) a = a or ScriptableObject.READONLY
            if (!enumerable) a = a or ScriptableObject.DONTENUM
            if (!configurable) a = a or ScriptableObject.PERMANENT
            return a
        }
    }
}

internal class RhinoObject(override val engine: RhinoKiteJs, override val target: Scriptable) : JsObject(), RhinoHandle {
    override fun get(name: String): JsValue = getAt(name)
    override fun get(index: Int): JsValue = getAt(index)
    override fun set(name: String, value: Any?) = setAt(name, value)
    override fun set(index: Int, value: Any?) = setAt(index, value)
    override fun has(name: String): Boolean = hasKey(name)
    override fun delete(name: String) = deleteKey(name)
    override val keys: List<String> get() = ownKeys()
    override fun call(name: String, vararg args: Any?): JsValue = callMethod(name, args)
    override fun toPrimitive(hint: PrimitiveHint): JsValue = primitive(hint)
    override fun isUsableHere(): Boolean = usable()
    override fun inertText(): String = inert()
    override fun toByteArrayOrNull(): ByteArray? = viewedBytes()
    override fun defineValue(name: String, value: Any?, flags: PropertyFlags) = defineData(name, value, flags)
    override fun defineAccessor(name: String, read: (() -> Any?)?, write: ((JsValue) -> Unit)?, flags: PropertyFlags) =
        defineAccessorProperty(name, read, write, flags)
    override fun equals(other: Any?): Boolean = other is RhinoHandle && other.target === target
    override fun hashCode(): Int = target.hashCode()
}

internal class RhinoArray(override val engine: RhinoKiteJs, override val target: NativeArray) : JsArray(), RhinoHandle {
    override val size: Int get() = engine.call(drain = false) { target.length.toInt() }
    override fun get(name: String): JsValue = getAt(name)
    override fun get(index: Int): JsValue = getAt(index)
    override fun set(name: String, value: Any?) = setAt(name, value)
    override fun set(index: Int, value: Any?) = setAt(index, value)
    override fun has(name: String): Boolean = hasKey(name)
    override fun delete(name: String) = deleteKey(name)
    override val keys: List<String> get() = ownKeys()
    override fun call(name: String, vararg args: Any?): JsValue = callMethod(name, args)
    override fun toPrimitive(hint: PrimitiveHint): JsValue = primitive(hint)
    override fun isUsableHere(): Boolean = usable()
    override fun inertText(): String = inert()
    override fun defineValue(name: String, value: Any?, flags: PropertyFlags) = defineData(name, value, flags)
    override fun defineAccessor(name: String, read: (() -> Any?)?, write: ((JsValue) -> Unit)?, flags: PropertyFlags) =
        defineAccessorProperty(name, read, write, flags)
    override fun equals(other: Any?): Boolean = other is RhinoHandle && other.target === target
    override fun hashCode(): Int = target.hashCode()
}

internal class RhinoFunction(override val engine: RhinoKiteJs, override val target: Scriptable) : JsFunction(), RhinoHandle {

    private val callable: Callable get() = target as Callable

    /** Calls it as `f.call(thisArg, ...args)` would, so a sloppy function given nothing sees the global object. */
    override fun callOn(thisArg: Any?, vararg args: Any?): JsValue = engine.call {
        val cx = engine.cx
        val scope = engine.scope
        val self = ScriptRuntime.getApplyOrCallThis(cx, scope, engine.toRhino(thisArg), 1, callable)
        engine.toJs(callable.call(cx, scope, self, engine.toRhinoArgs(args)))
    }

    /** Calls it as `new f(...args)` would. */
    override fun construct(vararg args: Any?): JsObject = engine.call {
        engine.toJs(ScriptRuntime.newObject(target, engine.cx, engine.scope, engine.toRhinoArgs(args))).raw as JsObject
    }

    /** What `f.bind(thisArg, ...args)` gives. */
    override fun bind(thisArg: Any?, vararg args: Any?): JsFunction = engine.call(drain = false) {
        val cx = engine.cx
        val scope = engine.scope
        val boundThis = ScriptRuntime.toObjectOrNull(cx, engine.toRhino(thisArg), scope)
        RhinoFunction(engine, BoundFunction(cx, scope, callable, boundThis, engine.toRhinoArgs(args)))
    }

    override fun get(name: String): JsValue = getAt(name)
    override fun get(index: Int): JsValue = getAt(index)
    override fun set(name: String, value: Any?) = setAt(name, value)
    override fun set(index: Int, value: Any?) = setAt(index, value)
    override fun has(name: String): Boolean = hasKey(name)
    override fun delete(name: String) = deleteKey(name)
    override val keys: List<String> get() = ownKeys()
    override fun call(name: String, vararg args: Any?): JsValue = callMethod(name, args)
    override fun toPrimitive(hint: PrimitiveHint): JsValue = primitive(hint)
    override fun isUsableHere(): Boolean = usable()
    override fun inertText(): String = inert()
    override fun defineValue(name: String, value: Any?, flags: PropertyFlags) = defineData(name, value, flags)
    override fun defineAccessor(name: String, read: (() -> Any?)?, write: ((JsValue) -> Unit)?, flags: PropertyFlags) =
        defineAccessorProperty(name, read, write, flags)
    override fun equals(other: Any?): Boolean = other is RhinoHandle && other.target === target
    override fun hashCode(): Int = target.hashCode()
}

/** A symbol, which Rhino holds as its [SymbolKey]. */
internal class RhinoSymbol(override val engine: RhinoKiteJs, val key: SymbolKey) : JsSymbol() {

    override val description: String? get() = key.description as? String

    override fun equals(other: Any?): Boolean = other is RhinoSymbol && other.engine === engine && other.key === key

    override fun hashCode(): Int = key.hashCode()
}

internal class RhinoScript(private val engine: RhinoKiteJs, private val script: Script) : JsScript() {
    override fun run(): JsValue = engine.run(script)
}
