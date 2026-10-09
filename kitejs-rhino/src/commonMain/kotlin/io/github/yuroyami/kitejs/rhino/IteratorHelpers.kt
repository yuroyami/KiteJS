/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs.rhino

import io.github.yuroyami.kitejs.rhino.ScriptableObject.Companion.DONTENUM
import io.github.yuroyami.kitejs.rhino.ScriptableObject.Companion.PERMANENT
import io.github.yuroyami.kitejs.rhino.ScriptableObject.Companion.READONLY

/**
 * The `Iterator` constructor of ECMAScript 2025 (27.1.3) and the helper methods on
 * %IteratorPrototype% (27.1.4), for language version ES6 and later. `Iterator` is abstract: only
 * a subclass can be constructed. The lazy helpers (`map`, `filter`, `take`, `drop`, `flatMap`)
 * answer an [IteratorHelper], and the others run the iterator to its end or to the answer.
 */
internal object IteratorHelpers {
    private val ITERATOR_CTOR_TAG: Any = "IteratorConstructor"
    private val HELPER_PROTOTYPE_TAG: Any = "IteratorHelperPrototype"
    private val WRAP_PROTOTYPE_TAG: Any = "WrapForValidIteratorPrototype"

    /** Puts the helpers, `constructor` and `Symbol.toStringTag` on a new %IteratorPrototype%. */
    fun installOn(proto: ScriptableObject, scope: ScriptableObject) {
        val cx = Context.getCurrentContext() ?: return
        method(proto, scope, "map", 1) { c, s, t, a -> lazy(c, s, t, a, Kind.MAP) }
        method(proto, scope, "filter", 1) { c, s, t, a -> lazy(c, s, t, a, Kind.FILTER) }
        method(proto, scope, "take", 1) { c, s, t, a -> lazy(c, s, t, a, Kind.TAKE) }
        method(proto, scope, "drop", 1) { c, s, t, a -> lazy(c, s, t, a, Kind.DROP) }
        method(proto, scope, "flatMap", 1) { c, s, t, a -> lazy(c, s, t, a, Kind.FLAT_MAP) }
        method(proto, scope, "reduce", 1, ::reduce)
        method(proto, scope, "toArray", 0, ::toArray)
        method(proto, scope, "forEach", 1) { c, s, t, a -> eager(c, s, t, a, Eager.FOR_EACH) }
        method(proto, scope, "some", 1) { c, s, t, a -> eager(c, s, t, a, Eager.SOME) }
        method(proto, scope, "every", 1) { c, s, t, a -> eager(c, s, t, a, Eager.EVERY) }
        method(proto, scope, "find", 1) { c, s, t, a -> eager(c, s, t, a, Eager.FIND) }
        // Both are accessors whose setter refuses to change the prototype itself (27.1.4.1, 27.1.4.14).
        proto.defineProperty(
            cx, "constructor",
            { _ -> ScriptableObject.getTopScopeValue(scope, ITERATOR_CTOR_TAG) },
            { target, value -> setterIgnoringPrototype(cx, target, proto, "constructor", value) },
            DONTENUM,
        )
        proto.defineProperty(
            cx, SymbolKey.TO_STRING_TAG,
            { _ -> "Iterator" },
            { target, value -> setterIgnoringPrototype(cx, target, proto, SymbolKey.TO_STRING_TAG, value) },
            DONTENUM,
        )
    }

    /** Defines the global `Iterator`, %IteratorHelperPrototype% and %WrapForValidIteratorPrototype%. */
    fun init(scope: ScriptableObject, sealed: Boolean) {
        val proto = ES6Iterator.iteratorPrototype(scope, sealed)
        val ctor = object : LambdaConstructor(
            scope, "Iterator", 0,
            SerializableCallable { _, _, _, _ -> throw ScriptRuntime.typeErrorById("msg.constructor.no.function", "Iterator") },
            SerializableConstructable { _, _, _ -> throw abstractError() },
        ) {
            override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>, newTarget: Scriptable): Scriptable {
                if (newTarget === this) throw abstractError()
                val obj = NativeObject()
                obj.prototype = AbstractEcmaObjectOperations.getPrototypeFromConstructor(cx, newTarget) { ES6Iterator.iteratorPrototype(getTopLevelScope(it) as ScriptableObject, false) }
                obj.parentScope = getTopLevelScope(scope)
                return obj
            }
        }
        ctor.setPrototypeProperty(proto)
        ctor.setPrototypePropertyAttributes(DONTENUM or READONLY or PERMANENT)
        ctor.defineConstructorMethod(scope, "from", 1, SerializableCallable { cx, s, _, args -> from(cx, s, args) })
        ctor.defineConstructorMethod(scope, "concat", 0, SerializableCallable { cx, s, _, args -> concat(cx, s, args) })
        ScriptableObject.defineProperty(scope, "Iterator", ctor, DONTENUM)
        scope.associateValue(ITERATOR_CTOR_TAG, ctor)

        val helperProto = NativeObject()
        helperProto.parentScope = scope
        helperProto.prototype = proto
        method(helperProto, scope, "next", 0) { cx, s, t, _ -> helper(t, "next").next(cx, s) }
        method(helperProto, scope, "return", 0) { cx, s, t, _ -> helper(t, "return").doReturn(cx, s) }
        helperProto.defineProperty(SymbolKey.TO_STRING_TAG, "Iterator Helper", DONTENUM or READONLY)
        scope.associateValue(HELPER_PROTOTYPE_TAG, helperProto)

        val wrapProto = NativeObject()
        wrapProto.parentScope = scope
        wrapProto.prototype = proto
        method(wrapProto, scope, "next", 0) { cx, s, t, _ -> wrapped(t, "next").let { r -> call(cx, s, r.nextMethod, r.iterator, ScriptRuntime.emptyArgs) } }
        method(wrapProto, scope, "return", 0) { cx, s, t, _ ->
            val iterator = wrapped(t, "return").iterator
            val method = getMethod(iterator, ES6Iterator.RETURN_PROPERTY)
            if (method == null) ES6Iterator.makeIteratorResult(cx, s, true) else method.call(cx, s, iterator, ScriptRuntime.emptyArgs)
        }
        scope.associateValue(WRAP_PROTOTYPE_TAG, wrapProto)
        if (sealed) {
            ctor.sealObject()
            helperProto.sealObject()
            wrapProto.sealObject()
        }
    }

    private fun abstractError(): EcmaError = ScriptRuntime.typeErrorById("msg.iterator.abstract")

    private fun method(target: ScriptableObject, scope: Scriptable, name: String, length: Int, body: SerializableCallable) {
        target.defineProperty(name, LambdaFunction(scope, name, length, body), DONTENUM)
    }

    /** SetterThatIgnoresPrototypeProperties (ECMAScript 2025, 10.4.5.x). */
    private fun setterIgnoringPrototype(cx: Context, thisObj: Scriptable?, home: Scriptable, key: Any, value: Any?) {
        val target = objectOrNull(thisObj) ?: throw ScriptRuntime.typeErrorById("msg.incompat.call", "set")
        if (target === home) throw ScriptRuntime.typeErrorById("msg.modify.readonly", key.toString())
        val has = if (key is Symbol) (target as? SymbolScriptable)?.has(key, target) == true else target.has(key as String, target)
        if (!has) {
            AbstractEcmaObjectOperations.createDataProperty(cx, target, key, value)
        } else if (key is Symbol) {
            ScriptableObject.putProperty(target, key, value)
        } else {
            ScriptableObject.putProperty(target, key as String, value)
        }
    }

    // ---- Iterator.from -----------------------------------------------------------------------

    /** `Iterator.from` (27.1.3.2.1): an iterator that already inherits Iterator.prototype comes back as is. */
    private fun from(cx: Context, scope: Scriptable, args: Array<Any?>): Any? {
        val record = flattenable(cx, scope, if (args.isNotEmpty()) args[0] else Undefined.instance, iterateStrings = true)
        val ctor = ScriptableObject.getTopScopeValue(scope, ITERATOR_CTOR_TAG)
        if (ScriptRuntime.ordinaryHasInstance(cx, ctor, record.iterator)) return record.iterator
        val wrapper = Wrapper(record)
        val top = ScriptableObject.getTopLevelScope(scope)
        wrapper.parentScope = top
        wrapper.prototype = ScriptableObject.getTopScopeValue(top, WRAP_PROTOTYPE_TAG) as Scriptable
        return wrapper
    }

    /** An object of %WrapForValidIteratorPrototype%: [record] is the spec's [[Iterated]]. */
    private class Wrapper(val record: OpenIterator) : ScriptableObject() {
        override val className: String get() = "Object"
    }

    private fun wrapped(thisObj: Scriptable?, name: String): OpenIterator =
        (thisObj as? Wrapper)?.record ?: throw ScriptRuntime.typeErrorById("msg.incompat.call", name)

    /** GetIteratorFlattenable (7.4.x): an object's iterator, or the object itself when it has none. */
    private fun flattenable(cx: Context, scope: Scriptable, value: Any?, iterateStrings: Boolean): OpenIterator {
        if (value !is Scriptable || Undefined.isUndefined(value)) {
            if (!iterateStrings || value !is CharSequence) throw ScriptRuntime.typeErrorById("msg.not.iterable", ScriptRuntime.toString(value))
        }
        val method = ScriptRuntime.getV(cx, scope, value, SymbolKey.ITERATOR)
        val iterator = if (method === Scriptable.NOT_FOUND || method == null || Undefined.isUndefined(method)) {
            value
        } else {
            if (method !is Callable) throw ScriptRuntime.notFunctionError(method)
            method.call(cx, scope, ScriptRuntime.toReceiver(cx, value, scope)!!, ScriptRuntime.emptyArgs)
        }
        if (iterator !is Scriptable) throw ScriptRuntime.typeErrorById("msg.iterator.result.not.object", ScriptRuntime.toString(iterator))
        return direct(iterator)
    }

    /** GetIteratorDirect: the object and its `next`, read once. */
    private fun direct(iterator: Scriptable): OpenIterator = OpenIterator(iterator, OpenIterator.read(iterator, ES6Iterator.NEXT_METHOD), -1)

    private fun call(cx: Context, scope: Scriptable, function: Any?, thisObj: Scriptable, args: Array<Any?>): Any? {
        if (function !is Callable) throw ScriptRuntime.notFunctionError(function)
        return function.call(cx, scope, thisObj, args)
    }

    private fun getMethod(obj: Scriptable, name: String): Callable? {
        val method = OpenIterator.read(obj, name)
        if (method == null || Undefined.isUndefined(method)) return null
        return method as? Callable ?: throw ScriptRuntime.notFunctionError(method)
    }

    // ---- The checks every helper starts with -----------------------------------------------

    /** Null for a missing `this` and for a primitive's wrapper made for the call. */
    private fun objectOrNull(thisObj: Scriptable?): Scriptable? =
        thisObj?.takeIf { it !== Undefined.SCRIPTABLE_UNDEFINED && ScriptRuntime.takeReceiver(it) == null }

    /** The `this` of a helper, which must be an object. */
    private fun receiver(thisObj: Scriptable?): Scriptable =
        objectOrNull(thisObj) ?: throw ScriptRuntime.typeErrorById("msg.incompat.call", "Iterator.prototype method")

    /** A bad argument closes the iterator before the error (ECMAScript 2025, 27.1.4: IteratorClose with a throw). */
    private fun closeAndThrow(cx: Context, scope: Scriptable, iterator: Scriptable, error: RuntimeException): Nothing {
        try {
            val method = getMethod(iterator, ES6Iterator.RETURN_PROPERTY)
            method?.call(cx, scope, iterator, ScriptRuntime.emptyArgs)
        } catch (_: RhinoException) {
            // The first error wins.
        }
        throw error
    }

    private fun callable(cx: Context, scope: Scriptable, iterator: Scriptable, value: Any?): Callable =
        value as? Callable ?: closeAndThrow(cx, scope, iterator, ScriptRuntime.notFunctionError(value))

    // ---- The lazy helpers ---------------------------------------------------------------------

    enum class Kind { MAP, FILTER, TAKE, DROP, FLAT_MAP }

    private fun lazy(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>, kind: Kind): Any? {
        val o = receiver(thisObj)
        val arg = if (args.isNotEmpty()) args[0] else Undefined.instance
        var fn: Callable? = null
        var limit = 0.0
        if (kind == Kind.TAKE || kind == Kind.DROP) {
            val number = try {
                ScriptRuntime.toNumber(arg)
            } catch (e: RhinoException) {
                closeAndThrow(cx, scope, o, e)
            }
            if (number.isNaN()) closeAndThrow(cx, scope, o, ScriptRuntime.rangeErrorById("msg.iterator.limit", ScriptRuntime.toString(arg)))
            limit = ScriptRuntime.toIntegerOrInfinity(number)
            if (limit < 0) closeAndThrow(cx, scope, o, ScriptRuntime.rangeErrorById("msg.iterator.limit", ScriptRuntime.toString(arg)))
        } else {
            fn = callable(cx, scope, o, arg)
        }
        val helper = IteratorHelper(kind, direct(o), fn, limit)
        val top = ScriptableObject.getTopLevelScope(scope)
        helper.parentScope = top
        helper.prototype = ScriptableObject.getTopScopeValue(top, HELPER_PROTOTYPE_TAG) as Scriptable
        return helper
    }

    private fun helper(thisObj: Scriptable?, name: String): HelperObject =
        thisObj as? HelperObject ?: throw ScriptRuntime.typeErrorById("msg.incompat.call", "Iterator Helper.$name")

    /** An object of %IteratorHelperPrototype%, whose `next` and `return` it answers. */
    private abstract class HelperObject : ScriptableObject() {
        override val className: String get() = "Object"

        abstract fun next(cx: Context, scope: Scriptable): Any?

        abstract fun doReturn(cx: Context, scope: Scriptable): Any?
    }

    /**
     * An iterator helper object (27.1.2.1): a generator-like state machine over [underlying]. A
     * `next` while it runs is a TypeError, as for a generator.
     */
    private class IteratorHelper(
        private val kind: Kind,
        private val underlying: OpenIterator,
        private val fn: Callable?,
        private var remaining: Double,
    ) : HelperObject() {
        private var state = 0 // 0 suspended-start, 1 suspended-yield, 2 executing, 3 completed
        private var counter = 0.0
        private var inner: OpenIterator? = null

        override fun next(cx: Context, scope: Scriptable): Any? {
            if (state == 2) throw ScriptRuntime.typeErrorById("msg.generator.executing")
            if (state == 3) return ES6Iterator.makeIteratorResult(cx, scope, true)
            state = 2
            val value = try {
                produce(cx, scope)
            } catch (e: Throwable) {
                state = 3
                throw e
            }
            if (value === Scriptable.NOT_FOUND) {
                state = 3
                return ES6Iterator.makeIteratorResult(cx, scope, true)
            }
            state = 1
            return ES6Iterator.makeIteratorResult(cx, scope, false, value)
        }

        /** `return`: closes what is open, an inner flatMap iterator first, and completes. */
        override fun doReturn(cx: Context, scope: Scriptable): Any? {
            if (state == 2) throw ScriptRuntime.typeErrorById("msg.generator.executing")
            if (state == 3) return ES6Iterator.makeIteratorResult(cx, scope, true)
            val started = state == 1
            // A started helper runs while its iterators close (GeneratorResumeAbrupt); one that
            // never started is already complete.
            state = if (started) 2 else 3
            try {
                if (started) {
                    inner?.let { open ->
                        inner = null
                        try {
                            open.close(cx, scope, quiet = false)
                        } catch (e: RhinoException) {
                            underlying.close(cx, scope, quiet = true)
                            throw e
                        }
                    }
                }
                underlying.close(cx, scope, quiet = false)
            } finally {
                state = 3
            }
            return ES6Iterator.makeIteratorResult(cx, scope, true)
        }

        /** The next value, or NOT_FOUND once done. A callback that throws closes the underlying iterator. */
        private fun produce(cx: Context, scope: Scriptable): Any? {
            when (kind) {
                Kind.MAP -> {
                    val value = underlying.step(cx, scope)
                    if (value === Scriptable.NOT_FOUND) return value
                    return guarded(cx, scope) { fn!!.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, arrayOf(value, counter++)) }
                }
                Kind.FILTER -> while (true) {
                    val value = underlying.step(cx, scope)
                    if (value === Scriptable.NOT_FOUND) return value
                    val selected = guarded(cx, scope) { ScriptRuntime.toBoolean(fn!!.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, arrayOf(value, counter++))) }
                    if (selected) return value
                }
                Kind.TAKE -> {
                    if (remaining == 0.0) {
                        underlying.close(cx, scope, quiet = false)
                        return Scriptable.NOT_FOUND
                    }
                    if (remaining != Double.POSITIVE_INFINITY) remaining--
                    return underlying.step(cx, scope)
                }
                Kind.DROP -> {
                    while (remaining > 0) {
                        if (remaining != Double.POSITIVE_INFINITY) remaining--
                        if (underlying.step(cx, scope) === Scriptable.NOT_FOUND) return Scriptable.NOT_FOUND
                    }
                    return underlying.step(cx, scope)
                }
                Kind.FLAT_MAP -> while (true) {
                    val open = inner
                    if (open != null) {
                        val value = guarded(cx, scope) { open.step(cx, scope) }
                        if (value !== Scriptable.NOT_FOUND) return value
                        inner = null
                        counter++
                        continue
                    }
                    val value = underlying.step(cx, scope)
                    if (value === Scriptable.NOT_FOUND) return value
                    inner = guarded(cx, scope) {
                        val mapped = fn!!.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, arrayOf(value, counter))
                        flattenable(cx, scope, mapped, iterateStrings = false)
                    }
                }
            }
        }

        private inline fun <T> guarded(cx: Context, scope: Scriptable, body: () -> T): T =
            try {
                body()
            } catch (e: RhinoException) {
                underlying.close(cx, scope, quiet = true)
                throw e
            }
    }

    // ---- Iterator.concat ------------------------------------------------------------------------

    /** `Iterator.concat` (ECMAScript 2026, 27.1.3.2.x): checks every item now, opens each one later. */
    private fun concat(cx: Context, scope: Scriptable, args: Array<Any?>): Any? {
        val iterables = args.map { item ->
            if (item !is Scriptable || !ScriptRuntime.isObject(item)) throw ScriptRuntime.typeErrorById("msg.not.iterable", ScriptRuntime.toString(item))
            val method = ScriptableObject.getProperty(item, SymbolKey.ITERATOR)
            if (method === Scriptable.NOT_FOUND || method == null || Undefined.isUndefined(method)) {
                throw ScriptRuntime.typeErrorById("msg.not.iterable", ScriptRuntime.toString(item))
            }
            if (method !is Callable) throw ScriptRuntime.notFunctionError(method)
            method to item
        }
        val helper = ConcatHelper(iterables)
        val top = ScriptableObject.getTopLevelScope(scope)
        helper.parentScope = top
        helper.prototype = ScriptableObject.getTopScopeValue(top, HELPER_PROTOTYPE_TAG) as Scriptable
        return helper
    }

    /** The helper `Iterator.concat` answers: each iterable's values in turn, a new result each. */
    private class ConcatHelper(private val iterables: List<Pair<Callable, Scriptable>>) : HelperObject() {
        private var state = 0 // as in IteratorHelper
        private var index = 0
        private var inner: OpenIterator? = null

        override fun next(cx: Context, scope: Scriptable): Any? {
            if (state == 2) throw ScriptRuntime.typeErrorById("msg.generator.executing")
            if (state == 3) return ES6Iterator.makeIteratorResult(cx, scope, true)
            state = 2
            val value = try {
                produce(cx, scope)
            } catch (e: Throwable) {
                state = 3
                inner = null
                throw e
            }
            if (value === Scriptable.NOT_FOUND) {
                state = 3
                return ES6Iterator.makeIteratorResult(cx, scope, true)
            }
            state = 1
            return ES6Iterator.makeIteratorResult(cx, scope, false, value)
        }

        private fun produce(cx: Context, scope: Scriptable): Any? {
            while (true) {
                inner?.let { open ->
                    val value = open.step(cx, scope)
                    if (value !== Scriptable.NOT_FOUND) return value
                    inner = null
                }
                if (index >= iterables.size) return Scriptable.NOT_FOUND
                val (method, iterable) = iterables[index++]
                val iterator = method.call(cx, scope, iterable, ScriptRuntime.emptyArgs)
                if (iterator !is Scriptable) throw ScriptRuntime.typeErrorById("msg.iterator.result.not.object", ScriptRuntime.toString(iterator))
                inner = direct(iterator)
            }
        }

        /** `return` reaches only the iterator that is open, so none before the first `next`. */
        override fun doReturn(cx: Context, scope: Scriptable): Any? {
            if (state == 2) throw ScriptRuntime.typeErrorById("msg.generator.executing")
            if (state == 3) return ES6Iterator.makeIteratorResult(cx, scope, true)
            // The helper runs while the iterator closes, so a `return` from inside it is a TypeError.
            state = 2
            try {
                inner?.let { open ->
                    inner = null
                    open.close(cx, scope, quiet = false)
                }
            } finally {
                state = 3
            }
            return ES6Iterator.makeIteratorResult(cx, scope, true)
        }
    }

    // ---- The eager helpers ------------------------------------------------------------------

    enum class Eager { FOR_EACH, SOME, EVERY, FIND }

    private fun eager(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>, kind: Eager): Any? {
        val o = receiver(thisObj)
        val fn = callable(cx, scope, o, if (args.isNotEmpty()) args[0] else Undefined.instance)
        val record = direct(o)
        var counter = 0.0
        while (true) {
            val value = record.step(cx, scope)
            if (value === Scriptable.NOT_FOUND) {
                return when (kind) {
                    Eager.SOME -> false
                    Eager.EVERY -> true
                    else -> Undefined.instance
                }
            }
            val result = try {
                fn.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, arrayOf(value, counter++))
            } catch (e: RhinoException) {
                record.close(cx, scope, quiet = true)
                throw e
            }
            val answer: Any? = when (kind) {
                Eager.FOR_EACH -> Scriptable.NOT_FOUND
                Eager.SOME -> if (ScriptRuntime.toBoolean(result)) true else Scriptable.NOT_FOUND
                Eager.EVERY -> if (!ScriptRuntime.toBoolean(result)) false else Scriptable.NOT_FOUND
                Eager.FIND -> if (ScriptRuntime.toBoolean(result)) value else Scriptable.NOT_FOUND
            }
            if (answer !== Scriptable.NOT_FOUND) {
                record.close(cx, scope, quiet = false)
                return answer
            }
        }
    }

    private fun reduce(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        val o = receiver(thisObj)
        val reducer = callable(cx, scope, o, if (args.isNotEmpty()) args[0] else Undefined.instance)
        val record = direct(o)
        var accumulator: Any?
        var counter: Double
        if (args.size < 2) {
            accumulator = record.step(cx, scope)
            if (accumulator === Scriptable.NOT_FOUND) throw ScriptRuntime.typeErrorById("msg.empty.iterator.reduce")
            counter = 1.0
        } else {
            accumulator = args[1]
            counter = 0.0
        }
        while (true) {
            val value = record.step(cx, scope)
            if (value === Scriptable.NOT_FOUND) return accumulator
            accumulator = try {
                reducer.call(cx, scope, Undefined.SCRIPTABLE_UNDEFINED, arrayOf(accumulator, value, counter++))
            } catch (e: RhinoException) {
                record.close(cx, scope, quiet = true)
                throw e
            }
        }
    }

    private fun toArray(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
        val record = direct(receiver(thisObj))
        val items = ArrayList<Any?>()
        while (true) {
            val value = record.step(cx, scope)
            if (value === Scriptable.NOT_FOUND) return cx.newArray(scope, items.toTypedArray())
            items.add(value)
        }
    }
}
